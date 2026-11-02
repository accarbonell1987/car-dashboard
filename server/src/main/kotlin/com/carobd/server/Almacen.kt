package com.carobd.server

import com.carobd.core.model.AvisoSalud
import com.carobd.core.model.EventoFalla
import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.LecturaOdometro
import com.carobd.core.model.Servicio
import com.carobd.core.model.Sincronizable
import com.carobd.core.model.Tablero
import com.carobd.core.model.Vehiculo
import com.carobd.core.model.Viaje
import com.carobd.core.sync.PaqueteCambios
import com.carobd.core.sync.SyncJson
import com.carobd.core.sync.SyncRequest
import com.carobd.core.sync.SyncResponse
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Aplica un paquete de cambios y devuelve lo que cambió para ese usuario desde `desde`. */
class Almacen(private val db: Database) {

    fun sincronizar(usuario: String, req: SyncRequest): SyncResponse = transaction(db) {
        val aceptados = mutableListOf<String>()
        val c = req.cambios
        aceptados += guardar(Vehiculos, usuario, c.vehiculos, Vehiculo.serializer()) { it.id }
        aceptados += guardar(Viajes, usuario, c.viajes, Viaje.serializer()) { it.vehiculoId }
        aceptados += guardar(LecturasOdometro, usuario, c.lecturasOdometro, LecturaOdometro.serializer()) { it.vehiculoId }
        aceptados += guardar(EventosFalla, usuario, c.eventosFalla, EventoFalla.serializer()) { it.vehiculoId }
        aceptados += guardar(AvisosSalud, usuario, c.avisosSalud, AvisoSalud.serializer()) { it.vehiculoId }
        aceptados += guardar(Servicios, usuario, c.servicios, Servicio.serializer()) { it.vehiculoId }
        aceptados += guardar(ItemsMantenimiento, usuario, c.itemsMantenimiento, ItemMantenimiento.serializer()) { it.vehiculoId }
        aceptados += guardar(Tableros, usuario, c.tableros, Tablero.serializer()) { it.vehiculoId }

        val desde = req.desde
        SyncResponse(
            aceptados = aceptados,
            cambios = PaqueteCambios(
                vehiculos = leer(Vehiculos, usuario, desde, Vehiculo.serializer()),
                viajes = leer(Viajes, usuario, desde, Viaje.serializer()),
                lecturasOdometro = leer(LecturasOdometro, usuario, desde, LecturaOdometro.serializer()),
                eventosFalla = leer(EventosFalla, usuario, desde, EventoFalla.serializer()),
                avisosSalud = leer(AvisosSalud, usuario, desde, AvisoSalud.serializer()),
                servicios = leer(Servicios, usuario, desde, Servicio.serializer()),
                itemsMantenimiento = leer(ItemsMantenimiento, usuario, desde, ItemMantenimiento.serializer()),
                tableros = leer(Tableros, usuario, desde, Tablero.serializer()),
            ),
            servidorAhora = Clock.System.now(),
        )
    }

    /** Upsert por id: inserta si no existe, reemplaza si el entrante es más reciente. Devuelve los ids aceptados. */
    private fun <T : Sincronizable> guardar(
        tabla: TablaSync,
        usuario: String,
        entrantes: List<T>,
        serializer: KSerializer<T>,
        vehiculoDe: (T) -> String,
    ): List<String> = entrantes.mapNotNull { e ->
        val existente = tabla.selectAll().where { tabla.id eq e.id }.firstOrNull()
        when {
            existente == null -> {
                tabla.insert {
                    it[id] = e.id
                    it[this.usuario] = usuario
                    it[vehiculoId] = vehiculoDe(e)
                    it[updatedAt] = e.updatedAt.toJavaInstant()
                    it[deletedAt] = e.deletedAt?.toJavaInstant()
                    it[json] = SyncJson.json.encodeToString(serializer, e)
                }
                e.id
            }
            existente[tabla.usuario] != usuario -> null // id ajeno: se ignora en silencio
            existente[tabla.updatedAt].toKotlinInstant() < e.updatedAt -> {
                tabla.update({ tabla.id eq e.id }) {
                    it[updatedAt] = e.updatedAt.toJavaInstant()
                    it[deletedAt] = e.deletedAt?.toJavaInstant()
                    it[json] = SyncJson.json.encodeToString(serializer, e)
                }
                e.id
            }
            else -> e.id // ya teníamos uno igual o más nuevo: aceptado igual, es idempotente
        }
    }

    private fun <T : Sincronizable> leer(tabla: TablaSync, usuario: String, desde: Instant?, serializer: KSerializer<T>): List<T> {
        val consulta = tabla.selectAll().where {
            if (desde == null) tabla.usuario eq usuario
            else (tabla.usuario eq usuario) and (tabla.updatedAt greater desde.toJavaInstant())
        }
        return consulta.map { SyncJson.json.decodeFromString(serializer, it[tabla.json]) }
    }
}
