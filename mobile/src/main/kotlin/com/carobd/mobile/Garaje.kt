package com.carobd.mobile

import android.content.Context
import com.carobd.core.model.Combustible
import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.Mantenimiento
import com.carobd.core.model.Servicio
import com.carobd.core.model.TipoVehiculo
import com.carobd.core.model.Vehiculo
import com.carobd.core.sync.SyncJson
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.UUID

/** Todo lo que el móvil guarda localmente. En la fase 3 pasa a Room y se sincroniza con el VPS. */
@Serializable
data class DatosGaraje(
    val vehiculo: Vehiculo,
    val odometroKm: Int,
    val items: List<ItemMantenimiento>,
    val servicios: List<Servicio> = emptyList(),
)

class Garaje(context: Context) {
    private val prefs = context.getSharedPreferences("garaje", Context.MODE_PRIVATE)

    fun cargar(): DatosGaraje =
        prefs.getString("datos", null)
            ?.let { runCatching { SyncJson.json.decodeFromString(DatosGaraje.serializer(), it) }.getOrNull() }
            ?: inicial().also(::guardar)

    fun guardar(d: DatosGaraje) {
        prefs.edit().putString("datos", SyncJson.json.encodeToString(DatosGaraje.serializer(), d)).apply()
    }

    /** Por ahora el vehículo es fijo: el Accent 2016 1.6 CRDi con 90 600 km. Elegir marca y modelo llega después. */
    private fun inicial(): DatosGaraje {
        val ahora = Clock.System.now()
        val id = UUID.randomUUID().toString()
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
        val items = Mantenimiento.plantillaAccentCrdi(id, ahora) { UUID.randomUUID().toString() }
        return DatosGaraje(vehiculo, 90_600, items)
    }

    companion object {
        /** Registra un servicio y deja al día los ítems marcados. El odómetro nunca baja. */
        fun registrarServicio(d: DatosGaraje, km: Int, tipo: String, itemsHechos: Set<String>, ahora: Instant = Clock.System.now()): DatosGaraje {
            val servicio = Servicio(
                id = UUID.randomUUID().toString(),
                vehiculoId = d.vehiculo.id,
                fecha = ahora,
                km = km,
                tipo = tipo,
                itemsHechos = itemsHechos.toList(),
                updatedAt = ahora,
            )
            val items = d.items.map { if (it.id in itemsHechos) it.copy(ultimoKm = km, ultimaFecha = ahora, updatedAt = ahora) else it }
            return d.copy(odometroKm = maxOf(d.odometroKm, km), items = items, servicios = listOf(servicio) + d.servicios)
        }
    }
}
