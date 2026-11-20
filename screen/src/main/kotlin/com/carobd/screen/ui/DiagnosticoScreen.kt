package com.carobd.screen.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.carobd.screen.ui.comun.IconoTrazo
import com.carobd.screen.ui.comun.Icono
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.miles
import com.carobd.core.model.Mantenimiento
import com.carobd.core.model.NivelAviso
import com.carobd.core.model.texto
import com.carobd.core.obd.Catalogo
import com.carobd.core.tablero.Alertas
import com.carobd.core.tablero.Celda
import com.carobd.core.tablero.TipoGauge
import com.carobd.screen.EstadoTablero
import com.carobd.screen.SENALES_GRAFICA
import com.carobd.screen.gauges.GaugeBarra
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.gauges.formatear
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Fila
import com.carobd.screen.ui.comun.Opcion
import com.carobd.screen.ui.comun.Tarjeta
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlin.math.floor

/** Salud: el estado del motor en vivo, alertas, mantenimiento y el último minuto. */
@Composable
fun DiagnosticoScreen(e: EstadoTablero) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EstadoSenales(e, Modifier.weight(1f))
            EstadoMotor(e, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TiempoReal(e, Modifier.weight(1.3f).fillMaxHeight())
            AlertasYMantenimiento(e, Modifier.weight(1f).fillMaxHeight())
            UltimoMinuto(e, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Every engine signal as a linear bar with its normal band, so the one out of range stands out. */
@Composable
private fun TiempoReal(e: EstadoTablero, modifier: Modifier) {
    Tarjeta(modifier, titulo = "Señales del motor") {
        for (fila in SENALES_SALUD.chunked(2)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (senal in fila.mapNotNull(Catalogo::porId)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { GaugeBarra(senal, e.valores[senal.id], Alertas.nivelDe(senal.id, e.alertas), iconoDe(senal.id)) }
                }
            }
        }
    }
}

private val SENALES_SALUD = listOf("temp_refrigerante", "temp_admision", "voltaje", "presion_riel", "boost", "consumo_l100")

private fun iconoDe(senalId: String) = when (senalId) {
    "voltaje" -> Icono.BATERIA
    "presion_riel" -> Icono.INYECTOR
    "boost" -> Icono.TURBO
    "consumo_l100" -> Icono.SURTIDOR
    "rpm" -> Icono.VELOCIMETRO
    else -> Icono.TERMOMETRO
}

private fun fueraDeRango(e: EstadoTablero) = SENALES_SALUD.count { id ->
    val v = e.valores[id]
    val normal = Alertas.rangoNormal(id)
    v != null && normal != null && v !in normal
}

/** A status tile: icon, headline and detail, coloured by state. */
@Composable
private fun TarjetaEstado(icono: Icono, titulo: String, detalle: String, color: Color, modifier: Modifier) {
    val p = LocalPaleta.current
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconoTrazo(icono, color, 34.dp)
        Column {
            Text(titulo, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(detalle, color = p.tenue, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EstadoSenales(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    val fuera = fueraDeRango(e)
    val leidas = SENALES_SALUD.count { it in e.valores }
    TarjetaEstado(
        Icono.DIAGNOSTICO,
        if (fuera == 0) "Todo en rango" else "$fuera ${if (fuera == 1) "señal fuera" else "señales fuera"} de rango",
        "$leidas de ${SENALES_SALUD.size} señales del motor leídas",
        if (fuera == 0) p.ok else p.atencion,
        modifier,
    )
}

/** Check engine lamp and stored code count, read from the engine (PID 0101). */
@Composable
private fun EstadoMotor(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    val mil = e.valores["mil"]
    val codigos = e.valores["codigos_falla"]?.toInt()
    val (titulo, color) = when (mil) {
        null -> "Check engine sin datos" to p.tenue
        1.0 -> "Check engine encendido" to p.atencion
        else -> "Check engine apagado" to p.ok
    }
    TarjetaEstado(
        Icono.MOTOR,
        titulo,
        when (codigos) { null -> "Conecta el adaptador para leerlo"; 1 -> "1 código guardado"; else -> "$codigos códigos guardados" },
        color,
        modifier,
    )
}

@Composable
private fun AlertasYMantenimiento(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    Tarjeta(modifier, titulo = "Alertas en vivo") {
        if (e.alertas.isEmpty()) {
            Text("Todo en rango", color = p.ok, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        } else {
            for (a in e.alertas.take(3)) {
                val s = Catalogo.porId(a.regla.senalId)
                val color = if (a.regla.nivel == NivelAviso.CRITICO) p.peligro else p.atencion
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconoTrazo(Icono.AVISO, color, 22.dp)
                    Text(
                        "${a.regla.mensaje} · ${s?.let { formatear(a.valor, it) + " " + it.unidad } ?: ""}",
                        color = color,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Text("MANTENIMIENTO", color = p.tenue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp))
        val g = e.garaje
        val odometro = maxOf(g?.odometroKm ?: 0, floor(e.odometroKm).toInt())
        val pendientes = g?.let { Mantenimiento.pendientes(it.items, odometro, Clock.System.now(), TimeZone.currentSystemDefault()) }.orEmpty()
        if (pendientes.isEmpty()) {
            Text("El plan se carga desde la app del móvil al sincronizar.", color = p.tenue, fontSize = 14.sp)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(pendientes, key = { it.item.id }) { pe ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val nombre = pe.item.nombre.lowercase()
                        val icono = when {
                            "aceite" in nombre -> Icono.GOTA
                            "filtro" in nombre -> Icono.FILTRO
                            else -> Icono.LLAVE
                        }
                        IconoTrazo(icono, if (pe.vencido) p.atencion else p.tenue, 20.dp)
                        Text(pe.item.nombre, color = p.texto, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(pe.texto(), color = if (pe.vencido) p.atencion else p.tenue, fontSize = 14.sp)
                    }
                }
            }
        }
        Text("Odómetro ${miles(odometro)} km", color = p.tenue, fontSize = 13.sp)
    }
}

@Composable
private fun UltimoMinuto(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    var elegida by rememberSaveable { mutableStateOf(SENALES_GRAFICA.first()) }
    val senal = Catalogo.porId(elegida)
    val serie = e.series[elegida].orEmpty()
    Tarjeta(modifier, titulo = "Último minuto") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (id in SENALES_GRAFICA) {
                val nombre = when (id) {
                    "temp_refrigerante" -> "Refrig."
                    "voltaje" -> "Voltaje"
                    "boost" -> "Boost"
                    else -> "RPM"
                }
                // Icon only: four labels don't fit the card; the name stays for screen readers.
                Opcion("", id == elegida, Modifier.weight(1f).semantics { contentDescription = nombre }, icono = iconoDe(id)) { elegida = id }
            }
        }
        val actual = e.valores[elegida]
        Row(verticalAlignment = Alignment.Bottom) {
            Text(senal?.let { formatear(actual, it) } ?: "--", color = p.texto, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(" ${senal?.unidad.orEmpty()}", color = p.tenue, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Grafica(serie, Modifier.fillMaxWidth().weight(1f))
        if (serie.size >= 2 && senal != null) {
            Row(Modifier.fillMaxWidth()) {
                Text("mín ${formatear(serie.min(), senal)}", color = p.tenue, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("máx ${formatear(serie.max(), senal)}", color = p.tenue, fontSize = 13.sp)
            }
        }
    }
}

/** Línea simple del último minuto; la escala se ajusta a los valores vistos. */
@Composable
private fun Grafica(serie: List<Double>, modifier: Modifier) {
    val p = LocalPaleta.current
    Canvas(modifier.semantics { contentDescription = "Gráfica del último minuto" }) {
        // Línea base
        drawLine(p.linea, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        if (serie.size < 2) return@Canvas
        val lo = serie.min()
        val hi = serie.max()
        val rango = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
        val paso = size.width / 59f
        val inicioX = size.width - paso * (serie.size - 1)
        val path = Path()
        serie.forEachIndexed { i, v ->
            val x = inicioX + paso * i
            val y = size.height - ((v - lo) / rango).toFloat() * size.height * 0.9f - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, p.normal, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
