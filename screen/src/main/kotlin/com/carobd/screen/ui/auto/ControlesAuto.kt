package com.carobd.screen.ui.auto

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.auto.AmbienteAuto
import com.carobd.core.auto.DemoAuto
import com.carobd.core.auto.EstadoVisualAuto
import com.carobd.core.auto.LucesAuto
import com.carobd.core.auto.SenalesLuces
import com.carobd.core.obd.MotorExtendido
import com.carobd.screen.EstadoTablero
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.ClimaActual
import com.carobd.screen.ui.FotoAuto
import com.carobd.screen.ui.comun.Interruptor
import com.carobd.screen.ui.comun.Opcion
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hand toggles for what the Accent does not send over OBD (signals, hazards, headlights) and a rain override,
 * to test the animations. Not saved: they are for trying things out, and a real source wins anyway.
 */
object ControlesAuto {
    val luces = MutableStateFlow(LucesAuto())
    /** null follows the weather; a number forces the rain (0 = dry). */
    val lluvia = MutableStateFlow<Float?>(null)
    /** In demo mode, run the light and rain cycle of [DemoAuto]. */
    val demo = MutableStateFlow(true)
    /** null follows the clock and the weather; a value forces that mood, to try them out. */
    val ambiente = MutableStateFlow<AmbienteAuto?>(null)

    fun cambiar(cambio: (LucesAuto) -> LucesAuto) = luces.update(cambio)
}

/** The car's visual state for this moment, merged from the readings, the weather, the demo and the toggles. */
@Composable
fun rememberEstadoVisualAuto(e: EstadoTablero): EstadoVisualAuto {
    val manual by ControlesAuto.luces.collectAsState()
    val lluviaManual by ControlesAuto.lluvia.collectAsState()
    val demoActivo by ControlesAuto.demo.collectAsState()
    val clima by ClimaActual.clima.collectAsState()
    val conDemo = e.esDemo && demoActivo
    // The demo cycle moves in quarter seconds; outside the demo this does not tick at all.
    val tDemo by produceState(0L, conDemo) {
        if (!conDemo) return@produceState
        val inicio = System.currentTimeMillis()
        while (true) {
            value = System.currentTimeMillis() - inicio
            delay(250)
        }
    }
    val escena = if (conDemo) DemoAuto.en(tDemo) else null
    val lluviaClima = clima?.let { EstadoVisualAuto.intensidadLluvia(it.codigo) } ?: 0f
    return remember(e.valores, manual, escena, lluviaClima, lluviaManual) {
        EstadoVisualAuto.combinar(e.valores, manual, escena, lluviaClima, lluviaManual)
    }
}

/** The mood for the 3D background right now: the forced one, or the clock (read every minute) plus the weather. */
@Composable
private fun rememberAmbiente(): AmbienteAuto? {
    val activo by Preferencia3D.ambientar.collectAsState()
    val forzado by ControlesAuto.ambiente.collectAsState()
    val clima by ClimaActual.clima.collectAsState()
    val hora by produceState(horaLocal()) {
        while (true) {
            delay(60_000)
            value = horaLocal()
        }
    }
    if (!activo) return null
    return forzado ?: AmbienteAuto.de(clima?.codigo, hora)
}

private fun horaLocal(): Float = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }

/**
 * The animated car as the screens show it: the 3D model when the screen can run it, the photo otherwise.
 * A long press on the car opens the light toggles over it,
 * to try the animations without going to Ajustes.
 */
