package com.carobd.core

import com.carobd.core.clima.Cielo
import com.carobd.core.clima.OpenMeteo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClimaTest {
    @Test
    fun leeLaRespuestaDeOpenMeteo() {
        // Trimmed real response shape from api.open-meteo.com/v1/forecast.
        val json = """
            {"latitude":-34.6,"longitude":-58.38,
             "current":{"time":"2026-11-20T15:00","temperature_2m":24.3,"weather_code":61},
             "daily":{"time":["2026-11-20"],"temperature_2m_max":[27.1],"temperature_2m_min":[18.4]}}
        """.trimIndent()
        val clima = OpenMeteo.leer(json)
        assertEquals(24.3, clima.temperatura)
        assertEquals(18.4, clima.minima)
        assertEquals(27.1, clima.maxima)
        assertEquals("Lluvia", clima.descripcion)
        assertEquals(Cielo.LLUVIA, clima.cielo)
    }

    @Test
    fun cieloSegunCodigoWmo() {
        assertEquals(Cielo.DESPEJADO, OpenMeteo.cielo(0))
        assertEquals(Cielo.NUBLADO, OpenMeteo.cielo(3))
        assertEquals(Cielo.LLUVIA, OpenMeteo.cielo(81))
        assertEquals(Cielo.LLUVIA, OpenMeteo.cielo(95))
    }

    @Test
    fun urlRedondeaLaUbicacion() {
        // Two decimals (~1 km) is enough for weather and leaks less of where the car is.
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=-34.6&longitude=-58.38" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
            OpenMeteo.url(-34.60372, -58.38159),
        )
    }
}
