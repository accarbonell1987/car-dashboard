package com.carobd.screen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.screen.EstadoTablero
import com.carobd.screen.Seccion
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Icono
import com.carobd.screen.ui.comun.IconoTrazo
import com.carobd.screen.ui.comun.dec
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * La pantalla del auto: barra de estado arriba (56dp), la sección elegida en el medio
 * y las cinco pestañas abajo (72dp, al alcance del pulgar).
 */
@Composable
fun PantallaPrincipal(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    Column(Modifier.fillMaxSize().background(p.fondo)) {
        BarraEstado(e, acciones)
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            when (e.seccion) {
                Seccion.INICIO -> InicioScreen(e, acciones)
                Seccion.TABLERO -> TableroScreen(e, acciones)
                Seccion.DIAGNOSTICO -> DiagnosticoScreen(e)
                Seccion.VEHICULO -> VehiculoScreen(e, acciones)
                Seccion.AJUSTES -> AjustesScreen(e, acciones)
            }
        }
        BarraPestanas(e.seccion, acciones)
    }
}

private val formatoHora = DateTimeFormatter.ofPattern("H:mm", Locale("es"))
private val formatoFecha = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es"))

@Composable
private fun BarraEstado(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    val ahora by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(10_000)
        }
    }
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            // Izquierda: marca y vehículo
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CAROBD", color = p.texto, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(nombreVehiculo(e), color = p.tenue, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Centro: hora y fecha
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ahora.format(formatoHora), color = p.texto, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(ahora.format(formatoFecha), color = p.tenue, fontSize = 12.sp)
            }
            // Derecha: OBD, nube y temperatura exterior
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.esDemo) p.atencion else p.ok))
                    Text(if (e.esDemo) "Demo" else "OBD", color = p.texto, fontSize = 14.sp)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { acciones.irA(Seccion.AJUSTES) }.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val n = e.nube
                    val color = when {
                        !n.configurada -> p.tenue
                        n.error != null -> p.atencion
                        n.pendientes > 0 -> p.texto
                        else -> p.ok
                    }
                    IconoTrazo(Icono.NUBE, color, 18.dp)
                    Text(
                        when {
                            !n.configurada -> "Sin nube"
                            n.sincronizando -> "…"
                            n.pendientes > 0 -> "${n.pendientes}"
                            else -> "Sync"
                        },
                        color = color,
                        fontSize = 14.sp,
                    )
                }
                e.valores["temp_ambiente"]?.let { Text("${dec(it, 0)} °C", color = p.texto, fontSize = 14.sp) }
            }
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.linea))
    }
}

fun nombreVehiculo(e: EstadoTablero): String =
    e.garaje?.vehiculo?.let { "${it.marca} ${it.modelo} ${it.anio}" } ?: "Hyundai Accent 2016"

@Composable
private fun BarraPestanas(actual: Seccion, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    Column {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(p.linea))
        Row(Modifier.fillMaxWidth().height(72.dp)) {
            for (s in Seccion.entries) {
                val activa = s == actual
                val color = if (activa) p.normal else p.tenue
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { selected = activa }
                        .clickable(role = Role.Tab) { acciones.irA(s) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IconoTrazo(iconoDe(s), color, 26.dp)
                    Text(s.etiqueta, color = color, fontSize = 13.sp, fontWeight = if (activa) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

private fun iconoDe(s: Seccion) = when (s) {
    Seccion.INICIO -> Icono.INICIO
    Seccion.TABLERO -> Icono.TABLERO
    Seccion.DIAGNOSTICO -> Icono.DIAGNOSTICO
    Seccion.VEHICULO -> Icono.VEHICULO
    Seccion.AJUSTES -> Icono.AJUSTES
}
