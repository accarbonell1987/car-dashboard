package com.carobd.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carobd.core.sync.SyncJson
import com.carobd.screen.gauges.Celda
import com.carobd.screen.gauges.DefinicionTablero
import com.carobd.screen.gauges.Tableros
import com.carobd.screen.obd.FuenteDatos
import com.carobd.screen.obd.FuenteDemo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoTablero(
    val tablero: DefinicionTablero,
    val valores: Map<String, Double> = emptyMap(),
    val fuente: String = "",
    val editando: Boolean = false,
)

class TableroViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tablero", Context.MODE_PRIVATE)
    private val _estado = MutableStateFlow(EstadoTablero(tablero = cargar()))
    val estado: StateFlow<EstadoTablero> = _estado.asStateFlow()
    private var lectura: Job? = null

    init {
        usarFuente(FuenteDemo())
    }

    fun usarFuente(fuente: FuenteDatos) {
        lectura?.cancel()
        _estado.update { it.copy(fuente = fuente.nombre, valores = emptyMap()) }
        lectura = viewModelScope.launch {
            fuente.lecturas().collect { l -> _estado.update { it.copy(valores = l.valores) } }
        }
    }

    fun alternarEdicion() = _estado.update { it.copy(editando = !it.editando) }

    fun cambiarCelda(indice: Int, celda: Celda) {
        _estado.update { e ->
            val celdas = e.tablero.celdas.toMutableList().also { it[indice] = celda }
            e.copy(tablero = e.tablero.copy(celdas = celdas))
        }
        guardar(_estado.value.tablero)
    }

    // Por ahora el tablero vive en SharedPreferences; en la fase 3 pasa a Room y se sincroniza como Tablero.
    private fun cargar(): DefinicionTablero =
        prefs.getString("definicion", null)
            ?.let { runCatching { SyncJson.json.decodeFromString(DefinicionTablero.serializer(), it) }.getOrNull() }
            ?: Tableros.manejo

    private fun guardar(t: DefinicionTablero) {
        prefs.edit().putString("definicion", SyncJson.json.encodeToString(DefinicionTablero.serializer(), t)).apply()
    }
}
