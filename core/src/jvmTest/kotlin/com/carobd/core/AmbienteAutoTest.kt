package com.carobd.core

import com.carobd.core.auto.AmbienteAuto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class AmbienteAutoTest {
    @Test
    fun `without a weather code only the clock counts`() {
        assertEquals(AmbienteAuto(21.5f), AmbienteAuto.de(null, 21.5f))
    }

    @Test
    fun `clear, overcast, fog and snow each set their own part`() {
        assertEquals(AmbienteAuto(12f), AmbienteAuto.de(0, 12f))
        assertEquals(1f, AmbienteAuto.de(3, 12f).nubes)
        assertEquals(1f, AmbienteAuto.de(45, 12f).niebla)
        assertEquals(1f, AmbienteAuto.de(75, 12f).nieve)
        assertEquals(0f, AmbienteAuto.de(63, 12f).nieve)
    }
}
