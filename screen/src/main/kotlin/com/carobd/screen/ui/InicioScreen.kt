package com.carobd.screen.ui

import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.min
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.BoxWithConstraints
import com.carobd.screen.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.carobd.core.obd.FaseConexion
import com.carobd.core.obd.Marchas
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
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
import com.carobd.core.model.Estado
import com.carobd.core.model.EstadoMantenimiento
import com.carobd.core.model.NivelAviso
import com.carobd.core.model.texto
import com.carobd.screen.EstadoTablero
import com.carobd.screen.Seccion
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Icono
import com.carobd.screen.ui.comun.IconoTrazo
import com.carobd.screen.ui.comun.Tarjeta
import com.carobd.screen.ui.comun.TarjetaIcono
import com.carobd.screen.ui.comun.dec
import com.carobd.screen.ui.auto.ANCLAS_ACCENT
import com.carobd.screen.ui.auto.AutoTablero
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/** Inicio, al estilo de un launcher: lo que se mira de reojo manejando. */
@Composable
fun InicioScreen(e: EstadoTablero, acciones: AccionesTablero) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaClima(Modifier.fillMaxWidth().weight(1f))
            TarjetaNavegacion(Modifier.fillMaxWidth().weight(1f))
            TarjetaViaje(e, Modifier.fillMaxWidth().weight(0.8f))
        }
        TarjetaAuto(e, acciones, Modifier.weight(1.5f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaMusica(Modifier.fillMaxWidth().weight(1f))
            TarjetaAdaptador(e, acciones, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
internal fun TituloConIcono(icono: Icono, texto: String) {
    val p = LocalPaleta.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconoTrazo(icono, p.tenue, 22.dp)
        Text(texto.uppercase(), color = p.tenue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
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
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconoApp("com.google.android.apps.maps", "Maps")
            IconoApp("com.waze", "Waze")
        }
    }
}

/** The app's own launcher icon from the system (no logo is redrawn); greyed out when it isn't installed. */
@Composable
private fun IconoApp(paquete: String, nombre: String) {
    val p = LocalPaleta.current
    val context = LocalContext.current
    val icono = remember(paquete) {
        runCatching { context.packageManager.getApplicationIcon(paquete).toBitmap(144, 144).asImageBitmap() }.getOrNull()
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { abrirApp(context, paquete, nombre) }
            .padding(8.dp)
            .semantics { contentDescription = "Abrir $nombre" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icono != null) {
            Image(icono, contentDescription = null, modifier = Modifier.size(60.dp))
        } else {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(p.pista), contentAlignment = Alignment.Center) {
                IconoTrazo(Icono.NAVEGACION, p.tenue, 36.dp)
            }
        }
        Text(nombre, color = if (icono != null) p.texto else p.tenue, fontSize = 16.sp)
    }
}

@Composable
private fun TarjetaViaje(e: EstadoTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    Tarjeta(modifier, titulo = "Viaje actual") {
        if (e.esDemo) {
            Text("El modo demo no graba viajes. Conecta el adaptador para empezar.", color = p.tenue, fontSize = 16.sp)
        } else if (e.viajeEnCursoKm == null) {
            Text("Sin viaje en curso. Empieza solo al arrancar el motor.", color = p.tenue, fontSize = 16.sp)
            e.ultimoViajeKm?.let { Text("Último: ${dec(it)} km", color = p.texto, fontSize = 17.sp) }
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
        Text(nombre, color = p.tenue, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(valor, color = p.texto, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        if (unidad.isNotEmpty()) Text(" $unidad", color = p.tenue, fontSize = 15.sp)
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
                Text(nombreVehiculo(e), color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("${v?.motor ?: "1.6 CRDi"} · manual", color = p.tenue, fontSize = 16.sp)
            }
            val (texto, color) = if (alerta == null) "Todo en orden" to p.ok else alerta.regla.mensaje to colorNivel(alerta.regla.nivel)
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(p.pista).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(texto, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        AutoTablero(e, Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val vs = e.valores
            Stat("Velocidad", vs["velocidad"]?.let { dec(it, 0) } ?: "--", "km/h", p.texto, Modifier.weight(1f))
            val marcha = vs["marcha"]?.toInt()
            Stat("Marcha", Marchas.texto(marcha), "", if (marcha == 0) p.ok else p.texto, Modifier.weight(1f))
            Stat("Refrigerante", vs["temp_refrigerante"]?.let { dec(it, 0) } ?: "--", "°C", colorNivel(nivelDe("temp_refrigerante")), Modifier.weight(1f))
            Stat("Batería", vs["voltaje"]?.let { dec(it, 1) } ?: "--", "V", colorNivel(nivelDe("voltaje")), Modifier.weight(1f))
        }
        val proximo = e.garaje?.let { g ->
            EstadoMantenimiento.calcular(
                g.items, g.servicios, g.vehiculo, maxOf(g.odometroKm, e.odometroKm.toInt()), Clock.System.now(), TimeZone.currentSystemDefault(),
            ).firstOrNull()
        }
        val alerta = proximo?.estado == Estado.VENCIDO || proximo?.probablementeVencido == true
        // Sin datos y sin asistente hecho: el atajo lleva al asistente, no a Salud.
        val ofrecerAsistente = e.asistentePendiente && (proximo == null || proximo.estado == Estado.DESCONOCIDO)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(p.pista)
                .clickable(role = Role.Button) { if (ofrecerAsistente) acciones.abrirAsistente() else acciones.irA(Seccion.DIAGNOSTICO) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconoTrazo(Icono.LLAVE, if (alerta) p.atencion else p.tenue, 20.dp)
            Spacer(Modifier.size(10.dp))
            Text(
                when {
                    ofrecerAsistente -> "Configura tu plan de mantenimiento: unas preguntas con el auto detenido"
                    proximo == null -> "Plan de mantenimiento"
                    else -> "Próximo servicio: ${proximo.item.nombre} · ${proximo.texto()}"
                },
                color = if (alerta) p.atencion else p.texto,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconoTrazo(Icono.FLECHA, p.tenue, 22.dp)
        }
    }
}

@Composable
private fun Stat(nombre: String, valor: String, unidad: String, color: Color, modifier: Modifier) {
    val p = LocalPaleta.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(p.pista).padding(10.dp)) {
        Text(nombre, color = p.tenue, fontSize = 14.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valor, color = color, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (unidad.isNotEmpty()) Text(" $unidad", color = p.tenue, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/**
 * Photo of the user's own Accent, cut out from its background (plate blanked: the repository is public), standing
 * on a soft platform shadow so it does not float on the card.
 */
@Composable
fun FotoAuto(modifier: Modifier) {
    val p = LocalPaleta.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Size of the photo once fitted, to put the shadow right under its wheels.
        val ancho = min(maxWidth.value, maxHeight.value * ANCLAS_ACCENT.proporcion).dp * ANCLAS_ACCENT.escala
        val alto = ancho / ANCLAS_ACCENT.proporcion
        // The photo is a three-quarter view: the footprint is an ellipse under both wheels, tilted with the car.
        Canvas(Modifier.size(ancho, alto)) {
            val centro = Offset(size.width * 0.53f, size.height * 0.85f)
            val radio = size.width * 0.47f
            rotate(-10f, centro) {
                scale(1f, 0.17f, pivot = centro) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color.Black.copy(alpha = 0.85f),
                            0.6f to Color.Black.copy(alpha = 0.5f),
                            1f to Color.Transparent,
                            center = centro,
                            radius = radio,
                        ),
                        radio,
                        centro,
                    )
                    // Faint rim: the platform edge, in the dashboard's accent colour.
                    drawCircle(p.normal.copy(alpha = 0.25f), radio * 0.97f, centro, style = Stroke(width = 14f))
                }
            }
        }
        Image(
            painterResource(R.drawable.accent),
            contentDescription = "Hyundai Accent 2016",
            modifier = Modifier.size(ancho, alto),
            contentScale = ContentScale.Fit,
        )
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
        Text("Controla la app de música que esté sonando.", color = p.tenue, fontSize = 16.sp)
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

/** One status button for the adapter: green connected, amber connecting, grey disconnected (tap to connect). */
@Composable
private fun TarjetaAdaptador(e: EstadoTablero, acciones: AccionesTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    val fase = e.conexion?.fase
    val (color, titulo, detalle) = when {
        e.esDemo -> Triple(p.tenue, "Toca para conectar", "Modo demo: simulador del Accent")
        fase == FaseConexion.CONECTADO -> Triple(p.ok, "Conectado", e.fuente)
        fase == FaseConexion.CONECTANDO || fase == FaseConexion.RECONECTANDO -> Triple(p.atencion, "Conectando…", e.fuente)
        else -> Triple(p.tenue, "Toca para conectar", "Desconectado")
    }
    Tarjeta(modifier) {
        TituloConIcono(Icono.ADAPTADOR, "Adaptador OBD")
        TarjetaIcono(Icono.ADAPTADOR, titulo, detalle, Modifier.fillMaxWidth().weight(1f), color = color) { acciones.irA(Seccion.AJUSTES) }
    }
}
