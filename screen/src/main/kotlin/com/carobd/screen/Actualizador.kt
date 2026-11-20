package com.carobd.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.carobd.core.actualizacion.ApkPublicada
import com.carobd.core.actualizacion.Releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface EstadoActualizacion {
    data object AlDia : EstadoActualizacion
    data object Buscando : EstadoActualizacion
    data class Disponible(val apk: ApkPublicada) : EstadoActualizacion
    data class Descargando(val apk: ApkPublicada, val progreso: Float) : EstadoActualizacion
    data class Error(val mensaje: String) : EstadoActualizacion
}

/**
 * Self-update from the GitHub releases. Android always asks the driver to confirm an install
 * (only a device-owner app installs silently), so this finds, downloads and opens the installer: one tap.
 * Updates only install over an APK signed with the same key.
 */
object Actualizador {
    private val _estado = MutableStateFlow<EstadoActualizacion>(EstadoActualizacion.AlDia)
    val estado: StateFlow<EstadoActualizacion> = _estado.asStateFlow()

    fun versionInstalada(context: Context): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    suspend fun buscar(context: Context) {
        if (_estado.value is EstadoActualizacion.Descargando) return
        _estado.value = EstadoActualizacion.Buscando
        _estado.value = runCatching {
            val json = withContext(Dispatchers.IO) {
                val conexion = URL(Releases.URL).openConnection() as HttpURLConnection
                conexion.setRequestProperty("Accept", "application/vnd.github+json")
                conexion.connectTimeout = 10_000
                conexion.readTimeout = 10_000
                conexion.inputStream.bufferedReader().use { it.readText() }
            }
            val apk = Releases.ultimaApk(json)
            if (apk != null && apk.versionCode > versionInstalada(context)) EstadoActualizacion.Disponible(apk) else EstadoActualizacion.AlDia
        }.getOrElse { EstadoActualizacion.Error("No se pudo consultar GitHub: ${it.message}") }
    }

    /** Downloads the APK into the cache and opens the system installer. */
    suspend fun instalar(context: Context, apk: ApkPublicada) {
        // Android 8+: the driver has to allow this app to install apps once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val archivo = File(context.cacheDir, "actualizaciones/car-dashboard-${apk.versionCode}.apk")
        _estado.value = EstadoActualizacion.Descargando(apk, 0f)
        val bajado = runCatching {
            withContext(Dispatchers.IO) {
                archivo.parentFile?.mkdirs()
                val conexion = URL(apk.url).openConnection() as HttpURLConnection
                conexion.instanceFollowRedirects = true
                val total = conexion.contentLengthLong.takeIf { it > 0 }
                conexion.inputStream.use { entrada ->
                    archivo.outputStream().use { salida ->
                        val buffer = ByteArray(64 * 1024)
                        var leidos = 0L
                        while (true) {
                            val n = entrada.read(buffer)
                            if (n < 0) break
                            salida.write(buffer, 0, n)
                            leidos += n
                            if (total != null) _estado.value = EstadoActualizacion.Descargando(apk, leidos.toFloat() / total)
                        }
                    }
                }
            }
        }
        if (bajado.isFailure) {
            _estado.value = EstadoActualizacion.Error("No se pudo descargar: ${bajado.exceptionOrNull()?.message}")
            return
        }
        _estado.value = EstadoActualizacion.Disponible(apk)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.archivos", archivo)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
