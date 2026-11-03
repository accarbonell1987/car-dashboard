package com.carobd.movil

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Vista previa en el Mac sin emulador: ./gradlew :mobile:run */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "CAROBD móvil",
        state = rememberWindowState(width = 400.dp, height = 820.dp),
    ) {
        App()
    }
}
