package com.carobd.core

import com.carobd.core.datos.Bitacora
import com.carobd.core.model.Garaje
import com.carobd.core.model.OrigenOdometro
import com.carobd.core.model.OrigenViaje
import com.carobd.core.model.Viaje
import com.carobd.core.sync.AlmacenPersistente
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

class BitacoraTest {
    private var ahora = Instant.parse("2026-11-03T08:00:00Z")
    private var n = 0
    private fun bitacora(disco: Array<String?> = arrayOf(null)) =
        Bitacora(AlmacenPersistente({ disco[0] }, { disco[0] = it }), reloj = { ahora }, crearId = { "id-${n++}" })

    private fun viaje(km: Double): Viaje {
        val inicio = ahora
        ahora += 1.hours
        return Viaje("v-${n++}", Bitacora.ID_ACCENT, inicio, ahora, km, 3600, origen = OrigenViaje.OBD, updatedAt = ahora)
    }

    @Test
    fun losViajesSumanAlOdometroSinPerderDecimales() = runTest {
        val b = bitacora()
        b.vehiculo()
        repeat(3) { b.guardarViaje(viaje(0.7)) }
        assertEquals(90_602.1, b.odometroKm(), 1e-9)
        val calculadas = b.almacen.leer { m -> m.lecturasOdometro.values.map { it.km }.sorted() }
        assertEquals(listOf(90_600, 90_601, 90_602), calculadas)
    }

    @Test
    fun unaCorreccionAManoEsLaNuevaBaseYElOdometroNuncaBaja() = runTest {
        val b = bitacora()
        val g = b.garaje()
        b.guardarViaje(viaje(12.0))
        ahora += 1.hours
        val g2 = b.garaje()
        b.guardarGaraje(g2, Garaje.corregirOdometro(g2, 91_000))
        b.guardarViaje(viaje(5.5))
        assertEquals(91_005.5, b.odometroKm(), 1e-9)
        // Una corrección menor no lo baja.
        val g3 = b.garaje()
        b.guardarGaraje(g3, Garaje.corregirOdometro(g3, 90_700))
        assertEquals(91_005.5, b.odometroKm(), 1e-9)
        assertTrue(g.items.isNotEmpty())
    }

    @Test
    fun unServicioQuedaGuardadoYPendienteDeSubir() = runTest {
        val disco = arrayOf<String?>(null)
        val b = bitacora(disco)
        val g = b.garaje()
        b.almacen.marcarEnviados(b.almacen.pendientes().let { p -> p.vehiculos.map { it.id } + p.itemsMantenimiento.map { it.id } })
        val aceite = g.items.first { it.nombre.startsWith("Aceite") }
        b.guardarGaraje(g, Garaje.registrarServicio(g, 95_000, "Cambio de aceite", setOf(aceite.id), ahora))

        val reabierta = bitacora(disco)
        val g2 = reabierta.garaje()
        assertEquals(95_000, g2.odometroKm)
        assertEquals(95_000, g2.items.first { it.id == aceite.id }.ultimoKm)
        val p = reabierta.almacen.pendientes()
        assertEquals(1, p.servicios.size)
        assertEquals(1, p.itemsMantenimiento.size)
        assertTrue(p.lecturasOdometro.isEmpty(), "el km del servicio ya va en el servicio")
    }

    @Test
    fun elGarajeViejoDelMovilSePasaConElIdComun() = runTest {
        val viejo = Garaje.corregirOdometro(Garaje.inicialAccent(ahora), 90_800)
        val b = bitacora()
        b.importarGarajeViejo(viejo)
        val g = b.garaje()
        assertEquals(Bitacora.ID_ACCENT, g.vehiculo.id)
        assertEquals(viejo.items.map { it.id }.toSet(), g.items.map { it.id }.toSet())
        assertTrue(g.items.all { it.vehiculoId == Bitacora.ID_ACCENT })
        assertEquals(90_800, g.odometroKm)
        assertEquals(OrigenOdometro.MANUAL, b.almacen.leer { it.lecturasOdometro.values.single().origen })
    }
}

class HistorialTest {
    @Test
    fun guardaUnaMuestraPorSegundoYSoloElUltimoMinuto() {
        val h = com.carobd.core.sim.Historial(listOf("rpm"), segundos = 60)
        var agregadas = 0
        for (i in 0 until 1300) { // 130 s a 10 lecturas por segundo
            if (h.agregar(com.carobd.core.sim.Lectura(i * 100L, mapOf("rpm" to i.toDouble())))) agregadas++
        }
        assertEquals(130, agregadas)
        val s = h.serie("rpm")
        assertEquals(60, s.size)
        assertEquals(1290.0, s.last())
        assertTrue(h.serie("velocidad").isEmpty())
    }
}
