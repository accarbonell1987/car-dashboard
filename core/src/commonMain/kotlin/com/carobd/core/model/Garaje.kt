package com.carobd.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Lo que el móvil guarda localmente de un vehículo. En la fase 3 pasa a una base local sincronizada. */
@Serializable
data class DatosGaraje(
    val vehiculo: Vehiculo,
    val odometroKm: Int,
    val items: List<ItemMantenimiento>,
    val servicios: List<Servicio> = emptyList(),
)

@OptIn(ExperimentalUuidApi::class)
fun nuevoId(): String = Uuid.random().toString()

/** Reglas del garaje, sin almacenamiento ni interfaz, para probarlas en cualquier plataforma. */
object Garaje {

    /** Por ahora el vehículo es fijo: el Accent 2016 1.6 CRDi con 90.600 km. Elegir marca y modelo llega después. */
    fun inicialAccent(ahora: Instant): DatosGaraje {
        val id = nuevoId()
        val vehiculo = Vehiculo(
            id = id,
            usuarioId = "",
            tipo = TipoVehiculo.AUTO,
            marca = "Hyundai",
            modelo = "Accent",
            anio = 2016,
            motor = "1.6 CRDi",
            combustible = Combustible.DIESEL,
            odometroInicialKm = 90_600,
            odometroInicialFecha = ahora,
            tieneObd = true,
            updatedAt = ahora,
        )
        return DatosGaraje(vehiculo, 90_600, Mantenimiento.plantillaAccentCrdi(id, ahora, ::nuevoId))
    }

    /** Registra un servicio y deja al día los ítems marcados. El odómetro nunca baja. */
    fun registrarServicio(d: DatosGaraje, km: Int, tipo: String, itemsHechos: Set<String>, ahora: Instant): DatosGaraje {
        val servicio = Servicio(
            id = nuevoId(),
            vehiculoId = d.vehiculo.id,
            fecha = ahora,
            km = km,
            tipo = tipo,
            itemsHechos = itemsHechos.toList(),
            updatedAt = ahora,
        )
        val items = d.items.map {
            if (it.id in itemsHechos) it.copy(ultimoKm = km, ultimaFecha = ahora, updatedAt = ahora) else it
        }
        return d.copy(odometroKm = maxOf(d.odometroKm, km), items = items, servicios = listOf(servicio) + d.servicios)
    }

    /** Corrige el odómetro a mano. Igual que en la sincronización, nunca baja. */
    fun corregirOdometro(d: DatosGaraje, km: Int): DatosGaraje = d.copy(odometroKm = maxOf(d.odometroKm, km))
}
