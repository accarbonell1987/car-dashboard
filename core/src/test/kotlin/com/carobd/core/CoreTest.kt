package com.carobd.core

import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.LecturaOdometro
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
    }

    @Test
    fun `evalua formulas con parentesis y variables`() {
        assertEquals(30.07, Formula.evaluar("((a*256)+b)/100", mapOf("a" to 11.0, "b" to 191.0)), 0.001)
        assertEquals(-1.0, Formula.evaluar("A-40", mapOf("A" to 39.0)))
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
        var n = 0
        val items = Mantenimiento.plantillaAccentCrdi("veh", t0) { "id${n++}" }
            .map { if (it.nombre.startsWith("Aceite")) it.copy(ultimoKm = 85_000) else it }
        val pendientes = Mantenimiento.pendientes(items, odometroKm = 90_600, hoy = t0)
        val aceite = pendientes.first { it.item.nombre.startsWith("Aceite") }
        assertEquals(4_400, aceite.faltanKm)
        assertEquals(8, items.size)
    }

    @Test
    fun `el paquete de sync va y vuelve por JSON`() {
        val req = SyncRequest("disp1", null, PaqueteCambios(viajes = listOf(viaje("v1", t0, 3.5))))
        val texto = SyncJson.json.encodeToString(SyncRequest.serializer(), req)
        val vuelta = SyncJson.json.decodeFromString(SyncRequest.serializer(), texto)
        assertEquals(req, vuelta)
    }

    @Test
    fun `marchas detecta la tercera`() {
        val m = Marchas()
        assertEquals(3, m.marcha(rpm = 2500.0, kmh = 50.0))
        assertEquals(0, m.marcha(rpm = 800.0, kmh = 0.0))
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
