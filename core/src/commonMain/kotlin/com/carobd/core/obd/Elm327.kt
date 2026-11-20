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
    /** Lee hasta el prompt '>' o hasta que pasen [timeoutMs]. */
    suspend fun leer(timeoutMs: Long): Respuesta
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

    fun lecturas(): Flow<Lectura> = flow {
        val inicio = reloj.markNow()
        var reintento = 0
        while (true) {
            _estado.value = EstadoConexion(if (reintento == 0) FaseConexion.CONECTANDO else FaseConexion.RECONECTANDO, intento = reintento)
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

    private suspend fun enviar(comando: String, timeout: Long = timeoutMs): String {
        transporte.escribir("$comando\r")
        val r = transporte.leer(timeout)
        if (r.completa) fallosSeguidos = 0
        else if (++fallosSeguidos >= intentos) throw AdaptadorSinRespuesta("sin respuesta a $comando")
        return r.texto
    }

    private suspend fun iniciar(): Boolean {
        fallosSeguidos = 0
        var banner: String? = null
        // El clon manda un byte basura antes del banner: se descarta todo lo anterior a "ELM327".
        for (i in 1..intentos) {
            val r = enviar("ATZ", 3000)
            if ("ELM327" in r) {
                banner = "ELM327" + r.substringAfter("ELM327").lineSequence().first().trimEnd()
                break
            }
        }
        banner ?: throw AdaptadorSinRespuesta("ATZ no devolvió el banner ELM327")
        // Protocolo fijo del Accent (ISO 15765-4 CAN 11/500): evita el SEARCHING... de ATSP0.
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6")) enviar(c)
        val soportados = leerSoportados()
        val vin = Decodificador.vin(enviar("0902", 3000))
        senales = Catalogo.estandar.filter {
            it.modo == "01" && it.pid != null && it.id !in excluir && (soportados.isEmpty() || it.pid.toInt(16) in soportados)
        }
        valores.clear()
        ciclo = 0
        carroceriaActiva = true
        fallosCarroceria = 0
        proximaEscucha = reloj.markNow()
        _estado.value = EstadoConexion(FaseConexion.CONECTADO, adaptador = banner, vin = vin, pidsSoportados = soportados.size, carroceria = true)
        return true
    }

    /** PIDs de modo 01 que declara el motor en los bitmaps 0100, 0120, 0140... Vacío si no contesta. */
    private suspend fun leerSoportados(): Set<Int> {
        val soportados = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val pids = Decodificador.pidsSoportados(base, enviar("01" + hex2(base))) ?: break
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
            if (toca) Decodificador.decodificar(enviar(senal.modo + senal.pid), senal)?.let { valores[senal.id] = it }
        }
        if (carroceriaActiva && proximaEscucha.hasPassedNow()) {
            escucharCarroceria()
            proximaEscucha = reloj.markNow() + periodoCarroceriaMs.milliseconds
        }
        derivadas()
        ciclo++
        return valores.toMap()
    }

    /**
     * Escucha pasiva: ATCAF0 (tramas crudas), luego ATCRA<id> + ATMA unos ms por cada ID, se detiene con un byte
     * y se espera el prompt. Al final restaura ATCRA y ATCAF1 para volver a pedir PIDs.
     * Tras 3 escuchas sin tramas o con error se desactiva sola; los PIDs siguen igual.
     */
    private suspend fun escucharCarroceria() {
        var conTramas = 0
        try {
            enviar("ATCAF0")
            for (id in Carroceria.ids) {
                enviar("ATCRA$id")
                transporte.escribir("ATMA\r")
                var r = transporte.leer(ventanaEscuchaMs)
                if (!r.completa) {
                    transporte.escribir(" ")
                    val cierre = transporte.leer(timeoutMs)
                    if (!cierre.completa) throw AdaptadorSinRespuesta("ATMA no se detuvo")
                    r = Respuesta(r.texto + "\r" + cierre.texto, true)
                }
                Carroceria.ultimaTrama(r.texto)?.let {
                    valores += Carroceria.decodificar(id, it)
                    conTramas++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            conTramas = 0
        } finally {
            enviar("ATCRA")
            enviar("ATCAF1")
        }
        fallosCarroceria = if (conTramas > 0) 0 else fallosCarroceria + 1
        if (fallosCarroceria >= 3) {
            carroceriaActiva = false
            Carroceria.senales.forEach { valores.remove(it) }
            _estado.value = _estado.value.copy(carroceria = false)
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

internal fun hex2(valor: Int): String = valor.toString(16).uppercase().padStart(2, '0')
