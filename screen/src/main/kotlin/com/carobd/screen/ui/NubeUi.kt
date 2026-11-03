package com.carobd.screen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.carobd.core.miles
import com.carobd.screen.EstadoTablero
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.floor

/** Lo que dice el botón de la nube en la barra: si está al día o cuántos cambios esperan. */
fun textoNube(e: EstadoTablero): String = when {
    !e.nube.configurada -> "☁ Sin nube"
    e.nube.sincronizando -> "☁ …"
    e.nube.error != null -> "☁ ! ${e.nube.pendientes}"
    e.nube.pendientes > 0 -> "☁ ${e.nube.pendientes}"
    else -> "☁ ✓"
}

@Composable
fun DialogoNube(e: EstadoTablero, acciones: AccionesTablero, onCerrar: () -> Unit) {
    var url by remember { mutableStateOf(e.config.url) }
    var token by remember { mutableStateOf(e.config.token) }
    val n = e.nube
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Datos y nube") },
        text = {
            // En horizontal el teclado tapa la mitad de la pantalla: el contenido se desplaza para llegar al token.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Odómetro: ${miles(floor(e.odometroKm).toInt())} km")
                e.ultimoViajeKm?.let { Text("Último viaje: ${"%.1f".format(it)} km") }
                Text(
                    buildString {
                        append(if (n.pendientes == 0) "Todo subido" else "${n.pendientes} cambios por subir")
                        n.ultimoSync?.let {
                            val h = it.toLocalDateTime(TimeZone.currentSystemDefault())
                            append(" · última vez ${h.date} ${h.hour.toString().padStart(2, '0')}:${h.minute.toString().padStart(2, '0')}")
                        }
                    },
                )
                n.error?.let { Text("Error: $it") }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Servidor (https://…)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
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
            TextButton(onClick = {
                if (url.trim() != e.config.url || token.trim() != e.config.token) acciones.configurarNube(url, token) else acciones.sincronizarAhora()
                onCerrar()
            }) { Text(if (url.isNotBlank() && token.isNotBlank()) "Guardar y sincronizar" else "Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
    )
}
