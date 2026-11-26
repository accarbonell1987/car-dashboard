package com.carobd.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Token efímero para que la pantalla hable directo con la Realtime API de OpenAI por WebRTC.
 * La clave de OpenAI vive solo en el servidor; la pantalla recibe un secreto que vence en minutos.
 */
@Serializable
data class TokenAsistente(
    val token: String,
    /** Segundos desde epoch en que el token deja de servir para abrir una sesión. */
    val venceEn: Long,
    val modelo: String,
)

/** Quien fabrica los tokens. En producción es OpenAI; en los tests, un doble. */
fun interface ProveedorTokens {
    fun crear(usuario: String): TokenAsistente
}

/**
 * Pide un client secret a `POST /v1/realtime/client_secrets`.
 * La sesión (modelo, voz, instrucciones) queda fijada acá, así la pantalla no puede cambiarla.
 */
class OpenAiRealtime(
    private val clave: String,
    private val modelo: String = "gpt-realtime-mini",
    private val voz: String = "marin",
    private val segundosDeVida: Int = 600,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : ProveedorTokens {

    override fun crear(usuario: String): TokenAsistente {
        val cuerpo = buildJsonObject {
            putJsonObject("expires_after") {
                put("anchor", "created_at")
                put("seconds", segundosDeVida)
            }
            putJsonObject("session") {
                put("type", "realtime")
                put("model", modelo)
                put("instructions", INSTRUCCIONES)
                putJsonObject("audio") { putJsonObject("output") { put("voice", voz) } }
            }
        }
        val pedido = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/realtime/client_secrets"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $clave")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
            .build()
        val respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofString())
        // No se reenvía el cuerpo del error: puede traer detalles de la cuenta.
        check(respuesta.statusCode() in 200..299) { "OpenAI respondió ${respuesta.statusCode()}" }
        val json = Json.parseToJsonElement(respuesta.body()).jsonObject
        return TokenAsistente(
            token = json.getValue("value").jsonPrimitive.content,
            venceEn = json.getValue("expires_at").jsonPrimitive.long,
            modelo = modelo,
        )
    }

    companion object {
        /** Null si el servidor no tiene OPENAI_API_KEY: el asistente queda apagado. */
        fun desdeEntorno(): OpenAiRealtime? {
            val clave = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() } ?: return null
            return OpenAiRealtime(
                clave = clave,
                modelo = System.getenv("CAROBD_ASISTENTE_MODELO")?.takeIf { it.isNotBlank() } ?: "gpt-realtime-mini",
                voz = System.getenv("CAROBD_ASISTENTE_VOZ")?.takeIf { it.isNotBlank() } ?: "marin",
            )
        }

        const val INSTRUCCIONES = """Sos el asistente de voz de CAROBD, en la pantalla de un Hyundai Accent 2016 1.6 CRDi diésel.
Hablás en español rioplatense, con respuestas cortas: la persona está manejando.
No inventes datos del auto: usá las funciones que te da la app para leerlos.
Las alertas críticas las decide la app; vos solo las explicás y, si hay riesgo, recomendás detenerse en un lugar seguro."""
    }
}

/**
 * Tope de tokens por usuario y por día, en memoria. No mide minutos de audio (la sesión va directo
 * de la pantalla a OpenAI); el tope de gasto real se pone además en el presupuesto del proyecto de OpenAI.
 */
class TopeDiario(private val maximo: Int, private val reloj: Clock = Clock.system(ZoneId.of("America/Argentina/Buenos_Aires"))) {
    private var dia: LocalDate = LocalDate.now(reloj)
    private val usados = mutableMapOf<String, Int>()

    @Synchronized
    fun tomar(usuario: String): Boolean {
        val hoy = LocalDate.now(reloj)
        if (hoy != dia) { dia = hoy; usados.clear() }
        val n = usados[usuario] ?: 0
        if (n >= maximo) return false
        usados[usuario] = n + 1
        return true
    }

    companion object {
        fun desdeEntorno() = TopeDiario(System.getenv("CAROBD_ASISTENTE_TOKENS_DIA")?.toIntOrNull() ?: 30)
    }
}
