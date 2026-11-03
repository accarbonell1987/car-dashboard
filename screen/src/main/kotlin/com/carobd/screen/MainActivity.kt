package com.carobd.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.gauges.Paleta
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import com.carobd.screen.ui.PantallaPrincipal

class MainActivity : ComponentActivity() {
    private val vm: TableroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pantallaCompleta()
        setContent {
            val estado by vm.estado.collectAsStateWithLifecycle()
            // Brillo propio de la app (Ajustes > Pantalla); null deja el del sistema.
            LaunchedEffect(estado.brillo) {
                window.attributes = window.attributes.apply {
                    screenBrightness = estado.brillo ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPaleta provides if (estado.esDeNoche) Paleta.noche else Paleta.dia) {
                    // safeDrawing cubre el notch o una barra que aparezca al deslizar.
                    Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
                        PantallaPrincipal(estado, vm)
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pantallaCompleta()
    }

    /** El tablero ocupa toda la pantalla: las barras de Android se ocultan y aparecen solo al deslizar desde el borde. */
    private fun pantallaCompleta() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
