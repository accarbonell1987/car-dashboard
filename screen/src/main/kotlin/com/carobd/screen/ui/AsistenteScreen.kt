package com.carobd.screen.ui

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.miles
import com.carobd.core.model.Asistente
import com.carobd.core.model.Estado
import com.carobd.core.model.EstadoMantenimiento
import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.PasoAsistente
import com.carobd.core.model.PreguntaChequeo
import com.carobd.core.model.RespuestaItem
import com.carobd.core.model.RespuestasAsistente
import com.carobd.core.model.texto
import com.carobd.screen.EstadoTablero
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Boton
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import java.time.YearMonth

/** Acciones del asistente de mantenimiento: las implementa el ViewModel. */
interface AccionesAsistente {
    fun abrirAsistente()
    /** Guarda el avance en el dispositivo para poder retomarlo (no se sincroniza). */
    fun responderAsistente(r: RespuestasAsistente)
    fun cerrarAsistente()
    /** Convierte las respuestas en datos del plan y cierra el asistente. */
    fun terminarAsistente(r: RespuestasAsistente)
}

/** Una pantalla del asistente: una sola pregunta (RNF-05). */
internal sealed interface Pregunta {
    val paso: PasoAsistente
    data object Motor : Pregunta { override val paso = PasoAsistente.AUTO }
    data object Caja : Pregunta { override val paso = PasoAsistente.AUTO }
    data object MesAlta : Pregunta { override val paso = PasoAsistente.AUTO }
    data object KmActual : Pregunta { override val paso = PasoAsistente.AUTO }
    data object Condiciones : Pregunta { override val paso = PasoAsistente.USO }
    data object En590 : Pregunta { override val paso = PasoAsistente.COMBUSTIBLE }
    data object Dpf : Pregunta { override val paso = PasoAsistente.COMBUSTIBLE }
    data object UltimoService : Pregunta { override val paso = PasoAsistente.HISTORIAL }
    data class Item(val item: ItemMantenimiento) : Pregunta { override val paso = PasoAsistente.HISTORIAL }
    data class Chequeo(val q: PreguntaChequeo) : Pregunta { override val paso = PasoAsistente.CHEQUEO }
    data object Resultado : Pregunta { override val paso = PasoAsistente.RESULTADO }
}

/** La lista de preguntas depende de lo ya respondido: el atajo del último service saca ítems, sin DPF no se pregunta su testigo. */
internal fun preguntas(r: RespuestasAsistente, items: List<ItemMantenimiento>, odometroDelTablero: Boolean): List<Pregunta> = buildList {
    add(Pregunta.Motor)
    add(Pregunta.Caja)
    add(Pregunta.MesAlta)
    if (!odometroDelTablero) add(Pregunta.KmActual)
    add(Pregunta.Condiciones)
    add(Pregunta.En590)
    add(Pregunta.Dpf)
    add(Pregunta.UltimoService)
    val delHito = r.ultimoServiceKm?.let { km -> Asistente.itemsDelHito(items, km).map { it.id }.toSet() }.orEmpty()
    items.filter { it.id !in delHito }.forEach { add(Pregunta.Item(it)) }
    Asistente.chequeo.filter { !(it.id == "testigo_dpf" && r.tieneDpf == false) }.forEach { add(Pregunta.Chequeo(it)) }
    add(Pregunta.Resultado)
}

/**
 * Asistente de alta del plan en la pantalla del auto (CU-01, CU-02). Solo se usa con el auto detenido (RF-12):
 * si hay velocidad, se tapa con un aviso y las respuestas quedan como estaban.
 * Layout "B": thin progress on top, the question in one line, and the answers as a full-width row of tiles.
 */
