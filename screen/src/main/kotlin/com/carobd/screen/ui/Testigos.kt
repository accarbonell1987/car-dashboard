package com.carobd.screen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import com.carobd.core.obd.Carroceria
import com.carobd.screen.gauges.LocalPaleta

/**
 * How a telltale moves while it is on. [ciclo] runs 0..1 forever with [periodoMs];
 * [encendido] eases 0..1 so the icon settles back smoothly when it turns off.
 */
private class Testigo(
    val id: String,
    val nombre: String,
    val color: Color,
    val periodoMs: Int,
    val repetir: RepeatMode,
    val dibujar: DrawScope.(color: Color, encendido: Float, ciclo: Float) -> Unit,
)

/** Body telltales (passive bus listening): only shown while the adapter is reading them. */
@Composable
internal fun Testigos(valores: Map<String, Double>) {
    val p = LocalPaleta.current
    val testigos = listOf(
        Testigo(Carroceria.PUERTA, "Puerta abierta", p.atencion, 900, RepeatMode.Reverse, DrawScope::puerta),
        Testigo(Carroceria.CINTURON, "Cinturón sin abrochar", p.peligro, 500, RepeatMode.Reverse, DrawScope::cinturon),
        // Steady like the real lamp: no loop (periodoMs = 0).
        Testigo("mil", "Check engine", p.atencion, 0, RepeatMode.Restart, DrawScope::motor),
        // ponytail: 60° per period is seamless because the snowflake has six-fold symmetry.
        Testigo(Carroceria.AIRE, "Aire acondicionado", p.normal, 1200, RepeatMode.Restart, DrawScope::copoDeNieve),
    ).filter { valores[it.id] != null }
    if (testigos.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (t in testigos) key(t.id) {
            val codigos = valores["codigos_falla"]?.toInt()?.takeIf { t.id == "mil" && it > 0 }
            IconoTestigo(t, valores[t.id] == 1.0, codigos)
        }
    }
}

@Composable
private fun IconoTestigo(t: Testigo, activo: Boolean, insignia: Int? = null) {
    val p = LocalPaleta.current
    val color by animateColorAsState(if (activo) t.color else p.tenue, tween(250), label = "color")
    val fondo by animateColorAsState(if (activo) t.color.copy(alpha = 0.18f) else p.pista, tween(250), label = "fondo")
    val encendido by animateFloatAsState(if (activo) 1f else 0f, tween(350, easing = FastOutSlowInEasing), label = "encendido")
    // Small overshoot when it lights up, like a physical lamp catching your eye.
    val escala by animateFloatAsState(
        if (activo) 1f else 0.9f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow),
        label = "escala",
    )
    // Only animate while on: a resting dashboard should not burn frames.
    val ciclo = if (activo && t.periodoMs > 0) {
        rememberInfiniteTransition(label = t.id).animateFloat(
            0f, 1f, infiniteRepeatable(tween(t.periodoMs, easing = LinearEasing), t.repetir), label = "ciclo",
        ).value
    } else 0f

    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(RoundedCornerShape(12.dp))
            .background(fondo)
            .semantics {
                contentDescription = if (insignia == null) t.nombre else "${t.nombre}, $insignia códigos de falla"
                stateDescription = if (activo) "encendido" else "apagado"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp)) {
            // Icons are authored on a 24x24 grid, like web SVG icons.
            scale(size.minDimension / 24f, pivot = Offset.Zero) { t.dibujar(this, color, encendido, ciclo) }
        }
        if (insignia != null) {
            Text(
                "$insignia",
                color = p.fondo,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).clip(CircleShape).background(t.color).padding(horizontal = 5.dp),
            )
        }
    }
}

private val trazo = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private fun svg(d: String) = PathParser().parsePathString(d).toPath()

// Car seen from above, driver door on the left (LHD).
private val carroceria = svg("M9 3h6a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-6a3 3 0 0 1 -3 -3v-12a3 3 0 0 1 3 -3z M8.5 8.5h7 M8.5 16.5h7")
private val hoja = svg("M6 9v5")

private fun DrawScope.puerta(color: Color, encendido: Float, ciclo: Float) {
    drawPath(carroceria, color, style = trazo)
    // The door swings out around its front hinge and keeps breathing while open.
    rotate(encendido * (30f + 15f * ciclo), pivot = Offset(6f, 9f)) { drawPath(hoja, color, style = trazo) }
}

// Seated person with a diagonal belt.
private val persona = svg("M12 2.5a2 2 0 1 1 0 4a2 2 0 1 1 0 -4z M7.5 21v-6a4.5 4.5 0 0 1 9 0v6")
private val banda = svg("M9 10.5l7 8")

// Engine block with the oil cap, like the check engine lamp.
private val bloque = svg("M8 6h4 M10 6v3 M6 9h8l2 2h2v-2h2v7h-2v-2h-2l-2 3h-6l-2-2h-2v-5h2z")

private fun DrawScope.motor(color: Color, encendido: Float, ciclo: Float) {
    drawPath(bloque, color, style = trazo)
}

private fun DrawScope.cinturon(color: Color, encendido: Float, ciclo: Float) {
    drawPath(persona, color, style = trazo)
    // Blinks like the real telltale while unbuckled.
    drawPath(banda, color, alpha = 1f - encendido * 0.8f * ciclo, style = trazo)
}

// One arm with its two chevrons; drawn three times, 60° apart.
private val brazo = svg("M12 2v20 M9.5 3.5l2.5 2l2.5 -2 M9.5 20.5l2.5 -2l2.5 2")

private fun DrawScope.copoDeNieve(color: Color, encendido: Float, ciclo: Float) {
    val centro = Offset(12f, 12f)
    withTransform({ rotate(encendido * ciclo * 60f, centro) }) {
        for (angulo in 0 until 180 step 60) rotate(angulo.toFloat(), centro) { drawPath(brazo, color, style = trazo) }
    }
}
