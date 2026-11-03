package com.carobd.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.gauges.Paleta
import com.carobd.screen.ui.TableroScreen

class MainActivity : ComponentActivity() {
    private val vm: TableroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val estado by vm.estado.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPaleta provides if (estado.esDeNoche) Paleta.noche else Paleta.dia) {
                    TableroScreen(estado, vm)
                }
            }
        }
    }
}
