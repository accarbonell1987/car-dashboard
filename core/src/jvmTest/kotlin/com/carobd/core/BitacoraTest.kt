package com.carobd.core

import com.carobd.core.datos.Bitacora
import com.carobd.core.model.CatalogoMantenimiento
import com.carobd.core.model.Garaje
import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.OrigenOdometro
import com.carobd.core.model.OrigenViaje
import com.carobd.core.model.Viaje
import com.carobd.core.sync.AlmacenPersistente
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun elOdometroDelTableroManda() = runTest {
        val b = bitacora()
        b.vehiculo()
        assertFalse(b.guardarOdometroTablero(1_456_947.2), "aceptó un valor imposible")
        // The estimate overshot (bad trips): the cluster corrects it downwards.
        repeat(2) { b.guardarViaje(viaje(600.0)) }
        assertEquals(91_800.0, b.odometroKm(), 1e-9)
        ahora += 1.hours
        assertTrue(b.guardarOdometroTablero(91_294.4))
        assertEquals(91_294.0, b.odometroKm(), 1e-9)
        b.guardarViaje(viaje(2.5))
        assertEquals(91_296.5, b.odometroKm(), 1e-9)
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
        assertEquals(viejo.items.map { it.codigo }.toSet(), g.items.map { it.codigo }.toSet())
        assertEquals(viejo.items.size, g.items.size)
        assertTrue(g.items.all { it.vehiculoId == Bitacora.ID_ACCENT })
        assertEquals(90_800, g.odometroKm)
        assertEquals(OrigenOdometro.MANUAL, b.almacen.leer { it.lecturasOdometro.values.single().origen })
    }

    @Test
    fun elMovilYLaPantallaCreanElPlanPorSeparadoYAlSincronizarNoHayRepetidos() = runTest {
        val movil = bitacora()
        val pantalla = bitacora()
        movil.garaje()
        ahora += 1.hours
        pantalla.garaje()
        pantalla.almacen.aplicarRemotos(movil.almacen.pendientes())
        val items = pantalla.almacen.leer { m -> m.itemsMantenimiento.values.filter { it.deletedAt == null } }
        assertEquals(19, items.size)
    }

    @Test
    fun elPlanViejoDeOchoItemsSeMigraSinPerderElUltimoServicio() = runTest {
        val b = bitacora()
        val v = b.vehiculo()
        val viejos = listOf(
            ItemMantenimiento("old-1", v.id, "Aceite y filtro de aceite", 10_000, 12, ultimoKm = 88_000, ultimaFecha = ahora, updatedAt = ahora),
            ItemMantenimiento("old-2", v.id, "Correa de accesorios", 50_000, null, ultimoKm = 80_000, updatedAt = ahora),
            ItemMantenimiento("old-3", v.id, "Lavado de motor", 30_000, null, updatedAt = ahora),
        )
        b.almacen.guardar(*viejos.toTypedArray())
        ahora += 1.hours
        val g = b.garaje()

        val aceite = g.items.single { it.codigo == CatalogoMantenimiento.ACEITE_MOTOR }
        assertEquals(88_000, aceite.ultimoKm)
        assertFalse(aceite.personalizado)
        val correas = g.items.single { it.codigo == "CORREAS" }
        assertEquals(80_000, correas.ultimoKm)
        assertTrue(correas.personalizado, "el usuario había cambiado el intervalo: se respeta")
        assertEquals(50_000, correas.cadaKm)
        assertTrue(g.items.any { it.id == "old-3" }, "un ítem propio del usuario se queda")
        assertTrue(g.items.none { it.id == "old-1" || it.id == "old-2" })
        assertEquals(20, g.items.size)

        // Correr la migración otra vez no cambia nada.
        val antes = b.almacen.leer { m -> m.itemsMantenimiento.toMap() }
        b.garaje()
        assertEquals(antes, b.almacen.leer { m -> m.itemsMantenimiento.toMap() })
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
