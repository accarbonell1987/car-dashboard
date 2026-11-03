package com.carobd.core.datos

import com.carobd.core.model.DatosGaraje
import com.carobd.core.model.Garaje
import com.carobd.core.model.LecturaOdometro
import com.carobd.core.model.Mantenimiento
import com.carobd.core.model.OrigenOdometro
import com.carobd.core.model.Sincronizable
import com.carobd.core.model.Vehiculo
import com.carobd.core.model.Viaje
import com.carobd.core.model.nuevoId
import com.carobd.core.sync.AlmacenEnMemoria
import com.carobd.core.sync.AlmacenPersistente
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.floor

/**
 * La historia del vehículo sobre el almacén local: lo que usan la pantalla y el móvil por igual.
 * Todo lo que se guarda aquí queda pendiente de subir y lo reparte la sincronización.
 */
class Bitacora(
    val almacen: AlmacenPersistente,
    private val reloj: () -> Instant = { Clock.System.now() },
    private val crearId: () -> String = ::nuevoId,
) {
    companion object {
        /**
         * Mientras el vehículo es fijo, la pantalla y el móvil usan el mismo id para el Accent.
         * Si cada uno inventara el suyo, al sincronizar aparecerían dos autos.
         */
        const val ID_ACCENT = "accent-2016-crdi"

        /**
         * Odómetro estimado: el último dato firme (inicial, corrección a mano o servicio) más los km de los viajes
         * posteriores. Nunca baja: si hay un dato mayor, gana ese.
         * Las lecturas CALCULADO no sirven de base porque van redondeadas y perderían los decimales de cada viaje.
         */
        fun odometro(m: AlmacenEnMemoria, vehiculoId: String): Double {
            val v = m.vehiculos[vehiculoId] ?: return 0.0
            val lecturas = m.lecturasOdometro.values.filter { it.vehiculoId == vehiculoId && it.deletedAt == null }
            val firmes = buildList {
                add(v.odometroInicialFecha to v.odometroInicialKm)
                lecturas.filter { it.origen != OrigenOdometro.CALCULADO }.forEach { add(it.fecha to it.km) }
                m.servicios.values.filter { it.vehiculoId == vehiculoId && it.deletedAt == null }.forEach { add(it.fecha to it.km) }
            }
            val base = firmes.maxBy { it.first }
            val recorrido = m.viajes.values
                .filter { it.vehiculoId == vehiculoId && it.deletedAt == null && it.inicio >= base.first }
                .sumOf { it.km }
            val maximo = (firmes.map { it.second } + lecturas.map { it.km }).max().toDouble()
            return maxOf(base.second + recorrido, maximo)
        }
    }

    /** El vehículo fijo; lo crea la primera vez. */
    suspend fun vehiculo(): Vehiculo {
        almacen.leer { it.vehiculos[ID_ACCENT] }?.let { return it }
        val nuevo = Garaje.inicialAccent(reloj()).vehiculo.copy(id = ID_ACCENT)
        almacen.guardar(nuevo)
        return nuevo
    }

    suspend fun odometroKm(vehiculoId: String = ID_ACCENT): Double = almacen.leer { odometro(it, vehiculoId) }

    /** Guarda un viaje cerrado y deja una lectura del odómetro calculado al terminar. */
    suspend fun guardarViaje(viaje: Viaje): LecturaOdometro {
        val km = almacen.leer { m ->
            val antes = odometro(m, viaje.vehiculoId)
            // El viaje todavía no está en el almacén: se suma a mano.
            antes + viaje.km
        }
        val lectura = LecturaOdometro(
            id = crearId(),
            vehiculoId = viaje.vehiculoId,
            fecha = viaje.fin,
            km = floor(km).toInt(),
            origen = OrigenOdometro.CALCULADO,
            updatedAt = reloj(),
        )
        almacen.guardar(viaje, lectura)
        return lectura
    }

    suspend fun viajes(vehiculoId: String = ID_ACCENT): List<Viaje> = almacen.leer { m ->
        m.viajes.values.filter { it.vehiculoId == vehiculoId && it.deletedAt == null }.sortedByDescending { it.inicio }
    }

    /** Lo que muestra la pantalla de mantenimiento del móvil. Si no hay plan todavía, carga el del Accent. */
    suspend fun garaje(): DatosGaraje {
        val v = vehiculo()
        val sinPlan = almacen.leer { m -> m.itemsMantenimiento.values.none { it.vehiculoId == v.id } }
        if (sinPlan) {
            val plan = Mantenimiento.plantillaAccentCrdi(v.id, reloj(), crearId)
            almacen.guardar(*plan.toTypedArray())
        }
        return almacen.leer { m ->
            DatosGaraje(
                vehiculo = v,
                odometroKm = floor(odometro(m, v.id)).toInt(),
                items = m.itemsMantenimiento.values.filter { it.vehiculoId == v.id && it.deletedAt == null }.sortedBy { it.nombre },
                servicios = m.servicios.values.filter { it.vehiculoId == v.id && it.deletedAt == null }.sortedByDescending { it.fecha },
            )
        }
    }

    /**
     * Guarda lo que cambió entre dos versiones del garaje (lo que devuelven las reglas de [Garaje]).
     * Un odómetro corregido a mano se guarda como lectura MANUAL; el de un servicio ya va en el servicio.
     */
    suspend fun guardarGaraje(antes: DatosGaraje, despues: DatosGaraje) {
        val cambios = mutableListOf<Sincronizable>()
        if (despues.vehiculo != antes.vehiculo) cambios += despues.vehiculo
        val itemsAntes = antes.items.associateBy { it.id }
        cambios += despues.items.filter { itemsAntes[it.id] != it }
        val serviciosAntes = antes.servicios.associateBy { it.id }
        val nuevosServicios = despues.servicios.filter { serviciosAntes[it.id] != it }
        cambios += nuevosServicios
        if (despues.odometroKm > antes.odometroKm && nuevosServicios.none { it.km == despues.odometroKm }) {
            val ahora = reloj()
            cambios += LecturaOdometro(crearId(), despues.vehiculo.id, ahora, despues.odometroKm, OrigenOdometro.MANUAL, ahora)
        }
        if (cambios.isNotEmpty()) almacen.guardar(*cambios.toTypedArray())
    }

    /** Pasa el garaje que el móvil guardaba antes de la fase 3 a la base local, con el id común del vehículo. */
    suspend fun importarGarajeViejo(viejo: DatosGaraje) {
        val ya = almacen.leer { m -> m.itemsMantenimiento.isNotEmpty() || m.servicios.isNotEmpty() }
        if (ya) return
        val id = ID_ACCENT
        val v = viejo.vehiculo.copy(id = id)
        val items = viejo.items.map { it.copy(vehiculoId = id) }
        val servicios = viejo.servicios.map { it.copy(vehiculoId = id) }
        val ahora = reloj()
        val lectura = if (viejo.odometroKm > v.odometroInicialKm) {
            listOf(LecturaOdometro(crearId(), id, ahora, viejo.odometroKm, OrigenOdometro.MANUAL, ahora))
        } else emptyList()
        almacen.guardar(*(listOf<Sincronizable>(v) + items + servicios + lectura).toTypedArray())
    }
}
