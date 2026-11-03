package com.carobd.movil.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.carobd.core.miles
import com.carobd.core.model.DatosGaraje
import com.carobd.core.model.Garaje
import com.carobd.core.model.Mantenimiento
import com.carobd.core.model.Pendiente
import com.carobd.core.model.texto
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MantenimientoScreen(datos: DatosGaraje, onCambio: (DatosGaraje) -> Unit) {
    var editandoKm by remember { mutableStateOf(false) }
    var nuevoServicio by remember { mutableStateOf(false) }
    val pendientes = Mantenimiento.pendientes(datos.items, datos.odometroKm, Clock.System.now(), TimeZone.currentSystemDefault())
    val v = datos.vehiculo

    Scaffold(
        topBar = { TopAppBar(title = { Text("CAROBD") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nuevoServicio = true }) { Text("Registrar servicio") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth().clickable { editandoKm = true }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${v.marca} ${v.modelo} ${v.anio}", style = MaterialTheme.typography.titleLarge)
                        Text("${v.motor ?: ""} · diésel · manual", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("${miles(datos.odometroKm)} km", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Toca para corregir el odómetro", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Plan de mantenimiento", style = MaterialTheme.typography.titleMedium) }
            items(pendientes, key = { it.item.id }) { p -> FilaPendiente(p) }
            if (datos.servicios.isNotEmpty()) {
                item { Text("Historial", style = MaterialTheme.typography.titleMedium) }
                items(datos.servicios, key = { it.id }) { s ->
                    Text("${miles(s.km)} km · ${s.tipo}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (editandoKm) {
        DialogoKm(
            actual = datos.odometroKm,
            onListo = { km ->
                onCambio(Garaje.corregirOdometro(datos, km))
                editandoKm = false
            },
            onCancelar = { editandoKm = false },
        )
    }
    if (nuevoServicio) {
        DialogoServicio(
            datos = datos,
            onListo = { km, tipo, hechos ->
                onCambio(Garaje.registrarServicio(datos, km, tipo, hechos, Clock.System.now()))
                nuevoServicio = false
            },
            onCancelar = { nuevoServicio = false },
        )
    }
}

@Composable
private fun FilaPendiente(p: Pendiente) {
    val colores = if (p.vencido) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth(), colors = colores) {
        Column(Modifier.padding(14.dp)) {
            Text(p.item.nombre, style = MaterialTheme.typography.titleSmall)
            Text(p.texto(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DialogoKm(actual: Int, onListo: (Int) -> Unit, onCancelar: () -> Unit) {
    var texto by remember { mutableStateOf(actual.toString()) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Odómetro") },
        text = {
            OutlinedTextField(
                value = texto,
                onValueChange = { nuevo -> texto = nuevo.filter { it.isDigit() } },
                label = { Text("km") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { texto.toIntOrNull()?.let(onListo) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

@Composable
private fun DialogoServicio(datos: DatosGaraje, onListo: (Int, String, Set<String>) -> Unit, onCancelar: () -> Unit) {
    var km by remember { mutableStateOf(datos.odometroKm.toString()) }
    var tipo by remember { mutableStateOf("Mantenimiento") }
    var hechos by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Nuevo servicio") },
        text = {
            Column {
                OutlinedTextField(
                    value = km,
                    onValueChange = { nuevo -> km = nuevo.filter { it.isDigit() } },
                    label = { Text("km") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(value = tipo, onValueChange = { tipo = it }, label = { Text("Qué se hizo") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Ítems que quedan al día", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.height(220.dp)) {
                    items(datos.items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                hechos = if (item.id in hechos) hechos - item.id else hechos + item.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = item.id in hechos, onCheckedChange = null)
                            Text(item.nombre)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { km.toIntOrNull()?.let { onListo(it, tipo.ifBlank { "Mantenimiento" }, hechos) } }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}
