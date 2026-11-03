package com.carobd.screen.ui

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
import com.carobd.screen.gauges.Gauge
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.gauges.formatear
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Fila
import com.carobd.screen.ui.comun.Opcion
import com.carobd.screen.ui.comun.Tarjeta
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlin.math.floor

/** Diagnóstico: el estado del motor en vivo, alertas, mantenimiento y el último minuto. */
@Composable
fun DiagnosticoScreen(e: EstadoTablero) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TiempoReal(e, Modifier.fillMaxWidth().weight(1.3f))
            CodigosFalla(e, Modifier.fillMaxWidth().weight(1f))
        }
        AlertasYMantenimiento(e, Modifier.weight(1f).fillMaxHeight())
        UltimoMinuto(e, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TiempoReal(e: EstadoTablero, modifier: Modifier) {
    Tarjeta(modifier, titulo = "En tiempo real") {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (senal in listOf("rpm", "temp_refrigerante").mapNotNull(Catalogo::porId)) {
                val id = senal.id
                Gauge(Celda("diag-$id", id, TipoGauge.ARCO, 0, 0), senal, e.valores[id], Alertas.nivelDe(id, e.alertas), Modifier.weight(1f).fillMaxHeight())
            }
        }
        for (senal in listOf("voltaje", "temp_aceite").mapNotNull(Catalogo::porId)) {
            val id = senal.id
            val nivel = Alertas.nivelDe(id, e.alertas)
            val p = LocalPaleta.current
            Fila(
                senal.nombre,
                "${formatear(e.valores[id], senal)} ${senal.unidad}",
                when (nivel) { NivelAviso.CRITICO -> p.peligro; NivelAviso.ATENCION -> p.atencion; else -> null },
            )
        }
    }
}

@Composable
private fun CodigosFalla(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    Tarjeta(modifier, titulo = "Códigos de falla") {
        Text(
            "Leer y borrar los códigos de la luz de check (modos 03, 07 y 04) llega en la fase 4, cuando probemos el vLinker en el auto.",
            color = p.tenue,
            fontSize = 14.sp,
        )
        Box(Modifier.weight(1f))
        Boton("Leer códigos", Modifier.fillMaxWidth(), activo = false) {}
    }
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
                Text(
                    "${a.regla.mensaje} · ${s?.let { formatear(a.valor, it) + " " + it.unidad } ?: ""}",
                    color = if (a.regla.nivel == NivelAviso.CRITICO) p.peligro else p.atencion,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
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
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
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
                Opcion(nombre, id == elegida, Modifier.weight(1f)) { elegida = id }
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
