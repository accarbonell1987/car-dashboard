package com.carobd.screen

import android.content.Context
import android.util.AtomicFile
import com.carobd.core.datos.Bitacora
import com.carobd.core.sync.AlmacenPersistente
import com.carobd.core.sync.ConfigNube
import com.carobd.core.sync.Nube
import com.carobd.core.sync.SyncJson
import com.carobd.core.sync.TransporteHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.util.UUID

/**
 * Los datos de la pantalla en el dispositivo: un JSON en la carpeta privada de la app (escritura atómica,
 * así un corte de energía al apagar el auto no deja el archivo a medias) y la conexión con la nube.
 */
class DatosLocales(context: Context) {
    private val prefs = context.getSharedPreferences("nube", Context.MODE_PRIVATE)
    private val archivo = AtomicFile(File(context.filesDir, "datos.json"))

    val almacen = AlmacenPersistente(
        leer = { runCatching { String(archivo.readFully(), Charsets.UTF_8) }.getOrNull() },
        escribir = { texto ->
            val salida = archivo.startWrite()
            try {
                salida.write(texto.toByteArray(Charsets.UTF_8))
                archivo.finishWrite(salida)
            } catch (e: Exception) {
                archivo.failWrite(salida)
            }
        },
    )
    val bitacora = Bitacora(almacen)

    private val cliente = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

    /** Id estable de esta pantalla, para que el servidor sepa de dónde vino cada cambio. */
    private val dispositivoId: String =
        prefs.getString("dispositivo", null) ?: ("pantalla-" + UUID.randomUUID()).also { prefs.edit().putString("dispositivo", it).apply() }

    val nube = Nube(almacen, dispositivoId, leerConfig()) { c -> TransporteHttp(c.url, c.token, cliente) }

    private fun leerConfig(): ConfigNube =
        prefs.getString("config", null)?.let { runCatching { SyncJson.json.decodeFromString(ConfigNube.serializer(), it) }.getOrNull() }
            ?: ConfigNube()

    suspend fun configurar(c: ConfigNube) {
        prefs.edit().putString("config", SyncJson.json.encodeToString(ConfigNube.serializer(), c)).apply()
        nube.configurar(c)
    }
}
