package com.carobd.screen.gauges

import androidx.compose.foundation.Canvas
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
        }
    }
}

private fun fraccion(valor: Double?, senal: Senal): Float =
    if (valor == null) 0f else ((valor - senal.min) / (senal.max - senal.min)).toFloat().coerceIn(0f, 1f)

private fun tamTexto(lado: Float, factor: Float) = (lado * factor).coerceAtLeast(10f).sp

@Composable
private fun GaugeArco(senal: Senal, valor: Double?, nivel: NivelAviso?) {
    val p = LocalPaleta.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val lado = min(maxWidth.value, maxHeight.value)
        val f = fraccion(valor, senal)
        val color = colorPara(p, f, nivel)
        Canvas(Modifier.fillMaxSize()) {
            val grosor = size.minDimension * 0.08f
            val d = size.minDimension - grosor
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            val arcoSize = Size(d, d)
            drawArc(p.pista, 135f, 270f, false, topLeft, arcoSize, style = Stroke(grosor, cap = StrokeCap.Round))
            if (f > 0f) drawArc(color, 135f, 270f * f, false, topLeft, arcoSize, style = Stroke(grosor, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatear(valor, senal), color = if (nivel != null) color else p.texto, fontSize = (lado * 0.22f).sp, fontWeight = FontWeight.Bold)
            Text(senal.unidad, color = p.tenue, fontSize = tamTexto(lado, 0.08f))
        }
        Text(senal.nombre, color = p.tenue, fontSize = tamTexto(lado, 0.08f), modifier = Modifier.align(Alignment.BottomCenter), maxLines = 1)
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

@Composable
private fun GaugeBarra(senal: Senal, valor: Double?, nivel: NivelAviso?) {
    val p = LocalPaleta.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lado = min(maxWidth.value, maxHeight.value)
        val f = fraccion(valor, senal)
        val color = colorPara(p, f, nivel)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(senal.nombre, color = p.tenue, fontSize = tamTexto(lado, 0.12f), maxLines = 1)
            Text("${formatear(valor, senal)} ${senal.unidad}", color = p.texto, fontSize = (lado * 0.22f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Canvas(Modifier.fillMaxWidth().height((lado * 0.1f).dp)) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(p.pista, cornerRadius = r)
                drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
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
