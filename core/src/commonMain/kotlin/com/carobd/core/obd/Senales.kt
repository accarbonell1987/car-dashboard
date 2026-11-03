package com.carobd.core.obd

import kotlinx.serialization.Serializable

@Serializable
enum class Ritmo { RAPIDO, MEDIO, LENTO }

/**
 * Una señal del catálogo: qué pedir al adaptador y cómo convertir la respuesta.
 * `modo` y `pid` en hex (p. ej. "01" y "0C"); `cabecera` solo para PIDs de fabricante (modo 22, "7E0").
 * `formula` usa A, B, C, D como los bytes de datos; es el mismo esquema que Torque.
 * Las señales calculadas no tienen PID y se derivan de otras (`dependeDe`).
 */
@Serializable
data class Senal(
    val id: String,
    val nombre: String,
    val unidad: String,
    val modo: String? = null,
    val pid: String? = null,
    val cabecera: String? = null,
    val formula: String? = null,
    val bytes: Int = 1,
    val min: Double,
    val max: Double,
    val ritmo: Ritmo,
    val dependeDe: List<String> = emptyList(),
)

/** Catálogo estándar (modo 01, SAE J1979). Los PIDs de Hyundai se añaden al descubrirlos con el vLinker. */
object Catalogo {
    val estandar: List<Senal> = listOf(
        Senal("rpm", "RPM", "rpm", "01", "0C", formula = "(A*256+B)/4", bytes = 2, min = 0.0, max = 5000.0, ritmo = Ritmo.RAPIDO),
        Senal("velocidad", "Velocidad", "km/h", "01", "0D", formula = "A", min = 0.0, max = 200.0, ritmo = Ritmo.RAPIDO),
        Senal("carga", "Carga del motor", "%", "01", "04", formula = "A*100/255", min = 0.0, max = 100.0, ritmo = Ritmo.RAPIDO),
        Senal("acelerador", "Acelerador", "%", "01", "11", formula = "A*100/255", min = 0.0, max = 100.0, ritmo = Ritmo.RAPIDO),
        Senal("map", "Presión de admisión", "kPa", "01", "0B", formula = "A", min = 0.0, max = 255.0, ritmo = Ritmo.RAPIDO),
        Senal("maf", "Flujo de aire", "g/s", "01", "10", formula = "(A*256+B)/100", bytes = 2, min = 0.0, max = 300.0, ritmo = Ritmo.MEDIO),
        Senal("temp_refrigerante", "Temp. refrigerante", "°C", "01", "05", formula = "A-40", min = -40.0, max = 130.0, ritmo = Ritmo.MEDIO),
        Senal("temp_admision", "Temp. admisión", "°C", "01", "0F", formula = "A-40", min = -40.0, max = 80.0, ritmo = Ritmo.MEDIO),
        Senal("temp_aceite", "Temp. aceite", "°C", "01", "5C", formula = "A-40", min = -40.0, max = 150.0, ritmo = Ritmo.MEDIO),
        Senal("presion_riel", "Presión de riel", "bar", "01", "23", formula = "(A*256+B)*10/100", bytes = 2, min = 0.0, max = 1800.0, ritmo = Ritmo.MEDIO),
        Senal("consumo_lh", "Consumo", "L/h", "01", "5E", formula = "(A*256+B)/20", bytes = 2, min = 0.0, max = 60.0, ritmo = Ritmo.MEDIO),
        Senal("combustible", "Combustible", "%", "01", "2F", formula = "A*100/255", min = 0.0, max = 100.0, ritmo = Ritmo.LENTO),
        Senal("voltaje", "Voltaje", "V", "01", "42", formula = "(A*256+B)/1000", bytes = 2, min = 0.0, max = 16.0, ritmo = Ritmo.LENTO),
        Senal("temp_ambiente", "Temp. ambiente", "°C", "01", "46", formula = "A-40", min = -40.0, max = 60.0, ritmo = Ritmo.LENTO),
        Senal("barometrica", "Presión barométrica", "kPa", "01", "33", formula = "A", min = 0.0, max = 255.0, ritmo = Ritmo.LENTO),
        Senal("temp_dpf", "Temp. DPF", "°C", "01", "7C", formula = "(A*256+B)/10-40", bytes = 2, min = 0.0, max = 800.0, ritmo = Ritmo.LENTO),
        Senal("tiempo_motor", "Tiempo desde arranque", "s", "01", "1F", formula = "A*256+B", bytes = 2, min = 0.0, max = 65535.0, ritmo = Ritmo.LENTO),
        // Calculadas
        Senal("boost", "Boost", "bar", formula = "(map-barometrica)/100", min = -0.5, max = 1.5, ritmo = Ritmo.RAPIDO, dependeDe = listOf("map", "barometrica")),
        Senal("marcha", "Marcha", "", min = 0.0, max = 5.0, ritmo = Ritmo.RAPIDO, dependeDe = listOf("rpm", "velocidad")),
        Senal("consumo_l100", "Consumo", "L/100 km", min = 0.0, max = 30.0, ritmo = Ritmo.MEDIO, dependeDe = listOf("consumo_lh", "velocidad")),
    )

    fun porId(id: String): Senal? = estandar.firstOrNull { it.id == id }
}
