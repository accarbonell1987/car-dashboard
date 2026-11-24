package com.carobd.screen.ui.auto

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.carobd.core.auto.EstadoVisualAuto
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 1.5 Hz, like the Accent's flasher relay. Hazards and signals share this clock, so both sides stay in step. */
private const val PERIODO_GIRO_MS = 667L

private val AMBAR = Color(0xFFFFA41C)
private val BLANCO_CALIDO = Color(0xFFFFF3DC)
private val BLANCO_AZULADO = Color(0xFFD9E7FF)
private val ROJO = Color(0xFFFF2414)
private val GOTA = Color(0xFFBFD8FF)
private val AIRE = Color(0xFFCFE6FF)

/**
 * The car with its animations: [foto] (the picture today, a 3D view tomorrow) between two Canvas layers.
 * - Behind: the wind streaks that pass behind the body (the opaque photo hides them, which gives depth).
 * - In front: lamp glows, beams, brake reflection on the ground, rain and the rest of the wind.
 *
 * Every position comes from [anclas]. One frame clock drives blinking, rain and wind; it only runs while
 * something moves ([EstadoVisualAuto.quieto] stops it), and all drawing reads state in the draw phase, so a
 * frame never recomposes. Brushes and paths are built once; per frame there are only transforms and draws.
 *
 * @param tiempoFijoMs freezes the clock (previews and screenshots).
 */
@Composable
fun AutoAnimado(
    estado: EstadoVisualAuto,
    modifier: Modifier = Modifier,
    anclas: AnclasFoto = ANCLAS_ACCENT,
    tiempoFijoMs: Long? = null,
    foto: @Composable () -> Unit,
) {
    val reloj = remember { Reloj() }
    val fijo = tiempoFijoMs != null
    val luces = estado.luces
    // On/off fades: a bulb takes a moment, the brake LED much less.
    val posicion = animateFloatAsState(if (luces.posicionEncendida) 1f else 0f, tween(if (fijo) 0 else 250), label = "posicion")
    val baja = animateFloatAsState(if (luces.baja) 1f else 0f, tween(if (fijo) 0 else 300), label = "baja")
    val alta = animateFloatAsState(if (luces.alta) 1f else 0f, tween(if (fijo) 0 else 200), label = "alta")
    val stop = animateFloatAsState(if (luces.stop) 1f else 0f, tween(if (fijo) 0 else 80), label = "stop")
    val lluvia = animateFloatAsState(estado.lluvia, tween(if (fijo) 0 else 1500), label = "lluvia")
    val velocidad = animateFloatAsState(estado.velocidadKmh, tween(if (fijo) 0 else 400), label = "velocidad")
    val actual = rememberUpdatedState(estado)

    if (tiempoFijoMs != null) {
        SideEffect { reloj.fijar(tiempoFijoMs, estado.velocidadKmh) }
    } else {
        LaunchedEffect(estado.quieto) {
            if (estado.quieto) return@LaunchedEffect
            var previo = withFrameMillis { it }
            while (true) {
                withFrameMillis { t ->
                    val dt = (t - previo).coerceIn(0L, 100L)
                    previo = t
                    reloj.avanzar(dt, velocidad.value)
                }
                if (actual.value.quieto) break
            }
        }
    }
    val v = Valores(reloj, posicion, baja, alta, stop, lluvia, velocidad, actual)

    Box(modifier) {
        Spacer(Modifier.fillMaxSize().drawWithCache { onDrawBehind { viento(anclas.rectFoto(size), anclas, v, delante = false) } })
        foto()
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val trazoSalpicadura = Stroke(width = 1.4f * density)
                onDrawBehind {
                    val r = anclas.rectFoto(size)
                    luces(r, anclas, v)
                    viento(r, anclas, v, delante = true)
                    lluvia(r, anclas, v, trazoSalpicadura)
                }
            },
        )
    }
}

/** The single animation clock. [ms] is snapshot state (reading it schedules a redraw); the phases are plain floats. */
private class Reloj {
    val ms: MutableLongState = mutableLongStateOf(0L)
    /** Distance the air has travelled, in "screen widths"; integrated so a speed change never jumps. */
    var viento = 0f
    var lluvia = 0f

    fun avanzar(dtMs: Long, kmh: Float) {
        val s = dtMs / 1000f
        viento = (viento + s * (0.15f + kmh / 70f)) % 1000f
        lluvia = (lluvia + s * 1.3f) % 1000f
        ms.longValue += dtMs
    }

