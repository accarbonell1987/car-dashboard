package com.carobd.screen.ui.auto

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // The photo stays on top until the 3D scene has drawn its first frame, and for good if it cannot run.
    var listo3D by remember(en3D) { mutableStateOf(false) }
    val tocar = { if (panel) panel = false }
    val mantener = { panel = !panel }
    Box(modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { mantener() }, onTap = { tocar() }) }) {
        if (en3D) {
            Auto3D(
                estado = estado,
                embrague = e.valores[MotorExtendido.EMBRAGUE] == 1.0,
                fondo = p.celda,
                pintura = pintura,
                modifier = Modifier.fillMaxSize(),
                alTocar = tocar,
                alMantener = mantener,
                alListo = { listo3D = true },
                alFallar = { Preferencia3D.lento.value = true },
            )
        }
        if (!en3D || !listo3D) {
            AutoAnimado(estado, Modifier.fillMaxSize().background(p.celda)) { FotoAuto(Modifier.fillMaxSize()) }
        }
        if (panel) {
            PanelLucesCompacto(e, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

private class Toggle(val corto: String, val nombre: String, val id: String?, val activo: (LucesAuto) -> Boolean, val cambiar: (LucesAuto, Boolean) -> LucesAuto)

private val TOGGLES = listOf(
    Toggle("◀", "Intermitente izquierdo", SenalesLuces.GIRO_IZQ, { it.giroIzq }) { l, v -> l.copy(giroIzq = v, giroDer = if (v) false else l.giroDer) },
    Toggle("▶", "Intermitente derecho", SenalesLuces.GIRO_DER, { it.giroDer }) { l, v -> l.copy(giroDer = v, giroIzq = if (v) false else l.giroIzq) },
    Toggle("Balizas", "Balizas", SenalesLuces.BALIZAS, { it.balizas }) { l, v -> l.copy(balizas = v) },
    Toggle("Stop", "Luz de freno (stop)", MotorExtendido.FRENO, { it.stop }) { l, v -> l.copy(stop = v) },
    Toggle("Posición", "Luces de posición", SenalesLuces.POSICION, { it.posicion }) { l, v -> l.copy(posicion = v) },
    Toggle("Baja", "Luz baja", SenalesLuces.BAJA, { it.baja }) { l, v -> l.copy(baja = v) },
    Toggle("Alta", "Luz alta", SenalesLuces.ALTA, { it.alta }) { l, v -> l.copy(alta = v) },
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

/** Ajustes > Luces del auto: every toggle with where its value comes from. */
@Composable
fun AjustesLucesAuto(e: EstadoTablero) {
    val p = LocalPaleta.current
    val manual by ControlesAuto.luces.collectAsState()
    val lluvia by ControlesAuto.lluvia.collectAsState()
    val demo by ControlesAuto.demo.collectAsState()
    Text(
        "El auto de Inicio muestra sus luces, la lluvia y el viento. Por OBD llegan el freno, la velocidad y la puerta; " +
            "intermitentes, balizas y faros no, así que se prenden a mano aquí (o manteniendo apretado el auto en Inicio).",
        color = p.tenue,
        fontSize = 16.sp,
    )
    for (t in TOGGLES) {
        val real = t.id != null && e.valores.containsKey(t.id)
        val detalle = if (real) "Dato real del auto: el interruptor no manda" else if (t.id == MotorExtendido.FRENO) "Sin dato del pedal: a mano" else "A mano (no llega por OBD)"
        Interruptor(t.nombre, t.activo(manual), detalle) { v -> ControlesAuto.cambiar { l -> t.cambiar(l, v) } }
    }
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
    Interruptor("Animar luces en el demo", demo, "Con el simulador recorre intermitentes, balizas, faros y lluvia") { ControlesAuto.demo.value = it }
}
