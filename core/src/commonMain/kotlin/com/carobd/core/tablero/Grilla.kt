package com.carobd.core.tablero

import kotlinx.serialization.Serializable

@Serializable
enum class TipoGauge { ARCO, NUMERO, BARRA, MARCHA }

/** Una celda de la grilla: qué señal muestra, cómo, y dónde (columna, fila, ancho y alto en celdas). */
@Serializable
data class Celda(
    val id: String,
    val senalId: String,
    val tipo: TipoGauge,
    val col: Int,
    val fila: Int,
    val ancho: Int = 1,
    val alto: Int = 1,
) {
    fun cubre(c: Int, f: Int): Boolean = c in col until col + ancho && f in fila until fila + alto
    fun choca(otra: Celda): Boolean =
        col < otra.col + otra.ancho && otra.col < col + ancho && fila < otra.fila + otra.alto && otra.fila < fila + alto
}

/** Un tablero: nombre, tamaño de la grilla y sus celdas. Se guarda como JSON en la entidad Tablero. */
@Serializable
data class DefinicionTablero(
    val id: String,
    val nombre: String,
    val columnas: Int = 6,
    val filas: Int = 3,
    val celdas: List<Celda>,
)

/**
 * Operaciones de edición. Todas devuelven un tablero nuevo; si el cambio no cabe
 * (se sale de la grilla o pisa otra celda) devuelven el mismo tablero sin tocar.
 */
object Grilla {
    const val MAX_LADO = 4

    fun cabe(t: DefinicionTablero, c: Celda): Boolean =
        c.ancho in 1..MAX_LADO && c.alto in 1..MAX_LADO &&
            c.col >= 0 && c.fila >= 0 && c.col + c.ancho <= t.columnas && c.fila + c.alto <= t.filas &&
            t.celdas.none { it.id != c.id && it.choca(c) }

    private fun reemplazar(t: DefinicionTablero, c: Celda): DefinicionTablero =
        if (cabe(t, c)) t.copy(celdas = t.celdas.map { if (it.id == c.id) c else it }) else t

    fun mover(t: DefinicionTablero, id: String, col: Int, fila: Int): DefinicionTablero {
        val c = t.celdas.firstOrNull { it.id == id } ?: return t
        return reemplazar(t, c.copy(col = col, fila = fila))
    }

    fun redimensionar(t: DefinicionTablero, id: String, ancho: Int, alto: Int): DefinicionTablero {
        val c = t.celdas.firstOrNull { it.id == id } ?: return t
        return reemplazar(t, c.copy(ancho = ancho, alto = alto))
    }

    fun cambiarSenal(t: DefinicionTablero, id: String, senalId: String, tipo: TipoGauge): DefinicionTablero =
        t.copy(celdas = t.celdas.map { if (it.id == id) it.copy(senalId = senalId, tipo = tipo) else it })

    fun quitar(t: DefinicionTablero, id: String): DefinicionTablero = t.copy(celdas = t.celdas.filterNot { it.id == id })

    /** Primer hueco libre de ancho x alto, recorriendo por filas. */
    fun huecoLibre(t: DefinicionTablero, ancho: Int = 1, alto: Int = 1): Pair<Int, Int>? {
        for (f in 0..t.filas - alto) for (c in 0..t.columnas - ancho) {
            if (cabe(t, Celda("", "", TipoGauge.NUMERO, c, f, ancho, alto))) return c to f
        }
        return null
    }

    /** Agrega un gauge de 1x1 en el primer hueco. Devuelve el mismo tablero si está lleno. */
    fun agregar(t: DefinicionTablero, id: String, senalId: String, tipo: TipoGauge): DefinicionTablero {
        val (c, f) = huecoLibre(t) ?: return t
        return t.copy(celdas = t.celdas + Celda(id, senalId, tipo, c, f))
    }

    /** Convierte un arrastre en píxeles a celdas, redondeando a la celda más cercana. */
    fun aCeldas(desplazamientoPx: Float, tamanoCeldaPx: Float): Int =
        if (tamanoCeldaPx <= 0f) 0 else kotlin.math.round(desplazamientoPx / tamanoCeldaPx).toInt()
}
