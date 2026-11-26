package com.carobd.core.obd

import com.carobd.core.sim.Lectura
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Lo leído hasta el prompt '>' (sin incluirlo). `completa` es false si venció el tiempo antes del prompt. */
data class Respuesta(val texto: String, val completa: Boolean)

/** Cómo llegan los bytes al ELM327: Bluetooth clásico, TCP (emulador o adaptador wifi), etc. */
interface TransporteElm {
    suspend fun abrir()
    suspend fun escribir(texto: String)
    /**
     * Lee hasta el prompt '>' o hasta que pasen [timeoutMs]. Lo que llegue después del '>' queda para la
     * próxima lectura: puede ser la respuesta buena detrás de un prompt atrasado.
     */
    suspend fun leer(timeoutMs: Long): Respuesta
    /** Tira lo que haya llegado y nadie leyó (restos de ATMA, prompts tardíos del clon por BLE). */
    fun descartar()
    fun cerrar()
}

enum class FaseConexion { DESCONECTADO, CONECTANDO, CONECTADO, RECONECTANDO }

data class EstadoConexion(
    val fase: FaseConexion,
    val detalle: String = "",
    val intento: Int = 0,
    val adaptador: String? = null,
    val vin: String? = null,
    val pidsSoportados: Int = 0,
    /** Si la escucha pasiva de testigos (ATMA) sigue activa. */
    val carroceria: Boolean = false,
)

/** El adaptador no contestó (con prompt) a [intentos] comandos seguidos: está colgado o se cortó el enlace. */
class AdaptadorSinRespuesta(mensaje: String) : Exception(mensaje)

/**
 * Protocolo ELM327 sobre cualquier [TransporteElm]. Arranca el adaptador, lee qué PIDs soporta el motor
 * y los pide por turnos según su [Ritmo] (ver [leerCiclo]): críticas en cada turno y un solo dato secundario por turno.
 * Cada [periodoCarroceriaMs] escucha el bus unos ms, todos los IDs de la ronda juntos, para los testigos de [Carroceria]. Si el adaptador se cuelga, cierra
 * y reconecta con espera creciente; el flujo nunca termina por un error del adaptador.
 */
