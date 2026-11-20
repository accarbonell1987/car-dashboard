package com.carobd.screen.obd

import com.carobd.core.obd.Catalogo
import com.carobd.core.obd.Elm327
import com.carobd.core.obd.EstadoConexion
import com.carobd.core.obd.TransporteElm
import com.carobd.core.sim.Lectura
import com.carobd.core.sim.SimuladorAccent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * De dónde salen los datos del tablero. La UI no sabe si es el simulador, el emulador o el adaptador real:
 * así se desarrolla todo sin el auto y al conectar el adaptador solo cambia esta pieza.
 */
interface FuenteDatos {
    val nombre: String
    /** El modo demo no graba viajes: ensuciaría el odómetro y la historia del auto. */
    val grabaViajes: Boolean get() = true
    /** Estado del enlace con el adaptador; null en las fuentes sin conexión (demo). */
    val conexion: StateFlow<EstadoConexion>? get() = null
    fun lecturas(): Flow<Lectura>
}

class FuenteDemo : FuenteDatos {
    override val nombre = "Demo"
    override val grabaViajes = false
    override fun lecturas(): Flow<Lectura> = SimuladorAccent().lecturas()
}

/** Un ELM327 sobre cualquier transporte (Bluetooth, TCP). Excluye lo que el Accent no da por OBD. */
class FuenteElm327(override val nombre: String, transporte: TransporteElm, override val grabaViajes: Boolean = true) : FuenteDatos {
    private val elm = Elm327(transporte, excluir = Catalogo.noSoportadasAccent)
    override val conexion: StateFlow<EstadoConexion> = elm.estado
    override fun lecturas(): Flow<Lectura> = elm.lecturas()
}
