package com.carobd.movil

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.carobd.movil.ui.MantenimientoScreen

/** Raíz de la app, igual en iPhone, Android y escritorio. */
@Composable
fun App(garaje: GarajeLocal = remember { GarajeLocal() }) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var datos by remember { mutableStateOf(garaje.cargar()) }
        MantenimientoScreen(
            datos = datos,
            onCambio = { nuevo ->
                datos = nuevo
                garaje.guardar(nuevo)
            },
        )
    }
}