@Composable
fun AsistenteScreen(e: EstadoTablero, r: RespuestasAsistente, acciones: AccionesAsistente) {
    val g = e.garaje
    val items = g?.items.orEmpty().filter { it.codigo != null }
    val lista = preguntas(r, items, e.odometroDelTablero)
    val indice = r.paso.coerceIn(0, lista.lastIndex)
    val pregunta = lista[indice]
    val enMarcha = (e.valores["velocidad"] ?: 0.0) > 0.0
    val delPaso = lista.withIndex().filter { it.value.paso == pregunta.paso }
    val enPaso = delPaso.indexOfFirst { it.index == indice }

    fun ir(nuevo: RespuestasAsistente, a: Int) = acciones.responderAsistente(nuevo.copy(paso = a.coerceIn(0, lista.lastIndex)))
    fun siguiente(nuevo: RespuestasAsistente = r) = ir(nuevo, indice + 1)
    fun saltarPaso() {
        val proximo = (indice + 1..lista.lastIndex).firstOrNull { lista[it].paso != pregunta.paso } ?: lista.lastIndex
        ir(r, proximo)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Cabecera(pregunta.paso, enPaso, delPaso.size, onCerrar = acciones::cerrarAsistente)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (enMarcha) {
                EnMarcha()
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (pregunta) {
                        Pregunta.Motor -> PreguntaMotor(r) { siguiente(it) }
                        Pregunta.Caja -> Elegir(
                            "¿Qué caja tiene?", null,
                            listOf(Eleccion("Manual", "MANUAL"), Eleccion("Automática", "AUTOMATICA")), r.transmision,
                        ) { siguiente(r.copy(transmision = it)) }
                        Pregunta.MesAlta -> PreguntaMes(
                            "¿Cuándo lo compraste o se matriculó por primera vez?",
                            "Sirve para los cambios que van por tiempo, como el refrigerante.",
                            r.mesAlta ?: "2016-06",
                        ) { siguiente(r.copy(mesAlta = it)) }
                        Pregunta.KmActual -> PreguntaKm(
                            "¿Cuántos km marca el tablero?", "Míralo en el cuentakilómetros del auto.",
                            r.kmActual ?: g?.odometroKm ?: 0,
                        ) { siguiente(r.copy(kmActual = it)) }
                        Pregunta.Condiciones -> PreguntaCondiciones(r) { siguiente(it) }
                        Pregunta.En590 -> Elegir(
                            "¿Qué gasoil cargas?", "El de bajo azufre (S10, S50 o \"premium\") cumple EN 590.",
                            listOf(Eleccion("Bajo azufre", true, "S10, S50, premium"), Eleccion("Común", false, "S500")), r.en590,
                        ) { siguiente(r.copy(en590 = it)) }
                        Pregunta.Dpf -> Elegir(
                            "¿Tu auto tiene filtro de partículas (DPF)?",
                            "Si alguna vez viste en el tablero un rectángulo lleno de puntitos, lo tiene.",
                            listOf(Eleccion("Sí", true), Eleccion("No", false)), r.tieneDpf,
                        ) { siguiente(r.copy(tieneDpf = it)) }
                        Pregunta.UltimoService -> PreguntaUltimoService(r) { siguiente(it) }
                        is Pregunta.Item -> PreguntaItem(pregunta.item, r.items[pregunta.item.id]) { resp ->
                            siguiente(r.copy(items = if (resp == null) r.items - pregunta.item.id else r.items + (pregunta.item.id to resp)))
                        }
                        is Pregunta.Chequeo -> Elegir(
                            pregunta.q.texto, pregunta.q.ayuda, listOf(Eleccion("Sí", true), Eleccion("No", false)), r.chequeo[pregunta.q.id],
                        ) { siguiente(r.copy(chequeo = if (it == null) r.chequeo - pregunta.q.id else r.chequeo + (pregunta.q.id to it))) }
                        Pregunta.Resultado -> Resultado(e, r, items)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Boton("Atrás", Modifier.width(180.dp), activo = !enMarcha && indice > 0) { ir(r, indice - 1) }
            Spacer(Modifier.weight(1f))
            if (pregunta != Pregunta.Resultado) {
                Boton("Saltar ${pregunta.paso.titulo.lowercase()}", activo = !enMarcha) { saltarPaso() }
            } else {
                Boton("Guardar y terminar", principal = true, activo = !enMarcha) { acciones.terminarAsistente(r.copy(terminado = true)) }
            }
        }
    }
}

/** Step name and question count over a six-segment bar: done steps green, the current one filling in blue. */
@Composable
private fun Cabecera(paso: PasoAsistente, enPaso: Int, delPaso: Int, onCerrar: () -> Unit) {
    val p = LocalPaleta.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(paso.titulo, color = p.texto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (paso != PasoAsistente.RESULTADO) Text("· pregunta ${enPaso + 1} de $delPaso", color = p.tenue, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Boton("Cerrar", onClick = onCerrar)
        }
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PasoAsistente.entries.forEach { s ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (s < paso) p.ok else p.pista)) {
                    if (s == paso) Box(Modifier.fillMaxWidth((enPaso + 1f) / delPaso).fillMaxHeight().background(p.normal))
                }
            }
        }
    }
}

