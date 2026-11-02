package com.carobd.core.obd

import kotlin.math.abs

/**
 * En un manual, RPM / km/h es casi constante por marcha. Se calibra conduciendo:
 * la app guarda la relación de cada marcha y luego elige la más cercana.
 * Valores iniciales aproximados para el Accent 1.6 CRDi de 5 velocidades; se reemplazan al calibrar.
 */
class Marchas(relaciones: List<Double> = listOf(135.0, 75.0, 50.0, 37.0, 29.0)) {
    private val rel = relaciones.toMutableList()
    val relaciones: List<Double> get() = rel

    /** 0 = neutro o embrague pisado (no se puede saber), 1..n = marcha. */
    fun marcha(rpm: Double, kmh: Double): Int {
        if (kmh < 5 || rpm < 600) return 0
        val r = rpm / kmh
        val idx = rel.indices.minByOrNull { abs(rel[it] - r) } ?: return 0
        val tolerancia = rel[idx] * 0.15
        return if (abs(rel[idx] - r) <= tolerancia) idx + 1 else 0
    }

    fun calibrar(marcha: Int, rpm: Double, kmh: Double) {
        require(marcha in 1..rel.size)
        if (kmh < 5) return
        val r = rpm / kmh
        rel[marcha - 1] = rel[marcha - 1] * 0.7 + r * 0.3
    }
}