    fun fijar(tMs: Long, kmh: Float) {
        ms.longValue = tMs
        val s = tMs / 1000f
        viento = s * (0.15f + kmh / 70f)
        lluvia = s * 1.3f
    }
}

private class Valores(
    val reloj: Reloj,
    val posicion: State<Float>,
    val baja: State<Float>,
    val alta: State<Float>,
    val stop: State<Float>,
    val lluvia: State<Float>,
    val velocidad: State<Float>,
    val estado: State<EstadoVisualAuto>,
)

/** Flasher brightness: snaps on, a short afterglow when it goes off (it reads as a lamp, not an LED). */
private fun parpadeo(ms: Long): Float {
    val f = (ms % PERIODO_GIRO_MS).toFloat() / PERIODO_GIRO_MS
    return if (f < 0.5f) min(1f, f / 0.04f) else max(0f, 1f - (f - 0.5f) / 0.12f)
}

// --- Lights ---

private fun brillo(c: Color) = Brush.radialGradient(
    0f to c,
    0.18f to c.copy(alpha = 0.75f),
    0.5f to c.copy(alpha = 0.22f),
    1f to Color.Transparent,
    center = Offset.Zero,
    radius = 1f,
)

// Unit-radius glows at the origin: moved and scaled per lamp, so no brush is ever built while animating.
private val brilloAmbar = brillo(AMBAR)
private val brilloBlanco = brillo(BLANCO_CALIDO)
private val brilloAzul = brillo(BLANCO_AZULADO)
private val brilloRojo = brillo(ROJO)
private fun DrawScope.glow(brush: Brush, centro: Offset, rx: Float, ry: Float = rx, alpha: Float) {
    if (alpha <= 0.01f) return
    withTransform({
        translate(centro.x, centro.y)
        scale(rx, ry, Offset.Zero)
    }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceAtMost(1f), blendMode = BlendMode.Screen)
    }
}

/** A glow stretched along a lamp strip (the LED position light under the headlight lens). */
private fun DrawScope.tira(brush: Brush, de: Offset, a: Offset, grosor: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val dx = a.x - de.x
    val dy = a.y - de.y
    val largo = kotlin.math.sqrt(dx * dx + dy * dy)
    withTransform({
        translate((de.x + a.x) / 2f, (de.y + a.y) / 2f)
        rotate(atan2(dy, dx) * 180f / PI.toFloat(), Offset.Zero)
        scale(largo * 0.62f, grosor, Offset.Zero)
    }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceAtMost(1f), blendMode = BlendMode.Screen)
    }
}

/**
 * A beam: soft glows stretched along [direccion], a tight bright one near the lamp and a wide faint one further
 * out. No hard-edged cone: on a photo, edges give it away.
 */
private fun DrawScope.rayo(lampara: Offset, direccion: Offset, largo: Float, ancho: Float, brush: Brush, alpha: Float) {
    if (alpha <= 0.01f) return
    val angulo = atan2(direccion.y, direccion.x) * 180f / PI.toFloat()
    for (k in 0 until 3) {
        val avance = largo * (0.18f + 0.3f * k)
        withTransform({
            translate(lampara.x + direccion.x * avance, lampara.y + direccion.y * avance)
            rotate(angulo, Offset.Zero)
            scale(largo * (0.22f + 0.22f * k), ancho * (0.35f + 0.4f * k), Offset.Zero)
        }) {
            drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = (alpha * (0.75f - 0.2f * k)).coerceAtMost(1f), blendMode = BlendMode.Screen)
        }
    }
}

