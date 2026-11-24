package com.carobd.core.auto

import com.carobd.core.obd.MotorExtendido

/**
 * Ids for the lights in [com.carobd.core.sim.Lectura.valores]. The Accent gives none of them over OBD (only the
 * brake, speed and door are real), so today they come from the manual toggles or the demo. A future source that
 * does read them (a CAN sniffer on the body bus, a light sensor) only has to put these keys in its readings:
 * [EstadoVisualAuto.combinar] prefers a real value over a toggle, and the drawing never changes.
 */
object SenalesLuces {
    const val POSICION = "luz_posicion"
    const val BAJA = "luz_baja"
    const val ALTA = "luz_alta"
    const val GIRO_IZQ = "intermitente_izq"
    const val GIRO_DER = "intermitente_der"
    const val BALIZAS = "balizas"
    /** Rain from 0 to 1, e.g. a rain sensor; when present it wins over the weather. */
    const val LLUVIA = "lluvia"
}

/** Which lights are on. Left and right are the car's own (driver's side is the left one). */
data class LucesAuto(
    val posicion: Boolean = false,
    val baja: Boolean = false,
    val alta: Boolean = false,
    val giroIzq: Boolean = false,
    val giroDer: Boolean = false,
    val balizas: Boolean = false,
    val stop: Boolean = false,
) {
    /** Hazards blink both sides together, over any turn signal. */
    val parpadeaIzq get() = balizas || giroIzq
    val parpadeaDer get() = balizas || giroDer
    /** The tail and position lamps are lit with any headlight. */
    val posicionEncendida get() = posicion || baja || alta

    /** OR of two sets: the demo cycle plus whatever the user toggled. */
    operator fun plus(o: LucesAuto) = LucesAuto(
        posicion || o.posicion, baja || o.baja, alta || o.alta, giroIzq || o.giroIzq, giroDer || o.giroDer, balizas || o.balizas, stop || o.stop,
    )
}

/**
 * Everything the animated car shows, already merged from every source. The UI only reads this: it does not know
 * whether a light is real, toggled by hand or from the demo.
 *
 * @param lluvia 0 (dry) to 1 (storm).
 */
data class EstadoVisualAuto(
    val luces: LucesAuto = LucesAuto(),
    val lluvia: Float = 0f,
    val velocidadKmh: Float = 0f,
) {
    /** Nothing moves: no blinking, no rain, no wind. The drawing can stop its clock. */
    val quieto get() = !luces.parpadeaIzq && !luces.parpadeaDer && lluvia <= 0f && velocidadKmh < 1f

    companion object {
        /**
         * Merges the sources, real data first.
         * - A light comes from [valores] when the reading has it ([SenalesLuces]); otherwise from [manual] plus [demo].
         * - The brake light is the real pedal ([MotorExtendido.FRENO], block 22ED01) when known; otherwise manual.
         * - Rain: [lluviaManual] (a test override) > [SenalesLuces.LLUVIA] > the weather and the demo.
         */
        fun combinar(
            valores: Map<String, Double>,
            manual: LucesAuto = LucesAuto(),
            demo: EscenaDemo? = null,
            lluviaClima: Float = 0f,
            lluviaManual: Float? = null,
        ): EstadoVisualAuto {
            val aMano = manual + (demo?.luces ?: LucesAuto())
            fun luz(id: String, otra: Boolean) = valores[id]?.let { it >= 0.5 } ?: otra
            val luces = LucesAuto(
                posicion = luz(SenalesLuces.POSICION, aMano.posicion),
                baja = luz(SenalesLuces.BAJA, aMano.baja),
                alta = luz(SenalesLuces.ALTA, aMano.alta),
                giroIzq = luz(SenalesLuces.GIRO_IZQ, aMano.giroIzq),
                giroDer = luz(SenalesLuces.GIRO_DER, aMano.giroDer),
                balizas = luz(SenalesLuces.BALIZAS, aMano.balizas),
                stop = luz(MotorExtendido.FRENO, aMano.stop),
            )
            val lluvia = lluviaManual
                ?: valores[SenalesLuces.LLUVIA]?.toFloat()
                ?: maxOf(lluviaClima, demo?.lluvia ?: 0f)
            return EstadoVisualAuto(
                luces = luces,
                lluvia = lluvia.coerceIn(0f, 1f),
                velocidadKmh = (valores["velocidad"] ?: 0.0).toFloat().coerceAtLeast(0f),
            )
        }

        /** How hard it rains for a WMO weather code (Open-Meteo), 0 when it does not. */
        fun intensidadLluvia(codigoClima: Int): Float = when (codigoClima) {
            51 -> 0.25f
            53, 56 -> 0.35f
            55, 57 -> 0.45f
            61, 66 -> 0.5f
            63 -> 0.7f
            65, 67 -> 1f
            80 -> 0.5f
            81 -> 0.75f
            82 -> 1f
            in 95..99 -> 1f
            else -> 0f
        }
    }
}

/** What the demo shows at a given moment, on top of the simulator's speed and brake. */
data class EscenaDemo(val luces: LucesAuto, val lluvia: Float)

/**
 * Demo cycle of [PERIODO_MS] so every animation gets shown without the car: left signal, right signal, hazards,
 * then night driving with low beam, a flash of high beam, and rain in the second half.
 */
object DemoAuto {
    const val PERIODO_MS = 48_000L

    fun en(tMs: Long): EscenaDemo {
        val s = ((tMs % PERIODO_MS + PERIODO_MS) % PERIODO_MS) / 1000.0
        val luces = LucesAuto(
            giroIzq = s < 6,
            giroDer = s >= 8 && s < 14,
            balizas = s >= 16 && s < 22,
            posicion = s >= 24,
            baja = s >= 27,
            alta = s >= 34 && s < 40,
        )
        return EscenaDemo(luces, lluvia = if (s >= 24 && s < 46) 0.7f else 0f)
    }
}
