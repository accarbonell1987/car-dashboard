package com.carobd.screen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.obd.Catalogo
import com.carobd.screen.gauges.Celda
import com.carobd.screen.gauges.Colores
import com.carobd.screen.gauges.DefinicionTablero
import com.carobd.screen.gauges.Gauge
import com.carobd.screen.gauges.TipoGauge

/**
 * Grilla de 6x3. En modo edición, tocar un gauge abre la lista de señales para cambiarlo.
 * Mover y redimensionar celdas con arrastre llega en la fase 2 (Reorderable).
 */
@Composable
fun TableroScreen(
    tablero: DefinicionTablero,
    valores: Map<String, Double>,
    fuente: String,
    editando: Boolean,
    onEditar: () -> Unit,
    onCambiarCelda: (indice: Int, Celda) -> Unit,
) {
    var eligiendo by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().background(Colores.fondo).padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("CAROBD · ${tablero.nombre}", color = Colores.texto, fontSize = 16.sp)
            Text("Fuente: $fuente", color = Colores.tenue, fontSize = 14.sp)
            Text(
                if (editando) "Listo" else "Editar",
                color = Colores.normal,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onEditar).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val espacio = 8.dp
            val anchoCelda = (maxWidth - espacio * (tablero.columnas - 1)) / tablero.columnas
            val altoCelda = (maxHeight - espacio * (tablero.filas - 1)) / tablero.filas
            tablero.celdas.forEachIndexed { i, celda ->
                val senal = Catalogo.porId(celda.senalId) ?: return@forEachIndexed
                var mod = Modifier
                    .offset(x = (anchoCelda + espacio) * celda.col, y = (altoCelda + espacio) * celda.fila)
                    .size(
                        width = anchoCelda * celda.ancho + espacio * (celda.ancho - 1),
                        height = altoCelda * celda.alto + espacio * (celda.alto - 1),
                    )
                if (editando) {
                    mod = mod
                        .border(2.dp, Colores.alerta, RoundedCornerShape(14.dp))
                        .clickable { eligiendo = i }
                }
                Gauge(celda, senal, valores[celda.senalId], mod)
            }
        }
    }

    eligiendo?.let { indice ->
        val actual = tablero.celdas[indice]
        AlertDialog(
            onDismissRequest = { eligiendo = null },
            confirmButton = { TextButton(onClick = { eligiendo = null }) { Text("Cerrar") } },
            title = { Text("Elegir señal") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(Catalogo.estandar) { s ->
                        Text(
                            "${s.nombre} (${s.unidad.ifEmpty { "-" }})",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val tipo = when {
                                        s.id == "marcha" -> TipoGauge.MARCHA
                                        actual.tipo == TipoGauge.MARCHA -> TipoGauge.NUMERO
                                        else -> actual.tipo
                                    }
                                    onCambiarCelda(indice, actual.copy(senalId = s.id, tipo = tipo))
                                    eligiendo = null
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }
}
