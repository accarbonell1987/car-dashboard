package com.carobd.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carobd.screen.ui.TableroScreen

class MainActivity : ComponentActivity() {
    private val vm: TableroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val estado by vm.estado.collectAsStateWithLifecycle()
                TableroScreen(
                    tablero = estado.tablero,
                    valores = estado.valores,
                    fuente = estado.fuente,
                    editando = estado.editando,
                    onEditar = vm::alternarEdicion,
                    onCambiarCelda = vm::cambiarCelda,
                )
            }
        }
    }
}
