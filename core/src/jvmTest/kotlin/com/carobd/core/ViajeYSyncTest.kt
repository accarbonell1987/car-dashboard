package com.carobd.core

import com.carobd.core.model.OrigenViaje
import com.carobd.core.model.Viaje
import com.carobd.core.sim.Lectura
import com.carobd.core.sim.SimuladorAccent
import com.carobd.core.sync.AlmacenEnMemoria
import com.carobd.core.sync.MotorSync
import com.carobd.core.sync.PaqueteCambios
import com.carobd.core.sync.ResultadoSync
import com.carobd.core.sync.SyncResponse
import com.carobd.core.sync.TransporteSync
import com.carobd.core.viaje.OdometroCalculado
import com.carobd.core.viaje.RegistradorViaje
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

class ViajeYSyncTest {
    private val t0 = Instant.parse("2026-11-03T08:00:00Z")
    private var n = 0
    private fun id() = "id-${n++}"
    private fun registrador() = RegistradorViaje("veh", reloj = { t0 + it.tMs.milliseconds }, nuevoId = ::id)
    private fun apagado(tMs: Long) = Lectura(tMs, mapOf("rpm" to 0.0, "velocidad" to 0.0))

    @Test
    fun registraUnViajeDelSimuladorYLoCierraConElMotorApagado() = runTest {
        val lecturas = SimuladorAccent(periodoMs = 100).lecturas().take(9_000).toList() // 15 minutos
        val r = registrador()
        lecturas.forEach { assertNull(r.procesar(it)) }
        assertTrue(r.enCurso)

        val ultimoMs = lecturas.last().tMs
        var viaje: Viaje? = null
        var t = ultimoMs
        while (viaje == null && t < ultimoMs + 120_000) {
            t += 1_000
            viaje = r.procesar(apagado(t))
        }
        assertNotNull(viaje)
        viaje!!
        val kmSimulador = lecturas.last().valores["odometro_viaje_km"]!!
        assertTrue(abs(viaje.km - kmSimulador) < 0.1, "km ${viaje.km} vs simulador $kmSimulador")
        assertTrue(viaje.km > 5, "15 minutos urbanos deberían dar varios km: ${viaje.km}")
        assertEquals(OrigenViaje.OBD, viaje.origen)
        assertEquals(t0, viaje.inicio)
        assertTrue(viaje.velocidadMaxKmh!! > 50)
        assertTrue(viaje.velocidadMediaKmh!! in 10.0..90.0)
        assertTrue(viaje.consumoLitros!! > 0.1)
        assertEquals(14.1, viaje.voltajeArranqueV!!, 0.2)
        assertTrue(!r.enCurso)
    }

    @Test
    fun descartaArranquesCortosEnElGaraje() {
        val r = registrador()
        for (s in 0..30) r.procesar(Lectura(s * 1_000L, mapOf("rpm" to 800.0, "velocidad" to if (s in 10..15) 5.0 else 0.0)))
        assertNull(r.cerrar())
    }

    @Test
    fun unCorteDeConexionNoInventaKilometros() {
        val r = registrador()
        r.procesar(Lectura(0, mapOf("rpm" to 2000.0, "velocidad" to 100.0)))
        r.procesar(Lectura(1_000, mapOf("rpm" to 2000.0, "velocidad" to 100.0)))
        // 10 minutos sin lecturas y vuelve: solo se cuentan 5 s de ese hueco, no 10 minutos a 100 km/h.
        r.procesar(Lectura(601_000, mapOf("rpm" to 2000.0, "velocidad" to 100.0)))
        assertTrue(r.kmEnCurso < 0.2, "km ${r.kmEnCurso}")
    }

    @Test
    fun odometroAcumulaDecimalesEntreViajes() {
        var estado = OdometroCalculado.Estado(90_600.0)
        repeat(3) {
            val v = Viaje(id(), "veh", t0, t0, km = 0.6, duracionSeg = 60, origen = OrigenViaje.OBD, updatedAt = t0)
            val (nuevo, lectura) = OdometroCalculado.trasViaje(estado, v, ::id)
            estado = nuevo
            if (it == 2) assertEquals(90_601, lectura.km) // 0,6 × 3 = 1,8 km, no 0 por redondear cada viaje
        }
    }

    @Test
    fun sinRedLosPendientesSeQuedanYLuegoSeSuben() = runTest {
        val almacen = AlmacenEnMemoria()
        almacen.guardar(Viaje("v1", "veh", t0, t0, 12.0, 900, origen = OrigenViaje.OBD, updatedAt = t0))

        val sinRed = MotorSync(almacen, { error("sin conexión") }, "pantalla")
        assertTrue(sinRed.sincronizar() is ResultadoSync.Fallo)
        assertEquals(1, almacen.cantidadPendientes)

        val recibidos = mutableListOf<String>()
        val servidor = TransporteSync { req ->
            recibidos += req.cambios.viajes.map { it.id }
            SyncResponse(req.cambios.viajes.map { it.id }, PaqueteCambios(), t0 + 1_000.milliseconds)
        }
        val ok = MotorSync(almacen, servidor, "pantalla").sincronizar()
        assertTrue(ok is ResultadoSync.Ok)
        assertEquals(listOf("v1"), recibidos)
        assertEquals(0, almacen.cantidadPendientes)
        assertEquals(t0 + 1_000.milliseconds, almacen.ultimoSync())
    }

    @Test
    fun unaEdicionLocalMasNuevaNoLaPisaElServidor() = runTest {
        val almacen = AlmacenEnMemoria()
        val local = Viaje("v1", "veh", t0, t0, 12.0, 900, origen = OrigenViaje.OBD, updatedAt = t0 + 5_000.milliseconds)
        almacen.guardar(local)
        val viejo = local.copy(km = 1.0, updatedAt = t0)
        almacen.aplicarRemotos(PaqueteCambios(viajes = listOf(viejo)))
        assertEquals(12.0, almacen.viajes["v1"]!!.km)
        assertEquals(1, almacen.cantidadPendientes, "sigue pendiente para subirse")
    }
}
