package com.carobd.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Dónde está el servidor propio (el VPS) y con qué token entra este dispositivo. */
@Serializable
data class ConfigNube(val url: String = "", val token: String = "") {
    val lista: Boolean get() = url.isNotBlank() && token.isNotBlank()
}

/** Lo que muestran las apps: cuánto falta subir, cuándo fue la última vez y si algo falló. */
data class EstadoNube(
    val configurada: Boolean = false,
    val pendientes: Int = 0,
    val ultimoSync: Instant? = null,
    val sincronizando: Boolean = false,
    val error: String? = null,
)

/**
 * La conexión con la nube que comparten la pantalla y el móvil.
 * Sin configuración, o sin red, todo sigue funcionando local y los cambios esperan el próximo intento.
 */
class Nube(
    private val almacen: AlmacenPersistente,
    private val dispositivoId: String,
    config: ConfigNube,
    private val crearTransporte: (ConfigNube) -> TransporteSync,
) {
    private val candado = Mutex()
    private var motor: MotorSync? = null
    var config: ConfigNube = config
        private set

    private val _estado = MutableStateFlow(EstadoNube(configurada = config.lista))
    val estado: StateFlow<EstadoNube> = _estado.asStateFlow()

    init {
        motor = armar(config)
    }

    private fun armar(c: ConfigNube) = if (c.lista) MotorSync(almacen, crearTransporte(c), dispositivoId) else null

    suspend fun configurar(c: ConfigNube) {
        candado.withLock {
            config = c
            motor = armar(c)
        }
        _estado.update { it.copy(configurada = c.lista, error = null) }
        refrescar()
    }

    /** Actualiza pendientes y fecha sin conectarse (después de guardar algo). */
    suspend fun refrescar() {
        val p = almacen.cantidadPendientes()
        val u = almacen.ultimoSync()
        _estado.update { it.copy(pendientes = p, ultimoSync = u) }
    }

    /** Un intento de sincronización. Si ya hay uno en curso, espera a que termine. Devuelve null si no hay nube. */
    suspend fun sincronizar(): ResultadoSync? = candado.withLock {
        val m = motor ?: return@withLock null.also { refrescar() }
        _estado.update { it.copy(sincronizando = true) }
        val r = m.sincronizar()
        _estado.update {
            it.copy(sincronizando = false, error = (r as? ResultadoSync.Fallo)?.causa?.let(::describir))
        }
        refrescar()
        r
    }

    private fun describir(e: Throwable): String {
        val msg = e.message.orEmpty()
        val tipo = e::class.simpleName.orEmpty()
        return when {
            "401" in msg -> "El token no es válido"
            "El servidor respondió" in msg -> msg.take(120)
            // Errores de red de cada motor (OkHttp, Darwin, CIO): en inglés y con detalles que no ayudan.
            "imeout" in msg || "imeout" in tipo -> "El servidor no respondió a tiempo"
            else -> "No se pudo conectar con el servidor"
        }
    }
}
