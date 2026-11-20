package com.carobd.core

import com.carobd.core.obd.BufferRespuestas
import com.carobd.core.obd.Decodificador
import com.carobd.core.obd.Respuesta
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** Lo que el TransporteBle hace con las notificaciones de FFF1, sin Bluetooth: así llega del clon MOCATEC. */
class BufferRespuestasTest {
    private fun bytes(texto: String) = texto.toByteArray(Charsets.ISO_8859_1)

    /** Parte [texto] en notificaciones de 20 bytes, como sin MTU negociado. */
    private fun notificar(buffer: BufferRespuestas, texto: String, tamano: Int = 20) =
        BufferRespuestas.fragmentar(bytes(texto), tamano).forEach(buffer::recibir)

    @Test
    fun fragmentaEscriturasPorTamano() {
        val trozos = BufferRespuestas.fragmentar(bytes("ATCRA690\rATMA\r0123456789ABCDEF"), 20)
        assertEquals(listOf(20, 10), trozos.map { it.size })
        assertEquals("ATCRA690\rATMA\r012345", String(trozos[0], Charsets.ISO_8859_1))
        assertEquals(listOf(5), BufferRespuestas.fragmentar(bytes("010C\r"), 182).map { it.size })
        assertTrue(BufferRespuestas.fragmentar(ByteArray(0), 20).isEmpty())
        assertArrayEquals(bytes("0123456789"), BufferRespuestas.fragmentar(bytes("0123456789"), 3).reduce { a, b -> a + b })
    }

    @Test
    fun atzConByteBasuraYPromptEnSuPropiaNotificacion() = runBlocking<Unit> {
        val buffer = BufferRespuestas()
        buffer.recibir(byteArrayOf(0xFC.toByte()))
        notificar(buffer, "\r\rELM327 v1.5\r\r")
        buffer.recibir(bytes(">"))
        val r = buffer.leer(1000)
        assertTrue(r.completa)
        assertEquals("ü\r\rELM327 v1.5\r\r", r.texto)
    }

    @Test
    fun vinPartidoEnTrozosDe20Bytes() = runBlocking<Unit> {
        val buffer = BufferRespuestas()
        val lector = async { buffer.leer(2000) }
        // Respuesta real del emulador a 0902 con ATS1, 75 bytes en 4 notificaciones que llegan de a poco.
        for (trozo in BufferRespuestas.fragmentar(bytes("014\r0: 49 02 01 4B 4D 48\r1: 43 55 34 31 55 41 47\r2: 55 30 30 30 30 30 30\r\r>"), 20)) {
            delay(10)
            buffer.recibir(trozo)
        }
        val r = lector.await()
        assertTrue(r.completa)
        assertEquals("KMHCU41UAGU000000", Decodificador.vin(r.texto))
    }

    @Test
    fun respuestasSeguidasNoSeMezclan() = runBlocking<Unit> {
        val buffer = BufferRespuestas()
        notificar(buffer, "41 0C 0C 32\r\r>")
        assertEquals(Respuesta("41 0C 0C 32\r\r", true), buffer.leer(500))
        notificar(buffer, "NO DATA\r\r>")
        assertEquals(Respuesta("NO DATA\r\r", true), buffer.leer(500))
    }

    @Test
    fun timeoutDevuelveLoAcumuladoYLoConsume() = runBlocking<Unit> {
        val buffer = BufferRespuestas()
        notificar(buffer, "0000410000002C00\r4000400000000800\r") // ATMA en curso: no hay prompt
        val inicio = System.nanoTime()
        val r = buffer.leer(150)
        assertFalse(r.completa)
        assertTrue((System.nanoTime() - inicio) / 1_000_000 >= 140, "no esperó el timeout")
        assertEquals("0000410000002C00\r4000400000000800\r", r.texto)
        notificar(buffer, "\rSTOPPED\r\r>")
        assertEquals(Respuesta("\rSTOPPED\r\r", true), buffer.leer(500))
        assertEquals(Respuesta("", false), buffer.leer(50))
    }

    @Test
    fun desconexionCortaLaLecturaEnCurso() = runBlocking<Unit> {
        val buffer = BufferRespuestas()
        val lector = async { runCatching { buffer.leer(5000) } }
        delay(50)
        buffer.fallar(IOException("BLE desconectado"))
        assertTrue(lector.await().exceptionOrNull() is IOException)
        assertThrows<IOException> { runBlocking { buffer.leer(10) } }
    }
}
