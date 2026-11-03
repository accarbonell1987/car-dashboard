package com.carobd.core.sim

import com.carobd.core.obd.Marchas
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Un instante de datos del auto, con los ids del catálogo como claves. */
data class Lectura(val tMs: Long, val valores: Map<String, Double>)

/**
 * Simula un Accent 1.6 CRDi en un ciclo urbano: arranca frío, acelera, cambia de marcha,
 * frena en semáforos. Sirve para desarrollar sin el auto y para el modo demo.
 */
class SimuladorAccent(private val periodoMs: Long = 100) {
    private val marchas = Marchas()

    fun lecturas(): Flow<Lectura> = flow {
        var t = 0L
        var kmh = 0.0
        var tempRefrig = 22.0
        var combustible = 62.0
        var odometroKm = 0.0
        var faseSemaforo = 0L
        while (true) {
            val seg = t / 1000.0
            // Perfil de velocidad: ondas largas con paradas cada ~90 s.
            val objetivo = if ((seg % 90) < 12) 0.0 else 30 + 40 * (0.5 + 0.5 * sin(seg / 25.0)) + 10 * sin(seg / 7.0)
            kmh += (objetivo - kmh) * 0.03
            kmh = max(0.0, min(130.0, kmh))
            val marcha = marchas.marcha(rpmPara(kmh), kmh).takeIf { it > 0 } ?: marchaPara(kmh)
            val rpm = if (kmh < 3) 800 + 30 * sin(seg * 3) else max(900.0, kmh * marchas.relaciones[marcha - 1])
            val acelerador = if (objetivo > kmh + 1) min(100.0, 20 + (objetivo - kmh) * 3) else 5.0
            val carga = min(100.0, 15 + acelerador * 0.7)
            val map = 100 + (acelerador / 100.0) * 120 * (rpm / 3000.0).coerceIn(0.3, 1.0)
            tempRefrig += ((88.0 - tempRefrig) * 0.0015)
            val consumoLh = 0.6 + carga / 100.0 * 9.0
            combustible -= consumoLh * periodoMs / 3_600_000.0 / 45.0 * 100.0
            odometroKm += kmh * periodoMs / 3_600_000.0
            val voltaje = if (rpm > 700) 14.1 + 0.1 * sin(seg) else 12.4
            emit(
                Lectura(
                    t,
                    mapOf(
                        "rpm" to rpm,
                        "velocidad" to kmh,
                        "carga" to carga,
                        "acelerador" to acelerador,
                        "map" to map,
                        "barometrica" to 101.0,
                        "boost" to (map - 101.0) / 100.0,
                        "maf" to 5 + carga * 1.8,
                        "temp_refrigerante" to tempRefrig,
                        "temp_admision" to 28.0 + carga / 10,
                        "presion_riel" to 300 + carga * 12,
                        "consumo_lh" to consumoLh,
                        "consumo_l100" to if (kmh > 5) consumoLh / kmh * 100 else 0.0,
                        "combustible" to combustible,
                        "voltaje" to voltaje,
                        "temp_ambiente" to 24.0,
                        "temp_dpf" to 180 + carga * 2.5,
                        "tiempo_motor" to seg,
                        "marcha" to marcha.toDouble(),
                        "odometro_viaje_km" to odometroKm,
                    ),
                ),
            )
            faseSemaforo++
            t += periodoMs
            delay(periodoMs)
        }
    }

    private fun marchaPara(kmh: Double): Int = when {
        kmh < 18 -> 1
        kmh < 35 -> 2
        kmh < 55 -> 3
        kmh < 80 -> 4
        else -> 5
    }

    private fun rpmPara(kmh: Double): Double = kmh * marchas.relaciones[marchaPara(kmh) - 1]
}
