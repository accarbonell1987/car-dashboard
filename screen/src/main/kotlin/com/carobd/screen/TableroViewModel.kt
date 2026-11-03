package com.carobd.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carobd.core.sync.SyncJson
import com.carobd.core.tablero.Alerta
import com.carobd.core.tablero.Alertas
import com.carobd.core.tablero.DefinicionTablero
import com.carobd.core.tablero.Grilla
import com.carobd.core.tablero.Tableros
import com.carobd.core.tablero.TipoGauge
import com.carobd.screen.obd.FuenteDatos
import com.carobd.screen.ui.AccionesTablero
import com.carobd.screen.obd.FuenteDemo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalTime
import java.util.UUID

enum class ModoNoche(val etiqueta: String) { AUTO("Auto"), DIA("Día"), NOCHE("Noche") }

data class EstadoTablero(
    val tableros: List<DefinicionTablero>,
    val indice: Int = 0,
    val valores: Map<String, Double> = emptyMap(),
    val alertas: List<Alerta> = emptyList(),
    val fuente: String = "",
    val editando: Boolean = false,
    val modoNoche: ModoNoche = ModoNoche.AUTO,
    val esDeNoche: Boolean = false,
) {
    val actual: DefinicionTablero get() = tableros[indice.coerceIn(0, tableros.lastIndex)]
}

class TableroViewModel(app: Application) : AndroidViewModel(app), AccionesTablero {
    private val prefs = app.getSharedPreferences("tablero", Context.MODE_PRIVATE)
    private val serializador = ListSerializer(DefinicionTablero.serializer())

    private val _estado = MutableStateFlow(
        EstadoTablero(
            tableros = cargarTableros(),
            indice = prefs.getInt("indice", 0),
            modoNoche = runCatching { ModoNoche.valueOf(prefs.getString("modo_noche", "AUTO")!!) }.getOrDefault(ModoNoche.AUTO),
        ),
    )
    val estado: StateFlow<EstadoTablero> = _estado.asStateFlow()
    private var lectura: Job? = null

    init {
        usarFuente(FuenteDemo())
        // Revisa cada minuto si ya es de noche (modo Auto).
        viewModelScope.launch {
            while (true) {
                actualizarNoche()
                delay(60_000)
            }
        }
    }

    fun usarFuente(fuente: FuenteDatos) {
        lectura?.cancel()
        _estado.update { it.copy(fuente = fuente.nombre, valores = emptyMap(), alertas = emptyList()) }
        lectura = viewModelScope.launch {
            fuente.lecturas().collect { l ->
                _estado.update { it.copy(valores = l.valores, alertas = Alertas.evaluar(l.valores)) }
            }
        }
    }

    // --- Navegación y modos ---

    override fun seleccionar(indice: Int) {
        _estado.update { it.copy(indice = indice.coerceIn(0, it.tableros.lastIndex)) }
        prefs.edit().putInt("indice", _estado.value.indice).apply()
    }

    override fun alternarEdicion() = _estado.update { it.copy(editando = !it.editando) }

    override fun siguienteModoNoche() {
        val siguiente = ModoNoche.entries[(_estado.value.modoNoche.ordinal + 1) % ModoNoche.entries.size]
        _estado.update { it.copy(modoNoche = siguiente) }
        prefs.edit().putString("modo_noche", siguiente.name).apply()
        actualizarNoche()
    }

    private fun actualizarNoche() {
        val hora = LocalTime.now().hour
        _estado.update {
            it.copy(
                esDeNoche = when (it.modoNoche) {
                    ModoNoche.DIA -> false
                    ModoNoche.NOCHE -> true
                    ModoNoche.AUTO -> hora >= 19 || hora < 7
                },
            )
        }
    }

    // --- Edición del tablero actual ---

    private fun editar(cambio: (DefinicionTablero) -> DefinicionTablero) {
        _estado.update { e ->
            val nuevos = e.tableros.toMutableList()
            nuevos[e.indice] = cambio(e.actual)
            e.copy(tableros = nuevos)
        }
        guardarTableros(_estado.value.tableros)
    }

    override fun mover(id: String, col: Int, fila: Int) = editar { Grilla.mover(it, id, col, fila) }

    override fun redimensionar(id: String, ancho: Int, alto: Int) = editar { Grilla.redimensionar(it, id, ancho, alto) }

    override fun cambiarSenal(id: String, senalId: String, tipo: TipoGauge) = editar { Grilla.cambiarSenal(it, id, senalId, tipo) }

    override fun quitar(id: String) = editar { Grilla.quitar(it, id) }

    /** Agrega un gauge de 1x1 en el primer hueco libre. Devuelve false si el tablero está lleno. */
    override fun agregar(senalId: String, tipo: TipoGauge): Boolean {
        val antes = _estado.value.actual
        val tipoReal = if (senalId == "marcha") TipoGauge.MARCHA else tipo
        editar { Grilla.agregar(it, UUID.randomUUID().toString(), senalId, tipoReal) }
        return _estado.value.actual !== antes
    }

    /** Vuelve el tablero actual a como viene de fábrica. */
    override fun restaurar() {
        val original = Tableros.predeterminados.firstOrNull { it.id == _estado.value.actual.id } ?: return
        editar { original }
    }

    // --- Persistencia (en la fase 3 pasa a la base local y se sincroniza como Tablero) ---

    private fun cargarTableros(): List<DefinicionTablero> {
        val guardados = prefs.getString("tableros", null)
            ?.let { runCatching { SyncJson.json.decodeFromString(serializador, it) }.getOrNull() }
            .orEmpty()
        // Los predeterminados que falten (p. ej. tras una actualización) se agregan al final.
        val faltan = Tableros.predeterminados.filter { p -> guardados.none { it.id == p.id } }
        return (guardados + faltan).ifEmpty { Tableros.predeterminados }
    }

    private fun guardarTableros(t: List<DefinicionTablero>) {
        prefs.edit().putString("tableros", SyncJson.json.encodeToString(serializador, t)).apply()
    }
}
