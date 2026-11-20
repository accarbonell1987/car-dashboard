package com.carobd.core.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Transporte sobre un par de streams bloqueantes (socket TCP o RFCOMM). Lee con `available()` para poder
 * cortar por tiempo: el InputStream de Bluetooth no tiene timeout propio.
 */
abstract class TransporteFlujos : TransporteElm {
    private var entrada: InputStream? = null
    private var salida: OutputStream? = null

    /** Abre la conexión y devuelve sus streams. Bloqueante; corre en Dispatchers.IO. */
    protected abstract fun conectar(): Pair<InputStream, OutputStream>
    protected abstract fun desconectar()

    override suspend fun abrir() = withContext(Dispatchers.IO) {
        val (e, s) = conectar()
        entrada = e
        salida = s
    }

    override suspend fun escribir(texto: String) = withContext(Dispatchers.IO) {
        val s = salida ?: throw IOException("transporte cerrado")
        s.write(texto.toByteArray(Charsets.ISO_8859_1))
        s.flush()
    }

    override suspend fun leer(timeoutMs: Long): Respuesta = withContext(Dispatchers.IO) {
        val e = entrada ?: throw IOException("transporte cerrado")
        val texto = StringBuilder()
        val buffer = ByteArray(1024)
        val fin = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < fin) {
            val disponibles = e.available()
            if (disponibles <= 0) {
                delay(5)
                continue
            }
            val n = e.read(buffer, 0, minOf(disponibles, buffer.size))
            if (n < 0) throw IOException("conexión cerrada por el adaptador")
            for (i in 0 until n) {
                val c = (buffer[i].toInt() and 0xFF).toChar()
                // ponytail: lo que llegue después del '>' se descarta; el ELM no manda nada hasta el próximo comando.
                if (c == '>') return@withContext Respuesta(texto.toString(), true)
                texto.append(c)
            }
        }
        Respuesta(texto.toString(), false)
    }

    override fun cerrar() {
        entrada = null
        salida = null
        desconectar()
    }
}

/** ELM327 por TCP: el emulador de obd/emulador o un adaptador wifi. */
class TransporteTcp(private val host: String, private val puerto: Int, private val timeoutConexionMs: Int = 5000) : TransporteFlujos() {
    private var socket: Socket? = null

    override fun conectar(): Pair<InputStream, OutputStream> {
        val s = Socket()
        s.connect(InetSocketAddress(host, puerto), timeoutConexionMs)
        s.tcpNoDelay = true
        socket = s
        return s.getInputStream() to s.getOutputStream()
    }

    override fun desconectar() {
        runCatching { socket?.close() }
        socket = null
    }
}
