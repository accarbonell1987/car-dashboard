package com.carobd.core.actualizacion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An APK attached to a GitHub release, named `car-dashboard-<versionCode>.apk`. */
data class ApkPublicada(val versionCode: Int, val nombre: String, val url: String)

/** Reads the repo's releases list and finds the newest installable APK. Drafts are skipped; prereleases count. */
object Releases {
    const val URL = "https://api.github.com/repos/accarbonell1987/car-dashboard/releases?per_page=20"
    private val patron = Regex("""car-dashboard-(\d+)\.apk""")

    fun ultimaApk(json: String): ApkPublicada? =
        Json.parseToJsonElement(json).jsonArray
            .map { it.jsonObject }
            .filter { it["draft"]?.jsonPrimitive?.boolean != true }
            .flatMap { release ->
                val nombre = release["name"]?.jsonPrimitive?.content ?: release.getValue("tag_name").jsonPrimitive.content
                release["assets"]?.jsonArray.orEmpty().mapNotNull { asset ->
                    val a = asset.jsonObject
                    val version = patron.matchEntire(a.getValue("name").jsonPrimitive.content)?.groupValues?.get(1)?.toInt()
                    version?.let { ApkPublicada(it, nombre, a.getValue("browser_download_url").jsonPrimitive.content) }
                }
            }
            .maxByOrNull { it.versionCode }

    fun hayNueva(json: String, versionActual: Int): Boolean = (ultimaApk(json)?.versionCode ?: 0) > versionActual
}
