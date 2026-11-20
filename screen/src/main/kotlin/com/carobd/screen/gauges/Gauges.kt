package com.carobd.screen.gauges

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.carobd.core.tablero.Alertas
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.model.NivelAviso
import com.carobd.core.obd.Senal
import com.carobd.core.tablero.Celda
import com.carobd.core.tablero.TipoGauge
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Color del valor. Si la señal tiene una alerta activa manda la alerta;
 * si no, se usa la cercanía al máximo de la escala (ámbar al 80 %, rojo al 92 %).
 */
private fun colorPara(p: Paleta, fraccion: Float, nivel: NivelAviso?): Color = when (nivel) {
    NivelAviso.CRITICO -> p.peligro
    NivelAviso.ATENCION -> p.atencion
    else -> when {
        fraccion >= 0.92f -> p.peligro
        fraccion >= 0.80f -> p.atencion
        else -> p.normal
    }
}

fun formatear(valor: Double?, senal: Senal): String {
    if (valor == null) return "--"
    val rango = senal.max - senal.min
    return when {
        rango <= 2.0 -> "%.2f".format(valor)
        rango <= 40.0 -> "%.1f".format(valor)
        else -> valor.roundToInt().toString()
    }
}

@Composable
fun Gauge(celda: Celda, senal: Senal, valor: Double?, nivel: NivelAviso?, modifier: Modifier = Modifier) {
    val p = LocalPaleta.current
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (nivel == NivelAviso.CRITICO) p.peligro.copy(alpha = 0.18f) else p.celda)
            .padding(10.dp),
    ) {
        when (celda.tipo) {
            TipoGauge.ARCO -> GaugeArco(senal, valor, nivel)
            TipoGauge.NUMERO -> GaugeNumero(senal, valor, nivel)
            TipoGauge.BARRA -> GaugeBarra(senal, valor, nivel)
            TipoGauge.MARCHA -> GaugeMarcha(valor)
            TipoGauge.AGUJA -> GaugeAguja(senal, valor, nivel)
        }
    }
}

private fun fraccion(valor: Double?, senal: Senal): Float =
    if (valor == null) 0f else ((valor - senal.min) / (senal.max - senal.min)).toFloat().coerceIn(0f, 1f)

// Mínimo 13sp: las etiquetas se leen de reojo, a casi un metro de la pantalla.
private fun tamTexto(lado: Float, factor: Float) = (lado * factor).coerceAtLeast(13f).sp

/** Smoothed fraction: readings arrive every ~60 ms, a spring keeps the dial from jumping. */
@Composable
private fun fraccionSuave(valor: Double?, senal: Senal): Float {
    val f by animateFloatAsState(fraccion(valor, senal), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow), label = "fraccion")
    return f
}

private fun fraccionDe(v: Double, senal: Senal) = ((v - senal.min) / (senal.max - senal.min)).toFloat().coerceIn(0f, 1f)

// ponytail: only RPM has a red zone without an alert rule; move it to Senal if more signals need one.
private val zonaRoja = mapOf("rpm" to 4000.0)

/** Fraction where the dial turns red: a fixed red zone, else the top of the normal band. */
private fun inicioRojo(senal: Senal): Float? =
    zonaRoja[senal.id]?.let { fraccionDe(it, senal) }
        ?: Alertas.rangoNormal(senal.id)?.endInclusive?.takeIf { it < senal.max }?.let { fraccionDe(it, senal) }

/** 1, 2 or 5 times a power of ten, so the scale reads 0, 1000, 2000… or 0, 50, 100… */
private fun pasoRedondo(bruto: Double): Double {
    val base = 10.0.pow(floor(log10(bruto)))
    return listOf(1.0, 2.0, 5.0, 10.0).first { it * base >= bruto } * base
}

