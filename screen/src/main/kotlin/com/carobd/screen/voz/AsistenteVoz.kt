package com.carobd.screen.voz

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import com.carobd.core.voz.AccionAsistente
import com.carobd.core.voz.ContextoAuto
import com.carobd.core.voz.EventoRealtime
import com.carobd.core.voz.FuncionesAuto
import com.carobd.core.voz.Pcm
import com.carobd.core.voz.Realtime
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FaseVoz { APAGADO, CONECTANDO, ESCUCHANDO, PENSANDO, HABLANDO }

data class LineaVoz(val delUsuario: Boolean, val texto: String)

data class EstadoVoz(
    val fase: FaseVoz = FaseVoz.APAGADO,
    val lineas: List<LineaVoz> = emptyList(),
    val error: String? = null,
)

data class ConfigVoz(val clave: String = "", val modelo: String = Realtime.MODELO) {
    val lista: Boolean get() = clave.isNotBlank()
}

/** Lo que la interfaz necesita del asistente de voz. */
interface ControlVoz {
    val estado: StateFlow<EstadoVoz>
    val config: StateFlow<ConfigVoz>
    /** Empieza a escuchar, o corta si ya estaba en una conversación. */
    fun alternar()
    fun detener()
    fun guardar(clave: String, modelo: String)
    /** Muestra un aviso en el panel (o lo borra con null). */
    fun avisar(texto: String?)
}

/**
 * Conversación de voz con la Realtime API de OpenAI por WebSocket, con la clave que cada usuario carga en Ajustes
 * (queda en las preferencias privadas de la app; nunca en el APK ni en el repo).
 *
 * Es "medio dúplex": mientras el asistente habla no se manda el micrófono, porque los parlantes del auto se cuelan
 * y el modelo se respondería a sí mismo. La conversación se cierra sola tras un rato sin que nadie hable.
 */
