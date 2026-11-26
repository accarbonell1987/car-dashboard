package com.carobd.core

import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.LecturaOdometro
import com.carobd.core.model.CatalogoMantenimiento
import com.carobd.core.model.Mantenimiento
import com.carobd.core.model.OrigenOdometro
import com.carobd.core.model.Viaje
import com.carobd.core.model.OrigenViaje
import com.carobd.core.obd.Catalogo
import com.carobd.core.obd.Decodificador
import com.carobd.core.obd.Formula
import com.carobd.core.obd.Marchas
import com.carobd.core.sim.SimuladorAccent
import com.carobd.core.sync.PaqueteCambios
import com.carobd.core.sync.SyncJson
import com.carobd.core.sync.SyncRequest
import com.carobd.core.sync.aplicar
import com.carobd.core.sync.odometroVigente
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreTest {
    private val t0 = Instant.parse("2026-11-02T20:00:00Z")
    private val t1 = Instant.parse("2026-11-02T21:00:00Z")

    @Test
    fun `decodifica rpm y temperatura desde respuestas ELM327`() {
        assertEquals(1726.0, Decodificador.decodificar("41 0C 1A F8", Catalogo.porId("rpm")!!))
        assertEquals(52.0, Decodificador.decodificar("41055C>", Catalogo.porId("temp_refrigerante")!!))
        assertEquals(14.2, Decodificador.decodificar("41 42 37 78", Catalogo.porId("voltaje")!!)!!, 0.001)
        assertNull(Decodificador.decodificar("NO DATA", Catalogo.porId("rpm")!!))
        // 017C del Accent: máscara 00 = sin sensor de DPF válido, aunque B-C traiga 05DC.
        assertNull(Decodificador.decodificar("41 7C 00 05 DC", Catalogo.porId("temp_dpf")!!))
        assertEquals(110.0, Decodificador.decodificar("417C0105DC", Catalogo.porId("temp_dpf")!!)!!, 0.001)
    }

    @Test
    fun `evalua formulas con parentesis y variables`() {
        assertEquals(30.07, Formula.evaluar("((a*256)+b)/100", mapOf("a" to 11.0, "b" to 191.0)), 0.001)
        assertEquals(-1.0, Formula.evaluar("A-40", mapOf("A" to 39.0)))
    }

    @Test
    fun `lee la luz de check engine y los codigos del PID 0101`() {
        // Real reply of the Accent: MIL off, no codes.
        assertEquals(0.0, Decodificador.decodificar("41 01 00 28 EB EB", Catalogo.porId("mil")!!))
        assertEquals(0.0, Decodificador.decodificar("41 01 00 28 EB EB", Catalogo.porId("codigos_falla")!!))
        // Bit 7 of A is the lamp, bits 0-6 the number of stored codes: 0x83 = lamp on, 3 codes.
        assertEquals(1.0, Decodificador.decodificar("41 01 83 28 EB EB", Catalogo.porId("mil")!!))
        assertEquals(3.0, Decodificador.decodificar("41 01 83 28 EB EB", Catalogo.porId("codigos_falla")!!))
    }

    @Test
    fun `gana el updatedAt mas reciente`() {
        val viejo = viaje("v1", t0, km = 10.0)
        val nuevo = viaje("v1", t1, km = 12.0)
        val resultado = aplicar(mapOf("v1" to nuevo), listOf(viejo))
        assertEquals(12.0, resultado["v1"]!!.km)
        val resultado2 = aplicar(mapOf("v1" to viejo), listOf(nuevo))
        assertEquals(12.0, resultado2["v1"]!!.km)
    }

    @Test
    fun `odometro vigente es el de mayor km`() {
        val calculado = LecturaOdometro("a", "veh", t1, 90_650, OrigenOdometro.CALCULADO, t1)
        val servicio = LecturaOdometro("b", "veh", t0, 90_700, OrigenOdometro.SERVICIO, t0)
        assertEquals("b", odometroVigente(listOf(calculado, servicio))!!.id)
    }

    @Test
    fun `plantilla del Accent y pendientes`() {
        val items = CatalogoMantenimiento.plan("veh", CatalogoMantenimiento.DIESEL_D4FB_MANUAL, t0)
            .map { if (it.codigo == CatalogoMantenimiento.ACEITE_MOTOR) it.copy(ultimoKm = 85_000) else it }
        val pendientes = Mantenimiento.pendientes(items, odometroKm = 90_600, hoy = t0)
        val aceite = pendientes.first { it.item.codigo == CatalogoMantenimiento.ACEITE_MOTOR }
        assertEquals(4_400, aceite.faltanKm)
        assertEquals(19, items.size)
    }

    @Test
    fun `el paquete de sync va y vuelve por JSON`() {
        val req = SyncRequest("disp1", null, PaqueteCambios(viajes = listOf(viaje("v1", t0, 3.5))))
        val texto = SyncJson.json.encodeToString(SyncRequest.serializer(), req)
        val vuelta = SyncJson.json.decodeFromString(SyncRequest.serializer(), texto)
        assertEquals(req, vuelta)
    }

    @Test
    fun `marchas reconoce las seis del Accent`() {
        val m = Marchas()
        // rpm per km/h of each gear of the 6-speed (3rd ~42 used to read as "4", 4th ~31 as "5").
        assertEquals(1, m.marcha(rpm = 2400.0, kmh = 20.0))
        assertEquals(2, m.marcha(rpm = 2640.0, kmh = 40.0))
        assertEquals(3, m.marcha(rpm = 2100.0, kmh = 50.0))
        assertEquals(4, m.marcha(rpm = 2170.0, kmh = 70.0))
        assertEquals(5, m.marcha(rpm = 2250.0, kmh = 90.0))
        assertEquals(6, m.marcha(rpm = 2200.0, kmh = 110.0))
        assertEquals(0, m.marcha(rpm = 800.0, kmh = 0.0))
    }

    @Test
    fun `marchas aprende la relacion real manejando estable`() {
        val m = Marchas()
        // This car's 3rd turns out to be 40 rpm/km/h: after a steady stretch the table moves toward it.
        repeat(60) { m.aprender(rpm = 2000.0, kmh = 50.0) }
        assertEquals(40.0, m.relaciones[2], 0.5)
        assertEquals(3, m.marcha(rpm = 2000.0, kmh = 50.0))
        // Still strictly decreasing, so gears never swap places.
        assertTrue(m.relaciones.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun `una cuarta entre dos relaciones no se convierte en quinta al aprender`() {
        val m = Marchas()
        // The reported bug: 4th shows "4" and later "5" without touching the lever. This car's 4th sits halfway
        // between the table's 4th (31) and 5th (25); a reading that leans to 5th used to teach 5th, which then
        // won every later reading.
        repeat(200) { i -> m.aprender(rpm = 2800.0 + if (i % 40 < 20) -10.0 else 10.0, kmh = 100.0) }
        assertEquals(4, m.marcha(rpm = 2800.0, kmh = 100.0))
        assertEquals(25.0, m.relaciones[4], 0.01)
    }

    @Test
    fun `la marcha mostrada no salta por una lectura suelta`() {
        val m = Marchas()
        repeat(3) { m.mostrar(4, kmh = 70.0) }
        assertEquals(4, m.mostrar(4, kmh = 70.0))
        assertEquals(4, m.mostrar(5, kmh = 70.0), "cambió con una sola lectura")
        assertEquals(4, m.mostrar(5, kmh = 70.0))
        assertEquals(5, m.mostrar(5, kmh = 70.0))
        assertEquals(0, m.mostrar(0, kmh = 0.0), "detenido tiene que mostrar N enseguida")
    }

    @Test
    fun `marchas no aprende con datos inestables`() {
        val m = Marchas()
        val antes = m.relaciones.toList()
        // Clutch slipping: the ratio jumps every sample.
        repeat(60) { i -> m.aprender(rpm = if (i % 2 == 0) 2000.0 else 2600.0, kmh = 50.0) }
        assertEquals(antes, m.relaciones)
    }

    @Test
    fun `el simulador emite lecturas con todas las claves`() = runTest {
        val l = SimuladorAccent(periodoMs = 1).lecturas().first()
        assertTrue(l.valores.containsKey("rpm") && l.valores.containsKey("temp_refrigerante"))
    }

    private fun viaje(id: String, updated: Instant, km: Double) = Viaje(
        id = id, vehiculoId = "veh", inicio = t0, fin = t1, km = km, duracionSeg = 3600,
        origen = OrigenViaje.OBD, updatedAt = updated,
    )
}
