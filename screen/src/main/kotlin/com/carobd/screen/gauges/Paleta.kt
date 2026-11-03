package com.carobd.screen.gauges

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colores del tablero. De noche todo baja de brillo para no encandilar al manejar. */
data class Paleta(
    val fondo: Color,
    val celda: Color,
    val pista: Color,
    val texto: Color,
    val tenue: Color,
    val normal: Color,
    val atencion: Color,
    val peligro: Color,
    val edicion: Color,
) {
    companion object {
        val dia = Paleta(
            fondo = Color(0xFF000000),
            celda = Color(0xFF111418),
            pista = Color(0xFF262B33),
            texto = Color(0xFFECEFF4),
            tenue = Color(0xFF8A93A3),
            normal = Color(0xFF3FA7FF),
            atencion = Color(0xFFFFB020),
            peligro = Color(0xFFFF4D4F),
            edicion = Color(0xFFFFB020),
        )
        val noche = Paleta(
            fondo = Color(0xFF000000),
            celda = Color(0xFF07080A),
            pista = Color(0xFF15181D),
            texto = Color(0xFF9AA1AD),
            tenue = Color(0xFF555C68),
            normal = Color(0xFF2B6CA8),
            atencion = Color(0xFFB37A12),
            peligro = Color(0xFFC23A3C),
            edicion = Color(0xFFB37A12),
        )
    }
}

val LocalPaleta = staticCompositionLocalOf { Paleta.dia }
