package com.carobd.core.obd

/**
 * Testigos de carrocería que se ven en el bus del puerto OBD del Accent 2016, por escucha pasiva (ATMA).
 * Bits confirmados con las capturas guiadas (obd/capturas/elm327-carroceria*.{csv,txt}):
 * - 690 b0 bit 0x40: puerta del conductor abierta (1). Las otras puertas no aparecen en este bus.
 * - 690 b2 bit 0x01: cinturón del conductor SIN abrochar (1). Con el cinturón puesto vale 0.
 * - 350 b1 bit 0x40: aire acondicionado encendido (1).
 * Los valores son 1.0 (activo) o 0.0.
 */
object Carroceria {
    const val PUERTA = "puerta_conductor_abierta"
    const val CINTURON = "cinturon_conductor_suelto"
    const val AIRE = "aire_acondicionado"

    val ids = listOf("690", "350")
    val senales = listOf(PUERTA, CINTURON, AIRE)

    fun decodificar(id: String, datos: List<Int>): Map<String, Double> = when (id) {
        "690" -> if (datos.size > 2) mapOf(PUERTA to bit(datos[0], 0x40), CINTURON to bit(datos[2], 0x01)) else emptyMap()
        "350" -> if (datos.size > 1) mapOf(AIRE to bit(datos[1], 0x40)) else emptyMap()
        else -> emptyMap()
    }

    /** Bytes de la última trama de una salida de ATMA con ATH0 (una trama por línea, sin la ID). */
    fun ultimaTrama(salida: String): List<Int>? = salida.split('\r', '\n')
        .map { it.replace(" ", "").uppercase() }
        .lastOrNull { it.length in 2..16 && it.length % 2 == 0 && it.all { c -> c in HEX } }
        ?.chunked(2)?.map { it.toInt(16) }

    private fun bit(valor: Int, mascara: Int) = if (valor and mascara != 0) 1.0 else 0.0
}

internal const val HEX = "0123456789ABCDEF"