/** A · analog dial with major and minor ticks, a fixed red zone and a sprung needle. */
@Composable
private fun GaugeAguja(senal: Senal, valor: Double?, nivel: NivelAviso?) {
    val p = LocalPaleta.current
    val medidor = rememberTextMeasurer()
    val f = fraccionSuave(valor, senal)
    val rojo = inicioRojo(senal)
    val rango = senal.max - senal.min
    val mayor = pasoRedondo(rango / 6)
    val menor = mayor / if (mayor / 10.0.pow(floor(log10(mayor))) == 2.0) 4 else 5
    val miles = senal.max >= 1000
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lado = min(maxWidth.value, maxHeight.value)
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "${senal.nombre} ${formatear(valor, senal)} ${senal.unidad}" }) {
            val r = min(size.width * 0.46f, size.height * 0.5f)
            val centro = Offset(size.width / 2, size.height * 0.55f)
            fun angulo(fr: Float) = -225f + 270f * fr
            fun punto(fr: Float, radio: Float): Offset {
                val a = Math.toRadians(angulo(fr).toDouble())
                return Offset(centro.x + radio * cos(a).toFloat(), centro.y + radio * sin(a).toFloat())
            }
            if (rojo != null) {
                val d = r * 1.82f
                drawArc(
                    p.peligro, angulo(rojo), 270f * (1f - rojo), false,
                    Offset(centro.x - d / 2, centro.y - d / 2), Size(d, d), style = Stroke(r * 0.07f),
                )
            }
            var v = senal.min
            while (v <= senal.max + 1e-6) {
                val fr = fraccionDe(v, senal)
                val esMayor = abs(v / mayor - (v / mayor).roundToInt()) < 1e-6
                val color = when {
                    rojo != null && fr >= rojo -> p.peligro
                    esMayor -> p.texto
                    else -> p.tenue
                }
                drawLine(color, punto(fr, r), punto(fr, r * if (esMayor) 0.85f else 0.93f), strokeWidth = if (esMayor) 5f else 2.5f)
                if (esMayor) {
                    val texto = (if (miles) v / 1000 else v).roundToInt().toString()
                    val medida = medidor.measure(texto, TextStyle(color = p.texto, fontSize = (r * 0.13f).toSp(), fontWeight = FontWeight.SemiBold))
                    val pos = punto(fr, r * 0.7f)
                    drawText(medida, topLeft = Offset(pos.x - medida.size.width / 2, pos.y - medida.size.height / 2))
                }
                v += menor
            }
            val enRojo = rojo != null && f >= rojo
            drawLine(
                if (enRojo || nivel == NivelAviso.CRITICO) p.peligro else p.atencion,
                centro * 2f - punto(f, r * 0.15f),
                punto(f, r * 0.95f),
                strokeWidth = (r * 0.035f).coerceAtLeast(4f), cap = StrokeCap.Round,
            )
            drawCircle(p.pista, r * 0.07f, centro)
            drawCircle(p.tenue, r * 0.07f, centro, style = Stroke(3f))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = (lado * 0.02f).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatear(valor, senal), color = colorPara(p, f, nivel).takeIf { nivel != null } ?: p.texto, fontSize = (lado * 0.16f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (miles) "${senal.unidad} · escala ×1000" else senal.unidad, color = p.tenue, fontSize = tamTexto(lado, 0.06f))
        }
    }
}

