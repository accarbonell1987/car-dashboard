package com.carobd.screen.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.carobd.core.clima.Cielo
import com.carobd.core.clima.Clima
import com.carobd.core.clima.OpenMeteo
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Icono
import com.carobd.screen.ui.comun.Tarjeta
import com.carobd.screen.ui.comun.dec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import kotlin.coroutines.resume

private const val PERMISO = Manifest.permission.ACCESS_COARSE_LOCATION

private fun tienePermiso(context: Context) =
    ContextCompat.checkSelfPermission(context, PERMISO) == PackageManager.PERMISSION_GRANTED

/** Newest last known position; if there is none, asks for one fix (head units often boot without one). */
@SuppressLint("MissingPermission")
private suspend fun ubicacion(context: Context): Location? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val proveedores = lm.getProviders(true)
    proveedores.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { return it }
    val proveedor = proveedores.firstOrNull() ?: return null
    return withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { c ->
            LocationManagerCompat.getCurrentLocation(lm, proveedor, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(context)) { lugar: Location? -> c.resume(lugar) }
        }
    }
}

/** Last weather fetched, also shown in the status bar as the outside temperature. */
internal object ClimaActual {
    val clima = MutableStateFlow<Clima?>(null)
}

/** Weather at the car's position, refreshed every 15 minutes. */
@Composable
internal fun TarjetaClima(modifier: Modifier) {
    val p = LocalPaleta.current
    val context = LocalContext.current
    var permiso by remember { mutableStateOf(tienePermiso(context)) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permiso = it }
    val clima by produceState<Clima?>(null, permiso) {
        if (!permiso) return@produceState
        while (true) {
            ubicacion(context)?.let { lugar ->
                runCatching {
                    withContext(Dispatchers.IO) { OpenMeteo.leer(URL(OpenMeteo.url(lugar.latitude, lugar.longitude)).readText()) }
                }.onSuccess {
                    value = it
                    ClimaActual.clima.value = it
                }
            }
            delay(if (value == null) 30_000 else 15 * 60_000)
        }
    }
    Tarjeta(modifier) {
        TituloConIcono(Icono.NUBE, "Clima")
        val c = clima
        when {
            !permiso -> {
                Text("Permite la ubicación para ver el clima donde está el auto.", color = p.tenue, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Boton("Permitir ubicación", Modifier.fillMaxWidth()) { pedir.launch(PERMISO) }
            }
            c == null -> Text("Buscando el clima…", color = p.tenue, fontSize = 16.sp)
            else -> Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconoCielo(c.cielo, if (c.cielo == Cielo.DESPEJADO) p.atencion else p.texto, 64.dp, c.descripcion)
                Column {
                    Text("${dec(c.temperatura, 0)}°", color = p.texto, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(c.descripcion, color = p.texto, fontSize = 17.sp)
                    Text("mín ${dec(c.minima, 0)}° · máx ${dec(c.maxima, 0)}°", color = p.tenue, fontSize = 15.sp)
                }
            }
        }
    }
}

private val trazo = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val nube = PathParser().parsePathString(Icono.NUBE.trazo).toPath()

/** Animated sky icon on a 24x24 grid: the sun turns, clouds drift, rain falls. */
@Composable
private fun IconoCielo(cielo: Cielo, color: Color, tamano: Dp, descripcion: String) {
    val ciclo by rememberInfiniteTransition(label = "cielo").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (cielo == Cielo.DESPEJADO) 12_000 else 1_400, easing = LinearEasing), RepeatMode.Restart),
        label = "ciclo",
    )
    Canvas(Modifier.size(tamano).semantics { contentDescription = descripcion }) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            when (cielo) {
                Cielo.DESPEJADO -> rotate(ciclo * 360f, Offset(12f, 12f)) {
                    drawCircle(color, 4.5f, Offset(12f, 12f), style = trazo)
                    for (i in 0 until 8) rotate(i * 45f, Offset(12f, 12f)) {
                        drawLine(color, Offset(12f, 2.5f), Offset(12f, 5f), 1.6f, StrokeCap.Round)
                    }
                }
                Cielo.NUBLADO -> translate(left = kotlin.math.sin(ciclo * 2 * Math.PI).toFloat() * 0.8f) { drawPath(nube, color, style = trazo) }
                Cielo.LLUVIA -> {
                    translate(top = -3f) { drawPath(nube, color, style = trazo) }
                    // Three drops, staggered, falling and fading out.
                    for ((i, x) in listOf(8f, 12f, 16f).withIndex()) {
                        val f = (ciclo + i / 3f) % 1f
                        val y = 16.5f + f * 5f
                        drawLine(color.copy(alpha = 1f - f), Offset(x, y), Offset(x - 0.8f, y + 2f), 1.6f, StrokeCap.Round)
                    }
                }
            }
        }
    }
}
