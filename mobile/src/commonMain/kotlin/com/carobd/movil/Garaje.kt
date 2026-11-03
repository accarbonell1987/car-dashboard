package com.carobd.movil

import com.carobd.core.model.DatosGaraje
import com.carobd.core.model.Garaje
import com.carobd.core.sync.SyncJson
import com.russhwolf.settings.Settings
import kotlinx.datetime.Clock

/**
 * Guarda el garaje en el almacenamiento de la plataforma: SharedPreferences en Android, NSUserDefaults en iOS.
 * Las reglas (plan, servicios, odómetro) están en core.
 */
class GarajeLocal(private val settings: Settings = Settings()) {

    fun cargar(): DatosGaraje =
        settings.getStringOrNull(CLAVE)
            ?.let { runCatching { SyncJson.json.decodeFromString(DatosGaraje.serializer(), it) }.getOrNull() }
            ?: Garaje.inicialAccent(Clock.System.now()).also(::guardar)

    fun guardar(d: DatosGaraje) {
        settings.putString(CLAVE, SyncJson.json.encodeToString(DatosGaraje.serializer(), d))
    }

    private companion object {
        const val CLAVE = "garaje"
    }
}