@Composable
private fun EnMarcha() {
    val p = LocalPaleta.current
    Row(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(p.atencion.copy(alpha = 0.12f), p.celda))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
    ) {
        AnimacionLottie(Animacion.PAUSA, Modifier.size(140.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sigue cuando el auto esté detenido", color = p.texto, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Tus respuestas quedan guardadas.", color = p.tenue, fontSize = 19.sp)
        }
    }
}

/** The question in one line: its animation, the question and the help under it. */
@Composable
private fun Titulo(texto: String, ayuda: String?, animacion: Animacion = Animacion.PROGRESO) {
    val p = LocalPaleta.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(p.normal.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            AnimacionLottie(animacion, Modifier.size(56.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(texto, color = p.texto, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
            ayuda?.let { Text(it, color = p.tenue, fontSize = 18.sp) }
        }
    }
}

/** Big answer tile. "No sé" is drawn dashed: always available (RF-08) but quieter than a real answer. */
@Composable
private fun Baldosa(texto: String, elegida: Boolean, modifier: Modifier = Modifier, detalle: String? = null, activo: Boolean = true, noSe: Boolean = false, onClick: () -> Unit) {
    val p = LocalPaleta.current
    val forma = RoundedCornerShape(18.dp)
    val borde = when {
        elegida -> Modifier.border(2.dp, p.normal, forma)
        noSe -> Modifier.border(2.dp, p.pista, forma)
        else -> Modifier
    }
    Column(
        modifier
            .clip(forma)
            .background(if (elegida) p.normal.copy(alpha = 0.12f) else if (noSe) Color.Transparent else p.pista)
            .then(borde)
            .clickable(enabled = activo, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = elegida }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        val color = if (!activo) p.tenue.copy(alpha = 0.5f) else if (noSe) p.tenue else p.texto
        Text(texto, color = color, fontSize = if (noSe) 20.sp else 24.sp, fontWeight = if (noSe) FontWeight.Medium else FontWeight.Bold, textAlign = TextAlign.Center)
        detalle?.let { Text(it, color = p.tenue.copy(alpha = if (activo) 1f else 0.5f), fontSize = 16.sp, textAlign = TextAlign.Center) }
    }
}

private data class Eleccion<T>(val texto: String, val valor: T, val detalle: String? = null)

/** Una pregunta de opciones grandes más "No sé", que siempre se acepta (RF-08). */
@Composable
private fun <T> ColumnScope.Elegir(titulo: String, ayuda: String?, opciones: List<Eleccion<T>>, actual: T?, onElegir: (T?) -> Unit) {
    Titulo(titulo, ayuda)
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        opciones.forEach { o -> Baldosa(o.texto, actual == o.valor, Modifier.weight(1f).fillMaxHeight(), o.detalle) { onElegir(o.valor) } }
        Baldosa("No sé", false, Modifier.weight(1f).fillMaxHeight(), noSe = true) { onElegir(null) }
    }
}

@Composable
private fun ColumnScope.PreguntaMotor(r: RespuestasAsistente, onListo: (RespuestasAsistente) -> Unit) {
    Titulo("¿Qué motor tiene tu Accent?", "Con eso elegimos el plan de mantenimiento del manual.")
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Asistente.motores.forEach { m ->
            Baldosa(
                m.nombre, r.plantilla != null && r.plantilla == m.plantilla, Modifier.weight(1f).fillMaxHeight(),
                detalle = if (m.plantilla != null) "Plan del manual cargado" else "Plan en camino", activo = m.plantilla != null,
            ) { onListo(r.copy(plantilla = m.plantilla)) }
        }
    }
}

