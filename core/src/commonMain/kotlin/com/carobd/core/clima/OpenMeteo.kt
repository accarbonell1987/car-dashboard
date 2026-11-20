package com.carobd.core.clima

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

enum class Cielo { DESPEJADO, NUBLADO, LLUVIA }

data class Clima(val temperatura: Double, val minima: Double, val maxima: Double, val codigo: Int) {
    val descripcion get() = OpenMeteo.descripcion(codigo)
    val cielo get() = OpenMeteo.cielo(codigo)
}

/** Current weather from Open-Meteo: free, no API key. Codes are WMO weather codes. */
object OpenMeteo {
    private fun redondear(grados: Double) = (grados * 100).roundToInt() / 100.0

    fun url(latitud: Double, longitud: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=${redondear(latitud)}&longitude=${redondear(longitud)}" +
            "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"

    fun leer(json: String): Clima {
        val raiz = Json.parseToJsonElement(json).jsonObject
        val actual = raiz.getValue("current").jsonObject
        val dia = raiz.getValue("daily").jsonObject
        return Clima(
            temperatura = actual.getValue("temperature_2m").jsonPrimitive.double,
            minima = dia.getValue("temperature_2m_min").jsonArray[0].jsonPrimitive.double,
            maxima = dia.getValue("temperature_2m_max").jsonArray[0].jsonPrimitive.double,
            codigo = actual.getValue("weather_code").jsonPrimitive.int,
        )
    }

    fun descripcion(codigo: Int) = when (codigo) {
        0 -> "Despejado"
        1 -> "Mayormente despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Niebla"
        in 51..57 -> "Llovizna"
        in 61..67 -> "Lluvia"
        in 71..77 -> "Nieve"
        in 80..82 -> "Chubascos"
        in 95..99 -> "Tormenta"
        else -> "Sin datos"
    }

    fun cielo(codigo: Int) = when {
        codigo <= 1 -> Cielo.DESPEJADO
        codigo in 51..67 || codigo in 80..99 -> Cielo.LLUVIA
        else -> Cielo.NUBLADO
    }
}