class AsistenteVoz(
    private val app: Context,
    private val alcance: CoroutineScope,
    private val contexto: () -> ContextoAuto,
) : ControlVoz {
    private val prefs = app.getSharedPreferences("asistente_voz", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(
        ConfigVoz(prefs.getString("clave", "") ?: "", prefs.getString("modelo", null)?.takeIf { it.isNotBlank() } ?: Realtime.MODELO),
    )
    override val config: StateFlow<ConfigVoz> = _config.asStateFlow()
    private val _estado = MutableStateFlow(EstadoVoz())
    override val estado: StateFlow<EstadoVoz> = _estado.asStateFlow()

    private val http = HttpClient(OkHttp) { install(WebSockets) }
    private var sesion: Job? = null

    /** True mientras sale audio por los parlantes (o falta que termine): el micrófono no se manda. */
    @Volatile private var hablando = false
    @Volatile private var ultimaActividad = 0L
    @Volatile private var cerrarAlTerminar = false
    @Volatile private var pidioTerminar = false

    override fun guardar(clave: String, modelo: String) {
        val c = ConfigVoz(clave.trim(), modelo.trim().ifEmpty { Realtime.MODELO })
        prefs.edit().putString("clave", c.clave).putString("modelo", c.modelo).apply()
        _config.value = c
    }

    override fun avisar(texto: String?) = _estado.update { it.copy(error = texto) }

    override fun alternar() {
        if (sesion?.isActive == true) detener() else iniciar()
    }

    override fun detener() {
        sesion?.cancel()
        sesion = null
        _estado.update { it.copy(fase = FaseVoz.APAGADO) }
    }

    private fun iniciar() {
        val c = _config.value
        if (!c.lista) {
            _estado.value = EstadoVoz(error = "Falta tu clave de OpenAI: cárgala en Ajustes → Asistente.")
            return
        }
        _estado.value = EstadoVoz(fase = FaseVoz.CONECTANDO)
        hablando = false
        cerrarAlTerminar = false
        pidioTerminar = false
        ultimaActividad = System.currentTimeMillis()
        sesion = alcance.launch(Dispatchers.IO) {
            val foco = Foco(app)
            try {
                foco.pedir()
                http.webSocket(urlString = Realtime.url(c.modelo), request = { header(HttpHeaders.Authorization, "Bearer ${c.clave}") }) {
                    conversar(this)
                }
                _estado.update { it.copy(fase = FaseVoz.APAGADO) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(fase = FaseVoz.APAGADO, error = explicar(e)) }
            } finally {
                foco.soltar()
            }
        }
    }

    private suspend fun conversar(ws: DefaultClientWebSocketSession) = withContext(Dispatchers.IO) {
        ws.send(Frame.Text(Realtime.configurarSesion(FuncionesAuto.INSTRUCCIONES, FuncionesAuto.lista)))
        val parlante = Parlante()
        val microfono = Microfono.abrir()
        if (microfono == null) {
            _estado.update { it.copy(error = "No se pudo abrir el micrófono.") }
            parlante.cerrar()
            ws.close()
            return@withContext
        }
        val salidaAudio = Channel<ByteArray>(Channel.UNLIMITED)
        val reproductor = launch {
            for (pcm in salidaAudio) {
                if (pcm.isEmpty()) {
                    // Marca de fin de respuesta: se espera a que suene todo antes de volver a escuchar.
                    parlante.esperarFin()
                    delay(250)
                    hablando = false
                    ultimaActividad = System.currentTimeMillis()
                    if (cerrarAlTerminar) {
                        ws.close()
                    } else {
                        _estado.update { it.copy(fase = FaseVoz.ESCUCHANDO) }
                    }
                } else {
                    parlante.escribir(pcm)
                }
            }
        }
        val grabador = launch {
            while (isActive) {
                val bloque = microfono.leer() ?: break
                if (!hablando && runCatching { ws.send(Frame.Text(Realtime.audio(bloque))) }.isFailure) break
            }
        }
        // Sin nadie hablando por 20 s, la conversación se cierra (cada minuto abierta cuesta).
        val vigia = launch {
            while (isActive) {
                delay(1_000)
                if (!hablando && _estado.value.fase == FaseVoz.ESCUCHANDO && System.currentTimeMillis() - ultimaActividad > 20_000) ws.close()
            }
        }
        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                for (ev in Realtime.leer(frame.readText())) manejar(ev, ws, salidaAudio)
            }
        } finally {
            withContext(NonCancellable) {
                vigia.cancel()
                reproductor.cancel()
                salidaAudio.close()
                grabador.cancel()
                // Detener hace que la lectura bloqueada vuelva; recién después se libera.
                microfono.detener()
                grabador.join()
                reproductor.join()
                microfono.liberar()
                parlante.cerrar()
            }
        }
    }

    private suspend fun manejar(ev: EventoRealtime, ws: DefaultClientWebSocketSession, salida: Channel<ByteArray>) {
        when (ev) {
            EventoRealtime.SesionLista -> if (_estado.value.fase == FaseVoz.CONECTANDO) {
                _estado.update { it.copy(fase = FaseVoz.ESCUCHANDO, error = null) }
            }
            EventoRealtime.HablaElUsuario -> {
                ultimaActividad = System.currentTimeMillis()
                _estado.update { it.copy(fase = FaseVoz.ESCUCHANDO) }
            }
            EventoRealtime.CallaElUsuario -> {
                ultimaActividad = System.currentTimeMillis()
                _estado.update { it.copy(fase = FaseVoz.PENSANDO) }
            }
            is EventoRealtime.Audio -> {
                hablando = true
                _estado.update { it.copy(fase = FaseVoz.HABLANDO) }
                salida.send(ev.pcm)
            }
            is EventoRealtime.DijoElUsuario -> agregar(LineaVoz(true, ev.texto))
            is EventoRealtime.DiceElAsistente -> agregar(LineaVoz(false, ev.texto))
            is EventoRealtime.LlamaFuncion -> {
                val r = FuncionesAuto.ejecutar(ev.nombre, ev.argumentos, contexto())
                ws.send(Frame.Text(Realtime.resultadoFuncion(ev.id, r.salida)))
                when (val a = r.accion) {
                    is AccionAsistente.Navegar -> {
                        withContext(Dispatchers.Main) { navegar(a) }
                        // La app queda detrás del mapa y Android no deja grabar desde atrás: se cierra al terminar de hablar.
                        cerrarAlTerminar = true
                    }
                    AccionAsistente.Terminar -> {
                        cerrarAlTerminar = true
                        pidioTerminar = true
                    }
                    null -> Unit
                }
            }
            is EventoRealtime.RespuestaTerminada -> {
                // Si pidió funciones (menos la de cerrar), se le pide que responda con los resultados.
                if (ev.conFunciones && !pidioTerminar) {
                    _estado.update { it.copy(fase = FaseVoz.PENSANDO) }
                    ws.send(Frame.Text(Realtime.responder()))
                } else if (hablando) {
                    salida.send(ByteArray(0))
                } else if (cerrarAlTerminar) {
                    ws.close()
                } else {
                    _estado.update { it.copy(fase = FaseVoz.ESCUCHANDO) }
                }
            }
            is EventoRealtime.Error -> _estado.update { it.copy(error = ev.mensaje) }
        }
    }

    private fun agregar(l: LineaVoz) = _estado.update { it.copy(lineas = (it.lineas + l).takeLast(6)) }

    private fun navegar(a: AccionAsistente.Navegar) {
        val q = Uri.encode(a.destino)
        val intentos = buildList {
            if (a.app == "waze") add(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$q&navigate=yes")).setPackage("com.waze"))
            add(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps"))
            add(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        }
        for (i in intentos) {
            val ok = runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
        _estado.update { it.copy(error = "No hay app de mapas para abrir el destino.") }
    }

    private fun explicar(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "401" in m || "Unauthorized" in m -> "OpenAI rechazó la clave. Revísala en Ajustes → Asistente."
            e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
                "Sin Internet: el asistente necesita conexión."
            else -> "No se pudo conectar con el asistente (${m.take(80)})."
        }
    }
}

/** Micrófono a 24 kHz; si la pantalla no lo acepta, graba a 48 o 16 kHz y convierte. Bloques de 100 ms. */
private class Microfono(private val grabacion: AudioRecord, private val hz: Int) {
    private val muestras = ShortArray(hz / 10)
    private val efectos: List<AudioEffect> = buildList {
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(grabacion.audioSessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(grabacion.audioSessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
    }

    fun leer(): ByteArray? {
        val n = grabacion.read(muestras, 0, muestras.size)
        if (n < 0) return null
        val a24 = Pcm.convertir(muestras, n, hz, Realtime.FRECUENCIA)
        return Pcm.aBytes(a24)
    }

    fun detener() {
        runCatching { grabacion.stop() }
    }

    fun liberar() {
        efectos.forEach { runCatching { it.release() } }
        grabacion.release()
    }

    companion object {
        @SuppressLint("MissingPermission") // La interfaz pide RECORD_AUDIO antes de empezar.
        fun abrir(): Microfono? {
            for (hz in listOf(Realtime.FRECUENCIA, 48_000, 16_000)) {
                val minimo = AudioRecord.getMinBufferSize(hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimo <= 0) continue
                val r = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, hz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, hz / 5 * 2))
                }.getOrNull() ?: continue
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    r.release(); continue
                }
                r.startRecording()
                return Microfono(r, hz)
            }
            return null
        }
    }
}

/** Salida por los parlantes del auto, como contenido multimedia (es lo que estas pantallas mandan al amplificador). */
private class Parlante {
    private val pista = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(Realtime.FRECUENCIA).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
        )
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(Realtime.FRECUENCIA, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), Realtime.FRECUENCIA))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
        .also { it.play() }
    private var escritos = 0L

    fun escribir(pcm: ByteArray) {
        pista.write(pcm, 0, pcm.size)
        escritos += pcm.size / 2
    }

    /** Espera a que suene lo escrito (como mucho 30 s, por si la pista se traba). */
    suspend fun esperarFin() {
        val limite = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < limite && (pista.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < escritos) delay(50)
    }

    fun cerrar() {
        runCatching { pista.stop() }
        pista.release()
    }
}

/** Pide el foco de audio para que la música baje o se pause mientras se conversa. */
private class Foco(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pedido: AudioFocusRequest? = null

    fun pedir() {
        if (Build.VERSION.SDK_INT >= 26) {
            val p = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            pedido = p
            runCatching { am.requestAudioFocus(p) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) }
        }
    }

    fun soltar() {
        if (Build.VERSION.SDK_INT >= 26) {
            pedido?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
    }
}
