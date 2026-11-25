package com.carobd.screen.ui

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
import com.carobd.screen.ui.comun.Opcion
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
private sealed interface Pregunta {
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
private fun preguntas(r: RespuestasAsistente, items: List<ItemMantenimiento>, odometroDelTablero: Boolean): List<Pregunta> = buildList {
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
 */
@Composable
fun AsistenteScreen(e: EstadoTablero, r: RespuestasAsistente, acciones: AccionesAsistente) {
    val p = LocalPaleta.current
    val g = e.garaje
    val items = g?.items.orEmpty().filter { it.codigo != null }
    val lista = preguntas(r, items, e.odometroDelTablero)
    val indice = r.paso.coerceIn(0, lista.lastIndex)
    val pregunta = lista[indice]
    val enMarcha = (e.valores["velocidad"] ?: 0.0) > 0.0

    fun ir(nuevo: RespuestasAsistente, a: Int) = acciones.responderAsistente(nuevo.copy(paso = a.coerceIn(0, lista.lastIndex)))
    fun siguiente(nuevo: RespuestasAsistente = r) = ir(nuevo, indice + 1)
    fun saltarPaso() {
        val proximo = (indice + 1..lista.lastIndex).firstOrNull { lista[it].paso != pregunta.paso } ?: lista.lastIndex
        ir(r, proximo)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cabecera(pregunta.paso, indice, lista.size, onCerrar = acciones::cerrarAsistente)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (enMarcha) {
                EnMarcha()
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (pregunta) {
                        Pregunta.Motor -> PreguntaMotor(r) { siguiente(it) }
                        Pregunta.Caja -> Elegir(
                            "¿Qué caja tiene?", null,
                            listOf("Manual" to "MANUAL", "Automática" to "AUTOMATICA"), r.transmision,
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
                            listOf("Bajo azufre" to true, "Común" to false), r.en590,
                        ) { siguiente(r.copy(en590 = it)) }
                        Pregunta.Dpf -> Elegir(
                            "¿Tu auto tiene filtro de partículas (DPF)?",
                            "Si alguna vez viste en el tablero un rectángulo lleno de puntitos, lo tiene.",
                            listOf("Sí" to true, "No" to false), r.tieneDpf,
                        ) { siguiente(r.copy(tieneDpf = it)) }
                        Pregunta.UltimoService -> PreguntaUltimoService(r) { siguiente(it) }
                        is Pregunta.Item -> PreguntaItem(pregunta.item, r.items[pregunta.item.id]) { resp ->
                            siguiente(r.copy(items = if (resp == null) r.items - pregunta.item.id else r.items + (pregunta.item.id to resp)))
                        }
                        is Pregunta.Chequeo -> Elegir(
                            pregunta.q.texto, pregunta.q.ayuda, listOf("Sí" to true, "No" to false), r.chequeo[pregunta.q.id],
                        ) { siguiente(r.copy(chequeo = if (it == null) r.chequeo - pregunta.q.id else r.chequeo + (pregunta.q.id to it))) }
                        Pregunta.Resultado -> Resultado(e, r, items)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Boton("Atrás", activo = !enMarcha && indice > 0) { ir(r, indice - 1) }
            Spacer(Modifier.weight(1f))
            if (pregunta != Pregunta.Resultado) {
                Boton("Saltar ${pregunta.paso.titulo.lowercase()}", activo = !enMarcha) { saltarPaso() }
            } else {
                Boton("Guardar y terminar", principal = true, activo = !enMarcha) { acciones.terminarAsistente(r.copy(terminado = true)) }
            }
        }
    }
}

@Composable
private fun Cabecera(paso: PasoAsistente, indice: Int, total: Int, onCerrar: () -> Unit) {
    val p = LocalPaleta.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AnimacionLottie(Animacion.PROGRESO, Modifier.size(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PasoAsistente.entries.forEach { s ->
                    Text(
                        s.titulo, fontSize = 16.sp,
                        color = if (s == paso) p.texto else p.tenue,
                        fontWeight = if (s == paso) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.pista)) {
                Box(Modifier.fillMaxWidth((indice + 1f) / total).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(p.normal))
            }
        }
        Boton("Cerrar", onClick = onCerrar)
    }
}

@Composable
private fun EnMarcha() {
    val p = LocalPaleta.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AnimacionLottie(Animacion.PAUSA, Modifier.size(120.dp))
        Text("Sigue cuando el auto esté detenido", color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text("Tus respuestas quedan guardadas.", color = p.tenue, fontSize = 18.sp)
    }
}

@Composable
private fun Titulo(texto: String, ayuda: String?) {
    val p = LocalPaleta.current
    Text(texto, color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    ayuda?.let { Text(it, color = p.tenue, fontSize = 18.sp) }
}

/** Una pregunta de opciones grandes más "No sé", que siempre se acepta (RF-08). */
@Composable
private fun <T> Elegir(titulo: String, ayuda: String?, opciones: List<Pair<String, T>>, actual: T?, onElegir: (T?) -> Unit) {
    Titulo(titulo, ayuda)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        opciones.forEach { (texto, valor) -> Opcion(texto, actual == valor, Modifier.width(220.dp)) { onElegir(valor) } }
        Opcion("No sé", actual == null, Modifier.width(220.dp)) { onElegir(null) }
    }
}

@Composable
private fun PreguntaMotor(r: RespuestasAsistente, onListo: (RespuestasAsistente) -> Unit) {
    val p = LocalPaleta.current
    Titulo("¿Qué motor tiene tu Accent?", "Con eso elegimos el plan de mantenimiento del manual.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Asistente.motores.forEach { m ->
            Opcion(m.nombre, r.plantilla != null && r.plantilla == m.plantilla, Modifier.width(240.dp), activo = m.plantilla != null) {
                onListo(r.copy(plantilla = m.plantilla))
            }
        }
    }
    Text("Los motores nafta llegan pronto: su plan todavía no está cargado.", color = p.tenue, fontSize = 16.sp)
}

@Composable
private fun PreguntaCondiciones(r: RespuestasAsistente, onListo: (RespuestasAsistente) -> Unit) {
    var marcadas by remember(r.condiciones) { mutableStateOf(r.condiciones.toSet()) }
    Titulo("¿Alguna de estas situaciones es habitual?", "Si marcas alguna, el plan pasa a uso severo: el aceite, por ejemplo, va cada 5.000 km.")
    Asistente.condiciones.chunked(4).forEach { fila ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fila.forEach { (letra, texto) ->
                Opcion(texto, letra in marcadas, Modifier.weight(1f)) {
                    marcadas = if (letra in marcadas) marcadas - letra else marcadas + letra
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Boton("Ninguna", activo = marcadas.isEmpty()) { onListo(r.copy(condiciones = emptyList(), usoSevero = false)) }
        Boton("Listo", principal = true, activo = marcadas.isNotEmpty()) {
            onListo(r.copy(condiciones = marcadas.sorted(), usoSevero = true))
        }
    }
}

@Composable
private fun PreguntaUltimoService(r: RespuestasAsistente, onListo: (RespuestasAsistente) -> Unit) {
    var km by remember { mutableStateOf(r.ultimoServiceKm ?: 0) }
    var mes by remember { mutableStateOf(r.ultimoServiceMes ?: YearMonth.now().minusMonths(6).toString()) }
    Titulo(
        "¿Le hiciste el service completo del manual hace poco?",
        "Escribe a qué km fue (por ejemplo 90.000). Marcamos todo lo que el plan pide en ese service y te preguntamos solo el resto.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) { TecladoKm(km) { km = it } }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectorMes(mes) { mes = it }
            Boton("Listo", principal = true, activo = km >= 10_000) { onListo(r.copy(ultimoServiceKm = km, ultimoServiceMes = mes)) }
            Boton("No me acuerdo") { onListo(r.copy(ultimoServiceKm = null, ultimoServiceMes = null)) }
        }
    }
}

@Composable
private fun PreguntaItem(item: ItemMantenimiento, actual: RespuestaItem?, onListo: (RespuestaItem?) -> Unit) {
    val p = LocalPaleta.current
    var tipo by remember(item.id) { mutableStateOf(actual?.tipo) }
    var km by remember(item.id) { mutableStateOf(actual?.km ?: 0) }
    var mes by remember(item.id) { mutableStateOf(actual?.mes ?: YearMonth.now().minusMonths(6).toString()) }
    val verbo = if (item.accion == "REVISAR") "revisaste" else "cambiaste"
    Titulo("¿Cuándo $verbo por última vez: ${item.nombre.removeSuffix(" (revisar)").lowercase()}?", item.notas)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Opcion("Sé el km", tipo == Asistente.EXACTO, Modifier.weight(1f)) { tipo = Asistente.EXACTO }
        Opcion("Más o menos", tipo == Asistente.APROX, Modifier.weight(1f)) { tipo = Asistente.APROX }
        Opcion("Nunca", tipo == Asistente.NUNCA, Modifier.weight(1f)) { onListo(RespuestaItem(Asistente.NUNCA)) }
        Opcion("No sé", tipo == null, Modifier.weight(1f)) { onListo(null) }
    }
    if (tipo == Asistente.EXACTO || tipo == Asistente.APROX) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { TecladoKm(km) { km = it } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectorMes(mes) { mes = it }
                Boton("Listo", principal = true, activo = km > 0) { onListo(RespuestaItem(tipo!!, km, mes)) }
                if (tipo == Asistente.APROX) Text("Lo marcamos como aproximado.", color = p.tenue, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun PreguntaKm(titulo: String, ayuda: String, inicial: Int, onListo: (Int) -> Unit) {
    var km by remember { mutableStateOf(inicial) }
    Titulo(titulo, ayuda)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) { TecladoKm(km) { km = it } }
        Column(Modifier.weight(1f)) { Boton("Listo", principal = true, activo = km > 0) { onListo(km) } }
    }
}

@Composable
private fun PreguntaMes(titulo: String, ayuda: String, inicial: String, onListo: (String?) -> Unit) {
    var mes by remember { mutableStateOf(inicial) }
    Titulo(titulo, ayuda)
    SelectorMes(mes) { mes = it }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Boton("Listo", principal = true) { onListo(mes) }
        Boton("No sé") { onListo(null) }
    }
}

private val meses = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

/** Mes y año con botones grandes, sin teclado. */
@Composable
private fun SelectorMes(mes: String, onCambio: (String) -> Unit) {
    val p = LocalPaleta.current
    val ym = runCatching { YearMonth.parse(mes) }.getOrDefault(YearMonth.now())
    val hoy = YearMonth.now()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Boton("−") { onCambio(ym.minusMonths(1).toString()) }
        Text("${meses[ym.monthValue - 1]} ${ym.year}", color = p.texto, fontSize = 24.sp, modifier = Modifier.width(240.dp))
        Boton("+", activo = ym < hoy) { onCambio(ym.plusMonths(1).toString()) }
        Boton("−1 año") { onCambio(ym.minusYears(1).toString()) }
    }
}

/** Teclado numérico grande para km: el único dato que se escribe (RNF-05). */
@Composable
private fun TecladoKm(km: Int, onCambio: (Int) -> Unit) {
    val p = LocalPaleta.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${miles(km)} km", color = p.texto, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("000", "0", "⌫")).forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fila.forEach { t ->
                    Boton(t, Modifier.width(96.dp)) {
                        val nuevo: Long = when (t) {
                            "⌫" -> km / 10L
                            "000" -> km * 1000L
                            else -> km * 10L + t.toInt()
                        }
                        onCambio(nuevo.coerceAtMost(999_999L).toInt())
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        AnimacionLottie(Animacion.LISTO, Modifier.size(96.dp))
        Column {
            Text("Así queda tu plan", color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${cuenta[Estado.VENCIDO] ?: 0} vencidos · ${cuenta[Estado.PROXIMO] ?: 0} próximos · " +
                    "${cuenta[Estado.AL_DIA] ?: 0} al día · ${cuenta[Estado.DESCONOCIDO] ?: 0} sin datos",
                color = p.tenue, fontSize = 18.sp,
            )
            if (res.avisos.isNotEmpty()) Text("${res.avisos.size} avisos del chequeo", color = p.atencion, fontSize = 18.sp)
        }
    }
    LazyColumn(Modifier.weight(1f)) {
        items(EstadoMantenimiento.proximoService(estados), key = { it.item.id }) { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(s.item.nombre, color = p.texto, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(s.texto(), color = if (s.estado == Estado.VENCIDO || s.probablementeVencido) p.atencion else p.tenue, fontSize = 18.sp)
            }
        }
    }
}
