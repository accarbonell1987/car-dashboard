package com.carobd.screen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.carobd.core.model.NivelAviso
import com.carobd.core.obd.Catalogo
import com.carobd.core.tablero.Alerta
import com.carobd.core.tablero.Alertas
import com.carobd.core.tablero.Celda
import com.carobd.core.tablero.Grilla
import com.carobd.core.tablero.TipoGauge
import com.carobd.screen.EstadoTablero
import com.carobd.screen.ModoNoche
import com.carobd.screen.Seccion
import com.carobd.screen.gauges.Gauge
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.gauges.formatear

/** Acciones que la pantalla le pide al ViewModel. */
interface AccionesTablero {
    fun seleccionar(indice: Int)
    fun alternarEdicion()
    fun mover(id: String, col: Int, fila: Int)
    fun redimensionar(id: String, ancho: Int, alto: Int)
    fun cambiarSenal(id: String, senalId: String, tipo: TipoGauge)
    fun quitar(id: String)
    fun agregar(senalId: String, tipo: TipoGauge): Boolean
    fun restaurar()
    fun configurarNube(url: String, token: String)
    fun sincronizarAhora()
    fun irA(s: Seccion)
    fun elegirModoNoche(m: ModoNoche)
    fun fijarBrillo(b: Float?)
}

/**
 * Tablero de 6x3. En modo edición:
 * - arrastrar un gauge lo mueve a otra celda,
 * - arrastrar la esquina inferior derecha cambia su tamaño,
 * - tocarlo cambia la señal o el tipo, y la × lo quita.
 * Si un cambio no cabe (se sale o pisa otro gauge) el gauge vuelve a su sitio.
 */
@Composable
fun TableroScreen(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var eligiendo by remember { mutableStateOf<Celda?>(null) }
    var agregando by remember { mutableStateOf(false) }
    var lleno by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(p.fondo)) {
        BarraTableros(e, acciones, onAgregar = { agregando = true })
        if (!e.editando && e.alertas.isNotEmpty()) BannerAlerta(e.alertas.first())
        Spacer(Modifier.height(6.dp))
        GrillaTablero(e, acciones, onElegir = { eligiendo = it })
    }

    eligiendo?.let { celda ->
        DialogoSenal(
            titulo = "Cambiar gauge",
            tipoInicial = celda.tipo,
            onElegir = { senalId, tipo ->
                acciones.cambiarSenal(celda.id, senalId, tipo)
                eligiendo = null
            },
            onCerrar = { eligiendo = null },
        )
    }
    if (agregando) {
        DialogoSenal(
            titulo = "Agregar gauge",
            tipoInicial = TipoGauge.NUMERO,
            onElegir = { senalId, tipo ->
                agregando = false
                if (!acciones.agregar(senalId, tipo)) lleno = true
            },
            onCerrar = { agregando = false },
        )
    }
    if (lleno) {
        AlertDialog(
            onDismissRequest = { lleno = false },
            confirmButton = { TextButton(onClick = { lleno = false }) { Text("Entendido") } },
            title = { Text("No hay espacio") },
            text = { Text("El tablero está lleno. Quita o achica un gauge para hacer lugar.") },
        )
    }
}

@Composable
private fun BarraTableros(e: EstadoTablero, acciones: AccionesTablero, onAgregar: () -> Unit) {
    val p = LocalPaleta.current
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        e.tableros.forEachIndexed { i, t ->
            val activo = i == e.indice
            Text(
                t.nombre,
                color = if (activo) p.texto else p.tenue,
                fontSize = 16.sp,
                fontWeight = if (activo) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (activo) p.celda else p.fondo)
                    .clickable(enabled = !e.editando) { acciones.seleccionar(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (e.editando) {
            BotonTexto("+ Agregar", p.normal, onAgregar)
            BotonTexto("Restaurar", p.tenue) { acciones.restaurar() }
        }
        BotonTexto(if (e.editando) "Listo" else "Editar", p.normal) { acciones.alternarEdicion() }
    }
}

@Composable
internal fun BotonTexto(texto: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        texto,
        color = color,
        fontSize = 16.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
internal fun BannerAlerta(a: Alerta) {
    val p = LocalPaleta.current
    val color = if (a.regla.nivel == NivelAviso.CRITICO) p.peligro else p.atencion
    val senal = Catalogo.porId(a.regla.senalId)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.22f))
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠ ${a.regla.mensaje}", color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (senal != null) Text("${senal.nombre}: ${formatear(a.valor, senal)} ${senal.unidad}", color = p.texto, fontSize = 15.sp)
    }
}

@Composable
private fun GrillaTablero(e: EstadoTablero, acciones: AccionesTablero, onElegir: (Celda) -> Unit) {
    val t = e.actual
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val espacio = 8.dp
        val anchoCelda = (maxWidth - espacio * (t.columnas - 1)) / t.columnas
        val altoCelda = (maxHeight - espacio * (t.filas - 1)) / t.filas
        for (celda in t.celdas) {
            key(t.id, celda.id) {
                CeldaTablero(
                    celda = celda,
                    nivel = Alertas.nivelDe(celda.senalId, e.alertas),
                    valor = e.valores[celda.senalId],
                    editando = e.editando,
                    anchoCelda = anchoCelda,
                    altoCelda = altoCelda,
                    espacio = espacio,
                    acciones = acciones,
                    onElegir = onElegir,
                )
            }
        }
    }
}

