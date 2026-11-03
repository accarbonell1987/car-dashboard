package com.carobd.movil

import com.carobd.core.datos.Bitacora
import com.carobd.core.model.DatosGaraje
import com.carobd.core.model.nuevoId
import com.carobd.core.sync.AlmacenPersistente
import com.carobd.core.sync.ConfigNube
import com.carobd.core.sync.Nube
import com.carobd.core.sync.SyncJson
import com.carobd.core.sync.TransporteHttp
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/**
 * Los datos del móvil: la base local (un JSON en SharedPreferences en Android, NSUserDefaults en iOS)
 * y la conexión con la nube. Las reglas (plan, servicios, odómetro, sincronización) están en core.
 */
class DatosMovil(private val settings: Settings = Settings()) {
    val almacen = AlmacenPersistente(
        leer = { settings.getStringOrNull(CLAVE_DATOS) },
        escribir = { settings.putString(CLAVE_DATOS, it) },
    )
    val bitacora = Bitacora(almacen)

    // Sin motor explícito: Ktor usa el que trae cada plataforma (OkHttp en Android y escritorio, Darwin en iOS).
    private val cliente = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

    private val dispositivoId: String =
        settings.getStringOrNull(CLAVE_DISPOSITIVO) ?: ("movil-" + nuevoId()).also { settings.putString(CLAVE_DISPOSITIVO, it) }

    val nube = Nube(almacen, dispositivoId, leerConfig()) { c -> TransporteHttp(c.url, c.token, cliente) }

    private fun leerConfig(): ConfigNube =
        settings.getStringOrNull(CLAVE_NUBE)?.let { runCatching { SyncJson.json.decodeFromString(ConfigNube.serializer(), it) }.getOrNull() }
            ?: ConfigNube()

    suspend fun configurar(c: ConfigNube) {
        settings.putString(CLAVE_NUBE, SyncJson.json.encodeToString(ConfigNube.serializer(), c))
        nube.configurar(c)
    }

    /** Carga el garaje. La primera vez después de actualizar, pasa lo que había guardado la versión anterior. */
    suspend fun iniciar(): DatosGaraje {
        settings.getStringOrNull(CLAVE_VIEJA)
            ?.let { runCatching { SyncJson.json.decodeFromString(DatosGaraje.serializer(), it) }.getOrNull() }
            ?.let { viejo ->
                bitacora.importarGarajeViejo(viejo)
                settings.remove(CLAVE_VIEJA)
            }
        return bitacora.garaje()
    }

    private companion object {
        const val CLAVE_DATOS = "datos"
        const val CLAVE_VIEJA = "garaje"
        const val CLAVE_DISPOSITIVO = "dispositivo"
        const val CLAVE_NUBE = "nube"
    }
}
