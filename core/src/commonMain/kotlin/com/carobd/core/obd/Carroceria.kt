package com.carobd.core.obd

/**
 * Testigos de carrocería que se ven en el bus del puerto OBD del Accent 2016, por escucha pasiva (ATMA).
 * Bits confirmados con las capturas guiadas (obd/capturas/elm327-carroceria*.{csv,txt}):
 * - 690 b0 bit 0x40: puerta del conductor abierta (1). Las otras puertas no aparecen en este bus.
 * - 690 b2 bit 0x01: cinturón del conductor SIN abrochar (1). Con el cinturón puesto vale 0.
 * - 350 b1 bit 0x40: aire acondicionado encendido (1).
 * Del DBC hyundai_i30_2014 (ver obd/codigos-can.md):
 * - 350 b3: temperatura exterior ×0,5 −40 °C (33 °C en la captura, falta compararla con un termómetro).
 * - 4F0 b5-b7: odómetro del tablero, little-endian ×0,1 km (91 268,7 km, igual al tablero).
 * - 4F1 b2 bits 0-1: aviso de reserva; b0 bit 0x20: luneta térmica.
 * - 545 b6: temperatura del combustible ×0,75 −48 °C.
 * - 5E4 b0 bits 1-2: falla de la dirección asistida.
 * Los testigos valen 1.0 (activo) o 0.0.
 */
object Carroceria {
    const val PUERTA = "puerta_conductor_abierta"
    const val CINTURON = "cinturon_conductor_suelto"
    const val AIRE = "aire_acondicionado"
    const val TEMP_EXTERIOR = "temp_exterior"
    const val ODOMETRO = "odometro_tablero"
    const val RESERVA = "reserva_combustible"
    const val LUNETA = "luneta_termica"
    const val TEMP_COMBUSTIBLE = "temp_combustible"
    const val DIRECCION = "falla_direccion"

    val ids = listOf("690", "350", "4F0", "545", "4F1", "5E4")
    val senales = listOf(PUERTA, CINTURON, AIRE, TEMP_EXTERIOR, ODOMETRO, RESERVA, LUNETA, TEMP_COMBUSTIBLE, DIRECCION)

    /**
     * IDs de la escucha número [n] (una cada 10 s): puertas y A/C siempre, y cada 3 escuchas uno de los lentos por
     * turno, empezando por el odómetro. Se escuchan juntos en una ventana (ver [filtro]); con pocos IDs la máscara
     * deja pasar poco tráfico y el buffer del clon no se llena. Así cada dato lento se refresca cada ~2 min.
     */
    fun idsEscucha(n: Int): List<String> =
        if (n % 3 == 0) ids.take(2) + ids[2 + (n / 3) % (ids.size - 2)] else ids.take(2)

    fun decodificar(id: String, datos: List<Int>): Map<String, Double> = when (id) {
        "690" -> if (datos.size > 2) mapOf(PUERTA to bit(datos[0], 0x40), CINTURON to bit(datos[2], 0x01)) else emptyMap()
        "350" -> if (datos.size > 3) mapOf(AIRE to bit(datos[1], 0x40), TEMP_EXTERIOR to datos[3] * 0.5 - 40) else emptyMap()
        "4F0" -> if (datos.size > 7) mapOf(ODOMETRO to (datos[5] or (datos[6] shl 8) or (datos[7] shl 16)) / 10.0) else emptyMap()
        "4F1" -> if (datos.size > 2) mapOf(RESERVA to bit(datos[2], 0x03), LUNETA to bit(datos[0], 0x20)) else emptyMap()
        "545" -> if (datos.size > 6) mapOf(TEMP_COMBUSTIBLE to datos[6] * 0.75 - 48) else emptyMap()
        "5E4" -> if (datos.isNotEmpty()) mapOf(DIRECCION to bit(datos[0], 0x06)) else emptyMap()
        else -> emptyMap()
    }

    /**
     * ATCF/ATCM (filtro y máscara, 3 dígitos hex) que dejan pasar todos los [ids]: la máscara conserva solo los bits
     * en que coinciden. Puede colar algún otro ID del bus (con 690+350+4F1 pasa también 4F0), por eso se escucha
     * con ATH1 y se separa por ID.
     */
    fun filtro(ids: List<String>): Pair<String, String> {
        val valores = ids.map { it.toInt(16) }
        val mascara = valores.fold(0x7FF) { m, v -> m and (v xor valores.first()).inv() and 0x7FF }
        return hex3(valores.first() and mascara) to hex3(mascara)
    }

    /** La última trama de cada ID en una salida de ATMA con ATH1 y ATS0 ("690" + hasta 8 bytes por línea). */
    fun ultimasTramas(salida: String): Map<String, List<Int>> = salida.split('\r', '\n')
        .map { it.replace(" ", "").uppercase() }
        .filter { it.length in 5..19 && it.length % 2 == 1 && it.all { c -> c in HEX } && it[0] in '0'..'7' }
        .associate { it.take(3) to it.drop(3).chunked(2).map { b -> b.toInt(16) } }

    private fun hex3(valor: Int) = valor.toString(16).uppercase().padStart(3, '0')

    private fun bit(valor: Int, mascara: Int) = if (valor and mascara != 0) 1.0 else 0.0
}

internal const val HEX = "0123456789ABCDEF"
