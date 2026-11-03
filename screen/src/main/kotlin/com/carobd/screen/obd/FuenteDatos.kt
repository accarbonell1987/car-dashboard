package com.carobd.screen.obd

import com.carobd.core.sim.Lectura
import com.carobd.core.sim.SimuladorAccent
import kotlinx.coroutines.flow.Flow

/**
 * De dónde salen los datos del tablero. La UI no sabe si es el simulador o el vLinker:
 * así se desarrolla todo con el modo demo y al llegar el adaptador solo cambia esta pieza.
 */
interface FuenteDatos {
    val nombre: String
    fun lecturas(): Flow<Lectura>
}

class FuenteDemo : FuenteDatos {
    override val nombre = "Demo"
    override fun lecturas(): Flow<Lectura> = SimuladorAccent().lecturas()
}
