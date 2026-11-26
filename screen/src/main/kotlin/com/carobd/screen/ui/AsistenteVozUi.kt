package com.carobd.screen.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.carobd.core.voz.Realtime
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Icono
import com.carobd.screen.ui.comun.IconoTrazo
import com.carobd.screen.ui.comun.Tarjeta
import com.carobd.screen.voz.ControlVoz
import com.carobd.screen.voz.FaseVoz

/** Botón de hablar de la barra de arriba: un toque empieza a escuchar, otro corta. Pide el micrófono la primera vez. */
@Composable
fun BotonHablar(voz: ControlVoz) {
    val p = LocalPaleta.current
    val context = LocalContext.current
    val estado by voz.estado.collectAsState()
    val pedirMicrofono = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voz.alternar() else voz.avisar("Sin permiso de micrófono no puedo escucharte. Puedes darlo en los ajustes de Android.")
    }
    val activo = estado.fase != FaseVoz.APAGADO
    val color = if (activo) p.normal else p.texto
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (activo) p.normal.copy(alpha = 0.22f) else p.pista)
            .border(2.dp, if (activo) p.normal else p.linea, CircleShape)
            .semantics { contentDescription = if (activo) "Terminar conversación" else "Hablar con el asistente" }
            .clickable(role = Role.Button) {
                val permiso = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (permiso || activo) voz.alternar() else pedirMicrofono.launch(Manifest.permission.RECORD_AUDIO)
            },
        contentAlignment = Alignment.Center,
    ) {
        IconoTrazo(Icono.MICROFONO, color, 26.dp)
    }
}

/** Panel de la conversación, abajo y encima de la sección: qué está haciendo el asistente y las últimas frases. */
@Composable
fun PanelVoz(voz: ControlVoz, modifier: Modifier = Modifier) {
    val p = LocalPaleta.current
    val e by voz.estado.collectAsState()
    if (e.fase == FaseVoz.APAGADO && e.error == null) return
    Column(
        modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.celda)
            .border(2.dp, if (e.error != null) p.atencion else p.normal, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconoTrazo(Icono.MICROFONO, if (e.error != null) p.atencion else p.normal, 34.dp)
            Text(
                when (e.fase) {
                    FaseVoz.CONECTANDO -> "Conectando…"
                    FaseVoz.ESCUCHANDO -> "Te escucho"
                    FaseVoz.PENSANDO -> "Pensando…"
                    FaseVoz.HABLANDO -> "Hablando…"
                    FaseVoz.APAGADO -> "Asistente"
                },
                color = p.texto,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (e.fase == FaseVoz.APAGADO) {
                Boton("Cerrar") { voz.avisar(null) }
            } else {
                Boton("Terminar", principal = true) { voz.detener() }
            }
        }
        e.error?.let { Text(it, color = p.atencion, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        for (l in e.lineas.takeLast(3)) {
            Text(
                (if (l.delUsuario) "Tú: " else "") + l.texto,
                color = if (l.delUsuario) p.tenue else p.texto,
                fontSize = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Ajustes → Asistente: la clave de OpenAI de quien usa la pantalla. */
@Composable
internal fun AjustesVoz(voz: ControlVoz) {
    val p = LocalPaleta.current
    val c by voz.config.collectAsState()
    var clave by remember(c.clave) { mutableStateOf(c.clave) }
    var modelo by remember(c.modelo) { mutableStateOf(c.modelo) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tarjeta(Modifier.weight(1.2f).fillMaxHeight()) {
            TituloConIcono(Icono.MICROFONO, "Asistente de voz")
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (c.lista) "Listo. Toca el micrófono de arriba y habla." else "Falta tu clave de OpenAI.",
                    color = if (c.lista) p.ok else p.atencion,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Puedes preguntarle por la temperatura, la batería, una alerta, el próximo service o pedirle que te lleve a un lugar. " +
                        "Lee los datos que ya muestra la pantalla; no manda nada al auto.",
                    color = p.texto,
                    fontSize = 16.sp,
                )
                Text(
                    "Usa tu propia cuenta de OpenAI: cada conversación se cobra a esa cuenta. Conviene ponerle un tope de gasto en platform.openai.com. " +
                        "La clave queda guardada solo en esta pantalla. Necesita Internet.",
                    color = p.tenue,
                    fontSize = 15.sp,
                )
            }
        }
        Tarjeta(Modifier.weight(1f).fillMaxHeight(), titulo = "Cuenta de OpenAI") {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = it },
                    label = { Text("Clave (sk-…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = coloresCampo(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelo,
                    onValueChange = { modelo = it },
                    label = { Text("Modelo") },
                    singleLine = true,
                    colors = coloresCampo(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("El modelo solo se cambia si OpenAI retira el actual (${Realtime.MODELO}).", color = p.tenue, fontSize = 14.sp)
            }
            Spacer(Modifier.padding(4.dp))
            val cambio = clave.trim() != c.clave || modelo.trim() != c.modelo
            Boton("Guardar", Modifier.fillMaxWidth(), principal = true, activo = cambio) { voz.guardar(clave, modelo) }
        }
    }
}