@Composable
fun AutoTablero(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    val contexto = LocalContext.current
    val estado = rememberEstadoVisualAuto(e)
    var panel by rememberSaveable { mutableStateOf(false) }
    remember { Preferencia3D.cargar(contexto) }
    val activo3D by Preferencia3D.activo.collectAsState()
    val lento by Preferencia3D.lento.collectAsState()
    val en3D = activo3D && !lento
    val pintura by Preferencia3D.color.collectAsState()
    val ambiente = rememberAmbiente()
    val listo3D by Motor3D.listo.collectAsState()
    val tocar = { if (panel) panel = false }
    val mantener = { panel = !panel }
    Box(modifier.clip(RoundedCornerShape(12.dp)).pointerInput(Unit) { detectTapGestures(onLongPress = { mantener() }, onTap = { tocar() }) }) {
        if (en3D) {
            Auto3D(
                estado = estado,
                embrague = e.valores[MotorExtendido.EMBRAGUE] == 1.0,
                fondo = p.celda,
                modifier = Modifier.fillMaxSize(),
                pintura = pintura,
                ambiente = ambiente,
                alTocar = tocar,
                alMantener = mantener,
            )
            // While the model loads (only the first time after opening the app) the card says so; it fades away over the car.
            AnimatedVisibility(!listo3D, Modifier.fillMaxSize(), enter = EnterTransition.None, exit = fadeOut(tween(500))) {
                Column(
                    Modifier.fillMaxSize().background(p.celda),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = p.normal, trackColor = p.pista)
                    Text("Cargando el auto…", color = p.tenue, fontSize = 16.sp)
                }
            }
        } else {
            // 3D switched off, or this screen cannot run it: the photo with its drawn lights.
            AutoAnimado(estado, Modifier.fillMaxSize()) { FotoAuto(Modifier.fillMaxSize()) }
        }
        if (panel) {
            PanelLucesCompacto(e, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

private val AMBAR = Color(0xFFF5A524)
private val ROJO = Color(0xFFFF4D4F)
private val BLANCO = Color(0xFFFFF6D8)
private val AZULADO = Color(0xFFCFE4FF)

/** One light: its hand toggle, the reading that overrides it, and how its tile is drawn (24x24 stroke icon, real colour). */
private class Toggle(
    val corto: String,
    val nombre: String,
    val id: String?,
    val color: Color,
    val trazo: String,
    val activo: (LucesAuto) -> Boolean,
    val cambiar: (LucesAuto, Boolean) -> LucesAuto,
)

private const val FARO = "M13 6 C17 6 20 8.7 20 12 C20 15.3 17 18 13 18 Z"

// In the order of the Ajustes mosaic: signals and hazards on top, brake and lamps below, high beam last.
private val TOGGLES = listOf(
    Toggle("◀", "Giro izquierdo", SenalesLuces.GIRO_IZQ, AMBAR, "M14 6 L8 12 L14 18", { it.giroIzq }) { l, v -> l.copy(giroIzq = v, giroDer = if (v) false else l.giroDer) },
    Toggle("Balizas", "Balizas", SenalesLuces.BALIZAS, AMBAR, "M12 4 L21 20 L3 20 Z", { it.balizas }) { l, v -> l.copy(balizas = v) },
    Toggle("▶", "Giro derecho", SenalesLuces.GIRO_DER, AMBAR, "M10 6 L16 12 L10 18", { it.giroDer }) { l, v -> l.copy(giroDer = v, giroIzq = if (v) false else l.giroIzq) },
    Toggle("Stop", "Freno", MotorExtendido.FRENO, ROJO, "M7 9 L17 9 A3 3 0 0 1 17 15 L7 15 A3 3 0 0 1 7 9 Z", { it.stop }) { l, v -> l.copy(stop = v) },
    Toggle("Posición", "Posición", SenalesLuces.POSICION, BLANCO, "M15 12 A3 3 0 1 1 9 12 A3 3 0 1 1 15 12 M12 3 L12 6 M12 18 L12 21 M3 12 L6 12 M18 12 L21 12", { it.posicion }) { l, v -> l.copy(posicion = v) },
    Toggle("Baja", "Luz baja", SenalesLuces.BAJA, BLANCO, "$FARO M3 10 L9 11.5 M3 13 L9 14.5 M3 16 L9 17.5", { it.baja }) { l, v -> l.copy(baja = v) },
    Toggle("Alta", "Luz alta", SenalesLuces.ALTA, AZULADO, "$FARO M3 7.5 L9 7.5 M3 10.5 L9 10.5 M3 13.5 L9 13.5 M3 16.5 L9 16.5", { it.alta }) { l, v -> l.copy(alta = v) },
)

/** Two rows of small toggles over the bottom of the car. */
@Composable
private fun PanelLucesCompacto(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    val manual by ControlesAuto.luces.collectAsState()
    val lluvia by ControlesAuto.lluvia.collectAsState()
    val filas = listOf(TOGGLES.take(4), TOGGLES.drop(4))
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(p.celda.copy(alpha = 0.92f)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((i, fila) in filas.withIndex()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in fila) {
                    // A light the car does report is shown but not toggled: the real value wins.
                    val real = t.id != null && e.valores.containsKey(t.id)
                    Chip(t.corto, t.activo(manual), !real, Modifier.weight(1f)) { ControlesAuto.cambiar { l -> t.cambiar(l, !t.activo(l)) } }
                }
                if (i == 1) {
                    Chip("Lluvia", (lluvia ?: 0f) > 0f, true, Modifier.weight(1f)) {
                        ControlesAuto.lluvia.value = if ((lluvia ?: 0f) > 0f) null else 0.8f
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(texto: String, encendido: Boolean, habilitado: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPaleta.current
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (encendido) p.pista else p.celda)
            .border(1.dp, if (encendido) p.normal else p.linea, RoundedCornerShape(10.dp))
            .clickable(enabled = habilitado, role = Role.Switch, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = if (!habilitado) p.tenue.copy(alpha = 0.5f) else if (encendido) p.texto else p.tenue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The light's icon; lit, it takes the light's colour over a soft halo. */
@Composable
private fun IconoLuz(t: Toggle, encendida: Boolean, tam: Dp) {
    val apagado = LocalPaleta.current.tenue
    val path = remember(t) { PathParser().parsePathString(t.trazo).toPath() }
    Canvas(Modifier.size(tam)) {
        if (encendida) drawCircle(Brush.radialGradient(listOf(t.color.copy(alpha = 0.38f), Color.Transparent)), radius = size.minDimension * 0.75f)
        val k = size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            drawPath(path, if (encendida) t.color else apagado, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * One tile of the lights mosaic. It shows what the car is showing (a real reading, the demo or the hand toggle) and
 * says where that comes from; a light the car does report is not toggled, the real value wins.
 */
@Composable
private fun BaldosaLuz(t: Toggle, e: EstadoTablero, manual: LucesAuto, visibles: LucesAuto, modifier: Modifier, ancha: Boolean = false) {
    val p = LocalPaleta.current
    val real = t.id != null && e.valores.containsKey(t.id)
    val encendida = t.activo(visibles)
    val forma = RoundedCornerShape(16.dp)
    val estado = if (t.nombre == "Balizas") "encendidas" else "encendida"
    val origen = when {
        real -> "Del auto"
        t.activo(manual) -> "A mano · $estado"
        encendida -> "Demo · $estado"
        else -> "A mano"
    }
    val caja = modifier
        .clip(forma)
        .background(p.pista)
        .background(if (encendida) t.color.copy(alpha = 0.10f) else Color.Transparent)
        .border(2.dp, if (encendida) t.color else Color.Transparent, forma)
        .clickable(enabled = !real, role = Role.Switch) { ControlesAuto.cambiar { l -> t.cambiar(l, !t.activo(l)) } }
        .padding(12.dp)
    val chip: @Composable () -> Unit = {
        Text(
            origen,
            color = if (real) p.ok else p.tenue,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (real) p.ok.copy(alpha = 0.14f) else p.celda).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    if (ancha) {
        Row(caja, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconoLuz(t, encendida, 40.dp)
            Text(t.nombre, color = p.texto, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            chip()
        }
    } else {
        Column(caja, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            IconoLuz(t, encendida, 52.dp)
            Text(t.nombre, color = p.texto, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            chip()
        }
    }
}

/** Ajustes > Luces del auto: the lights as a mosaic, then rain, the 3D car, its colour and the ambience. */
@Composable
fun AjustesLucesAuto(e: EstadoTablero) {
    val p = LocalPaleta.current
    val manual by ControlesAuto.luces.collectAsState()
    val lluvia by ControlesAuto.lluvia.collectAsState()
    val demo by ControlesAuto.demo.collectAsState()
    val visibles = rememberEstadoVisualAuto(e).luces
    Text(
        "Por OBD llegan el freno, la velocidad y la puerta. El resto se prende a mano, aquí o manteniendo apretado el auto en Inicio.",
        color = p.tenue,
        fontSize = 16.sp,
    )
    for (fila in TOGGLES.take(6).chunked(3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (t in fila) BaldosaLuz(t, e, manual, visibles, Modifier.weight(1f).height(150.dp))
        }
    }
    BaldosaLuz(TOGGLES[6], e, manual, visibles, Modifier.fillMaxWidth().heightIn(min = 64.dp), ancha = true)
    Text("Lluvia", color = p.texto, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Opcion("Según el clima", lluvia == null, Modifier.weight(1f)) { ControlesAuto.lluvia.value = null }
        Opcion("Seco", lluvia == 0f, Modifier.weight(1f)) { ControlesAuto.lluvia.value = 0f }
        Opcion("Lluvia", lluvia != null && lluvia!! > 0f && lluvia!! < 1f, Modifier.weight(1f)) { ControlesAuto.lluvia.value = 0.6f }
        Opcion("Tormenta", lluvia == 1f, Modifier.weight(1f)) { ControlesAuto.lluvia.value = 1f }
    }
    val contexto = LocalContext.current
    val activo3D by Preferencia3D.activo.collectAsState()
    val lento by Preferencia3D.lento.collectAsState()
    Interruptor(
        "Auto en 3D",
        activo3D && !lento,
        if (lento) "Esta pantalla no pudo moverlo con fluidez: se muestra la foto. Actívalo para reintentar" else "Con ruedas, frenada, cambios, lluvia y viento. Arrastra el auto para girarlo",
    ) { Preferencia3D.cambiar(contexto, it) }
    val informe by Preferencia3D.informe.collectAsState()
    informe?.let { Text(it, color = if (lento) p.atencion else p.tenue, fontSize = 14.sp) }
    if (activo3D && !lento) {
        val pintura by Preferencia3D.color.collectAsState()
        Text("Color del auto: ${pintura.nombre}", color = p.texto, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        for (fila in COLORES_AUTO.chunked(5)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in fila) {
                    val elegido = c == pintura
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF000000 or c.rgb.toLong()))
                            .border(if (elegido) 3.dp else 1.dp, if (elegido) p.normal else p.linea, RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton, onClickLabel = c.nombre) { Preferencia3D.pintar(contexto, c) },
                    )
                }
            }
        }
    }
    if (activo3D && !lento) {
        val ambientar by Preferencia3D.ambientar.collectAsState()
        val forzado by ControlesAuto.ambiente.collectAsState()
        Interruptor("Ambientar el fondo", ambientar, "El cielo y la luz siguen la hora y el clima: día, nublado, niebla, nieve, tormenta y noche") { Preferencia3D.ambientar(contexto, it) }
        if (ambientar) {
            val pruebas = listOf(
                "Automático" to null,
                "Día" to AmbienteAuto(12f),
                "Atardecer" to AmbienteAuto(18.6f),
                "Noche" to AmbienteAuto(22f),
                "Nublado" to AmbienteAuto(13f, nubes = 1f),
                "Niebla" to AmbienteAuto(9f, nubes = 0.8f, niebla = 1f),
                "Nieve" to AmbienteAuto(11f, nubes = 1f, nieve = 1f),
            )
            for (fila in pruebas.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((nombre, a) in fila) Opcion(nombre, forzado == a, Modifier.weight(1f)) { ControlesAuto.ambiente.value = a }
                }
            }
        }
    }
    Interruptor("Animar luces en el demo", demo, "Con el simulador recorre intermitentes, balizas, faros y lluvia") { ControlesAuto.demo.value = it }
}