@Composable
private fun ColumnScope.PreguntaCondiciones(r: RespuestasAsistente, onListo: (RespuestasAsistente) -> Unit) {
    val p = LocalPaleta.current
    var marcadas by remember(r.condiciones) { mutableStateOf(r.condiciones.toSet()) }
    Titulo("¿Alguna de estas situaciones es habitual?", "Si marcas alguna, el plan pasa a uso severo: el aceite, por ejemplo, va cada 5.000 km.")
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Asistente.condiciones.chunked(4).forEach { fila ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fila.forEach { (letra, texto) ->
                    val si = letra in marcadas
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (si) p.normal.copy(alpha = 0.12f) else p.pista)
                            .border(2.dp, if (si) p.normal else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Checkbox) { marcadas = if (si) marcadas - letra else marcadas + letra }
                            .semantics { selected = si }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        if (si) Text("✓", color = p.normal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(texto, color = p.texto, fontSize = 17.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Boton("Ninguna", Modifier.weight(1f), activo = marcadas.isEmpty()) { onListo(r.copy(condiciones = emptyList(), usoSevero = false)) }
        Boton(if (marcadas.isEmpty()) "Listo" else "Listo · ${marcadas.size} marcadas", Modifier.weight(1f), principal = true, activo = marcadas.isNotEmpty()) {
            onListo(r.copy(condiciones = marcadas.sorted(), usoSevero = true))
        }
    }
}

@Composable
private fun ColumnScope.PreguntaUltimoService(r: RespuestasAsistente, onListo: (RespuestasAsistente) -> Unit) {
    var km by remember { mutableStateOf(r.ultimoServiceKm ?: 0) }
    var mes by remember { mutableStateOf(r.ultimoServiceMes ?: YearMonth.now().minusMonths(6).toString()) }
    Titulo(
        "¿Le hiciste el service completo del manual hace poco?",
        "Escribe a qué km fue. Marcamos todo lo que el plan pide en ese service y te preguntamos solo el resto.",
    )
    KmYMes(km, { km = it }, mes, { mes = it }, Modifier.weight(1f)) {
        Boton("Listo", Modifier.fillMaxWidth(), principal = true, activo = km >= 10_000) { onListo(r.copy(ultimoServiceKm = km, ultimoServiceMes = mes)) }
        Boton("No me acuerdo", Modifier.fillMaxWidth()) { onListo(r.copy(ultimoServiceKm = null, ultimoServiceMes = null)) }
    }
}

@Composable
private fun ColumnScope.PreguntaItem(item: ItemMantenimiento, actual: RespuestaItem?, onListo: (RespuestaItem?) -> Unit) {
    val p = LocalPaleta.current
    var tipo by remember(item.id, actual) { mutableStateOf(actual?.tipo) }
    var km by remember(item.id, actual) { mutableStateOf(actual?.km ?: 0) }
    var mes by remember(item.id, actual) { mutableStateOf(actual?.mes ?: YearMonth.now().minusMonths(6).toString()) }
    val verbo = if (item.accion == "REVISAR") "revisaste" else "cambiaste"
    Titulo("¿Cuándo $verbo por última vez: ${item.nombre.removeSuffix(" (revisar)").lowercase()}?", item.notas)
    val conKm = tipo == Asistente.EXACTO || tipo == Asistente.APROX
    Row(Modifier.fillMaxWidth().then(if (conKm) Modifier.height(72.dp) else Modifier.weight(1f)), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Baldosa("Sé el km", tipo == Asistente.EXACTO, Modifier.weight(1f).fillMaxHeight()) { tipo = Asistente.EXACTO }
        Baldosa("Más o menos", tipo == Asistente.APROX, Modifier.weight(1f).fillMaxHeight()) { tipo = Asistente.APROX }
        Baldosa("Nunca", tipo == Asistente.NUNCA, Modifier.weight(1f).fillMaxHeight()) { onListo(RespuestaItem(Asistente.NUNCA)) }
        Baldosa("No sé", false, Modifier.weight(1f).fillMaxHeight(), noSe = true) { onListo(null) }
    }
    if (conKm) {
        KmYMes(km, { km = it }, mes, { mes = it }, Modifier.weight(1f)) {
            if (tipo == Asistente.APROX) Text("Lo marcamos como aproximado.", color = p.tenue, fontSize = 16.sp)
            Boton("Listo", Modifier.fillMaxWidth(), principal = true, activo = km > 0) { onListo(RespuestaItem(tipo!!, km, mes)) }
        }
    }
}

/** Km keypad on the left (its display on top), month and the actions on the right, bottom-aligned. */
@Composable
private fun KmYMes(km: Int, onKm: (Int) -> Unit, mes: String, onMes: (String) -> Unit, modifier: Modifier, acciones: @Composable ColumnScope.() -> Unit) {
    val p = LocalPaleta.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TecladoKm(km, onKm, Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
            Text("Mes", color = p.tenue, fontSize = 17.sp)
            SelectorMes(mes, onMes)
            acciones()
        }
    }
}

@Composable
private fun ColumnScope.PreguntaKm(titulo: String, ayuda: String, inicial: Int, onListo: (Int) -> Unit) {
    val p = LocalPaleta.current
    var km by remember { mutableStateOf(inicial) }
    Titulo(titulo, ayuda)
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(p.celda).padding(22.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${miles(km)} km", color = p.texto, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Boton("Listo", Modifier.fillMaxWidth(), principal = true, activo = km > 0) { onListo(km) }
        }
        TecladoKm(km, { km = it }, Modifier.weight(1.2f).fillMaxHeight(), conVisor = false)
    }
}

