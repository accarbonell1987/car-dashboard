package com.carobd.core.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.Serializable

/** Los seis pasos del asistente de alta (RF-05). Cada uno se puede saltar. */
enum class PasoAsistente(val titulo: String) {
    AUTO("Tu auto"),
    USO("Cómo lo usas"),
    COMBUSTIBLE("Combustible"),
    HISTORIAL("Historial"),
    CHEQUEO("Chequeo"),
    RESULTADO("Resultado"),
}

/** Qué sabe el usuario de un ítem del plan (RF-08). */
@Serializable
data class RespuestaItem(
    /** "EXACTO", "APROX", "NUNCA" o "NO_SE". */
    val tipo: String,
    val km: Int? = null,
    /** Año y mes, "2025-03". */
    val mes: String? = null,
)

/** Una pregunta del chequeo del estado actual. Las respuestas "sí" generan un aviso de salud ligado al ítem. */
data class PreguntaChequeo(
    val id: String,
    val texto: String,
    val ayuda: String,
    /** La respuesta que indica un problema. */
    val malaSi: Boolean,
    val sistema: SistemaSalud,
    val nivel: NivelAviso,
    val aviso: String,
    /** Código del catálogo al que queda ligado el aviso. */
    val codigo: String?,
)

/**
 * Lo que el usuario respondió hasta ahora. Se guarda en el dispositivo para retomarlo (RF-06) y no se sincroniza:
 * lo que se sincroniza es lo que produce [Asistente.aplicar].
 */
@Serializable
data class RespuestasAsistente(
    val paso: Int = 0,
    val plantilla: String? = null,
    val transmision: String? = null,
    /** Año y mes de compra o primera matriculación, "2016-06". */
    val mesAlta: String? = null,
    val kmActual: Int? = null,
    val condiciones: List<String> = emptyList(),
    val usoSevero: Boolean? = null,
    val en590: Boolean? = null,
    val tieneDpf: Boolean? = null,
    /** Atajo del historial: el último service completo (RF-07). */
    val ultimoServiceKm: Int? = null,
    val ultimoServiceMes: String? = null,
    /** Por id de ítem. */
    val items: Map<String, RespuestaItem> = emptyMap(),
    /** Por id de pregunta del chequeo: true = sí, false = no; ausente = no sé. */
    val chequeo: Map<String, Boolean> = emptyMap(),
    val terminado: Boolean = false,
)

/** El resultado de aplicar el asistente: lo que hay que guardar. */
data class ResultadoAsistente(
    val vehiculo: Vehiculo,
    val items: List<ItemMantenimiento>,
    val servicios: List<Servicio>,
    val avisos: List<AvisoSalud>,
)

object Asistente {
    const val EXACTO = "EXACTO"
    const val APROX = "APROX"
    const val NUNCA = "NUNCA"
    const val NO_SE = "NO_SE"

    /** Motores del Accent RB. Los que todavía no tienen plantilla se muestran pero no se pueden elegir. */
    data class Motor(val nombre: String, val plantilla: String?)

    val motores = listOf(
        Motor("1.6 CRDi diésel", CatalogoMantenimiento.DIESEL_D4FB_MANUAL),
        Motor("1.4 nafta", null),
        Motor("1.6 nafta", null),
    )

    /** Condiciones de uso severo del manual (A a L), en palabras del conductor. */
    val condiciones = listOf(
        "A" to "Muchos trayectos cortos",
        "B" to "Mucho tiempo en ralentí",
        "C" to "Caminos de tierra o en mal estado",
        "D" to "Sal, corrosivos o mucho frío",
        "E" to "Zonas con arena o polvo",
        "F" to "Tráfico denso con más de 32 °C",
        "G" to "Zonas de montaña",
        "H" to "Arrastra remolque",
        "I" to "Taxi, policía o uso comercial",
        "J" to "Más de 170 km/h",
        "K" to "Paradas y arranques frecuentes",
        "L" to "Clima muy frío",
    )

    val chequeo = listOf(
        PreguntaChequeo(
            "testigo_motor", "¿Queda algún testigo encendido con el motor en marcha?",
            "Por ejemplo el del motor (ámbar) o el de batería.", true,
            SistemaSalud.GENERAL, NivelAviso.ATENCION, "Testigo encendido con el motor en marcha", null,
        ),
        PreguntaChequeo(
            "arranque_frio", "¿Le cuesta arrancar en frío?",
            "Más de un intento, o mucho humo blanco al arrancar.", true,
            SistemaSalud.CALENTADORES, NivelAviso.ATENCION, "Cuesta arrancar en frío: revisar bujías de precalentamiento y batería", "BATERIA",
        ),
        PreguntaChequeo(
            "temperatura", "¿La aguja de temperatura llega al medio a los 15 minutos de andar?",
            "Si se queda baja, el termostato puede estar abierto.", false,
            SistemaSalud.TERMOSTATO, NivelAviso.ATENCION, "El motor no llega a temperatura: revisar termostato", "REFRIGERACION",
        ),
        PreguntaChequeo(
            "testigo_dpf", "¿Se enciende a veces el testigo del filtro de partículas?",
            "Un rectángulo con puntitos adentro.", true,
            SistemaSalud.DPF, NivelAviso.ATENCION, "Testigo del filtro de partículas encendido", null,
        ),
        PreguntaChequeo(
            "frenos_ruido", "¿Los frenos hacen ruido o el pedal se siente largo?",
            "Chirrido, roce metálico o pedal que baja mucho.", true,
            SistemaSalud.GENERAL, NivelAviso.ATENCION, "Frenos con ruido o pedal largo", "FRENOS",
        ),
    )

