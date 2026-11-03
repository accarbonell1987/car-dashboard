package com.carobd.core

import kotlin.math.abs

/** 90600 -> "90.600". Común a todas las plataformas (String.format solo existe en la JVM). */
fun miles(n: Int): String {
    val conPuntos = abs(n).toString().reversed().chunked(3).joinToString(".").reversed()
    return if (n < 0) "-$conPuntos" else conPuntos
}
