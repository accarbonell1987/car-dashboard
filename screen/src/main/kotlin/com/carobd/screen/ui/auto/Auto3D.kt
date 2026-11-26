package com.carobd.screen.ui.auto

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.carobd.core.auto.AmbienteAuto
import com.carobd.core.auto.EstadoVisualAuto
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

/**
 * Whether the car is drawn in 3D. The choice is saved; [lento] is not: it is set when the 3D view reports that
 * this screen's GPU cannot keep up (or has no WebGL), and sends the app back to the photo until it restarts.
 */
object Preferencia3D {
    val activo = MutableStateFlow(true)
    /** The body colour of the 3D car, one of [COLORES_AUTO]. */
    val color = MutableStateFlow(COLORES_AUTO.first())
    /** Whether the background follows the time of day and the weather. */
    val ambientar = MutableStateFlow(true)
    val lento = MutableStateFlow(false)
    /** Why the 3D view gave up, or what it runs on when it works; shown in Ajustes to diagnose a screen. */
    val informe = MutableStateFlow<String?>(null)
    private var cargado = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("auto3d", Context.MODE_PRIVATE)

    fun cargar(c: Context) {
        if (cargado) return
        cargado = true
        activo.value = prefs(c).getBoolean("activo", true)
        ambientar.value = prefs(c).getBoolean("ambientar", true)
        val guardado = prefs(c).getString("color", null)
        color.value = COLORES_AUTO.firstOrNull { it.id == guardado } ?: COLORES_AUTO.first()
    }

    fun ambientar(c: Context, v: Boolean) {
        ambientar.value = v
        prefs(c).edit().putBoolean("ambientar", v).apply()
    }

    fun pintar(c: Context, v: ColorAuto) {
        color.value = v
        prefs(c).edit().putString("color", v.id).apply()
    }

    fun cambiar(c: Context, v: Boolean) {
        activo.value = v
        if (v) {
            lento.value = false
            informe.value = null
            Motor3D.reiniciar()
        }
        if (!v) Motor3D.reiniciar()
        prefs(c).edit().putBoolean("activo", v).apply()
    }
}

/**
 * A paint for the 3D car. [metal] is how metallic it looks: solid colours want little, dark metallics a lot.
 */
data class ColorAuto(val id: String, val nombre: String, val rgb: Int, val metal: Float)

/** The paints offered in Ajustes, close to the Accent's catalogue. The first one is the default. */
val COLORES_AUTO = listOf(
    ColorAuto("rojo", "Rojo", 0x92060F, 0.62f),
    ColorAuto("blanco", "Blanco", 0xE9EAEC, 0.12f),
    ColorAuto("plata", "Plata", 0x8D9298, 0.8f),
    ColorAuto("gris", "Gris", 0x2E3238, 0.75f),
    ColorAuto("negro", "Negro", 0x050506, 0.6f),
    ColorAuto("azul", "Azul", 0x0A1F52, 0.65f),
    ColorAuto("celeste", "Celeste", 0x4C96D8, 0.45f),
    ColorAuto("beige", "Beige", 0x8F8165, 0.7f),
    ColorAuto("marron", "Marrón", 0x2E1A12, 0.65f),
    ColorAuto("verde", "Verde", 0x0F3024, 0.65f),
)

/** The page's assets are served under this made-up host, so the page has a normal https origin without any network. */
private const val HOST = "auto3d.carobd.local"

private fun tipo(ruta: String) = when (ruta.substringAfterLast('.')) {
    "html" -> "text/html"
    "js" -> "text/javascript"
    "glb" -> "model/gltf-binary"
    else -> "application/octet-stream"
}

/** What the page calls back. WebView runs these on its own thread, so each one hops to the main thread. */
private class Puente(val alListo: (String) -> Unit, val alLento: (String) -> Unit) {
    private val principal = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun listo(que: String) { principal.post { alListo(que) } }

    @JavascriptInterface
    fun lento(motivo: String) { principal.post { alLento(motivo) } }
}

/** The state as the call the page understands (see window.auto3d.set in assets/auto3d/index.html). */
internal fun jsEstado(e: EstadoVisualAuto, embrague: Boolean, ambiente: AmbienteAuto? = null): String {
    val l = e.luces
    return String.format(
        Locale.US,
        "window.auto3d&&auto3d.set({v:%.1f,lluvia:%.2f,freno:%b,embrague:%b,pos:%b,baja:%b,alta:%b,gi:%b,gd:%b,hora:%.2f,nubes:%.2f,niebla:%.2f,nieve:%.2f})",
        e.velocidadKmh, e.lluvia, l.stop, embrague, l.posicionEncendida, l.baja, l.alta, l.parpadeaIzq, l.parpadeaDer,
        ambiente?.hora ?: -1f, ambiente?.nubes ?: 0f, ambiente?.niebla ?: 0f, ambiente?.nieve ?: 0f,
    )
}

