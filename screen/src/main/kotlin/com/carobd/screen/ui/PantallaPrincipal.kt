package com.carobd.screen.ui

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.EstadoActualizacion
import com.carobd.screen.Actualizador
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import com.carobd.core.obd.Carroceria
import com.carobd.core.obd.FaseConexion
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
        AvisoActualizacion()
        CompositionLocalProvider(LocalEnMarcha provides ((e.valores["velocidad"] ?: 0.0) > 0.0)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                val asistente = e.asistente
                if (asistente != null) {
                    AsistenteScreen(e, asistente, acciones)
                } else {
                    when (e.seccion) {
                        Seccion.INICIO -> InicioScreen(e, acciones)
                        Seccion.TABLERO -> TableroScreen(e, acciones)
                        Seccion.DIAGNOSTICO -> DiagnosticoScreen(e, acciones)
                        Seccion.VEHICULO -> VehiculoScreen(e, acciones)
                        Seccion.AJUSTES -> AjustesScreen(e, acciones)
                    }
                }
                PanelVoz(acciones.voz, Modifier.align(Alignment.BottomCenter))
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
                Text("CAROBD", color = p.texto, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(nombreVehiculo(e), color = p.tenue, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Centro: hora y fecha
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ahora.format(formatoHora), color = p.texto, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(ahora.format(formatoFecha), color = p.tenue, fontSize = 14.sp)
            }
            // Derecha: OBD, nube y temperatura exterior
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                BotonHablar(acciones.voz)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val conectado = e.conexion?.fase == FaseConexion.CONECTADO
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (!e.esDemo && conectado) p.ok else p.atencion))
                    Text(if (e.esDemo) "Demo" else if (conectado) "OBD" else "Sin conexión", color = p.texto, fontSize = 16.sp)
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
                    IconoTrazo(Icono.NUBE, color, 22.dp)
                    Text(
                        when {
                            !n.configurada -> "Sin nube"
                            n.sincronizando -> "…"
                            n.pendientes > 0 -> "${n.pendientes}"
                            else -> "Sync"
                        },
                        color = color,
                        fontSize = 16.sp,
                    )
                }
                // Outside temperature: the A/C sensor on the bus first, else the weather.
                // The engine's "ambient" PID reads the hot air under the bonnet, so it is never used here.
                val clima by ClimaActual.clima.collectAsState()
                (e.valores[Carroceria.TEMP_EXTERIOR] ?: clima?.temperatura)?.let { Text("${dec(it, 0)} °C", color = p.texto, fontSize = 16.sp) }
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
                    Text(s.etiqueta, color = color, fontSize = 15.sp, fontWeight = if (activa) FontWeight.SemiBold else FontWeight.Normal)
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

/** Checks GitHub on start and every 6 hours; when a newer APK exists, one tap downloads and installs it. */
@Composable
private fun AvisoActualizacion() {
    val p = LocalPaleta.current
    val context = LocalContext.current
    val alcance = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            Actualizador.buscar(context)
            delay(6 * 60 * 60_000L)
        }
    }
    val estado by Actualizador.estado.collectAsState()
    val texto = when (val s = estado) {
        is EstadoActualizacion.Disponible -> "Hay una versión nueva: ${s.apk.nombre}"
        is EstadoActualizacion.Descargando -> "Descargando ${s.apk.nombre}… ${(s.progreso * 100).toInt()} %"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(p.normal.copy(alpha = 0.18f)).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, color = p.texto, fontSize = 17.sp, modifier = Modifier.weight(1f))
        val s = estado
        if (s is EstadoActualizacion.Disponible) {
            Boton("Actualizar", principal = true) { alcance.launch { Actualizador.instalar(context, s.apk) } }
        }
    }
}

