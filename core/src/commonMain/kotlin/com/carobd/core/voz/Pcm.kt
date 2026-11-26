package com.carobd.core.voz

/**
 * Audio PCM de 16 bits, mono, little-endian (lo que piden AudioRecord y la Realtime API).
 * Algunas pantallas no graban a 24 kHz: se graba a lo que acepten y se convierte con interpolación lineal.
 */
object Pcm {
    fun aBytes(muestras: ShortArray, cantidad: Int = muestras.size): ByteArray {
        val b = ByteArray(cantidad * 2)
        for (i in 0 until cantidad) {
            val v = muestras[i].toInt()
            b[2 * i] = (v and 0xFF).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    fun convertir(entrada: ShortArray, cantidad: Int, desdeHz: Int, aHz: Int): ShortArray {
        if (desdeHz == aHz) return entrada.copyOf(cantidad)
        if (cantidad == 0) return ShortArray(0)
        val salida = ShortArray((cantidad.toLong() * aHz / desdeHz).toInt())
        val paso = desdeHz.toDouble() / aHz
        for (i in salida.indices) {
            val pos = i * paso
            val j = pos.toInt().coerceAtMost(cantidad - 1)
            val k = (j + 1).coerceAtMost(cantidad - 1)
            val f = pos - j
            salida[i] = (entrada[j] * (1 - f) + entrada[k] * f).toInt().toShort()
        }
        return salida
    }
}
