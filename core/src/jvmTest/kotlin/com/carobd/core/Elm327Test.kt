package com.carobd.core

import com.carobd.core.obd.Carroceria
import com.carobd.core.obd.Catalogo
import com.carobd.core.obd.Decodificador
import com.carobd.core.obd.Elm327
import com.carobd.core.obd.FaseConexion
import com.carobd.core.obd.Respuesta
import com.carobd.core.obd.TransporteElm
import com.carobd.core.obd.TransporteTcp
import com.carobd.core.sim.Lectura
import com.carobd.core.sim.SimuladorAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** Respuestas reales del Accent (capturas en obd/capturas, VIN redactado) con ATE0 ATS0 ATH0. */
private val RESPUESTAS = mapOf(
    "ATZ" to "ATZ\rü\r\rELM327 v1.5\r\r",
    "0100" to "4100983BA013\r\r",
    "0120" to "4120B819A011\r\r",
    "0140" to "4140CCD3009D\r\r",
    "0160" to "4160EFFFFFF3\r\r",
    "0180" to "4180EAC27190\r\r",
    "010C" to "410C0C32\r\r",
    "010D" to "410D00\r\r",
    "0105" to "41057B\r\r",
    "0902" to "014\r0:4902014B4D48\r1:43553431554147\r2:55303030303030\r\r",
)

/** ELM327 falso: contesta como el emulador y cuenta lo que se le pide. */
private class TransporteFalso(var colgarTrasLecturas: Int = Int.MAX_VALUE) : TransporteElm {
    val enviados = mutableListOf<String>()
    var aperturas = 0
    var lecturas = 0
    var ultimo = ""
    val fasesAlAbrir = mutableListOf<FaseConexion>()
    var elm: Elm327? = null

    override suspend fun abrir() {
        aperturas++
        elm?.let { fasesAlAbrir += it.estado.value.fase }
        if (aperturas > 1) colgarTrasLecturas = Int.MAX_VALUE // tras reconectar responde de nuevo
    }

    override suspend fun escribir(texto: String) {
        ultimo = texto.trim()
        enviados += ultimo
    }

    override suspend fun leer(timeoutMs: Long): Respuesta {
        if (++lecturas > colgarTrasLecturas) return Respuesta("", false)
        return when {
            ultimo == "ATMA" -> Respuesta(if (enviados.last { it.startsWith("ATCRA") } == "ATCRA690") "0000410000002C00\r4000400000000800\r" else "0050C592990000DE\r", false)
            ultimo.isEmpty() -> Respuesta("\rSTOPPED\r\r", true) // el espacio que detiene ATMA
            ultimo.startsWith("AT") && ultimo != "ATZ" -> Respuesta("OK\r\r", true)
            else -> Respuesta(RESPUESTAS[ultimo] ?: "NO DATA\r\r", true)
        }
    }

    override fun cerrar() {}
}

