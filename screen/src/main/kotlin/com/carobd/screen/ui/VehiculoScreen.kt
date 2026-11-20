package com.carobd.screen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.miles
import com.carobd.screen.EstadoTablero
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Fila
import com.carobd.screen.ui.comun.Tarjeta
import kotlin.math.floor

/** El vehículo de la pantalla. Por ahora es fijo; el selector de marca, modelo y año llega con multi-vehículo. */
@Composable
fun VehiculoScreen(e: EstadoTablero, @Suppress("UNUSED_PARAMETER") acciones: AccionesTablero) {
    val p = LocalPaleta.current
    val v = e.garaje?.vehiculo
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tarjeta(Modifier.weight(1.4f).fillMaxHeight()) {
            Text(nombreVehiculo(e), color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("${v?.motor ?: "1.6 CRDi"} · diésel · manual", color = p.tenue, fontSize = 15.sp)
            Silueta(Color(0xFFB8BEC6), Modifier.fillMaxWidth().weight(1f))
        }
        Tarjeta(Modifier.weight(1f).fillMaxHeight(), titulo = "Perfil") {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Fila("Marca", v?.marca ?: "Hyundai")
                Fila("Modelo", v?.modelo ?: "Accent")
                Fila("Año", (v?.anio ?: 2016).toString())
                Fila("Motor", v?.motor ?: "1.6 CRDi")
                Fila("Odómetro", "${miles(floor(e.odometroKm).toInt())} km")
                Fila("Señales", "Perfil Accent 1.6 CRDi")
            }
            Text(
                "Elegir otro vehículo (marca, modelo, año y color) llega con la fase de varios vehículos. " +
                    "Los PIDs propios de Hyundai (modo 22) se suman al perfil cuando los descubramos con el vLinker.",
                color = p.tenue,
                fontSize = 14.sp,
            )
        }
    }
}
