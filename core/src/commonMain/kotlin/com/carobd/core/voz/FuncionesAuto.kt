package com.carobd.core.voz

import com.carobd.core.model.AvisoSalud
import com.carobd.core.model.Estado
import com.carobd.core.model.EstadoItem
import com.carobd.core.model.texto
import com.carobd.core.obd.Carroceria
import com.carobd.core.obd.Catalogo
import com.carobd.core.obd.MotorExtendido
import com.carobd.core.tablero.Alerta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** Lo que el asistente puede saber del auto en este momento. La app lo arma desde su estado. */
data class ContextoAuto(
    /** "auto" con el adaptador conectado, "demo" con datos simulados, "sin conexión" si no hay lecturas. */
    val fuente: String,
    val valores: Map<String, Double>,
    val alertas: List<Alerta>,
    val odometroKm: Double,
    val viajeKm: Double? = null,
    val viajeSeg: Long = 0,
    val viajeL100: Double? = null,
    val mantenimiento: List<EstadoItem> = emptyList(),
    val avisos: List<AvisoSalud> = emptyList(),
)

/** Algo que la app tiene que hacer porque el asistente lo pidió. */
sealed interface AccionAsistente {
    data class Navegar(val destino: String, val app: String) : AccionAsistente
    data object Terminar : AccionAsistente
}

data class ResultadoFuncion(val salida: String, val accion: AccionAsistente? = null)

/**
 * Las funciones que el modelo puede llamar. El modelo nunca toca el auto: solo lee lo que la app ya
 * tiene en pantalla (no manda comandos al adaptador) o le pide abrir el navegador.
 */
object FuncionesAuto {
    const val LEER_DATOS = "leer_datos_auto"
    const val MANTENIMIENTO = "consultar_mantenimiento"
    const val NAVEGAR = "navegar_a"
    const val TERMINAR = "terminar_conversacion"

    val INSTRUCCIONES = """
        Eres el asistente de voz de CAROBD, en la pantalla de un Hyundai Accent 2016 1.6 CRDi diésel.
        Hablas en español, con frases cortas y naturales: la persona está manejando y no puede leer.
        Nunca inventes lecturas del auto: para cualquier dato, alerta o falla llama a $LEER_DATOS, y para
        services y repuestos a $MANTENIMIENTO. Si la fuente es "demo" o "sin conexión", dilo.
        Las alertas las decide la app con reglas fijas; tú solo las explicas. Si hay una alerta crítica,
        recomienda detenerse en un lugar seguro.
        Para ir a un lugar usa $NAVEGAR. Cuando la persona se despida o diga que es todo, responde en pocas
        palabras y llama a $TERMINAR.
    """.trimIndent()

