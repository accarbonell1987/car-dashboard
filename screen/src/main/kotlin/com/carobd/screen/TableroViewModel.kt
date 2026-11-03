package com.carobd.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carobd.core.datos.Bitacora
import com.carobd.core.model.DatosGaraje
import com.carobd.core.sim.Historial
import com.carobd.core.sync.ConfigNube
import com.carobd.core.sync.EstadoNube
import com.carobd.core.sync.SyncJson
import com.carobd.core.viaje.RegistradorViaje
import com.carobd.core.tablero.Alerta
import com.carobd.core.tablero.Alertas
import com.carobd.core.tablero.DefinicionTablero
import com.carobd.core.tablero.Grilla
import com.carobd.core.tablero.Tableros
import com.carobd.core.tablero.TipoGauge
import com.carobd.screen.obd.FuenteDatos
import com.carobd.screen.ui.AccionesTablero
import com.carobd.screen.obd.FuenteDemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalTime
import java.util.UUID

enum class ModoNoche(val etiqueta: String) { AUTO("Auto"), DIA("Día"), NOCHE("Noche") }

/** Las pestañas de la barra inferior. */
enum class Seccion(val etiqueta: String) { INICIO("Inicio"), TABLERO("Tablero"), DIAGNOSTICO("Diagnóstico"), VEHICULO("Vehículo"), AJUSTES("Ajustes") }

/** Señales que guarda la gráfica del último minuto en Diagnóstico. */
val SENALES_GRAFICA = listOf("temp_refrigerante", "voltaje", "boost", "rpm")

data class EstadoTablero(
    val tableros: List<DefinicionTablero>,
    val indice: Int = 0,
    val valores: Map<String, Double> = emptyMap(),
    val alertas: List<Alerta> = emptyList(),
    val fuente: String = "",
    val editando: Boolean = false,
    val modoNoche: ModoNoche = ModoNoche.AUTO,
    val esDeNoche: Boolean = false,
    val nube: EstadoNube = EstadoNube(),
    val config: ConfigNube = ConfigNube(),
    val odometroKm: Double = 0.0,
    val viajeEnCursoKm: Double? = null,
    val ultimoViajeKm: Double? = null,
    val viajeEnCursoSeg: Long = 0,
    val viajeEnCursoL100: Double? = null,
    val seccion: Seccion = Seccion.INICIO,
    val esDemo: Boolean = true,
    /** Vehículo, plan de mantenimiento y servicios tal como llegaron del móvil por la nube. */
    val garaje: DatosGaraje? = null,
    /** Último minuto de [SENALES_GRAFICA], una muestra por segundo. */
    val series: Map<String, List<Double>> = emptyMap(),
    /** Brillo de la ventana de 0,1 a 1; null deja el del sistema. */
    val brillo: Float? = null,
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
            seccion = runCatching { Seccion.valueOf(prefs.getString("seccion", "INICIO")!!) }.getOrDefault(Seccion.INICIO),
            brillo = prefs.getFloat("brillo", -1f).takeIf { it > 0f },
        ),
    )
    val estado: StateFlow<EstadoTablero> = _estado.asStateFlow()
    private var lectura: Job? = null

    private val datos = DatosLocales(app)
    private val bitacora = datos.bitacora
    private var registrador: RegistradorViaje? = null
    private val historial = Historial(SENALES_GRAFICA)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            bitacora.vehiculo()
            _estado.update { it.copy(config = datos.nube.config, odometroKm = bitacora.odometroKm(), garaje = bitacora.garajeSinCrear()) }
            datos.nube.refrescar()
        }
        viewModelScope.launch { datos.nube.estado.collect { n -> _estado.update { it.copy(nube = n) } } }
        // Sincroniza al abrir y cada 15 minutos; si no hay red, los cambios esperan al siguiente intento.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                datos.nube.sincronizar()
                actualizarOdometro()
                delay(15 * 60_000L)
            }
        }
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
        historial.limpiar()
        _estado.update {
            it.copy(fuente = fuente.nombre, esDemo = !fuente.grabaViajes, valores = emptyMap(), alertas = emptyList(), viajeEnCursoKm = null, series = emptyMap())
        }
        val reg = if (fuente.grabaViajes) {
            RegistradorViaje(Bitacora.ID_ACCENT, reloj = { Clock.System.now() }, nuevoId = { UUID.randomUUID().toString() })
        } else null
        registrador = reg
        lectura = viewModelScope.launch {
            fuente.lecturas()
                // Si el adaptador se desconecta (se apagó el auto) o se cambia de fuente, el viaje en curso se cierra.
                .onCompletion { withContext(NonCancellable) { reg?.cerrar()?.let { guardarViaje(it) } } }
                .catch { _estado.update { it.copy(fuente = "${fuente.nombre}: desconectado", viajeEnCursoKm = null) } }
                .collect { l ->
                    val terminado = reg?.procesar(l)
                    val nuevaMuestra = historial.agregar(l)
                    _estado.update {
                        it.copy(
                            valores = l.valores,
                            alertas = Alertas.evaluar(l.valores),
                            viajeEnCursoKm = reg?.takeIf { r -> r.enCurso }?.kmEnCurso,
                            viajeEnCursoSeg = reg?.segEnCurso ?: 0,
                            viajeEnCursoL100 = reg?.l100EnCurso,
                            series = if (nuevaMuestra) SENALES_GRAFICA.associateWith(historial::serie) else it.series,
                        )
                    }
                    if (terminado != null) guardarViaje(terminado)
                }
        }
    }

    private suspend fun guardarViaje(v: com.carobd.core.model.Viaje) = withContext(Dispatchers.IO) {
        bitacora.guardarViaje(v)
        _estado.update { it.copy(ultimoViajeKm = v.km) }
        actualizarOdometro()
        datos.nube.sincronizar()
    }

    private suspend fun actualizarOdometro() {
        val km = bitacora.odometroKm()
        val g = bitacora.garajeSinCrear()
        _estado.update { it.copy(odometroKm = km, garaje = g) }
    }

    // --- Navegación y pantalla ---

    override fun irA(s: Seccion) {
        _estado.update { it.copy(seccion = s, editando = false) }
        prefs.edit().putString("seccion", s.name).apply()
    }

    override fun elegirModoNoche(m: ModoNoche) {
        _estado.update { it.copy(modoNoche = m) }
        prefs.edit().putString("modo_noche", m.name).apply()
        actualizarNoche()
    }

    override fun fijarBrillo(b: Float?) {
        _estado.update { it.copy(brillo = b) }
        prefs.edit().putFloat("brillo", b ?: -1f).apply()
    }

    // --- Nube ---

    override fun configurarNube(url: String, token: String) {
        val c = ConfigNube(url.trim(), token.trim())
        _estado.update { it.copy(config = c) }
        viewModelScope.launch(Dispatchers.IO) {
            datos.configurar(c)
            datos.nube.sincronizar()
            actualizarOdometro()
        }
    }

    override fun sincronizarAhora() {
        viewModelScope.launch(Dispatchers.IO) {
            datos.nube.sincronizar()
            actualizarOdometro()
        }
    }

    // --- Navegación y modos ---

    override fun seleccionar(indice: Int) {
        _estado.update { it.copy(indice = indice.coerceIn(0, it.tableros.lastIndex)) }
        prefs.edit().putInt("indice", _estado.value.indice).apply()
    }

    override fun alternarEdicion() = _estado.update { it.copy(editando = !it.editando) }

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
