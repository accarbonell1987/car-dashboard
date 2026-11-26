package com.carobd.core.voz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Mensajes de la Realtime API de OpenAI por WebSocket (interfaz GA, audio PCM16 mono a 24 kHz).
 * Solo arma y lee JSON: la conexión y el audio los pone cada plataforma.
 */
object Realtime {
    /** Modelo por defecto. Se puede cambiar en Ajustes si OpenAI lo retira. */
    const val MODELO = "gpt-realtime-2.1-mini"
    const val TRANSCRIPCION = "gpt-4o-mini-transcribe"
    const val VOZ = "marin"
    const val FRECUENCIA = 24_000

    fun url(modelo: String) = "wss://api.openai.com/v1/realtime?model=$modelo"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Configura la sesión: voz, instrucciones, funciones y detección de turnos en el servidor.
     * `interrupt_response` va apagado porque la app no escucha mientras el asistente habla (los parlantes del
     * auto se cuelan en el micrófono y el asistente se cortaría a sí mismo).
     */
    fun configurarSesion(
        instrucciones: String,
        funciones: List<FuncionAsistente>,
        voz: String = VOZ,
        transcripcion: String? = TRANSCRIPCION,
    ): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("instructions", instrucciones)
            putJsonArray("output_modalities") { add("audio") }
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", FRECUENCIA) }
                    if (transcripcion != null) putJsonObject("transcription") { put("model", transcripcion) }
                    putJsonObject("turn_detection") {
                        put("type", "server_vad")
                        put("silence_duration_ms", 700)
                        put("create_response", true)
                        put("interrupt_response", false)
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", FRECUENCIA) }
                    put("voice", voz)
                }
            }
            putJsonArray("tools") {
                funciones.forEach { f ->
                    addJsonObject {
                        put("type", "function")
                        put("name", f.nombre)
                        put("description", f.descripcion)
                        put("parameters", f.parametros)
                    }
                }
            }
            put("tool_choice", "auto")
        }
    }.toString()

    @OptIn(ExperimentalEncodingApi::class)
    fun audio(pcm: ByteArray, desde: Int = 0, hasta: Int = pcm.size): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.encode(pcm, desde, hasta))
    }.toString()

    /** Devuelve el resultado de una función al modelo. Después hay que pedir [responder]. */
    fun resultadoFuncion(llamadaId: String, salida: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", llamadaId)
            put("output", salida)
        }
    }.toString()

    fun responder(): String = """{"type":"response.create"}"""

    /** Un saludo inicial para que el asistente avise que está escuchando. */
    fun saludar(texto: String): String = buildJsonObject {
        put("type", "response.create")
        putJsonObject("response") { put("instructions", texto) }
    }.toString()

    /** Traduce un mensaje del servidor. Los que no le importan a la app devuelven null. */
    @OptIn(ExperimentalEncodingApi::class)
    fun leer(texto: String): List<EventoRealtime> {
        val o = runCatching { json.parseToJsonElement(texto).jsonObject }.getOrNull() ?: return emptyList()
        return when (o.str("type")) {
            "session.created", "session.updated" -> listOf(EventoRealtime.SesionLista)
            "input_audio_buffer.speech_started" -> listOf(EventoRealtime.HablaElUsuario)
            "input_audio_buffer.speech_stopped" -> listOf(EventoRealtime.CallaElUsuario)
            // El nombre GA y el de la beta, por si el modelo responde con el viejo.
            "response.output_audio.delta", "response.audio.delta" ->
                o.str("delta")?.let { listOf(EventoRealtime.Audio(Base64.decode(it))) }.orEmpty()
            "response.output_audio_transcript.done", "response.audio_transcript.done" ->
                o.str("transcript")?.takeIf { it.isNotBlank() }?.let { listOf(EventoRealtime.DiceElAsistente(it)) }.orEmpty()
            "conversation.item.input_audio_transcription.completed" ->
                o.str("transcript")?.takeIf { it.isNotBlank() }?.let { listOf(EventoRealtime.DijoElUsuario(it.trim())) }.orEmpty()
            // Las llamadas a funciones se toman de response.done, que trae el ítem completo.
            "response.done" -> {
                val r = o["response"]?.jsonObject
                val llamadas = (r?.get("output") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { it.str("type") == "function_call" }
                    .mapNotNull { f ->
                        val id = f.str("call_id") ?: return@mapNotNull null
                        val nombre = f.str("name") ?: return@mapNotNull null
                        EventoRealtime.LlamaFuncion(id, nombre, f.str("arguments") ?: "{}")
                    }
                llamadas + EventoRealtime.RespuestaTerminada(r?.str("status") ?: "completed", llamadas.isNotEmpty())
            }
            "error" -> listOf(EventoRealtime.Error(o["error"]?.jsonObject?.str("message") ?: "error de OpenAI"))
            else -> emptyList()
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}

sealed interface EventoRealtime {
    data object SesionLista : EventoRealtime
    data object HablaElUsuario : EventoRealtime
    data object CallaElUsuario : EventoRealtime
    class Audio(val pcm: ByteArray) : EventoRealtime
    data class DiceElAsistente(val texto: String) : EventoRealtime
    data class DijoElUsuario(val texto: String) : EventoRealtime
    data class LlamaFuncion(val id: String, val nombre: String, val argumentos: String) : EventoRealtime
    /** `conFunciones`: el modelo pidió funciones y espera sus resultados antes de seguir hablando. */
    data class RespuestaTerminada(val estado: String, val conFunciones: Boolean) : EventoRealtime
    data class Error(val mensaje: String) : EventoRealtime
}

/** Una función que el modelo puede pedir. `parametros` es un JSON Schema. */
class FuncionAsistente(val nombre: String, val descripcion: String, val parametros: JsonObject)

internal fun sinParametros() = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {}
}