    /**
     * Si un ítem toca en el hito `km` de su plan: a los `primeraKm` y luego cada `cadaKm`, o cada `cadaKm` desde 0.
     * Es lo que usa el atajo "último service completo a X km".
     */
    fun tocaEnHito(item: ItemMantenimiento, km: Int): Boolean {
        val cada = item.cadaKm ?: return false
        val primera = item.primeraKm
        return if (primera == null) km > 0 && km % cada == 0 else km == primera || (km > primera && (km - primera) % cada == 0)
    }

    /** Lo que el atajo marca como hecho en el último service completo. */
    fun itemsDelHito(items: List<ItemMantenimiento>, km: Int): List<ItemMantenimiento> = items.filter { tocaEnHito(it, km) }

    fun mesAInstant(mes: String?, zona: TimeZone = TimeZone.UTC): Instant? = mes?.let {
        runCatching { LocalDate.parse("$it-01").atStartOfDayIn(zona) }.getOrNull()
    }

    /**
     * Convierte las respuestas en datos: el vehículo con su uso y combustible, servicios declarados por cada ítem
     * con dato (RF-09) y avisos de salud por cada respuesta mala del chequeo. Los servicios declarados no mueven
     * el odómetro. Los ids de servicios y avisos se derivan del ítem, así que aplicar dos veces no duplica.
     */
    fun aplicar(
        r: RespuestasAsistente,
        vehiculo: Vehiculo,
        items: List<ItemMantenimiento>,
        ahora: Instant,
        zona: TimeZone = TimeZone.UTC,
    ): ResultadoAsistente {
        val severo = r.usoSevero ?: r.condiciones.isNotEmpty()
        val v = vehiculo.copy(
            plantilla = r.plantilla ?: vehiculo.plantilla,
            transmision = r.transmision ?: vehiculo.transmision,
            fechaAlta = mesAInstant(r.mesAlta, zona) ?: vehiculo.fechaAlta,
            condicionesSeveras = r.condiciones,
            usoSevero = severo,
            combustibleEn590 = r.en590,
            tieneDpf = r.tieneDpf,
            updatedAt = ahora,
        )
        val hito = r.ultimoServiceKm?.let { km -> itemsDelHito(items, km).map { it.id }.toSet() }.orEmpty()
        val servicios = items.mapNotNull { item ->
            val resp = r.items[item.id]
            val id = "declarado:${item.id}"
            fun servicio(km: Int, fecha: Instant?, aproximado: Boolean, nunca: Boolean = false) = Servicio(
                id = id, vehiculoId = v.id, fecha = fecha ?: v.fechaAlta ?: ahora, km = km, tipo = "Declarado en el asistente",
                itemsHechos = listOf(item.id), origen = ORIGEN_DECLARADO, aproximado = aproximado || fecha == null,
                nunca = nunca, updatedAt = ahora,
            )
            when {
                resp?.tipo == NUNCA -> servicio(0, v.fechaAlta, aproximado = false, nunca = true)
                (resp?.tipo == EXACTO || resp?.tipo == APROX) && resp.km != null ->
                    servicio(resp.km, mesAInstant(resp.mes, zona), aproximado = resp.tipo == APROX)
                resp == null && item.id in hito ->
                    servicio(r.ultimoServiceKm!!, mesAInstant(r.ultimoServiceMes, zona), aproximado = false)
                else -> null
            }
        }
        val avisos = chequeo.mapNotNull { q ->
            val dijo = r.chequeo[q.id] ?: return@mapNotNull null
            if (dijo != q.malaSi) return@mapNotNull null
            val item = q.codigo?.let { c -> items.firstOrNull { it.codigo == c } }
            AvisoSalud(
                id = "chequeo:${v.id}:${q.id}", vehiculoId = v.id, fecha = ahora, sistema = q.sistema, nivel = q.nivel,
                texto = q.aviso, datos = buildMap { put("origen", "asistente"); item?.let { put("item", it.id) } }, updatedAt = ahora,
            )
        }
        return ResultadoAsistente(v, items, servicios, avisos)
    }
}