private fun DrawScope.luces(r: Rect, a: AnclasFoto, v: Valores) {
    val e = v.estado.value.luces
    val w = r.width
    val pos = v.posicion.value
    val baja = v.baja.value
    val alta = v.alta.value
    val stop = v.stop.value
    val adelante = a.haciaAdelante
    // Low beam aims down at the road; high beam reaches further and flatter.
    val dirBaja = Offset(adelante.x, adelante.y + 0.22f)
    val faros = a.faros

    // Beams first, then the lamps on top of them.
    for (f in faros) {
        val c = r.en(f)
        rayo(c, dirBaja, w * 0.30f, w * 0.10f, brilloBlanco, baja * 0.6f)
        // Light pool on the road in front of the bumper.
        val piso = Offset(c.x + dirBaja.x * w * 0.18f, c.y + dirBaja.y * w * 0.18f + r.height * 0.12f)
        glow(brilloBlanco, piso, w * 0.16f, w * 0.035f, baja * 0.35f)
        rayo(c, adelante, w * 0.6f, w * 0.08f, brilloAzul, alta * 0.65f)
    }
    for (f in faros) {
        val c = r.en(f)
        glow(brilloBlanco, c, w * 0.05f, alpha = pos * 0.45f + baja * 0.55f)
        glow(brilloAzul, c, w * 0.085f, alpha = alta * 0.85f)
    }
    a.posicionIzq?.let { (de, hasta) -> tira(brilloBlanco, r.en(de), r.en(hasta), w * 0.016f, pos) }
    a.posicionDer?.let { (de, hasta) -> tira(brilloBlanco, r.en(de), r.en(hasta), w * 0.008f, pos * 0.7f) }
    a.antinieblaIzq?.let { glow(brilloBlanco, r.en(it), w * 0.03f, alpha = baja * 0.3f) }
    a.antinieblaDer?.let { glow(brilloBlanco, r.en(it), w * 0.025f, alpha = baja * 0.25f) }

    // Tail lamps: position red, the brake much brighter and bigger, plus its reflection on the ground.
    for (t in a.traseras) {
        val c = r.en(t)
        glow(brilloRojo, c, w * 0.035f + stop * w * 0.04f, alpha = pos * 0.45f + stop * 0.95f)
    }
    if (stop > 0.01f) glow(brilloRojo, r.en(a.pisoTrasero), w * 0.12f, w * 0.03f, stop * 0.45f)

    // Turn signals and hazards.
    val flash = if (e.parpadeaIzq || e.parpadeaDer) parpadeo(v.reloj.ms.longValue) else 0f
    if (flash > 0f) {
        val radio = w * 0.045f
        if (e.parpadeaIzq) {
            a.giroDelanteroIzq?.let { glow(brilloAmbar, r.en(it), radio * 1.25f, alpha = flash) }
            a.giroTraseroIzq?.let { glow(brilloAmbar, r.en(it), radio * 0.85f, alpha = flash) }
        }
        if (e.parpadeaDer) {
            a.giroDelanteroDer?.let { glow(brilloAmbar, r.en(it), radio * 0.8f, alpha = flash) }
            a.giroTraseroDer?.let { glow(brilloAmbar, r.en(it), radio * 0.85f, alpha = flash) }
        }
    }
}

// --- Rain ---

private const val GOTAS = 110
private const val SALPICADURAS = 16
private val azar = Random(1607)
private val gotaX = FloatArray(GOTAS) { azar.nextFloat() * 1.3f - 0.15f }
private val gotaFase = FloatArray(GOTAS) { azar.nextFloat() }
private val gotaVel = FloatArray(GOTAS) { 0.8f + azar.nextFloat() * 0.5f }
private val gotaLargo = FloatArray(GOTAS) { 0.6f + azar.nextFloat() * 0.6f }
private val salpicaFase = FloatArray(SALPICADURAS) { azar.nextFloat() }
private val salpicaVel = FloatArray(SALPICADURAS) { 1.2f + azar.nextFloat() * 1.2f }

/** Cheap integer hash to a 0..1 float: a new splash spot every cycle without allocating or storing anything. */
private fun hash01(n: Int): Float {
    var x = n * -0x61c88647
    x = x xor (x ushr 15)
    x *= 0x2c1b3c6d
    x = x xor (x ushr 12)
    return (x ushr 8) / 16_777_216f
}

