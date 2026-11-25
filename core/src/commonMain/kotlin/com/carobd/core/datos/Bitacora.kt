package com.carobd.core.datos

import com.carobd.core.model.Asistente
import com.carobd.core.model.AvisoSalud
import com.carobd.core.model.CatalogoMantenimiento
import com.carobd.core.model.RespuestasAsistente
import com.carobd.core.model.DatosGaraje
import com.carobd.core.model.Garaje
import com.carobd.core.model.LecturaOdometro
import com.carobd.core.model.MigracionPlan
import com.carobd.core.model.ORIGEN_DECLARADO
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
        const val PREFIJO_TABLERO = "tablero-"
        const val MAX_ODOMETRO_KM = 999_999.0

        /**
         * Odómetro estimado: el último dato firme (inicial, corrección a mano o servicio registrado) más los km de los viajes
         * posteriores. Nunca baja: si hay un dato mayor, gana ese.
         * Excepción: la última lectura del tablero (trama 0x4F0) es la verdad del auto; lo anterior a ella no cuenta,
         * así corrige un estimado que se había pasado.
         * Las lecturas CALCULADO no sirven de base porque van redondeadas y perderían los decimales de cada viaje.
         */
        fun odometro(m: AlmacenEnMemoria, vehiculoId: String): Double {
            val v = m.vehiculos[vehiculoId] ?: return 0.0
            val todas = m.lecturasOdometro.values.filter { it.vehiculoId == vehiculoId && it.deletedAt == null }
            val desde = todas.filter { it.id.startsWith(PREFIJO_TABLERO) }.maxOfOrNull { it.fecha }
            val lecturas = if (desde == null) todas else todas.filter { it.fecha >= desde }
            val firmes = buildList {
                if (desde == null) add(v.odometroInicialFecha to v.odometroInicialKm)
                lecturas.filter { it.origen != OrigenOdometro.CALCULADO }.forEach { add(it.fecha to it.km) }
                // Los servicios declarados en el asistente no cuentan: su km es de memoria y el odómetro nunca baja.
                m.servicios.values.filter { it.vehiculoId == vehiculoId && it.deletedAt == null && it.origen != ORIGEN_DECLARADO && (desde == null || it.fecha >= desde) }
                    .forEach { add(it.fecha to it.km) }
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

    /**
     * Guarda el odómetro que el tablero manda por el bus (trama 0x4F0). Es la verdad del auto: pisa al estimado
     * aunque sea menor. Solo descarta valores imposibles. Devuelve si la guardó.
     */
    suspend fun guardarOdometroTablero(km: Double, vehiculoId: String = ID_ACCENT): Boolean {
        if (km !in 1.0..MAX_ODOMETRO_KM) return false
        val ahora = reloj()
        // ponytail: the id prefix marks cluster readings without changing the synced format; add an OrigenOdometro
        // value once every app that syncs is updated together.
        almacen.guardar(LecturaOdometro(PREFIJO_TABLERO + crearId(), vehiculoId, ahora, floor(km).toInt(), OrigenOdometro.MANUAL, ahora))
        return true
    }

    /**
     * El garaje del vehículo, con el plan del catálogo al día. Crea o migra el plan si hace falta; los ids fijos
     * hacen que el móvil y la pantalla lleguen a los mismos ítems sin hablarse.
     */
    suspend fun garaje(): DatosGaraje {
        val v = vehiculo()
        val cambios = almacen.leer { m -> MigracionPlan.migrar(m.itemsMantenimiento.values.toList(), v, reloj()) }
        if (cambios.isNotEmpty()) almacen.guardar(*cambios.toTypedArray())
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
     * Antes la pantalla no podía crear el plan (habría ítems repetidos). Con los ids fijos del catálogo ya puede:
     * queda como alias de [garaje] para no tocar a quien lo llama.
     */
    suspend fun garajeSinCrear(): DatosGaraje = garaje()

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

    /**
     * Guarda lo que produjo el asistente: vehículo, servicios declarados y avisos del chequeo. Se puede llamar
     * otra vez con respuestas corregidas: los ids son fijos y un dato que pasó a "no sé" se borra.
     * El km actual, si es mayor al estimado, queda como corrección MANUAL del odómetro.
     */
    suspend fun guardarAsistente(r: RespuestasAsistente) {
        val g = garaje()
        val ahora = reloj()
        val res = Asistente.aplicar(r, g.vehiculo, g.items, ahora)
        val cambios = mutableListOf<Sincronizable>(res.vehiculo)
        cambios += almacen.leer { m -> MigracionPlan.migrar(m.itemsMantenimiento.values.toList(), res.vehiculo, ahora) }
        val nuevos = res.servicios.associateBy { it.id }
        cambios += res.servicios
        cambios += g.servicios.filter { it.id.startsWith("declarado:") && it.id !in nuevos }.map { it.copy(deletedAt = ahora, updatedAt = ahora) }
        cambios += res.avisos
        val avisosNuevos = res.avisos.map { it.id }.toSet()
        cambios += almacen.leer { m ->
            m.avisosSalud.values.filter { it.vehiculoId == g.vehiculo.id && it.id.startsWith("chequeo:") && it.deletedAt == null && it.id !in avisosNuevos }
        }.map { it.copy(deletedAt = ahora, updatedAt = ahora) }
        r.kmActual?.let { km ->
            if (km > g.odometroKm) cambios += LecturaOdometro(crearId(), g.vehiculo.id, ahora, km, OrigenOdometro.MANUAL, ahora)
        }
        almacen.guardar(*cambios.toTypedArray())
    }

    /** Avisos de salud vivos del vehículo, el más nuevo primero. */
    suspend fun avisos(vehiculoId: String = ID_ACCENT): List<AvisoSalud> = almacen.leer { m ->
        m.avisosSalud.values.filter { it.vehiculoId == vehiculoId && it.deletedAt == null }.sortedByDescending { it.fecha }
    }

    /** Pasa el garaje que el móvil guardaba antes de la fase 3 a la base local, con el id común del vehículo. */
    suspend fun importarGarajeViejo(viejo: DatosGaraje) {
        val ya = almacen.leer { m -> m.itemsMantenimiento.isNotEmpty() || m.servicios.isNotEmpty() }
        if (ya) return
        val id = ID_ACCENT
        val v = viejo.vehiculo.copy(id = id)
        // Los ítems del catálogo llevan el vehículo en el id: se rehacen con el id común.
        val nuevoIdItem = viejo.items.associate { it.id to (it.codigo?.let { c -> CatalogoMantenimiento.idItem(id, c, it.accion.orEmpty()) } ?: it.id) }
        val items = viejo.items.map { it.copy(id = nuevoIdItem.getValue(it.id), vehiculoId = id) }
        val servicios = viejo.servicios.map { s -> s.copy(vehiculoId = id, itemsHechos = s.itemsHechos.map { nuevoIdItem[it] ?: it }) }
        val ahora = reloj()
        val lectura = if (viejo.odometroKm > v.odometroInicialKm) {
            listOf(LecturaOdometro(crearId(), id, ahora, viejo.odometroKm, OrigenOdometro.MANUAL, ahora))
        } else emptyList()
        almacen.guardar(*(listOf<Sincronizable>(v) + items + servicios + lectura).toTypedArray())
    }
}