/**
 * The one WebView that runs the scene, kept for the whole life of the app. The model is loaded once, at the first
 * screen that shows the car; moving between Inicio and Vehículo only re-parents the view, so the car is there at once.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only (through a MutableContextWrapper)
internal object Motor3D {
    /** The page's script has run: calls to window.auto3d land. */
    val cargada = MutableStateFlow(false)
    /** The model is in and has drawn its first frame. */
    val listo = MutableStateFlow(false)
    private var web: WebView? = null
    private val principal = Handler(Looper.getMainLooper())

    private fun fallar(motivo: String) {
        Preferencia3D.informe.value = "No se pudo mostrar: $motivo"
        Preferencia3D.lento.value = true
        reiniciar() // the caller goes back to the photo; no reason to keep a scene running behind it
    }

    /** Throws the view away, so the next [obtener] starts clean (used when the user switches 3D back on). */
    fun reiniciar() {
        principal.removeCallbacksAndMessages(null)
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        cargada.value = false
        listo.value = false
    }

    fun js(codigo: String) { web?.evaluateJavascript(codigo, null) }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtener(ctx: Context, fondo: Int): WebView? {
        web?.let { return it }
        // Some head units ship without a working system WebView: building one throws.
        val w = try { WebView(MutableContextWrapper(ctx.applicationContext)) } catch (t: Throwable) { null }
        if (w == null) {
            principal.post { fallar("esta pantalla no tiene WebView del sistema") }
            return null
        }
        // Without this the WebView wraps its content and the page gets a viewport 0 px tall: nothing is drawn.
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.setBackgroundColor(fondo)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = WebView.OVER_SCROLL_NEVER
        val navegador = Regex("Chrome/[0-9.]+").find(w.settings.userAgentString.orEmpty())?.value ?: "WebView desconocido"
        w.addJavascriptInterface(
            Puente(
                alListo = { que -> listo.value = true; Preferencia3D.informe.value = "Funcionando: $que · $navegador" },
                alLento = { motivo -> fallar("$motivo · $navegador") },
            ),
            "Android",
        )
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val vacio = { WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))) }
                val url = request.url
                val ruta = url.path?.trimStart('/').orEmpty().ifEmpty { "index.html" }
                if (url.host != HOST || ".." in ruta) return vacio()
                return try {
                    WebResourceResponse(tipo(ruta), if (ruta.endsWith(".glb")) null else "utf-8", view.context.assets.open("auto3d/$ruta"))
                } catch (e: IOException) {
                    vacio()
                }
            }

            // The page never leaves its own host.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != HOST

            override fun onPageFinished(view: WebView, url: String?) { cargada.value = true }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fallar("el WebView se cerró solo")
                return true
            }
        }
        w.loadUrl("https://$HOST/index.html")
        // The model can take a while on a slow screen, but a page that never reports back must not leave the card waiting forever.
        principal.postDelayed({ if (!listo.value) fallar("no terminó de cargar en 4 minutos") }, 240_000)
        web = w
        return w
    }
}

/**
 * The 3D Accent: a WebView running the three.js scene bundled in assets/auto3d, fed with the car's state.
 * - Speed turns the wheels and moves the wind and the road; its changes lean the body a little.
 * - The brake lights the stop lamps and swings the camera to the rear; releasing the clutch gives the gear tug.
 * - Rain, lights and turn signals come from [estado] like in the photo version.
 * [ambiente] sets the sky and the light behind the car; null keeps the plain card colour.
 * Dragging turns the camera. Nothing is loaded from the network: any request outside the assets gets an empty answer.
 * The view itself lives in [Motor3D]; [Motor3D.listo] says when the car is on screen, and a failure (no WebView,
 * no WebGL, a GPU too slow) sets [Preferencia3D.lento] so the caller shows the photo instead.
 */
@Composable
fun Auto3D(
    estado: EstadoVisualAuto,
    embrague: Boolean,
    fondo: Color,
    modifier: Modifier = Modifier,
    pintura: ColorAuto = COLORES_AUTO.first(),
    ambiente: AmbienteAuto? = null,
    alTocar: () -> Unit = {},
    alMantener: () -> Unit = {},
) {
    val tocar by rememberUpdatedState(alTocar)
    val mantener by rememberUpdatedState(alMantener)
    val cargada by Motor3D.cargada.collectAsState()
    val densidad = LocalDensity.current.density

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).also { marco ->
                    Motor3D.obtener(ctx, fondo.toArgb())?.let { w ->
                        (w.context as? MutableContextWrapper)?.baseContext = ctx
                        (w.parent as? ViewGroup)?.removeView(w)
                        marco.addView(w)
                    }
                }
            },
            // The WebView is only taken out, never destroyed: the next screen puts it back with the car already loaded.
            onRelease = { marco -> marco.removeAllViews() },
        )
        // Touches stay in Compose: a tap and a long press go to the caller, a drag turns the camera.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onLongPress = { mantener() }, onTap = { tocar() }) }
                .pointerInput(Unit) {
                    detectDragGestures { cambio, d ->
                        cambio.consume()
                        Motor3D.js(String.format(Locale.US, "window.auto3d&&auto3d.girar(%.1f,%.1f)", d.x / densidad, d.y / densidad))
                    }
                },
        )
    }

    // Everything is sent as soon as the page's script is up, before the model arrives, so the car's first frame
    // already has its colour, its sky and its state.
    val js = remember(estado, embrague, ambiente) { jsEstado(estado, embrague, ambiente) }
    val colorFondo = remember(fondo) { String.format("#%06x", fondo.toArgb() and 0xFFFFFF) }
    LaunchedEffect(cargada, colorFondo) { if (cargada) Motor3D.js("window.auto3d&&auto3d.fondo('$colorFondo')") }
    LaunchedEffect(cargada, pintura) {
        if (cargada) Motor3D.js(String.format(Locale.US, "window.auto3d&&auto3d.color('#%06x',%.2f)", pintura.rgb, pintura.metal))
    }
    LaunchedEffect(cargada, js) { if (cargada) Motor3D.js(js) }
}