class Elm327Test {
    @Test
    fun bitmapsDePidsSoportados() {
        val pids = Decodificador.pidsSoportados(0x00, "41 00 98 3B A0 13")!!
        assertEquals(setOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x13, 0x1C, 0x1F, 0x20), pids)
        assertTrue(0x5C in Decodificador.pidsSoportados(0x40, "SEARCHING...\r4140CCD3009D")!!)
        assertFalse(0x2F in Decodificador.pidsSoportados(0x20, "4120B819A011")!!)
        assertNull(Decodificador.pidsSoportados(0x00, "NO DATA"))
    }

    @Test
    fun tramasDeCarroceria() {
        val cerrado = Carroceria.decodificar("690", "0000410000002C00".chunked(2).map { it.toInt(16) })
        assertEquals(mapOf(Carroceria.PUERTA to 0.0, Carroceria.CINTURON to 1.0), cerrado)
        val abierta = Carroceria.decodificar("690", "4000400000000800".chunked(2).map { it.toInt(16) })
        assertEquals(mapOf(Carroceria.PUERTA to 1.0, Carroceria.CINTURON to 0.0), abierta)
        assertEquals(0.0, Carroceria.decodificar("350", "0010C592990000DE".chunked(2).map { it.toInt(16) })[Carroceria.AIRE])
        assertEquals(1.0, Carroceria.decodificar("350", "0050C592990000DE".chunked(2).map { it.toInt(16) })[Carroceria.AIRE])
        assertEquals(listOf(0x40, 0, 0x40, 0, 0, 0, 8, 0), Carroceria.ultimaTrama("0000410000002C00\r40 00 40 00 00 00 08 00\rSTOPPED\r"))
        assertNull(Carroceria.ultimaTrama("BUFFER FULL\r?\r"))
    }

    @Test
    fun vinMultitramaConYSinCabeceras() {
        assertEquals("KMHCU41UAGU000000", Decodificador.vin(RESPUESTAS.getValue("0902")))
        assertEquals("KMHCU41UAGU000000", Decodificador.vin("014\r0: 49 02 01 4B 4D 48\r1: 43 55 34 31 55 41 47\r2: 55 30 30 30 30 30 30\r"))
        val conCabeceras = "7E8 10 14 49 02 01 4B 4D 48\r7E8 21 43 55 34 31 55 41 47\r7E8 22 55 30 30 30 30 30 30\r"
        assertEquals("KMHCU41UAGU000000", Decodificador.vin(conCabeceras))
        assertNull(Decodificador.vin("NO DATA"))
    }

    @Test
    fun protocoloContraRespuestasReales() = runTest {
        val transporte = TransporteFalso()
        val elm = Elm327(transporte, excluir = Catalogo.noSoportadasAccent)
        val l = elm.lecturas().first()
        assertEquals(780.5, l.valores["rpm"])
        assertEquals(0.0, l.valores["velocidad"])
        assertEquals(83.0, l.valores["temp_refrigerante"])
        assertEquals(1.0, l.valores[Carroceria.PUERTA])
        assertEquals(0.0, l.valores[Carroceria.CINTURON])
        assertEquals(1.0, l.valores[Carroceria.AIRE])
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6"), transporte.enviados.take(7))
        assertFalse("012F" in transporte.enviados, "pidió combustible, que no está en los bitmaps")
        assertFalse("015C" in transporte.enviados, "pidió aceite, excluido en el Accent")
        assertTrue(transporte.enviados.takeLast(2) == listOf("ATCRA", "ATCAF1"), "no restauró los filtros tras ATMA")
        val e = elm.estado.value
        assertEquals(FaseConexion.CONECTADO, e.fase)
        assertEquals("ELM327 v1.5", e.adaptador)
        assertEquals("KMHCU41UAGU000000", e.vin)
        assertEquals(81, e.pidsSoportados) // los listados en elm327-exploracion.txt
    }

    @Test
    fun adaptadorColgadoReconectaSinCortarElFlujo() = runTest {
        val transporte = TransporteFalso(colgarTrasLecturas = 40)
        val elm = Elm327(transporte, excluir = Catalogo.noSoportadasAccent)
        transporte.elm = elm
        val lecturas = elm.lecturas().take(3).toList()
        assertEquals(3, lecturas.size)
        assertEquals(2, transporte.aperturas)
        assertEquals(listOf(FaseConexion.CONECTANDO, FaseConexion.RECONECTANDO), transporte.fasesAlAbrir)
        assertEquals(FaseConexion.CONECTADO, elm.estado.value.fase)
    }

    @Test
    fun elSimuladorNoInventaLoQueElAccentNoDa() = runTest {
        val l = SimuladorAccent(periodoMs = 0).lecturas().first()
        assertTrue(Catalogo.noSoportadasAccent.none { it in l.valores })
    }

    /** De punta a punta contra obd/emulador por TCP. Se salta si no hay python3. */
    @Test
    fun puntaAPuntaContraElEmulador() = runBlocking {
        val script = File("../obd/emulador/elm327_emulador.py")
        assumeTrue(script.exists() && runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val proceso = ProcessBuilder("python3", script.path, "--puerto", "0").redirectErrorStream(true).start()
        try {
            val puerto = Regex(":(\\d+)$").find(proceso.inputStream.bufferedReader().readLine().trim())!!.groupValues[1].toInt()
            val consola = proceso.outputStream.bufferedWriter()
            fun consola(comando: String) = consola.apply { write("$comando\n"); flush() }
            consola("puerta off"); consola("ac off")
            val elm = Elm327(TransporteTcp("127.0.0.1", puerto), excluir = Catalogo.noSoportadasAccent)
            var ultima: Lectura? = null
            val lector = launch(Dispatchers.IO) { elm.lecturas().collect { ultima = it } }
            suspend fun esperar(condicion: (Map<String, Double>) -> Boolean) = withTimeout(10_000) {
                while (ultima?.valores?.let(condicion) != true) kotlinx.coroutines.delay(100)
            }
            esperar { it[Carroceria.PUERTA] == 0.0 && it[Carroceria.AIRE] == 0.0 && "rpm" in it && "velocidad" in it && "temp_refrigerante" in it }
            assertTrue(ultima!!.valores.getValue("rpm") in 500.0..5000.0)
            consola("puerta on"); consola("ac on")
            esperar { it[Carroceria.PUERTA] == 1.0 && it[Carroceria.AIRE] == 1.0 }
            assertEquals("KMHCU41UAGU000000", elm.estado.value.vin)
            assertNotNull(ultima!!.valores["temp_refrigerante"])
            lector.cancel()
        } finally {
            proceso.destroy()
            proceso.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