class Elm327(
    private val transporte: TransporteElm,
    private val excluir: Set<String> = emptySet(),
    private val timeoutMs: Long = 1500,
    private val intentos: Int = 3,
    // Each listen pauses the PIDs (~0.7 s over BLE): every 10 s, with short windows, keeps the needles live.
    // All the IDs of a round share one window; 690 sends at 10 Hz and 350 at 48 Hz, so 200 ms catches a frame of each.
    private val periodoCarroceriaMs: Long = 10_000,
    private val ventanaEscuchaMs: Long = 200,
) {
    private val _estado = MutableStateFlow(EstadoConexion(FaseConexion.DESCONECTADO))
    val estado: StateFlow<EstadoConexion> = _estado.asStateFlow()

    private val reloj = TimeSource.Monotonic
    private val marchas = Marchas()
    private var fallosSeguidos = 0
    private var senales: List<Senal> = emptyList()
    private val valores = mutableMapOf<String, Double>()
    private var ciclo = 0L
    private var carroceriaActiva = true
    private var fallosCarroceria = 0
    private var escuchas = 0
    private var extendidoActivo = true
    private val proxima = mutableMapOf<String, TimeMark>() // next due time of each secondary task
    private var cuenta = ""
    /** The clone counts CAN frames: "2" brings a two-frame reply whole, without waiting out the timeout. */
    private var cuentaMultitrama = false
    private var grupoCritico: List<Senal> = emptyList()

    /** Testigos de carrocería (escucha ATMA). Se puede apagar en caliente si molestan en algún adaptador. */
    var testigos: Boolean = true
        set(valor) {
            field = valor
            fallosCarroceria = 0 // reactivarlos a mano vuelve a intentar aunque se hubieran apagado solos
        }

    private val _registro = MutableStateFlow<List<String>>(emptyList())
    private val inicioRegistro = reloj.markNow()
    /**
     * Últimos [LARGO_REGISTRO] comandos y respuestas en crudo (~7 min), con segundos desde que arrancó la app, para
     * diagnosticar en el auto: la pantalla muestra los últimos y el resto se comparte como archivo.
     */
    val registro: StateFlow<List<String>> = _registro.asStateFlow()

    private fun anotar(linea: String) {
        val ms = inicioRegistro.elapsedNow().inWholeMilliseconds
        _registro.value = (_registro.value + "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')} $linea").takeLast(LARGO_REGISTRO)
    }

    fun lecturas(): Flow<Lectura> = flow {
        val inicio = reloj.markNow()
        var reintento = 0
        while (true) {
            // El detalle del último error se conserva mientras reintenta: es lo que hay que leer en el auto.
            _estado.value = EstadoConexion(
                if (reintento == 0) FaseConexion.CONECTANDO else FaseConexion.RECONECTANDO,
                detalle = if (reintento == 0) "" else _estado.value.detalle,
                intento = reintento,
            )
            val conectado = protegido { transporte.abrir(); iniciar() } == true
            while (conectado) {
                // emit fuera de protegido: un error del colector no se confunde con uno del adaptador.
                val leidos = protegido { leerCiclo() } ?: break
                reintento = 0
                emit(Lectura(inicio.elapsedNow().inWholeMilliseconds, leidos))
            }
            runCatching { transporte.cerrar() }
            reintento++
            delay(minOf(1000L shl minOf(reintento - 1, 5), 30_000L))
        }
    }

    /** Ejecuta [bloque]; si falla (salvo cancelación) deja el estado en RECONECTANDO y devuelve null. */
    private suspend fun <T> protegido(bloque: suspend () -> T): T? = try {
        bloque()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _estado.value = _estado.value.copy(fase = FaseConexion.RECONECTANDO, detalle = e.message ?: e::class.simpleName.orEmpty())
        null
    }

    /**
     * Envía [comando] y devuelve SU respuesta. Antes descarta la basura pendiente, y después saltea las respuestas
     * completas que no pasan [valida]: son atrasadas de otro comando. El clon por BLE manda un prompt tardío
     * tras ATMA; sin esto, cada respuesta quedaba emparejada con el comando anterior y los PIDs no se
     * actualizaban. NO DATA, "?" y los errores cuentan como respuesta.
     */
    private suspend fun enviar(comando: String, timeout: Long = timeoutMs, valida: (String) -> Boolean = { true }): String {
        transporte.descartar()
        transporte.escribir("$comando\r")
        val fin = reloj.markNow() + timeout.milliseconds
        while (fin.hasNotPassedNow()) {
            val r = transporte.leer(-fin.elapsedNow().inWholeMilliseconds)
            if (!r.completa) {
                anotar("$comando → ${legible(r.texto)} (sin prompt)")
                break
            }
            if (valida(r.texto) || esError(r.texto)) {
                anotar("$comando → ${legible(r.texto)}")
                fallosSeguidos = 0
                return r.texto
            }
            anotar("$comando ✕ atrasada: ${legible(r.texto)}")
        }
        if (++fallosSeguidos >= intentos) throw AdaptadorSinRespuesta("sin respuesta a $comando")
        return ""
    }

    private fun limpio(texto: String) = texto.uppercase().replace(" ", "").replace("\r", "").replace("\n", "")
    private fun esError(texto: String) = limpio(texto).let { t -> t == "?" || "NODATA" in t || "ERROR" in t || "UNABLE" in t }
    private fun esOk(texto: String) = "OK" in limpio(texto)
    private fun responde(prefijo: String): (String) -> Boolean = { prefijo in limpio(it) }
    private fun legible(texto: String) = texto.trim().replace("\r", " ⏎ ").replace("\n", "").take(120)

    private suspend fun iniciar(): Boolean {
        fallosSeguidos = 0
        var banner: String? = null
        // El clon manda un byte basura antes del banner: se descarta todo lo anterior a "ELM327".
        for (i in 1..intentos) {
            val r = enviar("ATZ", 3000) { "ELM327" in it }
            if ("ELM327" in r) {
                banner = "ELM327" + r.substringAfter("ELM327").lineSequence().first().trimEnd()
                break
            }
        }
        banner ?: throw AdaptadorSinRespuesta("ATZ no devolvió el banner ELM327")
        // Protocolo fijo del Accent (ISO 15765-4 CAN 11/500): evita el SEARCHING... de ATSP0.
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6")) enviar(c, valida = ::esOk)
        // Physical address of the engine (7E0): only the engine is asked, never the other modules.
        enviar("ATSH7E0", valida = ::esOk)
        val soportados = leerSoportados()
        val vin = Decodificador.vin(enviar("0902", 3000, responde("4902")))
        senales = Catalogo.estandar.filter {
            it.modo == "01" && it.pid != null && it.id !in excluir && (soportados.isEmpty() || it.pid.toInt(16) in soportados)
        }
        // Response count "1": the adapter returns as soon as the engine answers instead of waiting for others.
        cuenta = if ("410D" in limpio(enviar("010D1", valida = responde("410D")))) "1" else ""
        // Critical PIDs in one request when the engine accepts several at once (one round trip instead of three).
        val criticas = senales.filter { it.ritmo == Ritmo.CRITICO }
        cuentaMultitrama = false
        grupoCritico = criticas.takeIf { it.size > 1 && leerGrupo(it) != null }.orEmpty()
        if (cuenta.isNotEmpty() && bytesGrupo(grupoCritico) > 7) cuentaMultitrama = probarCuentaMultitrama()
        anotar(
            "pedidos: ${if (grupoCritico.isEmpty()) "de a un PID" else "agrupados"}, ${if (cuenta.isEmpty()) "sin" else "con"} cuenta de respuestas" +
                if (cuentaMultitrama) ", también en las de varias tramas" else "",
        )
        valores.clear()
        proxima.clear()
        ciclo = 0
        extendidoActivo = true
        carroceriaActiva = testigos
        fallosCarroceria = 0
        _estado.value = EstadoConexion(FaseConexion.CONECTADO, adaptador = banner, vin = vin, pidsSoportados = soportados.size, carroceria = testigos)
        return true
    }

    /** PIDs de modo 01 que declara el motor en los bitmaps 0100, 0120, 0140... Vacío si no contesta. */
    private suspend fun leerSoportados(): Set<Int> {
        val soportados = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val pids = Decodificador.pidsSoportados(base, enviar("01" + hex2(base), valida = responde("41" + hex2(base)))) ?: break
            soportados += pids
            if (base + 0x20 !in pids) break
            base += 0x20
        }
        return soportados
    }

    private suspend fun leerGrupo(grupo: List<Senal>): Map<String, Double>? {
        val r = enviar("01" + grupo.joinToString("") { it.pid.orEmpty() } + cuentaPara(bytesGrupo(grupo)), valida = responde("41"))
        return Decodificador.variosPids(r, grupo).also { if (it == null) cortada(r) }
    }

    private fun bytesGrupo(grupo: List<Senal>) = 1 + grupo.sumOf { 1 + it.bytes }

    /**
     * The response count: "1" for replies that fit one CAN frame (7 bytes), and the number of ISO-TP frames for longer
     * ones when [cuentaMultitrama] holds. Without a count the adapter waits its timeout for more ECUs after the reply
     * (~55 ms over BLE, measured in the car); with a smaller count it returns early and the reply comes cut.
     */
    private fun cuentaPara(bytesRespuesta: Int): String = when {
        bytesRespuesta <= 7 -> cuenta
        cuentaMultitrama -> tramasIsoTp(bytesRespuesta).takeIf { it <= 0xF }?.toString(16)?.uppercase().orEmpty()
        else -> ""
    }

    /**
     * Whether the adapter counts CAN frames: the critical group asked with its frame count has to come back whole,
     * and not slower than without the count (an adapter that counts whole replies would wait its timeout).
     */
    private suspend fun probarCuentaMultitrama(): Boolean {
        val pedido = "01" + grupoCritico.joinToString("") { it.pid.orEmpty() }
        val sin = reloj.markNow()
        enviar(pedido, valida = responde("41"))
        val msSin = sin.elapsedNow()
        val con = reloj.markNow()
        val completa = Decodificador.variosPids(enviar(pedido + tramasIsoTp(bytesGrupo(grupoCritico)).toString(16), valida = responde("41")), grupoCritico) != null
        // An adapter that waited its timeout would be ~50 ms slower; 25 ms of slack absorbs the BLE jitter.
        return completa && con.elapsedNow() < msSin + 25.milliseconds
    }

    /** A multi-frame reply asked with a frame count came back cut: stop counting them instead of losing readings. */
    private fun cortada(respuesta: String) {
        if (!cuentaMultitrama || esError(respuesta) || respuesta.isBlank()) return
        cuentaMultitrama = false
        anotar("respuesta de varias tramas incompleta: se piden sin cuenta")
    }

    /**
     * One turn on the single ELM327 channel: the critical PIDs first (the screen updates right after), then at most
     * ONE secondary task: the brake/clutch block whenever it is due, else the most overdue of fast PIDs, the body listen,
     * medium and slow.
     * Nothing secondary ever delays the needles by more than one request.
     */
    private suspend fun leerCiclo(): Map<String, Double> {
        leerCriticas()
        if (!testigos && _estado.value.carroceria) {
            Carroceria.senales.forEach { valores.remove(it) }
            _estado.value = _estado.value.copy(carroceria = false)
        }
        if (testigos && !_estado.value.carroceria && fallosCarroceria < MAX_FALLOS_CARROCERIA) {
            carroceriaActiva = true
            _estado.value = _estado.value.copy(carroceria = true)
        }
        secundaria()
        derivadas()
        ciclo++
        return valores.toMap()
    }

    private suspend fun leerCriticas() {
        if (grupoCritico.isNotEmpty()) leerGrupo(grupoCritico)?.let { valores += it }
        else senales.filter { it.ritmo == Ritmo.CRITICO }.forEach { leerPid(it) }
    }

    private suspend fun secundaria() {
        // Brake, clutch and neutral are as critical as the needles: when due, they go before anything else.
        if (extendidoActivo && proxima[MotorExtendido.COMANDO]?.hasPassedNow() != false) {
            proxima[MotorExtendido.COMANDO] = reloj.markNow() + PERIODO_EXTENDIDO_MS.milliseconds
            leerExtendido()
            return
        }
        val tareas = buildList {
            senales.filter { it.ritmo != Ritmo.CRITICO }.forEach { add(it.id to PERIODOS_MS.getValue(it.ritmo)) }
            if (testigos && carroceriaActiva) add(ESCUCHA to periodoCarroceriaMs)
        }
        // Earliest deadline first: the most overdue task runs, so when the channel is saturated every task slows
        // down evenly instead of the low ones starving. Ties (first turn) go by list order, which is the priority.
        val (id, periodo) = tareas.filter { proxima[it.first]?.hasPassedNow() != false }
            .maxByOrNull { proxima[it.first]?.elapsedNow() ?: Duration.INFINITE } ?: return
        when (id) {
            ESCUCHA -> {
                // The whole round in one window: each listen costs ~8 AT round trips, whatever the number of IDs.
                proxima[id] = reloj.markNow() + periodo.milliseconds
                escuchar(Carroceria.idsEscucha(escuchas++))
            }
            else -> {
                proxima[id] = reloj.markNow() + periodo.milliseconds
                leerPid(senales.first { it.id == id })
            }
        }
    }

    private suspend fun leerPid(senal: Senal) {
        val respuesta = enviar(senal.modo + senal.pid + cuentaPara(2 + senal.bytes), valida = responde("41" + senal.pid))
        Decodificador.decodificar(respuesta, senal)?.let { valores[senal.id] = it }
    }

    private suspend fun leerExtendido() {
        // A negative answer (7F 22) counts as the reply, or enviar would wait it out as a late one.
        val r = enviar(MotorExtendido.COMANDO + cuentaPara(MotorExtendido.BYTES_RESPUESTA)) { "62ED01" in limpio(it) || "7F22" in limpio(it) }
        val leidos = MotorExtendido.decodificar(r)
        if (leidos != null) valores += leidos
        else if ("7F22" !in limpio(r)) cortada(r)
        // An engine without the block refuses it: stop asking. NO DATA or a truncated reply is just skipped.
        else if ("7F22" in limpio(r)) extendidoActivo = false
    }

    /**
     * Escucha pasiva de los [ids] de una ronda en una sola ventana: ATCAF0 (tramas crudas), ATH1 (cada línea con su
     * ID), ATAR + ATCF/ATCM con la máscara que deja pasar esos IDs, y ATMA unos ms; se detiene con un byte, se drena
     * hasta el prompt y se tiran los restos tardíos. Al final vuelve ATH0, el filtro al motor (ATCRA7E8) y ATCAF1
     * esperando OK, para volver a pedir PIDs. Antes era una escucha por ID con todo ese ida y vuelta cada vez (~0,6 s
     * por ID sin PIDs). Tras 3 escuchas seguidas sin tramas o con error se desactiva sola; los PIDs siguen igual.
     */
    private suspend fun escuchar(ids: List<String>) {
        var conTramas = false
        try {
            val (filtro, mascara) = Carroceria.filtro(ids)
            for (c in listOf("ATCAF0", "ATH1", "ATAR", "ATCF$filtro", "ATCM$mascara")) {
                if (!esOk(enviar(c, valida = ::esOk))) throw IllegalStateException("$c sin OK")
            }
            transporte.descartar()
            transporte.escribir("ATMA\r")
            var r = transporte.leer(ventanaEscuchaMs)
            if (!r.completa) {
                // A lost stop byte leaves the adapter monitoring and every later PID unanswered: retry it.
                var cierre = Respuesta("", false)
                for (intento in 1..INTENTOS_PARADA) {
                    transporte.escribir(" ")
                    cierre = transporte.leer(timeoutMs / INTENTOS_PARADA)
                    if (cierre.completa) break
                    anotar("ATMA: no se detuvo, reintento $intento")
                }
                if (!cierre.completa) throw AdaptadorSinRespuesta("ATMA no se detuvo")
                r = Respuesta(r.texto + "\r" + cierre.texto, true)
            }
            anotar("ATMA ${ids.joinToString(",")} → ${legible(r.texto).takeLast(90)}")
            // El clon por BLE sigue mandando restos (y a veces otro '>') un rato después de detenerse.
            delay(RESTOS_ATMA_MS)
            transporte.descartar()
            for ((id, trama) in Carroceria.ultimasTramas(r.texto)) {
                val leidos = Carroceria.decodificar(id, trama)
                valores += leidos
                if (id in ids && leidos.isNotEmpty()) conTramas = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            anotar("testigos: ${e.message}")
        }
        // Listening must never take the adapter down: its failures don't count toward a reconnect.
        // If the filters can't be restored, the telltales switch off; the PIDs alone decide a reconnect.
        // The BLE clone answers "?" to a bare ATCRA (reset), which left the 690 filter on: every later PID reply
        // was 690 traffic and the app reconnected. Point the filter back at the engine (7E8) instead; ATAR as backup.
        // ATCRA also overrides the ATCF/ATCM of the listen.
        val restaurado = runCatching {
            esOk(enviar("ATH0", valida = ::esOk)) &&
                (esOk(enviar("ATCRA7E8", valida = ::esOk)) || esOk(enviar("ATAR", valida = ::esOk))) &&
                esOk(enviar("ATCAF1", valida = ::esOk))
        }.getOrDefault(false)
        fallosSeguidos = 0
        if (!restaurado) {
            anotar("testigos: el adaptador no confirmó ATH0/ATCRA/ATCAF1")
            conTramas = false
            fallosCarroceria = MAX_FALLOS_CARROCERIA - 1
        }
        fallosCarroceria = if (conTramas) 0 else fallosCarroceria + 1
        if (fallosCarroceria >= MAX_FALLOS_CARROCERIA) {
            carroceriaActiva = false
            Carroceria.senales.forEach { valores.remove(it) }
            _estado.value = _estado.value.copy(carroceria = false)
            anotar("testigos desactivados: 3 escuchas sin tramas")
        }
    }

    private fun derivadas() {
        val map = valores["map"]
        val baro = valores["barometrica"] ?: 101.0
        if (map != null) valores["boost"] = (map - baro) / 100.0
        val rpm = valores["rpm"]
        val kmh = valores["velocidad"]
        if (rpm != null && kmh != null) {
            val embrague = valores[MotorExtendido.EMBRAGUE] == 1.0
            // The gearbox switch (22ED01): null when the engine doesn't give it, and then 0 keeps meaning N.
            val neutro = valores[MotorExtendido.PUNTO_MUERTO]?.let { it == 1.0 }
            // With the clutch down the ratio slips: learning from it would skew the gear table.
            if (!embrague) marchas.aprender(rpm, kmh)
            val m = marchas.mostrar(if (neutro == true) 0 else marchas.marcha(rpm, kmh), kmh)
            // Stopped (or the ratio fits no gear) with the switch saying a gear is in: which one can't be known
            // until the car rolls, but it is NOT neutral. It used to show a green N with first gear in.
            valores["marcha"] = (if (m == 0 && neutro == false) Marchas.PUESTA else m).toDouble()
        }
        val lh = valores["consumo_lh"]
        if (lh != null && kmh != null && kmh > 5) valores["consumo_l100"] = lh / kmh * 100
    }
}

private const val LARGO_REGISTRO = 5_000
private const val RESTOS_ATMA_MS = 200L
private const val INTENTOS_PARADA = 3
// Brake and clutch are top priority: 4 times a second. The research capture cut off after ~1 min of back-to-back
// requests (cause unconfirmed); if the adapter hangs, lower this first.
private const val PERIODO_EXTENDIDO_MS = 250L
private const val ESCUCHA = "escucha"
private val PERIODOS_MS = mapOf(Ritmo.RAPIDO to 500L, Ritmo.MEDIO to 3_000L, Ritmo.LENTO to 15_000L)
private const val MAX_FALLOS_CARROCERIA = 3

internal fun hex2(valor: Int): String = valor.toString(16).uppercase().padStart(2, '0')

/** ISO-TP frames of a [bytes]-long reply: one up to 7 bytes, then a first frame of 6 and consecutive ones of 7. */
internal fun tramasIsoTp(bytes: Int): Int = if (bytes <= 7) 1 else 1 + bytes / 7 // 1 + ceil((bytes - 6) / 7)
