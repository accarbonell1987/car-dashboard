package com.carobd.movil

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.carobd.core.model.DatosGaraje
import com.carobd.core.model.Viaje
import com.carobd.core.sync.ConfigNube
import com.carobd.movil.ui.MantenimientoScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Raíz de la app, igual en iPhone, Android y escritorio. */
@Composable
fun App(datosMovil: DatosMovil = remember { DatosMovil() }) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val scope = rememberCoroutineScope()
        var datos by remember { mutableStateOf<DatosGaraje?>(null) }
        var viajes by remember { mutableStateOf<List<Viaje>>(emptyList()) }
        val nube by datosMovil.nube.estado.collectAsState()

        suspend fun recargar() {
            datos = datosMovil.bitacora.garaje()
            viajes = datosMovil.bitacora.viajes().take(10)
        }

        suspend fun sincronizar() {
            datosMovil.nube.sincronizar()
            recargar()
        }

        LaunchedEffect(Unit) {
            datos = datosMovil.iniciar()
            recargar()
            datosMovil.nube.refrescar()
            // Al abrir y cada 5 minutos con la app abierta; sin red, los cambios esperan.
            while (true) {
                sincronizar()
                delay(5 * 60_000L)
            }
        }

        val actual = datos
        if (actual == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            MantenimientoScreen(
                datos = actual,
                viajes = viajes,
                nube = nube,
                config = datosMovil.nube.config,
                onCambio = { nuevo ->
                    datos = nuevo
                    scope.launch {
                        datosMovil.bitacora.guardarGaraje(actual, nuevo)
                        recargar()
                        sincronizar()
                    }
                },
                onSincronizar = { scope.launch { sincronizar() } },
                onConfigurar = { url, token ->
                    scope.launch {
                        datosMovil.configurar(ConfigNube(url.trim(), token.trim()))
                        sincronizar()
                    }
                },
            )
        }
    }
}
