package com.carobd.core

import com.carobd.core.obd.Carroceria
import com.carobd.core.obd.Catalogo
import com.carobd.core.obd.Decodificador
import com.carobd.core.obd.Elm327
import com.carobd.core.obd.FaseConexion
import com.carobd.core.obd.MotorExtendido
import com.carobd.core.obd.Respuesta
import com.carobd.core.obd.TransporteElm
import com.carobd.core.obd.TransporteTcp
import com.carobd.core.sim.Lectura
import com.carobd.core.sim.SimuladorAccent
import kotlinx.coroutines.CoroutineScope
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
    "22ED01" to "025\r0:62ED01FFFF7E\r1:F8000112007F48\r2:656380082513CB\r3:0F009900FF0000\r4:00469B64FF0001\r5:000019\r\r",
    "0902" to "014\r0:4902014B4D48\r1:43553431554147\r2:55303030303030\r\r",
)

/** ELM327 falso: contesta como el emulador y cuenta lo que se le pide. */
private class TransporteFalso(
    var colgarTrasLecturas: Int = Int.MAX_VALUE,
    val sordoAlEscuchar: Boolean = false,
    /** Stop bytes the adapter misses (as a dropped BLE write would): ATMA keeps running through them. */
    var paradasPerdidas: Int = 0,
    /** Ignition on, engine and bus asleep: every PID answers NO DATA and ATMA hears nothing. */
    val autoApagado: Boolean = false,
) : TransporteElm {
    val enviados = mutableListOf<String>()
    var aperturas = 0
    var lecturas = 0
    var ultimo = ""
    val fasesAlAbrir = mutableListOf<FaseConexion>()
    var elm: Elm327? = null

    override suspend fun abrir() {
        aperturas++
        // AssertionError is not an Exception: it escapes the retry loop instead of reconnecting forever.
        if (sordoAlEscuchar && aperturas > 1) throw AssertionError("reconectó por culpa de los testigos")
        elm?.let { fasesAlAbrir += it.estado.value.fase }
        if (aperturas > 1) colgarTrasLecturas = Int.MAX_VALUE // tras reconectar responde de nuevo
    }

    override suspend fun escribir(texto: String) {
        ultimo = texto.trim()
        enviados += ultimo
    }

    override suspend fun leer(timeoutMs: Long): Respuesta {
        if (++lecturas > colgarTrasLecturas) return Respuesta("", false)
        // Like the BLE clone in the car: the filter commands around ATMA get no prompt back.
        if (sordoAlEscuchar && (ultimo == "ATCAF0" || ultimo == "ATCAF1" || ultimo.startsWith("ATCRA"))) return Respuesta("", false)
        if (ultimo.isEmpty() && paradasPerdidas > 0) {
            paradasPerdidas--
            return Respuesta("", false)
        }
        if (autoApagado && ultimo == "ATMA") return Respuesta("", false)
        if (autoApagado && !ultimo.startsWith("AT") && ultimo.isNotEmpty()) return Respuesta("NO DATA\r\r", true)
        return when {
            ultimo == "ATMA" -> Respuesta(if (enviados.last { it.startsWith("ATCRA") } == "ATCRA690") "0000410000002C00\r4000400000000800\r" else "0050C592990000DE\r", false)
            ultimo.isEmpty() -> Respuesta("\rSTOPPED\r\r", true) // el espacio que detiene ATMA
            ultimo.startsWith("AT") && ultimo != "ATZ" -> Respuesta("OK\r\r", true)
            else -> Respuesta(RESPUESTAS[ultimo] ?: "NO DATA\r\r", true)
        }
    }

    override fun descartar() {}

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
    fun tramasDelBusConDatosNuevos() {
        fun d(id: String, hex: String) = Carroceria.decodificar(id, hex.chunked(2).map { it.toInt(16) })
        // Real frames from capturas/elm327-guiada.csv (2026-11-20).
        assertEquals(91_268.7, d("4F0", "00007A00002FED0D")[Carroceria.ODOMETRO]!!, 1e-9)
        assertEquals(33.0, d("350", "001005929900001E")[Carroceria.TEMP_EXTERIOR])
        assertEquals(mapOf(Carroceria.RESERVA to 1.0, Carroceria.LUNETA to 1.0), d("4F1", "2000010000000000"))
        assertEquals(mapOf(Carroceria.RESERVA to 0.0, Carroceria.LUNETA to 0.0), d("4F1", "0000000000000000"))
        assertEquals(60.0, d("545", "0000000000009000")[Carroceria.TEMP_COMBUSTIBLE])
        assertEquals(1.0, d("5E4", "0400000000000000")[Carroceria.DIRECCION])
        assertEquals(0.0, d("5E4", "0100000000000000")[Carroceria.DIRECCION])
        assertEquals(emptyMap<String, Double>(), d("4F0", "0000"), "trama corta")
    }

    @Test
    fun cadaTresEscuchasSumaUnIdLentoPorTurno() {
        val turnos = (0 until 12).map { Carroceria.idsEscucha(it) }
        assertTrue(turnos.all { it.take(2) == listOf("690", "350") })
        assertEquals(listOf("4F0", "545", "4F1", "5E4"), turnos.filter { it.size == 3 }.map { it[2] })
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
        // Secondary data arrives one per turn: wait until the body listen and the coolant have had theirs.
        val l = elm.lecturas().first { "temp_refrigerante" in it.valores && Carroceria.AIRE in it.valores }
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
    fun cadaTurnoLeeLasCriticasYUnSoloDatoSecundario() = runTest {
        val transporte = TransporteFalso()
        Elm327(transporte, excluir = Catalogo.noSoportadasAccent, periodoCarroceriaMs = Long.MAX_VALUE).lecturas().take(8).toList()
        val turnos = transporte.enviados.dropWhile { it != "010C" }.joinToString(" ").split("010C").drop(1)
        assertEquals(8, turnos.size, "RPM no se leyó en cada turno")
        assertTrue(turnos.all { "010D" in it }, "velocidad no se leyó en cada turno")
        for (t in turnos) {
            val secundarios = t.split(" ").filter { (it.startsWith("01") || it.startsWith("22")) && it != "010D" && it != "0111" }
            assertTrue(secundarios.size <= 1, "más de un dato secundario en un turno: $t")
        }
        assertEquals("22ED01", turnos.first().split(" ").first { it.startsWith("01") && it !in listOf("010D", "0111") || it.startsWith("22") }, "freno y embrague no van primero")
    }

    /** ATH0 multi-frame reply as the ELM327 prints it: length line, then "n:" frames of 6 then 7 bytes. */
    private fun multitrama(hex: String): String {
        val b = hex.chunked(2)
        val tramas = listOf(b.take(6)) + b.drop(6).chunked(7)
        return "%03X\r".format(b.size) + tramas.mapIndexed { i, t -> "${i % 16}:" + t.joinToString("") }.joinToString("\r") + "\r\r"
    }

    @Test
    fun variosPidsEnUnPedido() {
        val criticas = Catalogo.estandar.filter { it.id in listOf("rpm", "velocidad", "acelerador") }
        // 8 bytes: the ECU splits it into two ISO-TP frames.
        assertEquals(mapOf("rpm" to 780.5, "velocidad" to 42.0, "acelerador" to 20.0), Decodificador.variosPids("008\r0:410C0C320D2A\r1:1133\r\r", criticas))
        assertNull(Decodificador.variosPids("410C0C32\r\r", criticas), "faltan PIDs y no cayó a uno por pedido")
        assertNull(Decodificador.variosPids("NO DATA\r\r", criticas))
    }

    @Test
    fun bloqueExtendidoDelMotor() {
        // Real 22ED01 replies from obd/capturas/elm327-motor22-pedales-*.csv.
        fun d(datos: String) = MotorExtendido.decodificar(multitrama("62ED01$datos"))
        val reposo = "FFFF7EF8000112007F48656380082513CB0F009900FF000000469B64FF0001000019"
        assertEquals(mapOf(MotorExtendido.FRENO to 0.0, MotorExtendido.EMBRAGUE to 0.0, MotorExtendido.PUNTO_MUERTO to 1.0), d(reposo))
        assertEquals(1.0, d("FFFF7EF8000112007F48656381482513CB0F009800FF000000469B64FF0001000019")!![MotorExtendido.FRENO])
        assertEquals(1.0, d("FFFF7EF8000112007F48656380092513CB0F009900FF000000469B64FF0001000019")!![MotorExtendido.EMBRAGUE])
        assertEquals(0.0, d("FFFF7EF8000112007F48656380002513CB0F009900FF000000469B64FF0001000019")!![MotorExtendido.PUNTO_MUERTO])
        assertNull(d(reposo.take(40)), "aceptó una respuesta truncada")
        assertNull(MotorExtendido.decodificar("7F2231\r\r"))
    }

    @Test
    fun soloSeLePreguntaAlMotorYElBloqueVaUnaVezPorSegundo() = runTest {
        val transporte = TransporteFalso()
        val lecturas = Elm327(transporte, excluir = Catalogo.noSoportadasAccent, periodoCarroceriaMs = Long.MAX_VALUE).lecturas().take(6).toList()
        assertEquals("ATSH7E0", transporte.enviados[7], "no fijó la cabecera del motor")
        assertEquals(1, transporte.enviados.count { it == "22ED01" })
        assertEquals(1.0, lecturas.last().valores[MotorExtendido.PUNTO_MUERTO])
    }

    @Test
    fun unaParadaPerdidaDeLaEscuchaNoReconecta() = runTest {
        val transporte = TransporteFalso(paradasPerdidas = 2)
        val elm = Elm327(transporte, excluir = Catalogo.noSoportadasAccent, periodoCarroceriaMs = 0)
        transporte.elm = elm
        val lecturas = elm.lecturas().take(40).toList()
        assertEquals(1, transporte.aperturas, "reconectó por un byte de parada perdido")
        assertTrue(elm.estado.value.carroceria, "apagó los testigos por un byte de parada perdido")
        assertEquals(1.0, lecturas.last().valores[Carroceria.PUERTA])
        assertEquals(780.5, lecturas.last().valores["rpm"])
    }

    @Test
    fun conElAutoApagadoYTestigosPrendidosNoReconecta() = runTest {
        val transporte = TransporteFalso(autoApagado = true)
        val elm = Elm327(transporte, excluir = Catalogo.noSoportadasAccent, periodoCarroceriaMs = 0)
        transporte.elm = elm
        elm.lecturas().take(60).toList()
        assertEquals(1, transporte.aperturas, "reconectó con el auto apagado")
        assertEquals(FaseConexion.CONECTADO, elm.estado.value.fase)
        assertFalse(elm.estado.value.carroceria, "sin tramas, los testigos deberían apagarse solos")
    }

    @Test
    fun adaptadorColgadoReconectaSinCortarElFlujo() = runTest {
        val transporte = TransporteFalso(colgarTrasLecturas = 20)
        val elm = Elm327(transporte, excluir = Catalogo.noSoportadasAccent)
        transporte.elm = elm
        val lecturas = elm.lecturas().take(3).toList()
        assertEquals(3, lecturas.size)
        assertEquals(2, transporte.aperturas)
        assertEquals(listOf(FaseConexion.CONECTANDO, FaseConexion.RECONECTANDO), transporte.fasesAlAbrir)
        assertEquals(FaseConexion.CONECTADO, elm.estado.value.fase)
    }

    @Test
    fun siLaEscuchaFallaSeApaganLosTestigosSinReconectar() = runTest {
        val transporte = TransporteFalso(sordoAlEscuchar = true)
        val elm = Elm327(transporte, excluir = Catalogo.noSoportadasAccent, periodoCarroceriaMs = 0)
        val lecturas = elm.lecturas().take(40).toList()
        assertEquals(1, transporte.aperturas, "reconectó por culpa de los testigos")
        assertFalse(elm.estado.value.carroceria, "los testigos siguen prendidos aunque fallan")
        assertEquals(780.5, lecturas.last().valores["rpm"])
        assertEquals(FaseConexion.CONECTADO, elm.estado.value.fase)
    }

    @Test
    fun elSimuladorNoInventaLoQueElAccentNoDa() = runTest {
        val l = SimuladorAccent(periodoMs = 0).lecturas().first()
        assertTrue(Catalogo.noSoportadasAccent.none { it in l.valores })
    }

    /** Lanza obd/emulador en un puerto libre; se salta el test si no hay python3. */
    private fun conEmulador(vararg opciones: String, bloque: suspend (puerto: Int, consola: (String) -> Unit) -> Unit) = runBlocking<Unit> {
        val script = File("../obd/emulador/elm327_emulador.py")
        assumeTrue(script.exists() && runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val proceso = ProcessBuilder(listOf("python3", script.path, "--puerto", "0") + opciones).redirectErrorStream(true).start()
        try {
            val salida = proceso.inputStream.bufferedReader()
            val puerto = Regex(":(\\d+)$").find(salida.readLine().trim())!!.groupValues[1].toInt()
            Thread { salida.forEachLine { } }.apply { isDaemon = true }.start() // que el pipe de stdout no se llene
            val stdin = proceso.outputStream.bufferedWriter()
            bloque(puerto) { comando -> stdin.write("$comando\n"); stdin.flush() }
        } finally {
            proceso.destroy()
            proceso.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private suspend fun esperar(ultima: () -> Lectura?, segundos: Long = 10, condicion: (Map<String, Double>) -> Boolean) =
        withTimeout(segundos * 1000) { while (ultima()?.valores?.let(condicion) != true) kotlinx.coroutines.delay(50) }

    /** De punta a punta contra obd/emulador por TCP. */
    @Test
    fun puntaAPuntaContraElEmulador() = conEmulador { puerto, consola ->
        consola("puerta off"); consola("ac off")
        val elm = Elm327(TransporteTcp("127.0.0.1", puerto), excluir = Catalogo.noSoportadasAccent, periodoCarroceriaMs = 2000)
        var ultima: Lectura? = null
        val lector = CoroutineScope(Dispatchers.IO).launch { elm.lecturas().collect { ultima = it } }
        try {
            esperar({ ultima }) { it[Carroceria.PUERTA] == 0.0 && it[Carroceria.AIRE] == 0.0 && "rpm" in it && "velocidad" in it && "temp_refrigerante" in it }
            assertTrue(ultima!!.valores.getValue("rpm") in 500.0..5000.0)
            assertTrue(elm.registro.value.any { "agrupados, con cuenta" in it }, "no agrupó los PIDs críticos")
            assertTrue("acelerador" in ultima!!.valores)
            consola("puerta on"); consola("ac on")
            esperar({ ultima }) { it[Carroceria.PUERTA] == 1.0 && it[Carroceria.AIRE] == 1.0 }
            assertEquals("KMHCU41UAGU000000", elm.estado.value.vin)
        } finally {
            lector.cancel()
        }
    }

    /**
     * Lo que pasó en el auto: el clon por BLE manda un prompt tardío después de detener ATMA, y sin resincronizar
     * cada respuesta quedaba emparejada con el comando anterior (PIDs congelados, testigos bien).
     * Con escucha cada 300 ms, rpm tiene que seguir cada cambio durante [E2E_SEGUNDOS] (8 por defecto).
     */
    @Test
    fun losPidsSiguenConTestigosYPromptsTardios() = conEmulador("--clon-ble", "--latencia", "20") { puerto, consola ->
        val elm = Elm327(TransporteTcp("127.0.0.1", puerto), excluir = Catalogo.noSoportadasAccent, periodoCarroceriaMs = 300)
        var ultima: Lectura? = null
        val lector = CoroutineScope(Dispatchers.IO).launch { elm.lecturas().collect { ultima = it } }
        try {
            val segundos = System.getenv("E2E_SEGUNDOS")?.toIntOrNull() ?: 8
            val fin = System.nanoTime() + segundos * 1_000_000_000L
            var paso = 0
            while (System.nanoTime() < fin) {
                val rpm = 1000.0 + 100 * (paso++ % 30)
                consola("rpm ${rpm.toInt()}")
                esperar({ ultima }, segundos = 3) { it["rpm"] == rpm }
            }
            assertTrue(paso >= segundos / 2, "solo $paso cambios de rpm en $segundos s")
            assertEquals(FaseConexion.CONECTADO, elm.estado.value.fase)
            assertTrue(elm.estado.value.carroceria, "los testigos se desactivaron")
            assertEquals(1, elm.registro.value.count { " ATZ " in "$it " || it.endsWith(" ATZ") || " ATZ →" in it }, "reconectó durante la prueba")
        } finally {
            lector.cancel()
        }
    }
}
