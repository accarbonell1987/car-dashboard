package com.carobd.core.auto

/**
 * The mood behind the 3D car: the time of day and what the sky is doing. Rain is not here; it already travels in
 * [EstadoVisualAuto.lluvia].
 *
 * @param hora local time as a decimal hour, 0 to 24.
 * @param nubes 0 (clear) to 1 (overcast).
 * @param niebla 0 to 1.
 * @param nieve 0 to 1.
 */
data class AmbienteAuto(val hora: Float, val nubes: Float = 0f, val niebla: Float = 0f, val nieve: Float = 0f) {
    companion object {
        /** From an Open-Meteo (WMO) weather code; without one (no location permission) only the clock counts. */
        fun de(codigoClima: Int?, hora: Float): AmbienteAuto = when (codigoClima) {
            null, 0 -> AmbienteAuto(hora)
            1 -> AmbienteAuto(hora, nubes = 0.15f)
            2 -> AmbienteAuto(hora, nubes = 0.5f)
            3 -> AmbienteAuto(hora, nubes = 1f)
            45, 48 -> AmbienteAuto(hora, nubes = 0.8f, niebla = 1f)
            71, 77 -> AmbienteAuto(hora, nubes = 1f, nieve = 0.4f)
            73, 85 -> AmbienteAuto(hora, nubes = 1f, nieve = 0.7f)
            75, 86 -> AmbienteAuto(hora, nubes = 1f, nieve = 1f)
            in 51..67, in 80..82 -> AmbienteAuto(hora, nubes = 0.9f)
            in 95..99 -> AmbienteAuto(hora, nubes = 1f)
            else -> AmbienteAuto(hora, nubes = 0.5f)
        }
    }
}
