package com.carobd.core.obd

import kotlin.math.abs

/**
 * En un manual, RPM / km/h es casi constante por marcha: se elige la relación más cercana de la tabla.
 * Valores iniciales estimados para la caja de 6 del Accent 1.6 CRDi con cubierta 185/65 R15;
 * [aprender] los ajusta al auto real mientras se maneja estable.
 */
class Marchas(relaciones: List<Double> = listOf(120.0, 66.0, 42.0, 31.0, 25.0, 20.0)) {
    private val rel = relaciones.toMutableList()
    val relaciones: List<Double> get() = rel

    private var previa = 0.0
    private var estables = 0

    /** 0 = neutro o embrague pisado (no se puede saber), 1..n = marcha. */
    fun marcha(rpm: Double, kmh: Double): Int {
        if (kmh < 5 || rpm < 600) return 0
        val r = rpm / kmh
        val idx = rel.indices.minByOrNull { abs(rel[it] - r) } ?: return 0
        val tolerancia = rel[idx] * 0.15
        return if (abs(rel[idx] - r) <= tolerancia) idx + 1 else 0
    }

    /**
     * Moves the detected gear's ratio toward the measured one, only after [MUESTRAS_ESTABLES] samples in a row
     * within 3 % (no clutch slip, no shifting). Each ratio stays between its neighbours, so gears never swap.
     * ponytail: learned ratios live only while connected; persist them if the first minutes read wrong.
     */
    fun aprender(rpm: Double, kmh: Double) {
        if (kmh < 15 || rpm < 1000) {
            estables = 0
            return
        }
        val r = rpm / kmh
        estables = if (previa > 0 && abs(r - previa) <= previa * 0.03) estables + 1 else 0
        previa = r
        if (estables < MUESTRAS_ESTABLES) return
        val i = marcha(rpm, kmh) - 1
        if (i < 0) return
        val nuevo = rel[i] + (r - rel[i]) * 0.1
        val techo = rel.getOrNull(i - 1) ?: Double.MAX_VALUE
        val piso = rel.getOrNull(i + 1) ?: 0.0
        if (nuevo < techo && nuevo > piso) rel[i] = nuevo
    }

    private companion object {
        const val MUESTRAS_ESTABLES = 8
    }
}