private fun DrawScope.lluvia(r: Rect, a: AnclasFoto, v: Valores, trazo: Stroke) {
    val intensidad = v.lluvia.value
    if (intensidad <= 0.01f) return
    v.reloj.ms.longValue // redraw with the clock
    val fase = v.reloj.lluvia
    // Moving forward (nose left), the rain seems to come from the front: it slants back with speed.
    val inclinacion = min(1f, v.velocidad.value / 90f) * 0.9f + 0.12f
    val arriba = r.top - r.height * 0.45f
    val alto = r.height * 1.45f
    val largo = r.height * (0.05f + 0.04f * intensidad)
    val visibles = (GOTAS * (0.25f + 0.75f * intensidad)).toInt()
    val grosor = max(1f, r.width * 0.0028f)
    for (i in 0 until visibles) {
        val u = (fase * gotaVel[i] + gotaFase[i]) % 1f
        val y = arriba + u * alto
        val x = r.left + gotaX[i] * r.width + u * alto * inclinacion * 0.25f
        val l = largo * gotaLargo[i]
        // Fade in at the top and out at the bottom.
        val alpha = (0.25f + 0.35f * intensidad) * sin(u * PI.toFloat())
        drawLine(GOTA, Offset(x, y), Offset(x - l * inclinacion * 0.35f, y - l), grosor, StrokeCap.Round, alpha = alpha)
    }
    // Splashes on the roof, bonnet, boot and ground: small rings that open and fade.
    val superficies = a.superficiesLluvia
    if (superficies.isEmpty()) return
    val n = (SALPICADURAS * intensidad).toInt()
    for (i in 0 until n) {
        val t = fase * salpicaVel[i] + salpicaFase[i]
        val ciclo = t.toInt()
        val p = t - ciclo
        val (de, hasta) = superficies[(hash01(ciclo * 31 + i) * superficies.size).toInt().coerceAtMost(superficies.size - 1)]
        val k = hash01(ciclo * 17 + i * 7 + 3)
        val c = r.en(Offset(de.x + (hasta.x - de.x) * k, de.y + (hasta.y - de.y) * k))
        val radio = r.width * (0.004f + 0.014f * p)
        withTransform({
            translate(c.x, c.y)
            scale(1f, 0.35f, Offset.Zero)
        }) {
            drawCircle(GOTA, radio, Offset.Zero, alpha = (1f - p) * 0.8f, style = trazo)
        }
    }
}

// --- Wind ---

private const val ESTELAS = 22
private val estelaY = FloatArray(ESTELAS) { i ->
    // Bands: over the roof, along the body, and low along the ground.
    when (i % 3) {
        0 -> -0.12f + azar.nextFloat() * 0.18f
        1 -> 0.25f + azar.nextFloat() * 0.5f
        // Starts under the front bumper; the tilt keeps it under the sills to the rear.
        else -> 1.04f + azar.nextFloat() * 0.16f
    }
}
private val estelaFase = FloatArray(ESTELAS) { azar.nextFloat() }
private val estelaVel = FloatArray(ESTELAS) { 0.7f + azar.nextFloat() * 0.6f }
private val estelaLargo = FloatArray(ESTELAS) { 0.6f + azar.nextFloat() * 0.8f }
/** Streaks over the body pass behind it; those above and below go in front. */
private val estelaDelante = BooleanArray(ESTELAS) { i -> i % 3 != 1 || azar.nextFloat() < 0.25f }

/** Fading tail in unit space: transparent at x = -1, bright at the head (x = 0). */
private val estela = Brush.horizontalGradient(0f to Color.Transparent, 1f to AIRE, startX = -1f, endX = 0f)

private fun DrawScope.viento(r: Rect, a: AnclasFoto, v: Valores, delante: Boolean) {
    val kmh = v.velocidad.value
    if (kmh < 1f) return
    v.reloj.ms.longValue // redraw with the clock
    val s = min(1.2f, kmh / 100f)
    val visibles = (ESTELAS * min(1f, 0.15f + kmh / 60f)).toInt()
    val margen = r.width * 0.25f
    val recorrido = r.width + 2 * margen
    // Air flows from the nose to the tail along the car's axis: [haciaAdelante] without its tilt towards the viewer.
    val pendiente = a.haciaAdelante.y * 0.5f / a.haciaAdelante.x
    val angulo = atan(pendiente) * 180f / PI.toFloat()
    val grosor = max(1f, r.width * 0.0035f)
    for (i in 0 until visibles) {
        if (estelaDelante[i] != delante) continue
        val u = (v.reloj.viento * estelaVel[i] + estelaFase[i]) % 1f
        val x = r.left - margen + u * recorrido
        val y = r.top + estelaY[i] * r.height + (x - r.left) * pendiente
        val largo = r.width * (0.06f + 0.22f * s) * estelaLargo[i]
        val alpha = (0.12f + 0.4f * s) * sin(u * PI.toFloat())
        withTransform({
            translate(x, y)
            rotate(angulo, Offset.Zero)
            scale(largo, 1f, Offset.Zero)
        }) {
            drawLine(estela, Offset(-1f, 0f), Offset.Zero, grosor, StrokeCap.Butt, alpha = alpha, blendMode = BlendMode.Screen)
        }
    }
}
