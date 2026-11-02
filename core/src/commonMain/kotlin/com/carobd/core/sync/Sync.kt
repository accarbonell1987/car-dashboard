package com.carobd.core.sync

import com.carobd.core.model.AvisoSalud
import com.carobd.core.model.EventoFalla
import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.LecturaOdometro
import com.carobd.core.model.Servicio
import com.carobd.core.model.Sincronizable
import com.carobd.core.model.Tablero
import com.carobd.core.model.Vehiculo
import com.carobd.core.model.Viaje
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Contrato de sincronización entre dispositivos y servidor.
 *
 * El dispositivo manda todo lo que cambió desde su último sync (`push`) y recibe
 * lo que cambió en el servidor desde `desde` (`pull`). Subir es idempotente:
 * reenviar el mismo paquete no duplica nada, porque cada registro va por `id`.
 */
object SyncJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class PaqueteCambios(
    val vehiculos: List<Vehiculo> = emptyList(),
    val viajes: List<Viaje> = emptyList(),
    val lecturasOdometro: List<LecturaOdometro> = emptyList(),
    val eventosFalla: List<EventoFalla> = emptyList(),
    val avisosSalud: List<AvisoSalud> = emptyList(),
    val servicios: List<Servicio> = emptyList(),
    val itemsMantenimiento: List<ItemMantenimiento> = emptyList(),
    val tableros: List<Tablero> = emptyList(),
) {
    val vacio: Boolean
        get() = vehiculos.isEmpty() && viajes.isEmpty() && lecturasOdometro.isEmpty() &&
            eventosFalla.isEmpty() && avisosSalud.isEmpty() && servicios.isEmpty() &&
            itemsMantenimiento.isEmpty() && tableros.isEmpty()

    fun total(): Int = vehiculos.size + viajes.size + lecturasOdometro.size + eventosFalla.size +
        avisosSalud.size + servicios.size + itemsMantenimiento.size + tableros.size
}

/** Lo que manda el dispositivo: sus cambios pendientes y desde cuándo quiere recibir. */
@Serializable
data class SyncRequest(
    val dispositivoId: String,
    val desde: Instant?,
    val cambios: PaqueteCambios,
)

/** Lo que responde el servidor: qué ids aceptó y los cambios ajenos desde `desde`. */
@Serializable
data class SyncResponse(
    val aceptados: List<String>,
    val cambios: PaqueteCambios,
    val servidorAhora: Instant,
)

/** Regla única de conflicto: gana el `updatedAt` más reciente; en empate, el que ya estaba. */
fun <T : Sincronizable> resolver(local: T?, remoto: T): T =
    if (local == null || remoto.updatedAt > local.updatedAt) remoto else local

/** Aplica un paquete remoto sobre un mapa local por id, devolviendo el mapa resultante. */
fun <T : Sincronizable> aplicar(locales: Map<String, T>, remotos: List<T>): Map<String, T> {
    val resultado = locales.toMutableMap()
    for (r in remotos) resultado[r.id] = resolver(resultado[r.id], r)
    return resultado
}
