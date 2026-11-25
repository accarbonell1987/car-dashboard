package com.carobd.screen.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/** true con el auto en movimiento: las animaciones se quedan quietas para no distraer (RNF-09). */
val LocalEnMarcha = compositionLocalOf { false }

/** Una animación Lottie de `assets/lottie`. Los archivos los genera `scripts/generar-lottie.py`. */
@Composable
fun AnimacionLottie(animacion: Animacion, modifier: Modifier = Modifier, repetir: Boolean = animacion.repetir) {
    val composicion by rememberLottieComposition(LottieCompositionSpec.Asset("lottie/${animacion.archivo}.json"))
    val enMarcha = LocalEnMarcha.current
    val progreso by animateLottieCompositionAsState(
        composicion,
        iterations = if (repetir) LottieConstants.IterateForever else 1,
        isPlaying = !enMarcha,
    )
    LottieAnimation(composicion, { if (enMarcha && !repetir) 1f else progreso }, modifier)
}

enum class Animacion(val archivo: String, val repetir: Boolean) {
    PROGRESO("progreso", true),
    PAUSA("pausa", true),
    LISTO("listo", false),
}
