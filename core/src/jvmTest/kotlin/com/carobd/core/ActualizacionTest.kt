package com.carobd.core

import com.carobd.core.actualizacion.ApkPublicada
import com.carobd.core.actualizacion.Releases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ActualizacionTest {
    // Trimmed shape of GET api.github.com/repos/<owner>/<repo>/releases (prereleases included).
    private val json = """
        [
          {"tag_name":"apk-prueba-4","name":"APK de prueba 4","draft":false,"prerelease":true,
           "assets":[{"name":"car-dashboard-131.apk","browser_download_url":"https://github.com/o/r/releases/download/apk-prueba-4/car-dashboard-131.apk"}]},
          {"tag_name":"apk-prueba-5","name":"APK de prueba 5","draft":true,"prerelease":true,
           "assets":[{"name":"car-dashboard-140.apk","browser_download_url":"https://x/draft.apk"}]},
          {"tag_name":"apk-prueba-3","name":"APK de prueba 3","draft":false,"prerelease":true,
           "assets":[{"name":"car-dashboard.apk","browser_download_url":"https://x/old.apk"}]}
        ]
    """.trimIndent()

    @Test
    fun eligeLaApkMasNuevaPublicada() {
        assertEquals(
            ApkPublicada(131, "APK de prueba 4", "https://github.com/o/r/releases/download/apk-prueba-4/car-dashboard-131.apk"),
            Releases.ultimaApk(json),
        )
    }

    @Test
    fun ignoraBorradoresYApksSinVersion() {
        // Only the draft (140) and an unversioned asset: nothing to offer.
        assertNull(Releases.ultimaApk(json.replace("car-dashboard-131.apk", "notas.txt")))
    }

    @Test
    fun soloOfreceSiEsMasNueva() {
        assertEquals(true, Releases.hayNueva(json, versionActual = 130))
        assertEquals(false, Releases.hayNueva(json, versionActual = 131))
    }
}
