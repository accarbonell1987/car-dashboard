package com.carobd.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Toda entidad sincronizable comparte estas reglas:
 * - `id` es un UUID generado en el dispositivo donde nace.
 * - `updatedAt` decide quién gana cuando dos dispositivos editaron lo mismo.
 * - `deletedAt` marca el borrado; nunca se borra de verdad.
 */
interface Sincronizable {
    val id: String
    val updatedAt: Instant
    val deletedAt: Instant?
}

@Serializable
enum class TipoVehiculo { AUTO, MOTO }

@Serializable
enum class Combustible { DIESEL, GASOLINA, HIBRIDO, ELECTRICO, OTRO }

@Serializable
data class Vehiculo(
    override val id: String,
    val usuarioId: String,
    val tipo: TipoVehiculo,
    val marca: String,
    val modelo: String,
    val anio: Int,
    val motor: String? = null,
    val combustible: Combustible = Combustible.OTRO,
    val vin: String? = null,
    val odometroInicialKm: Int,
    val odometroInicialFecha: Instant,
    val tieneObd: Boolean,
    /** Plantilla de mantenimiento elegida en el asistente, por ejemplo [CatalogoMantenimiento.DIESEL_D4FB_MANUAL]. */
    val plantilla: String? = null,
    /** "MANUAL" o "AUTOMATICA". Texto y no enum, para que una app vieja pueda leerlo. */
    val transmision: String? = null,
    /** Fecha de compra o primera matriculación. Base de los ítems por tiempo que nunca se hicieron. */
    val fechaAlta: Instant? = null,
    val kmCompra: Int? = null,
    /** Uso severo del manual: cambia los intervalos de los ítems que lo tienen. */
    val usoSevero: Boolean = false,
    /** Letras de las condiciones severas del manual (A a L) que marcó el usuario. */
    val condicionesSeveras: List<String> = emptyList(),
    /** Filtro de partículas. null = no se sabe. */
    val tieneDpf: Boolean? = null,
    /** Gasoil EN 590 o de bajo azufre. null = no se sabe, y se toma como que no. */
    val combustibleEn590: Boolean? = null,
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable

@Serializable
enum class OrigenViaje { OBD, GPS, MIXTO }

/** Resumen de un viaje. Las lecturas en vivo nunca se sincronizan, solo este resumen. */
@Serializable
data class Viaje(
    override val id: String,
    val vehiculoId: String,
    val inicio: Instant,
    val fin: Instant,
    val km: Double,
    val duracionSeg: Long,
    val consumoLitros: Double? = null,
    val velocidadMaxKmh: Double? = null,
    val velocidadMediaKmh: Double? = null,
    val rpmMax: Int? = null,
    val tempRefrigeranteMaxC: Double? = null,
    val voltajeArranqueV: Double? = null,
    val origen: OrigenViaje,
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable

@Serializable
enum class OrigenOdometro { CALCULADO, MANUAL, SERVICIO }

@Serializable
data class LecturaOdometro(
    override val id: String,
    val vehiculoId: String,
    val fecha: Instant,
    val km: Int,
    val origen: OrigenOdometro,
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable

@Serializable
enum class EstadoFalla { ACTIVA, BORRADA }

@Serializable
data class EventoFalla(
    override val id: String,
    val vehiculoId: String,
    val fecha: Instant,
    val codigo: String,
    val descripcion: String? = null,
    val estado: EstadoFalla,
    val km: Int? = null,
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable

@Serializable
enum class SistemaSalud { BATERIA, TERMOSTATO, DPF, TURBO, RIEL, CALENTADORES, GENERAL }

@Serializable
enum class NivelAviso { INFO, ATENCION, CRITICO }

@Serializable
data class AvisoSalud(
    override val id: String,
    val vehiculoId: String,
    val fecha: Instant,
    val sistema: SistemaSalud,
    val nivel: NivelAviso,
    val texto: String,
    val datos: Map<String, String> = emptyMap(),
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable

const val ORIGEN_REGISTRADO = "REGISTRADO"
const val ORIGEN_DECLARADO = "DECLARADO"

@Serializable
data class Servicio(
    override val id: String,
    val vehiculoId: String,
    val fecha: Instant,
    val km: Int,
    val tipo: String,
    val costo: Double? = null,
    val moneda: String? = null,
    val taller: String? = null,
    val notas: String? = null,
    val fotoUrl: String? = null,
    /** Ítems del plan de mantenimiento que este servicio deja al día, por id de ítem. */
    val itemsHechos: List<String> = emptyList(),
    /** [ORIGEN_REGISTRADO] (hecho y cargado al volver del taller) o [ORIGEN_DECLARADO] (contado en el asistente). */
    val origen: String = ORIGEN_REGISTRADO,
    /** El km o la fecha son a ojo. */
    val aproximado: Boolean = false,
    /** Declaración "nunca se hizo": cuenta desde 0 km y la fecha de alta del auto. */
    val nunca: Boolean = false,
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable

@Serializable
data class ItemMantenimiento(
    override val id: String,
    val vehiculoId: String,
    val nombre: String,
    val cadaKm: Int? = null,
    val cadaMeses: Int? = null,
    val ultimoKm: Int? = null,
    val ultimaFecha: Instant? = null,
    /** Código del catálogo ("ACEITE_MOTOR"); null en ítems propios del usuario. */
    val codigo: String? = null,
    /** "REVISAR" o "CAMBIAR". */
    val accion: String? = null,
    /** Primera vez, contada desde 0 km y la fecha de alta, cuando nunca se hizo. */
    val primeraKm: Int? = null,
    val primeraMeses: Int? = null,
    /** Intervalo en uso severo. */
    val severoKm: Int? = null,
    val severoMeses: Int? = null,
    /** El usuario cambió los intervalos: una actualización del catálogo no los pisa. */
    val personalizado: Boolean = false,
    val activo: Boolean = true,
    /** No lo pide Hyundai; lo recomienda la app. */
    val recomendacion: Boolean = false,
    val notas: String? = null,
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable

/** Un tablero de la pantalla: la grilla y sus gauges, guardados como JSON opaco para el servidor. */
@Serializable
data class Tablero(
    override val id: String,
    val vehiculoId: String,
    val nombre: String,
    val definicionJson: String,
    override val updatedAt: Instant,
    override val deletedAt: Instant? = null,
) : Sincronizable