    val lista: List<FuncionAsistente> = listOf(
        FuncionAsistente(
            LEER_DATOS,
            "Lecturas actuales del motor (velocidad, rpm, temperaturas, voltaje, consumo, etc.), alertas activas, " +
                "check engine, odómetro, viaje en curso y avisos de salud del vehículo.",
            sinParametros(),
        ),
        FuncionAsistente(
            MANTENIMIENTO,
            "Estado del plan de mantenimiento: qué está vencido, qué viene pronto y cuánto falta para cada ítem.",
            sinParametros(),
        ),
        FuncionAsistente(
            NAVEGAR,
            "Abre la navegación hacia un destino (dirección, lugar o comercio).",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("destino") {
                        put("type", "string")
                        put("description", "Destino tal como lo buscaría en el mapa, con ciudad si se sabe.")
                    }
                    putJsonObject("app") {
                        put("type", "string")
                        putJsonArray("enum") { add("maps"); add("waze") }
                        put("description", "Solo si la persona pidió una app en particular.")
                    }
                }
                putJsonArray("required") { add("destino") }
            },
        ),
        FuncionAsistente(TERMINAR, "Cierra la conversación de voz.", sinParametros()),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun ejecutar(nombre: String, argumentos: String, c: ContextoAuto): ResultadoFuncion = when (nombre) {
        LEER_DATOS -> ResultadoFuncion(datos(c))
        MANTENIMIENTO -> ResultadoFuncion(mantenimiento(c))
        NAVEGAR -> {
            val args = runCatching { json.parseToJsonElement(argumentos).jsonObject }.getOrNull()
            val destino = (args?.get("destino") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val app = (args?.get("app") as? JsonPrimitive)?.contentOrNull?.takeIf { it == "waze" } ?: "maps"
            if (destino.isEmpty()) {
                ResultadoFuncion("""{"ok":false,"error":"falta el destino"}""")
            } else {
                ResultadoFuncion(buildJsonObject { put("ok", true); put("abriendo", app) }.toString(), AccionAsistente.Navegar(destino, app))
            }
        }
        TERMINAR -> ResultadoFuncion("""{"ok":true}""", AccionAsistente.Terminar)
        else -> ResultadoFuncion("""{"ok":false,"error":"función desconocida"}""")
    }

    private fun datos(c: ContextoAuto): String = buildJsonObject {
        put("fuente", c.fuente)
        putJsonArray("lecturas") {
            c.valores.entries.sortedBy { it.key }.forEach { (id, v) ->
                val (nombre, unidad) = describir(id) ?: return@forEach
                addJsonObject {
                    put("nombre", nombre)
                    put("valor", redondear(v))
                    if (unidad.isNotEmpty()) put("unidad", unidad)
                }
            }
        }
        putJsonArray("alertas") {
            c.alertas.forEach { a ->
                addJsonObject {
                    put("nivel", a.regla.nivel.name.lowercase())
                    put("mensaje", a.regla.mensaje)
                    put("valor", redondear(a.valor))
                    describir(a.regla.senalId)?.let { put("senal", it.first) }
                }
            }
        }
        put("odometro_km", c.odometroKm.roundToInt())
        c.viajeKm?.let { km ->
            putJsonObject("viaje_en_curso") {
                put("km", redondear(km))
                put("minutos", c.viajeSeg / 60)
                c.viajeL100?.let { put("litros_cada_100_km", redondear(it)) }
            }
        }
        putJsonArray("avisos_salud") {
            c.avisos.filter { it.deletedAt == null }.take(5).forEach { a ->
                addJsonObject {
                    put("nivel", a.nivel.name.lowercase())
                    put("sistema", a.sistema.name.lowercase())
                    put("texto", a.texto)
                }
            }
        }
    }.toString()

    private fun mantenimiento(c: ContextoAuto): String = buildJsonObject {
        if (c.mantenimiento.isEmpty()) {
            put("sin_datos", "No hay plan cargado. Se completa en el asistente de mantenimiento de la pantalla.")
        }
        putJsonArray("items") {
            c.mantenimiento.forEach { e ->
                addJsonObject {
                    put("nombre", e.item.nombre)
                    put("estado", when {
                        e.estado == Estado.VENCIDO -> "vencido"
                        e.probablementeVencido -> "probablemente vencido"
                        e.estado == Estado.PROXIMO -> "próximo"
                        e.estado == Estado.AL_DIA -> "al día"
                        else -> "sin datos"
                    })
                    put("detalle", e.texto())
                }
            }
        }
        put("odometro_km", c.odometroKm.roundToInt())
    }.toString()

    private fun redondear(v: Double): Double = (v * 10).roundToInt() / 10.0

    /** Nombre legible y unidad de una señal; null para las que el asistente no necesita (estados internos). */
    internal fun describir(id: String): Pair<String, String>? {
        Catalogo.porId(id)?.let { return it.nombre to it.unidad }
        return when (id) {
            Carroceria.PUERTA -> "Puerta del conductor abierta (1 = sí)" to ""
            Carroceria.CINTURON -> "Cinturón del conductor suelto (1 = sí)" to ""
            Carroceria.AIRE -> "Aire acondicionado encendido (1 = sí)" to ""
            Carroceria.TEMP_EXTERIOR -> "Temperatura exterior" to "°C"
            Carroceria.ODOMETRO -> "Odómetro del tablero" to "km"
            Carroceria.RESERVA -> "Luz de reserva de combustible (1 = encendida)" to ""
            Carroceria.TEMP_COMBUSTIBLE -> "Temperatura del combustible" to "°C"
            Carroceria.DIRECCION -> "Falla de dirección asistida (1 = sí)" to ""
            "marcha" -> "Marcha estimada (0 = punto muerto)" to ""
            in MotorExtendido.senales -> id.replace('_', ' ') to ""
            else -> null
        }
    }
}
