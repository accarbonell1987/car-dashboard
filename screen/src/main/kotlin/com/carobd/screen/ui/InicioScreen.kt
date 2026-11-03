package com.carobd.screen.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.model.Mantenimiento
import com.carobd.core.model.NivelAviso
import com.carobd.core.model.texto
import com.carobd.screen.EstadoTablero
import com.carobd.screen.Seccion
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Icono
import com.carobd.screen.ui.comun.IconoTrazo
import com.carobd.screen.ui.comun.Tarjeta
import com.carobd.screen.ui.comun.dec
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/** Inicio, al estilo de un launcher: lo que se mira de reojo manejando. */
@Composable
fun InicioScreen(e: EstadoTablero, acciones: AccionesTablero) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaNavegacion(Modifier.fillMaxWidth().weight(1f))
            TarjetaViaje(e, Modifier.fillMaxWidth().weight(1f))
        }
        TarjetaAuto(e, acciones, Modifier.weight(1.5f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaMusica(Modifier.fillMaxWidth().weight(1f))
            TarjetaAdaptador(e, acciones, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun TituloConIcono(icono: Icono, texto: String) {
    val p = LocalPaleta.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconoTrazo(icono, p.tenue, 18.dp)
        Text(texto.uppercase(), color = p.tenue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}

/** Abre una app instalada por su paquete; si no está, lo avisa. */
private fun abrirApp(context: Context, paquete: String, nombre: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(paquete)
    if (intent == null) {
        Toast.makeText(context, "$nombre no está instalada en la pantalla", Toast.LENGTH_SHORT).show()
    } else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun TarjetaNavegacion(modifier: Modifier) {
    val p = LocalPaleta.current
    val context = LocalContext.current
    Tarjeta(modifier) {
        TituloConIcono(Icono.NAVEGACION, "Navegación")
        Text("Abre la app de mapas instalada en la pantalla.", color = p.tenue, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Boton("Maps", Modifier.weight(1f), principal = true) { abrirApp(context, "com.google.android.apps.maps", "Google Maps") }
            Boton("Waze", Modifier.weight(1f)) { abrirApp(context, "com.waze", "Waze") }
        }
    }
}

@Composable
private fun TarjetaViaje(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    Tarjeta(modifier, titulo = "Viaje actual") {
        if (e.esDemo) {
            Text("El modo demo no graba viajes. Conecta el vLinker para empezar.", color = p.tenue, fontSize = 14.sp)
        } else if (e.viajeEnCursoKm == null) {
            Text("Sin viaje en curso. Empieza solo al arrancar el motor.", color = p.tenue, fontSize = 14.sp)
            e.ultimoViajeKm?.let { Text("Último: ${dec(it)} km", color = p.texto, fontSize = 15.sp) }
        } else {
            val seg = e.viajeEnCursoSeg
            Dato("Distancia", dec(e.viajeEnCursoKm), "km")
            Dato("Tiempo", "%d:%02d".format(seg / 60, seg % 60), "")
            Dato("Consumo medio", e.viajeEnCursoL100?.let { dec(it) } ?: "—", "L/100")
        }
    }
}

@Composable
private fun Dato(nombre: String, valor: String, unidad: String) {
    val p = LocalPaleta.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(nombre, color = p.tenue, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(valor, color = p.texto, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (unidad.isNotEmpty()) Text(" $unidad", color = p.tenue, fontSize = 13.sp)
    }
}

@Composable
private fun TarjetaAuto(e: EstadoTablero, acciones: AccionesTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    val v = e.garaje?.vehiculo
    val alerta = e.alertas.firstOrNull()
    val nivelDe = { id: String -> e.alertas.firstOrNull { it.regla.senalId == id }?.regla?.nivel }
    fun colorNivel(n: NivelAviso?) = when (n) {
        NivelAviso.CRITICO -> p.peligro
        NivelAviso.ATENCION -> p.atencion
        else -> p.texto
    }
    Tarjeta(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(nombreVehiculo(e), color = p.texto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${v?.motor ?: "1.6 CRDi"} · manual", color = p.tenue, fontSize = 14.sp)
            }
            val (texto, color) = if (alerta == null) "Todo en orden" to p.ok else alerta.regla.mensaje to colorNivel(alerta.regla.nivel)
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(p.pista).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(texto, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Silueta(Color(0xFFB8BEC6), Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val vs = e.valores
            Stat("Velocidad", vs["velocidad"]?.let { dec(it, 0) } ?: "--", "km/h", p.texto, Modifier.weight(1f))
            Stat("Marcha", vs["marcha"]?.toInt()?.takeIf { it > 0 }?.toString() ?: "N", "", p.texto, Modifier.weight(1f))
            Stat("Refrigerante", vs["temp_refrigerante"]?.let { dec(it, 0) } ?: "--", "°C", colorNivel(nivelDe("temp_refrigerante")), Modifier.weight(1f))
            Stat("Combustible", vs["combustible"]?.let { dec(it, 0) } ?: "--", "%", colorNivel(nivelDe("combustible")), Modifier.weight(1f))
        }
        val proximo = e.garaje?.let { g ->
            Mantenimiento.pendientes(g.items, maxOf(g.odometroKm, e.odometroKm.toInt()), Clock.System.now(), TimeZone.currentSystemDefault()).firstOrNull()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(p.pista)
                .clickable(role = Role.Button) { acciones.irA(Seccion.DIAGNOSTICO) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (proximo == null) "Plan de mantenimiento: se carga desde el móvil" else "Próximo servicio: ${proximo.item.nombre} · ${proximo.texto()}",
                color = if (proximo?.vencido == true) p.atencion else p.texto,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconoTrazo(Icono.FLECHA, p.tenue, 18.dp)
        }
    }
}

@Composable
private fun Stat(nombre: String, valor: String, unidad: String, color: Color, modifier: Modifier) {
    val p = LocalPaleta.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(p.pista).padding(10.dp)) {
        Text(nombre, color = p.tenue, fontSize = 12.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valor, color = color, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (unidad.isNotEmpty()) Text(" $unidad", color = p.tenue, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** Silueta lateral de un sedán (por ahora el Accent; las demás carrocerías llegan con el selector de vehículo). */
@Composable
fun Silueta(color: Color, modifier: Modifier) {
    val p = LocalPaleta.current
    val cuerpo = remember {
        PathParser().parsePathString(
            "M20,95 L30,72 Q40,66 70,64 L120,60 Q150,34 190,30 L250,30 Q285,32 310,58 L360,64 Q385,68 388,85 L390,98 " +
                "Q390,104 382,104 L340,104 A28,28 0 0 0 284,104 L132,104 A28,28 0 0 0 76,104 L28,104 Q20,104 20,95 Z",
        ).toPath()
    }
    val vidrios = remember {
        PathParser().parsePathString("M132,60 Q156,40 190,37 L222,37 L224,60 Z M232,37 L250,37 Q278,39 298,60 L234,60 Z").toPath()
    }
    Box(modifier.semantics { contentDescription = "Silueta del auto" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(410f / 135f)) {
            val k = size.width / 410f
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                drawPath(cuerpo, color)
                drawPath(vidrios, p.celda.copy(alpha = 0.85f))
                for (x in listOf(104f, 312f)) {
                    drawCircle(p.fondo, radius = 22f, center = androidx.compose.ui.geometry.Offset(x, 104f))
                    drawCircle(p.pista, radius = 22f, center = androidx.compose.ui.geometry.Offset(x, 104f), style = Stroke(6f))
                    drawCircle(p.tenue, radius = 8f, center = androidx.compose.ui.geometry.Offset(x, 104f))
                }
            }
        }
    }
}

/** Envía una tecla multimedia al reproductor que esté sonando (Spotify, YouTube Music, radio...). No pide permisos. */
private fun teclaMultimedia(context: Context, codigo: Int) {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val t = SystemClock.uptimeMillis()
    audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, codigo, 0))
    audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, codigo, 0))
}

@Composable
private fun TarjetaMusica(modifier: Modifier) {
    val p = LocalPaleta.current
    val context = LocalContext.current
    Tarjeta(modifier) {
        TituloConIcono(Icono.MUSICA, "Música")
        Text("Controla la app de música que esté sonando.", color = p.tenue, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BotonMedia(Icono.ANTERIOR, "Anterior") { teclaMultimedia(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            BotonMedia(Icono.PLAY, "Reproducir o pausar", principal = true) { teclaMultimedia(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            BotonMedia(Icono.SIGUIENTE, "Siguiente") { teclaMultimedia(context, KeyEvent.KEYCODE_MEDIA_NEXT) }
        }
    }
}

@Composable
private fun BotonMedia(icono: Icono, descripcion: String, principal: Boolean = false, onClick: () -> Unit) {
    val p = LocalPaleta.current
    Box(
        Modifier
            .size(if (principal) 64.dp else 52.dp)
            .clip(CircleShape)
            .background(if (principal) p.normal else p.pista)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = descripcion },
        contentAlignment = Alignment.Center,
    ) {
        IconoTrazo(icono, p.texto, if (principal) 28.dp else 22.dp)
    }
}

@Composable
private fun TarjetaAdaptador(e: EstadoTablero, acciones: AccionesTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    Tarjeta(modifier) {
        TituloConIcono(Icono.ADAPTADOR, "Adaptador OBD")
        Text(if (e.esDemo) "Simulador del Accent" else e.fuente, color = p.texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Boton(if (e.esDemo) "Conectar vLinker" else "Ver adaptador", Modifier.fillMaxWidth()) { acciones.irA(Seccion.AJUSTES) }
    }
}
