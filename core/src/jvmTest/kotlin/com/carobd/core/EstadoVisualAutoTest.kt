package com.carobd.core

import com.carobd.core.auto.DemoAuto
import com.carobd.core.auto.EstadoVisualAuto
import com.carobd.core.auto.LucesAuto
import com.carobd.core.auto.SenalesLuces
import com.carobd.core.obd.MotorExtendido
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EstadoVisualAutoTest {
    @Test
    fun elFrenoRealGanaAlInterruptor() {
        val pisado = EstadoVisualAuto.combinar(mapOf(MotorExtendido.FRENO to 1.0), manual = LucesAuto(stop = false))
        assertTrue(pisado.luces.stop)
        val suelto = EstadoVisualAuto.combinar(mapOf(MotorExtendido.FRENO to 0.0), manual = LucesAuto(stop = true))
        assertFalse(suelto.luces.stop)
        // Without the 22ED01 block (no adapter) the toggle decides.
        assertTrue(EstadoVisualAuto.combinar(emptyMap(), manual = LucesAuto(stop = true)).luces.stop)
    }

    @Test
    fun lasLucesSinDatoSalenDelInterruptorYDelDemo() {
        val e = EstadoVisualAuto.combinar(emptyMap(), manual = LucesAuto(baja = true), demo = DemoAuto.en(1_000))
        assertTrue(e.luces.baja)
        assertTrue(e.luces.giroIzq)
        assertTrue(e.luces.posicionEncendida)
    }

    @Test
    fun unaFuenteFuturaConLucesPisaLosInterruptores() {
        val e = EstadoVisualAuto.combinar(
            mapOf(SenalesLuces.GIRO_DER to 1.0, SenalesLuces.BAJA to 0.0),
            manual = LucesAuto(baja = true),
        )
        assertTrue(e.luces.giroDer)
        assertFalse(e.luces.baja)
    }

    @Test
    fun balizasParpadeanAmbosLados() {
        val l = LucesAuto(balizas = true)
        assertTrue(l.parpadeaIzq && l.parpadeaDer)
        assertFalse(EstadoVisualAuto(l).quieto)
        assertTrue(EstadoVisualAuto(LucesAuto(baja = true, stop = true)).quieto)
    }

    @Test
    fun lluviaYVelocidad() {
        val clima = EstadoVisualAuto.intensidadLluvia(63)
        assertEquals(0.7f, clima)
        assertEquals(0f, EstadoVisualAuto.intensidadLluvia(3))
        val e = EstadoVisualAuto.combinar(mapOf("velocidad" to 72.0), lluviaClima = clima)
        assertEquals(72f, e.velocidadKmh)
        assertEquals(0.7f, e.lluvia)
        // The test override wins, also to turn rain off.
        assertEquals(0f, EstadoVisualAuto.combinar(emptyMap(), lluviaClima = 1f, lluviaManual = 0f).lluvia)
        assertEquals(0.2f, EstadoVisualAuto.combinar(mapOf(SenalesLuces.LLUVIA to 0.2), lluviaClima = 1f).lluvia)
    }

    @Test
    fun elDemoRecorreTodasLasLuces() {
        val escenas = (0 until DemoAuto.PERIODO_MS step 500).map { DemoAuto.en(it) }
        assertTrue(escenas.any { it.luces.giroIzq && !it.luces.giroDer })
        assertTrue(escenas.any { it.luces.giroDer && !it.luces.giroIzq })
        assertTrue(escenas.any { it.luces.balizas })
        assertTrue(escenas.any { it.luces.alta })
        assertTrue(escenas.any { it.luces.baja && !it.luces.alta })
        assertTrue(escenas.any { it.lluvia > 0f })
        assertTrue(escenas.any { it.lluvia == 0f })
        // Repeats.
        assertEquals(DemoAuto.en(3_000), DemoAuto.en(DemoAuto.PERIODO_MS + 3_000))
    }
}
