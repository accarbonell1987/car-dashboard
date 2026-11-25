package com.carobd.screen.ui.auto

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
        if (v) lento.value = false
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
 * The 3D Accent: a WebView running the three.js scene bundled in assets/auto3d, fed with the car's state.
 * - Speed turns the wheels and moves the wind and the road; its changes lean the body a little.
 * - The brake lights the stop lamps and swings the camera to the rear; releasing the clutch gives the gear tug.
 * - Rain, lights and turn signals come from [estado] like in the photo version.
 * [ambiente] sets the sky and the light behind the car; null keeps the plain card colour.
 * Dragging turns the camera. Nothing is loaded from the network: any request outside the assets gets an empty answer.
 *
 * @param alListo called once the scene is drawing, so the caller can take the photo away.
 * @param alFallar called when there is no WebView, no WebGL or the GPU is too slow: the caller shows the photo instead.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
    alListo: () -> Unit = {},
    alFallar: (String) -> Unit = {},
) {
    val listoActual by rememberUpdatedState(alListo)
    val fallarActual by rememberUpdatedState(alFallar)
    val tocar by rememberUpdatedState(alTocar)
    val mantener by rememberUpdatedState(alMantener)
    var listo by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val densidad = LocalDensity.current.density

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // Some head units ship without a working system WebView: building one throws.
                val w = try { WebView(ctx) } catch (t: Throwable) { null }
                if (w == null) {
                    Handler(Looper.getMainLooper()).post { fallarActual("esta pantalla no tiene WebView del sistema") }
                    return@AndroidView android.view.View(ctx)
                }
                // Without this the WebView wraps its content and the page gets a viewport 0 px tall: nothing is drawn.
                w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                w.settings.javaScriptEnabled = true
                w.settings.allowFileAccess = false
                w.settings.allowContentAccess = false
                w.setBackgroundColor(fondo.toArgb())
                w.isVerticalScrollBarEnabled = false
                w.isHorizontalScrollBarEnabled = false
                w.overScrollMode = WebView.OVER_SCROLL_NEVER
                val navegador = Regex("Chrome/[0-9.]+").find(w.settings.userAgentString.orEmpty())?.value ?: "WebView desconocido"
                w.addJavascriptInterface(
                    Puente(
                        alListo = { que -> listo = true; Preferencia3D.informe.value = "Funcionando: $que · $navegador"; listoActual() },
                        alLento = { motivo -> fallarActual("$motivo · $navegador") },
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

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        fallarActual("el WebView se cerró solo")
                        return true
                    }
                }
                w.loadUrl("https://$HOST/index.html")
                web = w
                w
            },
            onRelease = { v ->
                web = null
                (v as? WebView)?.destroy()
            },
        )
        // Touches stay in Compose: a tap and a long press go to the caller, a drag turns the camera.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onLongPress = { mantener() }, onTap = { tocar() }) }
                .pointerInput(Unit) {
                    detectDragGestures { cambio, d ->
                        cambio.consume()
                        web?.evaluateJavascript(String.format(Locale.US, "window.auto3d&&auto3d.girar(%.1f,%.1f)", d.x / densidad, d.y / densidad), null)
                    }
                },
        )
    }

    val js = remember(estado, embrague, ambiente) { jsEstado(estado, embrague, ambiente) }
    val colorFondo = remember(fondo) { String.format("#%06x", fondo.toArgb() and 0xFFFFFF) }
    LaunchedEffect(web, listo, colorFondo) {
        if (listo) web?.evaluateJavascript("window.auto3d&&auto3d.fondo('$colorFondo')", null)
    }
    LaunchedEffect(web, listo, pintura) {
        if (listo) web?.evaluateJavascript(String.format(Locale.US, "window.auto3d&&auto3d.color('#%06x',%.2f)", pintura.rgb, pintura.metal), null)
    }
    // The model can take a while on a slow screen. A page that never reports back (a WebView too old to parse it, a stuck load) must not hide behind the photo forever.
    LaunchedEffect(web) {
        if (web == null) return@LaunchedEffect
        kotlinx.coroutines.delay(240_000)
        if (!listo) fallarActual("no terminó de cargar en 4 minutos")
    }
    // Before the page is up the call is a no-op; once it reports ready, the current state goes in.
    LaunchedEffect(web, listo, js) { web?.evaluateJavascript(js, null) }
}
