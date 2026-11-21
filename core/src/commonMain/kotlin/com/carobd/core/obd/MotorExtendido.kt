package com.carobd.core.obd

/**
 * Engine-only extended block: "22ED01" sent to 7E0 answers "62 ED 01" + 34 bytes (multi-frame ISO-TP).
 * Bits validated by pressing the pedals with the ignition on (obd/codigos-can.md, byte n counted after the echo):
 * - b12 bit 0x01 and b13 bit 0x40: brake switches 1 and 2 (either one = pedal pressed).
 * - b13 bit 0x01: clutch pressed.
 * - b13 bit 0x08: gearbox in neutral.
 * Values are 1.0 (active) or 0.0. Truncated replies are discarded whole.
 */
object MotorExtendido {
    const val COMANDO = "22ED01"
    const val FRENO = "freno_pisado"
    const val EMBRAGUE = "embrague_pisado"
    const val PUNTO_MUERTO = "punto_muerto"
    val senales = listOf(FRENO, EMBRAGUE, PUNTO_MUERTO)
    private const val LARGO = 34

    fun decodificar(respuesta: String): Map<String, Double>? {
        val bytes = Decodificador.unirMultitrama(respuesta) ?: return null
        val inicio = bytes.windowed(3).indexOfFirst { it == listOf(0x62, 0xED, 0x01) }
        if (inicio < 0) return null
        val d = bytes.drop(inicio + 3)
        if (d.size < LARGO) return null
        return mapOf(
            FRENO to bit(d[12] and 0x01 or (d[13] and 0x40)),
            EMBRAGUE to bit(d[13] and 0x01),
            PUNTO_MUERTO to bit(d[13] and 0x08),
        )
    }

    private fun bit(valor: Int) = if (valor != 0) 1.0 else 0.0
}
