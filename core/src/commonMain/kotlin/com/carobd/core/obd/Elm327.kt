package com.carobd.core.obd

import com.carobd.core.sim.Lectura
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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
 * y los pide en ciclos: rápidas siempre, medias cada 10 ciclos y lentas cada 50. Cada [periodoCarroceriaMs]
 * escucha el bus unos ms por ID para los testigos de [Carroceria]. Si el adaptador se cuelga, cierra
 * y reconecta con espera creciente; el flujo nunca termina por un error del adaptador.
 */
class Elm327(
    private val transporte: TransporteElm,
    private val excluir: Set<String> = emptySet(),
    private val timeoutMs: Long = 1500,
    private val intentos: Int = 3,
    private val periodoCarroceriaMs: Long = 2000,
    private val ventanaEscuchaMs: Long = 300,
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
    private var proximaEscucha = reloj.markNow()

    /** Testigos de carrocería (escucha ATMA). Se puede apagar en caliente si molestan en algún adaptador. */
    var testigos: Boolean = true
        set(valor) {
            field = valor
            fallosCarroceria = 0 // reactivarlos a mano vuelve a intentar aunque se hubieran apagado solos
        }

    private val _registro = MutableStateFlow<List<String>>(emptyList())
    /** Últimos [LARGO_REGISTRO] comandos y respuestas en crudo, para diagnosticar en el auto. */
    val registro: StateFlow<List<String>> = _registro.asStateFlow()

    private fun anotar(linea: String) {
        _registro.value = (_registro.value + linea).takeLast(LARGO_REGISTRO)
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
        val soportados = leerSoportados()
        val vin = Decodificador.vin(enviar("0902", 3000, responde("4902")))
        senales = Catalogo.estandar.filter {
            it.modo == "01" && it.pid != null && it.id !in excluir && (soportados.isEmpty() || it.pid.toInt(16) in soportados)
        }
        valores.clear()
        ciclo = 0
        carroceriaActiva = testigos
        fallosCarroceria = 0
        proximaEscucha = reloj.markNow()
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

    private suspend fun leerCiclo(): Map<String, Double> {
        for (senal in senales) {
            val toca = when (senal.ritmo) {
                Ritmo.RAPIDO -> true
                Ritmo.MEDIO -> ciclo % 10 == 0L
                Ritmo.LENTO -> ciclo % 50 == 0L
            }
            if (toca) {
                val respuesta = enviar(senal.modo + senal.pid, valida = responde("41" + senal.pid))
                Decodificador.decodificar(respuesta, senal)?.let { valores[senal.id] = it }
            }
        }
        if (!testigos && _estado.value.carroceria) {
            Carroceria.senales.forEach { valores.remove(it) }
            _estado.value = _estado.value.copy(carroceria = false)
        }
        if (testigos && !_estado.value.carroceria && fallosCarroceria < 3) {
            carroceriaActiva = true
            _estado.value = _estado.value.copy(carroceria = true)
        }
        if (testigos && carroceriaActiva && proximaEscucha.hasPassedNow()) {
            escucharCarroceria()
            proximaEscucha = reloj.markNow() + periodoCarroceriaMs.milliseconds
        }
        derivadas()
        ciclo++
        return valores.toMap()
    }

    /**
     * Escucha pasiva: ATCAF0 (tramas crudas), luego ATCRA<id> + ATMA unos ms por cada ID; se detiene con un byte,
     * se drena hasta el prompt y se tiran los restos tardíos. Al final restaura ATCRA y ATCAF1 esperando OK,
     * para volver a pedir PIDs. Tras 3 escuchas sin tramas o con error se desactiva sola; los PIDs siguen igual.
     */
    private suspend fun escucharCarroceria() {
        var conTramas = 0
        try {
            enviar("ATCAF0", valida = ::esOk)
            for (id in Carroceria.ids) {
                enviar("ATCRA$id", valida = ::esOk)
                transporte.descartar()
                transporte.escribir("ATMA\r")
                var r = transporte.leer(ventanaEscuchaMs)
                if (!r.completa) {
                    transporte.escribir(" ")
                    val cierre = transporte.leer(timeoutMs)
                    if (!cierre.completa) throw AdaptadorSinRespuesta("ATMA no se detuvo")
                    r = Respuesta(r.texto + "\r" + cierre.texto, true)
                }
                anotar("ATMA $id → ${legible(r.texto).takeLast(60)}")
                // El clon por BLE sigue mandando restos (y a veces otro '>') un rato después de detenerse.
                delay(RESTOS_ATMA_MS)
                transporte.descartar()
                Carroceria.ultimaTrama(r.texto)?.let {
                    valores += Carroceria.decodificar(id, it)
                    conTramas++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            anotar("testigos: ${e.message}")
            conTramas = 0
        } finally {
            enviar("ATCRA", valida = ::esOk)
            enviar("ATCAF1", valida = ::esOk)
        }
        fallosCarroceria = if (conTramas > 0) 0 else fallosCarroceria + 1
        if (fallosCarroceria >= 3) {
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
        if (rpm != null && kmh != null) valores["marcha"] = marchas.marcha(rpm, kmh).toDouble()
        val lh = valores["consumo_lh"]
        if (lh != null && kmh != null && kmh > 5) valores["consumo_l100"] = lh / kmh * 100
    }
}

private const val LARGO_REGISTRO = 50
private const val RESTOS_ATMA_MS = 200L

internal fun hex2(valor: Int): String = valor.toString(16).uppercase().padStart(2, '0')
