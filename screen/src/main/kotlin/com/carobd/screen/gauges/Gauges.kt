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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.obd.Senal
import kotlin.math.min
import kotlin.math.roundToInt

object Colores {
    val fondo = Color(0xFF000000)
    val celda = Color(0xFF111418)
    val pista = Color(0xFF262B33)
    val texto = Color(0xFFECEFF4)
    val tenue = Color(0xFF8A93A3)
    val normal = Color(0xFF3FA7FF)
    val alerta = Color(0xFFFFB020)
    val peligro = Color(0xFFFF4D4F)
}

/** Color según qué tan cerca del máximo está la señal (verde zona normal, ámbar al 80 %, rojo al 92 %). */
private fun colorPara(fraccion: Float): Color = when {
    fraccion >= 0.92f -> Colores.peligro
    fraccion >= 0.80f -> Colores.alerta
    else -> Colores.normal
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
fun Gauge(celda: Celda, senal: Senal, valor: Double?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Colores.celda)
            .padding(10.dp),
    ) {
        when (celda.tipo) {
            TipoGauge.ARCO -> GaugeArco(senal, valor)
            TipoGauge.NUMERO -> GaugeNumero(senal, valor)
            TipoGauge.BARRA -> GaugeBarra(senal, valor)
            TipoGauge.MARCHA -> GaugeMarcha(valor)
        }
    }
}

private fun fraccion(valor: Double?, senal: Senal): Float =
    if (valor == null) 0f else ((valor - senal.min) / (senal.max - senal.min)).toFloat().coerceIn(0f, 1f)

@Composable
private fun GaugeArco(senal: Senal, valor: Double?) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val lado = min(maxWidth.value, maxHeight.value)
        val f = fraccion(valor, senal)
        Canvas(Modifier.fillMaxSize()) {
            val grosor = size.minDimension * 0.08f
            val d = size.minDimension - grosor
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            val arcoSize = Size(d, d)
            drawArc(Colores.pista, 135f, 270f, false, topLeft, arcoSize, style = Stroke(grosor, cap = StrokeCap.Round))
            if (f > 0f) {
                drawArc(colorPara(f), 135f, 270f * f, false, topLeft, arcoSize, style = Stroke(grosor, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatear(valor, senal), color = Colores.texto, fontSize = (lado * 0.22f).sp, fontWeight = FontWeight.Bold)
            Text(senal.unidad, color = Colores.tenue, fontSize = (lado * 0.08f).coerceAtLeast(10f).sp)
        }
        Text(
            senal.nombre,
            color = Colores.tenue,
            fontSize = (lado * 0.08f).coerceAtLeast(10f).sp,
            modifier = Modifier.align(Alignment.BottomCenter),
            maxLines = 1,
        )
    }
}

@Composable
private fun GaugeNumero(senal: Senal, valor: Double?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lado = min(maxWidth.value, maxHeight.value)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(senal.nombre, color = Colores.tenue, fontSize = (lado * 0.12f).coerceAtLeast(10f).sp, maxLines = 1)
            Text(
                formatear(valor, senal),
                color = colorPara(fraccion(valor, senal)).takeIf { valor != null } ?: Colores.texto,
                fontSize = (lado * 0.34f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(senal.unidad, color = Colores.tenue, fontSize = (lado * 0.11f).coerceAtLeast(10f).sp)
        }
    }
}

@Composable
private fun GaugeBarra(senal: Senal, valor: Double?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lado = min(maxWidth.value, maxHeight.value)
        val f = fraccion(valor, senal)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(senal.nombre, color = Colores.tenue, fontSize = (lado * 0.12f).coerceAtLeast(10f).sp, maxLines = 1)
            Text(
                "${formatear(valor, senal)} ${senal.unidad}",
                color = Colores.texto,
                fontSize = (lado * 0.22f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Canvas(Modifier.fillMaxWidth().height((lado * 0.1f).dp)) {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(Colores.pista, cornerRadius = r)
                drawRoundRect(colorPara(f), size = Size(size.width * f, size.height), cornerRadius = r)
            }
        }
    }
}

@Composable
private fun GaugeMarcha(valor: Double?) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val lado = min(maxWidth.value, maxHeight.value)
        val m = valor?.roundToInt()
        Text(
            when (m) { null -> "--"; 0 -> "N"; else -> m.toString() },
            color = Colores.texto,
            fontSize = (lado * 0.6f).sp,
            fontWeight = FontWeight.Black,
        )
        Text("Marcha", color = Colores.tenue, fontSize = (lado * 0.1f).coerceAtLeast(10f).sp, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
