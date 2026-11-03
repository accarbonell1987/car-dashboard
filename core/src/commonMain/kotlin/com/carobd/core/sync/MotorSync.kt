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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Lo que cada app guarda localmente. La pantalla y el móvil lo implementan sobre su base de datos;
 * [AlmacenEnMemoria] sirve de referencia y para pruebas.
 *
 * Regla: todo cambio local se guarda primero aquí y queda "pendiente" hasta que el servidor lo acepta.
 * Así nada se pierde sin conexión.
 */
interface AlmacenLocal {
    suspend fun pendientes(): PaqueteCambios
    suspend fun marcarEnviados(ids: Collection<String>)
    /** Aplica cambios remotos con la regla de conflicto ([resolver]); no los marca como pendientes. */
    suspend fun aplicarRemotos(cambios: PaqueteCambios)
    suspend fun ultimoSync(): Instant?
    suspend fun guardarUltimoSync(t: Instant)
}

/** Cómo llega la petición al servidor (HTTP en las apps, directo en pruebas). */
fun interface TransporteSync {
    suspend fun sincronizar(req: SyncRequest): SyncResponse
}

sealed interface ResultadoSync {
    data class Ok(val enviados: Int, val recibidos: Int, val servidorAhora: Instant) : ResultadoSync
    /** Sin red, servidor caído, token inválido...: los pendientes siguen ahí para el próximo intento. */
    data class Fallo(val causa: Throwable) : ResultadoSync
}

/** Un ciclo de sincronización: sube pendientes, baja lo nuevo y recuerda hasta cuándo quedó al día. */
class MotorSync(
    private val almacen: AlmacenLocal,
    private val transporte: TransporteSync,
    private val dispositivoId: String,
) {
    suspend fun sincronizar(): ResultadoSync {
        val pendientes = almacen.pendientes()
        val req = SyncRequest(dispositivoId, almacen.ultimoSync(), pendientes)
        val resp = try {
            transporte.sincronizar(req)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ResultadoSync.Fallo(e)
        }
        // Solo se dejan de reenviar los que el servidor confirmó.
        almacen.marcarEnviados(resp.aceptados)
        almacen.aplicarRemotos(resp.cambios)
        almacen.guardarUltimoSync(resp.servidorAhora)
        return ResultadoSync.Ok(pendientes.total(), resp.cambios.total(), resp.servidorAhora)
    }
}

/**
 * Almacén en memoria: un mapa por entidad más el conjunto de ids pendientes.
 * Lo usan las pruebas y sirve de modelo para las implementaciones sobre base de datos.
 */
class AlmacenEnMemoria : AlmacenLocal {
    val vehiculos = mutableMapOf<String, Vehiculo>()
    val viajes = mutableMapOf<String, Viaje>()
    val lecturasOdometro = mutableMapOf<String, LecturaOdometro>()
    val eventosFalla = mutableMapOf<String, EventoFalla>()
    val avisosSalud = mutableMapOf<String, AvisoSalud>()
    val servicios = mutableMapOf<String, Servicio>()
    val itemsMantenimiento = mutableMapOf<String, ItemMantenimiento>()
    val tableros = mutableMapOf<String, Tablero>()
    private val sucios = mutableSetOf<String>()
    private var ultimo: Instant? = null

    /** Cambio hecho en este dispositivo: se guarda y queda pendiente de subir. */
    fun guardar(e: Sincronizable) {
        @Suppress("UNCHECKED_CAST")
        when (e) {
            is Vehiculo -> vehiculos[e.id] = e
            is Viaje -> viajes[e.id] = e
            is LecturaOdometro -> lecturasOdometro[e.id] = e
            is EventoFalla -> eventosFalla[e.id] = e
            is AvisoSalud -> avisosSalud[e.id] = e
            is Servicio -> servicios[e.id] = e
            is ItemMantenimiento -> itemsMantenimiento[e.id] = e
            is Tablero -> tableros[e.id] = e
            else -> error("Entidad desconocida: ${e::class}")
        }
        sucios += e.id
    }

    val cantidadPendientes: Int get() = sucios.size

    override suspend fun pendientes() = PaqueteCambios(
        vehiculos = vehiculos.values.filter { it.id in sucios },
        viajes = viajes.values.filter { it.id in sucios },
        lecturasOdometro = lecturasOdometro.values.filter { it.id in sucios },
        eventosFalla = eventosFalla.values.filter { it.id in sucios },
        avisosSalud = avisosSalud.values.filter { it.id in sucios },
        servicios = servicios.values.filter { it.id in sucios },
        itemsMantenimiento = itemsMantenimiento.values.filter { it.id in sucios },
        tableros = tableros.values.filter { it.id in sucios },
    )

