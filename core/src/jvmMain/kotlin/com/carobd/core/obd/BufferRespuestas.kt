package com.carobd.core.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Junta los bytes que llegan en trozos (notificaciones BLE de ~20 bytes) y entrega respuestas completas
 * hasta el prompt '>', con la misma semántica que [TransporteFlujos.leer]: si vence el tiempo devuelve
 * lo acumulado con `completa = false` y lo consume; lo que llega después de un '>' queda para la próxima lectura.
 * [recibir] y [fallar] se llaman desde el hilo de los callbacks de Bluetooth; [leer] desde una corrutina.
 */
class BufferRespuestas {
    private val pendiente = StringBuilder()
    private val aviso = Channel<Unit>(Channel.CONFLATED)
    private var error: IOException? = null

    fun recibir(bytes: ByteArray) {
        synchronized(this) { for (b in bytes) pendiente.append((b.toInt() and 0xFF).toChar()) }
        aviso.trySend(Unit)
    }

    /** El enlace se cayó: la lectura en curso y las siguientes fallan, y Elm327 reconecta. */
    fun fallar(causa: IOException) {
        synchronized(this) { error = causa }
        aviso.trySend(Unit)
    }

    /** Tira lo recibido y no leído (restos de ATMA, prompts tardíos). */
    fun descartar() {
        synchronized(this) { pendiente.setLength(0) }
    }

    suspend fun leer(timeoutMs: Long): Respuesta {
        val fin = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            synchronized(this) {
                error?.let { throw it }
                val prompt = pendiente.indexOf(">")
                if (prompt >= 0) {
                    // Lo que sigue al '>' queda para la próxima lectura: puede ser la respuesta buena tras un prompt tardío.
                    val texto = pendiente.substring(0, prompt)
                    pendiente.delete(0, prompt + 1)
                    return Respuesta(texto, true)
                }
            }
            val restante = (fin - System.nanoTime()) / 1_000_000
            if (restante <= 0 || withTimeoutOrNull(restante) { aviso.receive() } == null) {
                synchronized(this) {
                    error?.let { throw it }
                    val texto = pendiente.toString()
                    pendiente.setLength(0)
                    return Respuesta(texto, false)
                }
            }
        }
    }

    companion object {
        /** Trozos de como mucho [tamano] bytes para escribir en una característica (MTU - 3; 20 sin negociar). */
        fun fragmentar(datos: ByteArray, tamano: Int): List<ByteArray> {
            require(tamano > 0) { "tamaño de fragmento inválido: $tamano" }
            return (datos.indices step tamano).map { datos.copyOfRange(it, minOf(it + tamano, datos.size)) }
        }
    }
}
