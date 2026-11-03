package com.carobd.core

import com.carobd.core.model.Garaje
import com.carobd.core.model.Mantenimiento
import com.carobd.core.model.texto
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GarajeTest {
    private val ahora = Instant.parse("2026-11-02T12:00:00Z")

    @Test
    fun formateaMiles() {
        assertEquals("90.600", miles(90_600))
        assertEquals("1.000.000", miles(1_000_000))
        assertEquals("950", miles(950))
        assertEquals("-1.200", miles(-1_200))
    }

    @Test
    fun registrarServicioDejaItemsAlDiaYNoBajaElOdometro() {
        val d = Garaje.inicialAccent(ahora)
        val aceite = d.items.first { it.nombre.startsWith("Aceite") }
        val tras = Garaje.registrarServicio(d, 90_500, "Cambio de aceite", setOf(aceite.id), ahora)

        assertEquals(90_600, tras.odometroKm)
        assertEquals(1, tras.servicios.size)
        assertEquals(90_500, tras.items.first { it.id == aceite.id }.ultimoKm)

        val p = Mantenimiento.pendientes(tras.items, tras.odometroKm, ahora).first { it.item.id == aceite.id }
        assertEquals(9_900, p.faltanKm)
        assertEquals("faltan 9.900 km · faltan 365 días", p.texto())
    }

    @Test
    fun corregirOdometroSoloSube() {
        val d = Garaje.inicialAccent(ahora)
        assertEquals(91_000, Garaje.corregirOdometro(d, 91_000).odometroKm)
        assertEquals(90_600, Garaje.corregirOdometro(d, 80_000).odometroKm)
    }

    @Test
    fun itemSinHistorialLoDice() {
        val d = Garaje.inicialAccent(ahora)
        val p = Mantenimiento.pendientes(d.items, d.odometroKm, ahora).first { it.item.nombre == "Líquido de frenos" }
        assertEquals("sin registro previo", p.texto())
    }
}
