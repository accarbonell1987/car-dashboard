package com.carobd.core.tablero

import com.carobd.core.tablero.TipoGauge.ARCO
import com.carobd.core.tablero.TipoGauge.BARRA
import com.carobd.core.tablero.TipoGauge.MARCHA
import com.carobd.core.tablero.TipoGauge.NUMERO

/** Tableros que trae la app para el Accent 1.6 CRDi en la pantalla de 1280x720 (grilla de 6x3). */
object Tableros {
    private fun c(id: String, senal: String, tipo: TipoGauge, col: Int, fila: Int, ancho: Int = 1, alto: Int = 1) =
        Celda(id, senal, tipo, col, fila, ancho, alto)

    val manejo = DefinicionTablero(
        id = "manejo",
        nombre = "Manejo",
        celdas = listOf(
            c("m1", "rpm", ARCO, 0, 0, 2, 2),
            c("m2", "velocidad", ARCO, 2, 0, 2, 2),
            c("m3", "marcha", MARCHA, 4, 0),
            c("m4", "boost", ARCO, 5, 0),
            c("m5", "temp_refrigerante", ARCO, 4, 1),
            c("m6", "consumo_l100", NUMERO, 5, 1),
            c("m7", "carga", BARRA, 0, 2),
            c("m8", "acelerador", BARRA, 1, 2),
            c("m9", "temp_aceite", NUMERO, 2, 2),
            c("m10", "presion_riel", NUMERO, 3, 2),
            c("m11", "voltaje", NUMERO, 4, 2),
            c("m12", "combustible", BARRA, 5, 2),
        ),
    )

    /** Salud del motor: temperaturas, carga y eléctrico, para revisar con el auto parado o en ruta. */
    val motor = DefinicionTablero(
        id = "motor",
        nombre = "Motor",
        celdas = listOf(
            c("o1", "temp_refrigerante", ARCO, 0, 0, 2, 2),
            c("o2", "temp_aceite", ARCO, 2, 0, 2, 2),
            c("o3", "voltaje", ARCO, 4, 0, 2, 2),
            c("o4", "temp_admision", NUMERO, 0, 2),
            c("o5", "temp_ambiente", NUMERO, 1, 2),
            c("o6", "carga", BARRA, 2, 2),
            c("o7", "rpm", NUMERO, 3, 2),
            c("o8", "tiempo_motor", NUMERO, 4, 2),
            c("o9", "barometrica", NUMERO, 5, 2),
        ),
    )

    /** Lo propio del diésel: turbo, riel común, aire y filtro de partículas. */
    val diesel = DefinicionTablero(
        id = "diesel",
        nombre = "Diésel",
        celdas = listOf(
            c("d1", "boost", ARCO, 0, 0, 2, 2),
            c("d2", "presion_riel", ARCO, 2, 0, 2, 2),
            c("d3", "temp_dpf", ARCO, 4, 0, 2, 2),
            c("d4", "maf", NUMERO, 0, 2),
            c("d5", "map", NUMERO, 1, 2),
            c("d6", "consumo_lh", NUMERO, 2, 2),
            c("d7", "consumo_l100", NUMERO, 3, 2),
            c("d8", "acelerador", BARRA, 4, 2),
            c("d9", "temp_admision", NUMERO, 5, 2),
        ),
    )

    val predeterminados: List<DefinicionTablero> = listOf(manejo, motor, diesel)
}
