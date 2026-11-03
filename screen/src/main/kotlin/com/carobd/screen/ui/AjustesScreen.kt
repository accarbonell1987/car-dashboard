package com.carobd.screen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.screen.EstadoTablero
import com.carobd.screen.ModoNoche
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Fila
import com.carobd.screen.ui.comun.Opcion
import com.carobd.screen.ui.comun.Tarjeta
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private enum class SeccionAjustes(val nombre: String) {
    PANTALLA("Pantalla"), ADAPTADOR("Adaptador"), CUENTA("Cuenta y sync"), ACERCA("Acerca de")
}

@Composable
fun AjustesScreen(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var seccion by rememberSaveable { mutableStateOf(SeccionAjustes.PANTALLA) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SeccionAjustes.entries) {
                val activa = s == seccion
                Text(
                    s.nombre,
                    color = if (activa) p.texto else p.tenue,
                    fontSize = 17.sp,
                    fontWeight = if (activa) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (activa) p.pista else p.fondo)
                        .semantics { selected = activa }
                        .clickable(role = Role.Tab) { seccion = s }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                )
            }
        }
        // Se desplaza: en horizontal el teclado tapa la mitad de la pantalla.
        Tarjeta(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(seccion.nombre, color = p.texto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                when (seccion) {
                    SeccionAjustes.PANTALLA -> AjustesPantalla(e, acciones)
                    SeccionAjustes.ADAPTADOR -> AjustesAdaptador(e)
                    SeccionAjustes.CUENTA -> AjustesCuenta(e, acciones)
                    SeccionAjustes.ACERCA -> AjustesAcerca()
                }
            }
        }
    }
}

@Composable
private fun AjustesPantalla(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    Text("Modo noche", color = p.texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text("Baja el brillo de todos los colores para no encandilar. En Auto cambia solo de 19:00 a 7:00.", color = p.tenue, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in ModoNoche.entries) Opcion(m.etiqueta, m == e.modoNoche, Modifier.weight(1f)) { acciones.elegirModoNoche(m) }
    }
    Spacer(Modifier.height(8.dp))
    Text("Brillo", color = p.texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Opcion("Del sistema", e.brillo == null) { acciones.fijarBrillo(null) }
        Slider(
            value = e.brillo ?: 0.8f,
            onValueChange = { acciones.fijarBrillo(it.coerceIn(0.1f, 1f)) },
            valueRange = 0.1f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = p.normal, activeTrackColor = p.normal, inactiveTrackColor = p.pista),
        )
    }
}

@Composable
private fun AjustesAdaptador(e: EstadoTablero) {
    val p = LocalPaleta.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Opcion("Modo demo", e.esDemo, Modifier.weight(1f)) {}
        Opcion("vLinker BM", !e.esDemo, Modifier.weight(1f), activo = false) {}
    }
    Fila("Fuente de datos", if (e.esDemo) "Simulador del Accent" else e.fuente)
    Fila("Conexión", "Bluetooth clásico, ELM327")
    Text(
        "Conectar el vLinker llega en la fase 1, cuando tengas el adaptador: se empareja en el Bluetooth de Android y se elige aquí.",
        color = p.tenue,
        fontSize = 14.sp,
    )
}

@Composable
private fun AjustesCuenta(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var url by remember(e.config) { mutableStateOf(e.config.url) }
    var token by remember(e.config) { mutableStateOf(e.config.token) }
    val n = e.nube
    Fila("Cambios por enviar", n.pendientes.toString())
    Fila(
        "Última sincronización",
        n.ultimoSync?.toLocalDateTime(TimeZone.currentSystemDefault())?.let {
            "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        } ?: "Nunca",
    )
    n.error?.let { Text("Error: $it", color = p.atencion, fontSize = 14.sp) }
    val colores = OutlinedTextFieldDefaults.colors(
        focusedTextColor = p.texto,
        unfocusedTextColor = p.texto,
        focusedBorderColor = p.normal,
        unfocusedBorderColor = p.linea,
        focusedLabelColor = p.normal,
        unfocusedLabelColor = p.tenue,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Servidor (https://…)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        colors = colores,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        colors = colores,
        modifier = Modifier.fillMaxWidth(),
    )
    val cambio = url.trim() != e.config.url || token.trim() != e.config.token
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Boton(if (cambio) "Guardar y sincronizar" else "Sincronizar ahora", principal = true, activo = url.isNotBlank() && token.isNotBlank()) {
            if (cambio) acciones.configurarNube(url, token) else acciones.sincronizarAhora()
        }
    }
}

@Composable
private fun AjustesAcerca() {
    val p = LocalPaleta.current
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Fila("Versión de la app", version)
    Text(
        "Las actualizaciones desde tu servidor (con verificación de firma del APK) llegan después de subir el servidor al VPS.",
        color = p.tenue,
        fontSize = 14.sp,
    )
}