@Composable
private fun ColumnScope.PreguntaMes(titulo: String, ayuda: String, inicial: String, onListo: (String?) -> Unit) {
    var mes by remember { mutableStateOf(inicial) }
    Titulo(titulo, ayuda)
    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        SelectorMes(mes, { mes = it }, grande = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Boton("Listo", Modifier.weight(1f).height(72.dp), principal = true) { onListo(mes) }
            Boton("No sé", Modifier.weight(1f).height(72.dp)) { onListo(null) }
        }
    }
}

private val meses = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

/** Mes y año con botones grandes, sin teclado. */
@Composable
private fun SelectorMes(mes: String, onCambio: (String) -> Unit, grande: Boolean = false) {
    val p = LocalPaleta.current
    val ym = runCatching { YearMonth.parse(mes) }.getOrDefault(YearMonth.now())
    val hoy = YearMonth.now()
    val alto = if (grande) 72.dp else 60.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Boton("−", Modifier.height(alto)) { onCambio(ym.minusMonths(1).toString()) }
        Box(Modifier.weight(1f).height(alto).clip(RoundedCornerShape(14.dp)).background(p.celda), contentAlignment = Alignment.Center) {
            Text("${meses[ym.monthValue - 1]} ${ym.year}", color = p.texto, fontSize = if (grande) 30.sp else 24.sp, fontWeight = FontWeight.Bold)
        }
        Boton("+", Modifier.height(alto), activo = ym < hoy) { onCambio(ym.plusMonths(1).toString()) }
        Boton("−1 año", Modifier.height(alto)) { onCambio(ym.minusYears(1).toString()) }
    }
}

/** Teclado numérico grande para km: el único dato que se escribe (RNF-05). Fills the height it is given. */
@Composable
private fun TecladoKm(km: Int, onCambio: (Int) -> Unit, modifier: Modifier = Modifier, conVisor: Boolean = true) {
    val p = LocalPaleta.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (conVisor) Text("${miles(km)} km", color = p.texto, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("000", "0", "⌫")).forEach { fila ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fila.forEach { t ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.pista)
                            .clickable(role = Role.Button, onClickLabel = if (t == "⌫") "Borrar" else t) {
                                val nuevo: Long = when (t) {
                                    "⌫" -> km / 10L
                                    "000" -> km * 1000L
                                    else -> km * 10L + t.toInt()
                                }
                                onCambio(nuevo.coerceAtMost(999_999L).toInt())
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t, color = p.texto, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Lo que va a quedar con estas respuestas, antes de guardar. */
@Composable
private fun ColumnScope.Resultado(e: EstadoTablero, r: RespuestasAsistente, items: List<ItemMantenimiento>) {
    val p = LocalPaleta.current
    val g = e.garaje ?: return
    val res = Asistente.aplicar(r, g.vehiculo, items, Clock.System.now(), TimeZone.currentSystemDefault())
    val odometro = maxOf(g.odometroKm, r.kmActual ?: 0, e.odometroKm.toInt())
    val estados = EstadoMantenimiento.calcular(items, g.servicios.filter { !it.id.startsWith("declarado:") } + res.servicios,
        res.vehiculo, odometro, Clock.System.now(), TimeZone.currentSystemDefault())
    val cuenta = estados.groupingBy { it.estado }.eachCount()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        AnimacionLottie(Animacion.LISTO, Modifier.size(76.dp))
        Text("Así queda tu plan", color = p.texto, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (res.avisos.isNotEmpty()) {
            Text(
                "${res.avisos.size} avisos del chequeo",
                color = p.atencion, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(p.atencion.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple(cuenta[Estado.VENCIDO] ?: 0, "vencidos", p.atencion),
            Triple(cuenta[Estado.PROXIMO] ?: 0, "próximos", p.normal),
            Triple(cuenta[Estado.AL_DIA] ?: 0, "al día", p.ok),
            Triple(cuenta[Estado.DESCONOCIDO] ?: 0, "sin datos", p.tenue),
        ).forEach { (n, texto, color) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(p.celda).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("$n", color = color, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text(texto, color = p.tenue, fontSize = 16.sp)
            }
        }
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(EstadoMantenimiento.proximoService(estados), key = { it.item.id }) { s ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.celda).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(s.item.nombre, color = p.texto, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Text(s.texto(), color = if (s.estado == Estado.VENCIDO || s.probablementeVencido) p.atencion else p.tenue, fontSize = 19.sp)
            }
        }
    }
}
