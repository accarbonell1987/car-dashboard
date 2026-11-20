package com.carobd.screen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.miles
import com.carobd.screen.EstadoTablero
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Icono
import com.carobd.screen.ui.comun.IconoTrazo
import com.carobd.screen.ui.comun.Tarjeta
import kotlin.math.floor

/** The car: a large silhouette (later the 3D model) and its data as icon tiles. */
@Composable
fun VehiculoScreen(e: EstadoTablero, @Suppress("UNUSED_PARAMETER") acciones: AccionesTablero) {
    val p = LocalPaleta.current
    val v = e.garaje?.vehiculo
    val vin = e.conexion?.vin
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tarjeta(Modifier.weight(1.4f).fillMaxHeight()) {
            Text(nombreVehiculo(e), color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("${v?.motor ?: "1.6 CRDi"} · diésel · manual 6", color = p.tenue, fontSize = 15.sp)
            Silueta(Color(0xFFB8BEC6), Modifier.fillMaxWidth().weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val datos = listOf(
                Triple(Icono.MOTOR, "Motor", v?.motor ?: "1.6 CRDi"),
                Triple(Icono.CAJA, "Caja", "Manual 6"),
                Triple(Icono.SURTIDOR, "Combustible", "Diésel"),
                Triple(Icono.VELOCIMETRO, "Odómetro", "${miles(floor(e.odometroKm).toInt())} km"),
                Triple(Icono.ADAPTADOR, "Adaptador", if (e.esDemo) "Modo demo" else e.fuente),
                // Shortened: the screen is photographed and shared; the full VIN stays in Ajustes.
                Triple(Icono.INFO, "VIN", vin?.let { "${it.take(3)}…${it.takeLast(3)}" } ?: "Sin leer"),
            )
            for (fila in datos.chunked(2)) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((icono, nombre, valor) in fila) DatoVehiculo(icono, nombre, valor, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun DatoVehiculo(icono: Icono, nombre: String, valor: String, modifier: Modifier) {
    val p = LocalPaleta.current
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.celda)
            .border(1.dp, p.linea, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconoTrazo(icono, p.normal, 30.dp)
        Column {
            Text(nombre, color = p.tenue, fontSize = 13.sp)
            Text(valor, color = p.texto, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
