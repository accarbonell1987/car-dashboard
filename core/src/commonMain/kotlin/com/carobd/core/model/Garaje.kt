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
        return DatosGaraje(vehiculo, 90_600, CatalogoMantenimiento.plan(id, vehiculo.plantilla, ahora))
    }

    /**
     * Registra un servicio y deja al día los ítems marcados. `fecha` permite cargar uno pasado (por defecto, ahora).
     * Un servicio más viejo que el último del ítem no lo hace retroceder. Solo los registrados suben el odómetro,
     * que nunca baja; los declarados en el asistente son de memoria.
     */
    fun registrarServicio(
        d: DatosGaraje,
        km: Int,
        tipo: String,
        itemsHechos: Set<String>,
        ahora: Instant,
        fecha: Instant = ahora,
        origen: String = ORIGEN_REGISTRADO,
        aproximado: Boolean = false,
        nunca: Boolean = false,
    ): DatosGaraje {
        val servicio = Servicio(
            id = nuevoId(),
            vehiculoId = d.vehiculo.id,
            fecha = fecha,
            km = km,
            tipo = tipo,
            itemsHechos = itemsHechos.toList(),
            origen = origen,
            aproximado = aproximado,
            nunca = nunca,
            updatedAt = ahora,
        )
        val items = d.items.map {
            val masNuevo = it.ultimoKm == null || km > it.ultimoKm || (km == it.ultimoKm && (it.ultimaFecha == null || fecha > it.ultimaFecha))
            if (it.id in itemsHechos && !nunca && masNuevo) it.copy(ultimoKm = km, ultimaFecha = fecha, updatedAt = ahora) else it
        }
        val odometro = if (origen == ORIGEN_REGISTRADO) maxOf(d.odometroKm, km) else d.odometroKm
        return d.copy(odometroKm = odometro, items = items, servicios = listOf(servicio) + d.servicios)
    }

    /** Corrige el odómetro a mano. Igual que en la sincronización, nunca baja. */
    fun corregirOdometro(d: DatosGaraje, km: Int): DatosGaraje = d.copy(odometroKm = maxOf(d.odometroKm, km))
}
