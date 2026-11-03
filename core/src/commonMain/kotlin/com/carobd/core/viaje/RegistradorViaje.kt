package com.carobd.core.viaje

import com.carobd.core.model.LecturaOdometro
import com.carobd.core.model.OrigenOdometro
import com.carobd.core.model.OrigenViaje
import com.carobd.core.model.Viaje
import com.carobd.core.sim.Lectura
import kotlinx.datetime.Instant
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Convierte el flujo de lecturas del OBD en viajes.
 *
 * - Empieza cuando el motor arranca (RPM sobre [RPM_ENCENDIDO]).
 * - Termina cuando el motor lleva [ESPERA_FIN_MS] apagado o sin lecturas, o al llamar [cerrar].
 * - Los km salen de integrar la velocidad en el tiempo; los litros, de integrar el consumo en L/h.
 * - Un viaje de menos de [KM_MINIMO] y [SEG_MINIMO] se descarta (arrancar para mover el auto en el garaje).
 *
 * `reloj` da la hora real de cada lectura; `nuevoId` genera los UUID del dispositivo.
 */
class RegistradorViaje(
    private val vehiculoId: String,
    private val reloj: (Lectura) -> Instant,
    private val nuevoId: () -> String,
) {
    companion object {
        const val RPM_ENCENDIDO = 400.0
        const val ESPERA_FIN_MS = 60_000L
        const val KM_MINIMO = 0.2
        const val SEG_MINIMO = 120L

        /** Hueco máximo entre lecturas que se integra; si se corta la conexión no se inventan km. */
        const val HUECO_MAX_MS = 5_000L
    }

    private class EnCurso(val inicio: Instant, val inicioMs: Long) {
        var km = 0.0
        var litros = 0.0
        var hayConsumo = false
        var velMax = 0.0
        var rpmMax = 0.0
        var tempMax: Double? = null
        var voltajeArranque: Double? = null
        var segEnMovimiento = 0.0
        var ultimoMs = inicioMs
        var ultimoEncendidoMs = inicioMs
        var fin: Instant = inicio
    }

    private var actual: EnCurso? = null
    private var ultima: Lectura? = null

    val enCurso: Boolean get() = actual != null
    val kmEnCurso: Double get() = actual?.km ?: 0.0
    val segEnCurso: Long get() = actual?.let { (it.ultimoMs - it.inicioMs) / 1000 } ?: 0L
    /** Consumo medio del viaje en curso, si la ECU informa consumo y ya hay distancia para que tenga sentido. */
    val l100EnCurso: Double? get() = actual?.takeIf { it.hayConsumo && it.km > 0.5 }?.let { it.litros / it.km * 100 }

    /** Procesa una lectura. Devuelve el viaje si con ella terminó uno. */
    fun procesar(l: Lectura): Viaje? {
        val rpm = l.valores["rpm"] ?: 0.0
        val encendido = rpm >= RPM_ENCENDIDO
        val v = actual
        val previa = ultima
        ultima = l

        if (v == null) {
            if (encendido) actual = EnCurso(reloj(l), l.tMs).also { acumular(it, l, null) }
            return null
        }
        if (previa != null) acumular(v, l, previa)
        if (encendido) v.ultimoEncendidoMs = l.tMs
        return if (l.tMs - v.ultimoEncendidoMs >= ESPERA_FIN_MS) terminar() else null
    }

    /** Cierra el viaje en curso, p. ej. al perder la conexión con el adaptador o al cerrar la app. */
    fun cerrar(): Viaje? = if (actual != null) terminar() else null

    private fun acumular(v: EnCurso, l: Lectura, previa: Lectura?) {
        val dtMs = if (previa == null) 0L else (l.tMs - previa.tMs).coerceIn(0L, HUECO_MAX_MS)
        val horas = dtMs / 3_600_000.0
        val kmh = l.valores["velocidad"] ?: 0.0
        v.km += kmh * horas
        if (kmh > 3) v.segEnMovimiento += dtMs / 1000.0
        l.valores["consumo_lh"]?.let { v.litros += it * horas; v.hayConsumo = true }
        v.velMax = max(v.velMax, kmh)
        v.rpmMax = max(v.rpmMax, l.valores["rpm"] ?: 0.0)
        l.valores["temp_refrigerante"]?.let { t -> v.tempMax = max(v.tempMax ?: t, t) }
        // Voltaje con el motor recién arrancado: dice cómo está la batería y el alternador.
        if (v.voltajeArranque == null) l.valores["voltaje"]?.let { v.voltajeArranque = it }
        v.ultimoMs = l.tMs
        v.fin = reloj(l)
    }

    private fun terminar(): Viaje? {
        val v = actual ?: return null
        actual = null
        val seg = ((v.ultimoMs - v.inicioMs).milliseconds.inWholeSeconds)
        if (v.km < KM_MINIMO && seg < SEG_MINIMO) return null
        return Viaje(
            id = nuevoId(),
            vehiculoId = vehiculoId,
            inicio = v.inicio,
            fin = v.fin,
            km = (v.km * 100).roundToInt() / 100.0,
            duracionSeg = seg,
            consumoLitros = if (v.hayConsumo) (v.litros * 100).roundToInt() / 100.0 else null,
            velocidadMaxKmh = v.velMax,
            velocidadMediaKmh = if (v.segEnMovimiento > 0) (v.km / (v.segEnMovimiento / 3600.0) * 10).roundToInt() / 10.0 else null,
            rpmMax = v.rpmMax.roundToInt(),
            tempRefrigeranteMaxC = v.tempMax,
            voltajeArranqueV = v.voltajeArranque,
            origen = OrigenViaje.OBD,
            updatedAt = v.fin,
        )
    }
}

/**
 * Odómetro calculado tras un viaje: el último conocido más los km del viaje.
 * Lleva los decimales acumulados aparte para no perder km al redondear viaje a viaje.
 */
object OdometroCalculado {
    data class Estado(val kmExactos: Double)

    fun trasViaje(estado: Estado, viaje: Viaje, nuevoId: () -> String): Pair<Estado, LecturaOdometro> {
        val nuevo = Estado(estado.kmExactos + viaje.km)
        val lectura = LecturaOdometro(
            id = nuevoId(),
            vehiculoId = viaje.vehiculoId,
            fecha = viaje.fin,
            km = nuevo.kmExactos.toInt(),
            origen = OrigenOdometro.CALCULADO,
            updatedAt = viaje.fin,
        )
        return nuevo to lectura
    }
}
