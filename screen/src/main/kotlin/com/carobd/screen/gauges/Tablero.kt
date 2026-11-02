package com.carobd.screen.gauges

import kotlinx.serialization.Serializable

@Serializable
enum class TipoGauge { ARCO, NUMERO, BARRA, MARCHA }

/** Una celda de la grilla: qué señal muestra, cómo, y dónde (columna, fila, ancho y alto en celdas). */
@Serializable
data class Celda(
    val senalId: String,
    val tipo: TipoGauge,
    val col: Int,
    val fila: Int,
    val ancho: Int = 1,
    val alto: Int = 1,
)

@Serializable
data class DefinicionTablero(
    val nombre: String,
    val columnas: Int = 6,
    val filas: Int = 3,
    val celdas: List<Celda>,
)

object Tableros {
    /** Tablero de manejo propuesto para el Accent CRDi en la pantalla de 1280x720. */
    val manejo = DefinicionTablero(
        nombre = "Manejo",
        celdas = listOf(
            Celda("rpm", TipoGauge.ARCO, col = 0, fila = 0, ancho = 2, alto = 2),
            Celda("velocidad", TipoGauge.ARCO, col = 2, fila = 0, ancho = 2, alto = 2),
            Celda("marcha", TipoGauge.MARCHA, col = 4, fila = 0),
            Celda("boost", TipoGauge.ARCO, col = 5, fila = 0),
            Celda("temp_refrigerante", TipoGauge.ARCO, col = 4, fila = 1),
            Celda("consumo_l100", TipoGauge.NUMERO, col = 5, fila = 1),
            Celda("carga", TipoGauge.BARRA, col = 0, fila = 2),
            Celda("acelerador", TipoGauge.BARRA, col = 1, fila = 2),
            Celda("temp_aceite", TipoGauge.NUMERO, col = 2, fila = 2),
            Celda("presion_riel", TipoGauge.NUMERO, col = 3, fila = 2),
            Celda("voltaje", TipoGauge.NUMERO, col = 4, fila = 2),
            Celda("combustible", TipoGauge.BARRA, col = 5, fila = 2),
        ),
    )
}