/** B · LED arc: segments light one by one, each coloured by where it sits on the scale. */
@Composable
private fun GaugeArco(senal: Senal, valor: Double?, nivel: NivelAviso?) {
    val p = LocalPaleta.current
    val f = fraccionSuave(valor, senal)
    val rojo = inicioRojo(senal)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val lado = min(maxWidth.value, maxHeight.value)
        Canvas(Modifier.fillMaxSize()) {
            val segmentos = 20
            val paso = 240f / segmentos
            val grosor = size.minDimension * 0.1f
            val d = size.minDimension * 0.86f - grosor
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            for (i in 0 until segmentos) {
                val posicion = (i + 0.5f) / segmentos
                val color = when {
                    nivel == NivelAviso.CRITICO -> p.peligro
                    nivel == NivelAviso.ATENCION -> p.atencion
                    rojo != null && posicion >= rojo -> p.peligro
                    else -> colorPara(p, posicion, null)
                }
                val inicio = 150f + i * paso + 1.5f
                drawArc(p.pista, inicio, paso - 3f, false, topLeft, Size(d, d), style = Stroke(grosor))
                val encendido = (f * segmentos - i).coerceIn(0f, 1f)
                if (encendido > 0f) drawArc(color, inicio, paso - 3f, false, topLeft, Size(d, d), alpha = encendido, style = Stroke(grosor))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatear(valor, senal), color = if (nivel != null) colorPara(p, f, nivel) else p.texto, fontSize = (lado * 0.22f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(senal.unidad, color = p.tenue, fontSize = tamTexto(lado, 0.08f))
        }
        Text(senal.nombre, color = p.tenue, fontSize = tamTexto(lado, 0.08f), modifier = Modifier.align(Alignment.BottomCenter), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GaugeNumero(senal: Senal, valor: Double?, nivel: NivelAviso?) {
    val p = LocalPaleta.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lado = min(maxWidth.value, maxHeight.value)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(senal.nombre, color = p.tenue, fontSize = tamTexto(lado, 0.12f), maxLines = 1)
            Text(
                formatear(valor, senal),
                color = if (valor == null) p.texto else colorPara(p, fraccion(valor, senal), nivel),
                fontSize = (lado * 0.34f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(senal.unidad, color = p.tenue, fontSize = tamTexto(lado, 0.11f))
        }
    }
}

/** D · linear bar: shaded normal band, the value, and a marker for the highest reading of the last seconds. */
@Composable
internal fun GaugeBarra(senal: Senal, valor: Double?, nivel: NivelAviso?) {
    val p = LocalPaleta.current
    val f = fraccionSuave(valor, senal)
    val normal = Alertas.rangoNormal(senal.id)
    val fuera = valor != null && normal != null && valor !in normal
    var pico by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000)
            pico = 0f
        }
    }
    val actual = fraccion(valor, senal)
    if (actual > pico) pico = actual
    val color = when {
        nivel != null -> colorPara(p, f, nivel)
        fuera -> p.atencion
        else -> p.normal
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lado = min(maxWidth.value, maxHeight.value)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(senal.nombre, color = p.tenue, fontSize = tamTexto(lado, 0.12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
                Text(formatear(valor, senal), color = if (nivel != null || fuera) color else p.texto, fontSize = (lado * 0.3f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(" ${senal.unidad}", color = p.tenue, fontSize = tamTexto(lado, 0.11f), modifier = Modifier.padding(bottom = (lado * 0.04f).dp))
            }
            Canvas(Modifier.fillMaxWidth().height((lado * 0.16f).coerceAtLeast(14f).dp)) {
                val alto = size.height * 0.5f
                val y = (size.height - alto) / 2
                val radio = CornerRadius(alto / 2, alto / 2)
                if (normal != null) {
                    val desde = fraccionDe(normal.start.coerceAtLeast(senal.min), senal)
                    val hasta = fraccionDe(normal.endInclusive.coerceAtMost(senal.max), senal)
                    drawRect(p.ok.copy(alpha = 0.2f), Offset(size.width * desde, 0f), Size(size.width * (hasta - desde), size.height))
                }
                drawRoundRect(p.pista, Offset(0f, y), Size(size.width, alto), radio)
                drawRoundRect(color, Offset(0f, y), Size((size.width * f).coerceAtLeast(alto), alto), radio)
                drawLine(p.texto, Offset(size.width * pico, 0f), Offset(size.width * pico, size.height), strokeWidth = 3f)
            }
        }
    }
}

@Composable
private fun GaugeMarcha(valor: Double?) {
    val p = LocalPaleta.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val lado = min(maxWidth.value, maxHeight.value)
        val m = valor?.roundToInt()
        Text(
            when (m) { null -> "--"; 0 -> "N"; else -> m.toString() },
            color = p.texto,
            fontSize = (lado * 0.6f).sp,
            fontWeight = FontWeight.Black,
        )
        Text("Marcha", color = p.tenue, fontSize = tamTexto(lado, 0.1f), modifier = Modifier.align(Alignment.BottomCenter))
    }
}
