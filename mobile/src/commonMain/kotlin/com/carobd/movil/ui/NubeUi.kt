package com.carobd.movil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.carobd.core.model.Viaje
import com.carobd.core.sync.ConfigNube
import com.carobd.core.sync.EstadoNube
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

fun textoNube(n: EstadoNube): String = when {
    !n.configurada -> "Sin nube"
    n.sincronizando -> "Sincronizando…"
    n.error != null -> "Sin conexión · ${n.pendientes}"
    n.pendientes > 0 -> "${n.pendientes} por subir"
    else -> "Al día ✓"
}

private fun fechaCorta(t: Instant): String {
    val h = t.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${h.dayOfMonth.toString().padStart(2, '0')}/${h.monthNumber.toString().padStart(2, '0')} " +
        "${h.hour.toString().padStart(2, '0')}:${h.minute.toString().padStart(2, '0')}"
}

/** Un decimal sin depender de String.format, que no existe en iOS. */
private fun unDecimal(x: Double): String {
    val d = (x * 10).roundToInt()
    return "${d / 10},${d % 10}"
}

@Composable
fun FilaViaje(v: Viaje) {
    val min = (v.duracionSeg / 60).toInt()
    val consumo = v.consumoLitros?.takeIf { v.km > 1 }?.let { " · ${unDecimal(it / v.km * 100)} L/100" } ?: ""
    Text(
        "${fechaCorta(v.inicio)} · ${unDecimal(v.km)} km · $min min$consumo",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun DialogoNube(nube: EstadoNube, config: ConfigNube, onGuardar: (String, String) -> Unit, onCerrar: () -> Unit) {
    var url by remember { mutableStateOf(config.url) }
    var token by remember { mutableStateOf(config.token) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Nube") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildString {
                        append(if (nube.pendientes == 0) "Todo subido" else "${nube.pendientes} cambios por subir")
                        nube.ultimoSync?.let { append(" · última vez ${fechaCorta(it)}") }
                    },
                )
                nube.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Servidor (https://…)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onGuardar(url, token) }) {
                Text(if (url.isNotBlank() && token.isNotBlank()) "Guardar y sincronizar" else "Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}
