package com.carobd.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Una tabla por entidad con el mismo esquema: id, usuario, vehiculo, updated_at, deleted_at y el JSON completo.
 * El servidor no interpreta el contenido; solo resuelve conflictos por updated_at y reparte cambios.
 * `recibido_en` es la hora del servidor al guardar: el pull se hace por esa columna, no por updated_at,
 * porque un cambio hecho sin conexión llega con un updated_at viejo y no debe perderse.
 * Guardar el JSON entero evita migraciones cada vez que una entidad gana un campo.
 */
abstract class TablaSync(nombre: String) : Table(nombre) {
    val id = varchar("id", 64)
    val usuario = varchar("usuario", 128).index()
    val vehiculoId = varchar("vehiculo_id", 64).index()
    val updatedAt = timestamp("updated_at").index()
    val deletedAt = timestamp("deleted_at").nullable()
    val recibidoEn = timestamp("recibido_en").index()
    val json = text("json")
    override val primaryKey = PrimaryKey(id)
}

object Vehiculos : TablaSync("vehiculos")
object Viajes : TablaSync("viajes")
object LecturasOdometro : TablaSync("lecturas_odometro")
object EventosFalla : TablaSync("eventos_falla")
object AvisosSalud : TablaSync("avisos_salud")
object Servicios : TablaSync("servicios")
object ItemsMantenimiento : TablaSync("items_mantenimiento")
object Tableros : TablaSync("tableros")

val todasLasTablas = listOf(Vehiculos, Viajes, LecturasOdometro, EventosFalla, AvisosSalud, Servicios, ItemsMantenimiento, Tableros)

object BaseDeDatos {
    fun desdeEntorno(): Database {
        val url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/carobd"
        val usuario = System.getenv("DATABASE_USER") ?: "carobd"
        val clave = System.getenv("DATABASE_PASSWORD") ?: "carobd"
        return conectar(url, usuario, clave)
    }

    fun conectar(url: String, usuario: String, clave: String, driver: String? = null): Database {
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = usuario
            password = clave
            driver?.let { driverClassName = it }
            maximumPoolSize = 5
        }
        val db = Database.connect(HikariDataSource(config))
        transaction(db) { SchemaUtils.createMissingTablesAndColumns(*todasLasTablas.toTypedArray()) }
        return db
    }
}