    override suspend fun marcarEnviados(ids: Collection<String>) {
        sucios -= ids.toSet()
    }

    override suspend fun aplicarRemotos(cambios: PaqueteCambios) {
        fun <T : Sincronizable> mezclar(destino: MutableMap<String, T>, remotos: List<T>) {
            for (r in remotos) {
                val local = destino[r.id]
                val gana = resolver(local, r)
                // Si el local pendiente es más nuevo, se queda y se sigue subiendo.
                if (gana === r) {
                    destino[r.id] = r
                    sucios -= r.id
                }
            }
        }
        mezclar(vehiculos, cambios.vehiculos)
        mezclar(viajes, cambios.viajes)
        mezclar(lecturasOdometro, cambios.lecturasOdometro)
        mezclar(eventosFalla, cambios.eventosFalla)
        mezclar(avisosSalud, cambios.avisosSalud)
        mezclar(servicios, cambios.servicios)
        mezclar(itemsMantenimiento, cambios.itemsMantenimiento)
        mezclar(tableros, cambios.tableros)
    }

    override suspend fun ultimoSync(): Instant? = ultimo
    override suspend fun guardarUltimoSync(t: Instant) {
        ultimo = t
    }

    /** Todo el contenido, para guardarlo en disco. */
    fun foto(): FotoAlmacen = FotoAlmacen(
        datos = PaqueteCambios(
            vehiculos.values.toList(), viajes.values.toList(), lecturasOdometro.values.toList(), eventosFalla.values.toList(),
            avisosSalud.values.toList(), servicios.values.toList(), itemsMantenimiento.values.toList(), tableros.values.toList(),
        ),
        pendientes = sucios.toSet(),
        ultimoSync = ultimo,
    )

    companion object {
        fun desde(foto: FotoAlmacen): AlmacenEnMemoria = AlmacenEnMemoria().apply {
            val d = foto.datos
            d.vehiculos.forEach { vehiculos[it.id] = it }
            d.viajes.forEach { viajes[it.id] = it }
            d.lecturasOdometro.forEach { lecturasOdometro[it.id] = it }
            d.eventosFalla.forEach { eventosFalla[it.id] = it }
            d.avisosSalud.forEach { avisosSalud[it.id] = it }
            d.servicios.forEach { servicios[it.id] = it }
            d.itemsMantenimiento.forEach { itemsMantenimiento[it.id] = it }
            d.tableros.forEach { tableros[it.id] = it }
            sucios += foto.pendientes
            ultimo = foto.ultimoSync
        }
    }
}

@Serializable
data class FotoAlmacen(
    val datos: PaqueteCambios,
    val pendientes: Set<String> = emptySet(),
    val ultimoSync: Instant? = null,
)

/**
 * Almacén que sobrevive a reinicios: guarda una foto en JSON después de cada cambio.
 * `leer` y `escribir` los pone cada plataforma (SharedPreferences, NSUserDefaults, un archivo...).
 * Para el volumen de un dueño (cientos de viajes al año) un JSON alcanza y evita migraciones de base de datos.
 */
class AlmacenPersistente(
    leer: () -> String?,
    private val escribir: (String) -> Unit,
) : AlmacenLocal {
    private val candado = Mutex()
    private val mem: AlmacenEnMemoria = leer()
        ?.let { runCatching { SyncJson.json.decodeFromString(FotoAlmacen.serializer(), it) }.getOrNull() }
        ?.let { AlmacenEnMemoria.desde(it) }
        ?: AlmacenEnMemoria()

    private fun persistir() = escribir(SyncJson.json.encodeToString(FotoAlmacen.serializer(), mem.foto()))

    /** Lectura del contenido actual (copia), para armar pantallas. */
    suspend fun <R> leer(bloque: (AlmacenEnMemoria) -> R): R = candado.withLock { bloque(mem) }

    /** Guarda cambios hechos en este dispositivo; quedan pendientes de subir. */
    suspend fun guardar(vararg entidades: Sincronizable) = candado.withLock {
        entidades.forEach { mem.guardar(it) }
        persistir()
    }

    suspend fun cantidadPendientes(): Int = candado.withLock { mem.cantidadPendientes }

    override suspend fun pendientes(): PaqueteCambios = candado.withLock { mem.pendientes() }
    override suspend fun marcarEnviados(ids: Collection<String>) = candado.withLock { mem.marcarEnviados(ids); persistir() }
    override suspend fun aplicarRemotos(cambios: PaqueteCambios) = candado.withLock { mem.aplicarRemotos(cambios); persistir() }
    override suspend fun ultimoSync(): Instant? = candado.withLock { mem.ultimoSync() }
    override suspend fun guardarUltimoSync(t: Instant) = candado.withLock { mem.guardarUltimoSync(t); persistir() }
}