@Composable
private fun CeldaTablero(
    celda: Celda,
    nivel: NivelAviso?,
    valor: Double?,
    editando: Boolean,
    anchoCelda: Dp,
    altoCelda: Dp,
    espacio: Dp,
    acciones: AccionesTablero,
    onElegir: (Celda) -> Unit,
) {
    val p = LocalPaleta.current
    val senal = Catalogo.porId(celda.senalId) ?: return
    val densidad = LocalDensity.current
    val pasoX = with(densidad) { (anchoCelda + espacio).toPx() }
    val pasoY = with(densidad) { (altoCelda + espacio).toPx() }
    val actual by rememberUpdatedState(celda)

    var arrastre by remember { mutableStateOf(Offset.Zero) }
    var estirar by remember { mutableStateOf(Offset.Zero) }

    // Vista previa mientras se estira: tamaño redondeado a celdas, mínimo 1x1.
    val anchoVista = (celda.ancho + Grilla.aCeldas(estirar.x, pasoX)).coerceIn(1, Grilla.MAX_LADO)
    val altoVista = (celda.alto + Grilla.aCeldas(estirar.y, pasoY)).coerceIn(1, Grilla.MAX_LADO)

    var mod = Modifier
        .offset(x = (anchoCelda + espacio) * celda.col, y = (altoCelda + espacio) * celda.fila)
        .size(
            width = anchoCelda * anchoVista + espacio * (anchoVista - 1),
            height = altoCelda * altoVista + espacio * (altoVista - 1),
        )

    if (editando) {
        mod = mod
            .zIndex(if (arrastre != Offset.Zero || estirar != Offset.Zero) 1f else 0f)
            .graphicsLayer {
                translationX = arrastre.x
                translationY = arrastre.y
                alpha = if (arrastre != Offset.Zero) 0.85f else 1f
            }
            .border(2.dp, p.edicion, RoundedCornerShape(14.dp))
            .pointerInput(celda.id) { detectTapGestures(onTap = { onElegir(actual) }) }
            .pointerInput(celda.id, pasoX, pasoY) {
                detectDragGestures(
                    onDrag = { cambio, delta ->
                        cambio.consume()
                        arrastre += delta
                    },
                    onDragEnd = {
                        val c = actual
                        acciones.mover(c.id, c.col + Grilla.aCeldas(arrastre.x, pasoX), c.fila + Grilla.aCeldas(arrastre.y, pasoY))
                        arrastre = Offset.Zero
                    },
                    onDragCancel = { arrastre = Offset.Zero },
                )
            }
    }

    Box(mod) {
        Gauge(celda, senal, valor, nivel, Modifier.fillMaxSize())
        if (editando) {
            // Quitar
            Text(
                "×",
                color = p.texto,
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(p.peligro.copy(alpha = 0.7f))
                    .clickable { acciones.quitar(actual.id) }
                    .padding(start = 9.dp),
            )
            // Estirar
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                    .background(p.edicion.copy(alpha = 0.8f))
                    .pointerInput(celda.id, pasoX, pasoY) {
                        detectDragGestures(
                            onDrag = { cambio, delta ->
                                cambio.consume()
                                estirar += delta
                            },
                            onDragEnd = {
                                val c = actual
                                acciones.redimensionar(
                                    c.id,
                                    c.ancho + Grilla.aCeldas(estirar.x, pasoX),
                                    c.alto + Grilla.aCeldas(estirar.y, pasoY),
                                )
                                estirar = Offset.Zero
                            },
                            onDragCancel = { estirar = Offset.Zero },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("⤡", color = p.fondo, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun DialogoSenal(
    titulo: String,
    tipoInicial: TipoGauge,
    onElegir: (senalId: String, tipo: TipoGauge) -> Unit,
    onCerrar: () -> Unit,
) {
    var tipo by remember { mutableStateOf(if (tipoInicial == TipoGauge.MARCHA) TipoGauge.NUMERO else tipoInicial) }
    AlertDialog(
        onDismissRequest = onCerrar,
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
        title = { Text(titulo) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((t, nombre) in listOf(TipoGauge.ARCO to "Arco", TipoGauge.NUMERO to "Número", TipoGauge.BARRA to "Barra")) {
                        val activo = t == tipo
                        Text(
                            nombre,
                            fontWeight = if (activo) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(if (activo) 2.dp else 1.dp, androidx.compose.material3.MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .clickable { tipo = t }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.height(320.dp).width(420.dp)) {
                    items(Catalogo.estandar) { s ->
                        Text(
                            "${s.nombre} (${s.unidad.ifEmpty { "-" }})",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onElegir(s.id, if (s.id == "marcha") TipoGauge.MARCHA else tipo) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
    )
}
