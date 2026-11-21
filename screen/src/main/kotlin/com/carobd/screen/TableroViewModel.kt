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
import com.carobd.screen.obd.FuenteElm327
import com.carobd.screen.obd.DispositivoBle
import com.carobd.screen.obd.TransporteBle
import com.carobd.core.obd.EstadoConexion
import com.carobd.core.obd.Carroceria
import com.carobd.core.obd.FaseConexion
import com.carobd.core.obd.TransporteTcp
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

/** Qué fuente de datos eligió el usuario en Ajustes. */
enum class TipoFuente { DEMO, EMULADOR, BLE }

enum class ModoNoche(val etiqueta: String) { AUTO("Auto"), DIA("Día"), NOCHE("Noche") }

/** Las pestañas de la barra inferior. */
enum class Seccion(val etiqueta: String) { INICIO("Inicio"), TABLERO("Tablero"), DIAGNOSTICO("Salud"), VEHICULO("Vehículo"), AJUSTES("Ajustes") }

/** Señales que guarda la gráfica del último minuto en Diagnóstico. */
val SENALES_GRAFICA = listOf("temp_refrigerante", "voltaje", "boost", "rpm")

const val DIRECCION_EMULADOR = "10.0.2.2:35000"

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
    /** True once this connection read the cluster's own odometer; otherwise [odometroKm] is the estimate. */
    val odometroDelTablero: Boolean = false,
    val viajeEnCursoKm: Double? = null,
    val ultimoViajeKm: Double? = null,
    val viajeEnCursoSeg: Long = 0,
    val viajeEnCursoL100: Double? = null,
    val seccion: Seccion = Seccion.INICIO,
    val esDemo: Boolean = true,
    val tipoFuente: TipoFuente = TipoFuente.DEMO,
    /** host:puerto del emulador ELM327 por TCP; 10.0.2.2 es el localhost del ordenador visto desde el emulador de Android. */
    val direccionEmulador: String = DIRECCION_EMULADOR,
    /** Adaptador ELM327 BLE elegido en Ajustes (dirección MAC y nombre), o null si nunca se eligió. */
    val adaptadorBle: DispositivoBle? = null,
    /** Estado del enlace con el adaptador; null en modo demo. */
    val conexion: EstadoConexion? = null,
    /** Escuchar el bus para los testigos de puerta, cinturón y A/C. */
    val testigos: Boolean = true,
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
            direccionEmulador = prefs.getString("emulador", DIRECCION_EMULADOR)!!,
            testigos = prefs.getBoolean("testigos", true),
            adaptadorBle = prefs.getString("ble_direccion", null)?.let { DispositivoBle(it, prefs.getString("ble_nombre", null) ?: it, 0) },
        ),
    )
    val estado: StateFlow<EstadoTablero> = _estado.asStateFlow()
    private var lectura: Job? = null
    private var vigilancia: Job? = null
    private var fuenteActual: FuenteDatos? = null
    private val _registro = MutableStateFlow<List<String>>(emptyList())
    override val registro: StateFlow<List<String>> = _registro.asStateFlow()

    private val datos = DatosLocales(app)
    private val bitacora = datos.bitacora
    private var registrador: RegistradorViaje? = null
    private var odometroLeido = false
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
        val ble = _estado.value.adaptadorBle
        when (prefs.getString("fuente", null)) {
            TipoFuente.EMULADOR.name -> usarEmulador(_estado.value.direccionEmulador)
            // Al abrir la app se reconecta al último adaptador BLE elegido.
            TipoFuente.BLE.name -> if (ble != null) usarBle(ble) else usarDemo()
            else -> usarDemo()
        }
        // Revisa cada minuto si ya es de noche (modo Auto).
        viewModelScope.launch {
            while (true) {
                actualizarNoche()
                delay(60_000)
            }
        }
    }

    override fun usarDemo() {
        prefs.edit().putString("fuente", TipoFuente.DEMO.name).apply()
        _estado.update { it.copy(tipoFuente = TipoFuente.DEMO) }
        usarFuente(FuenteDemo())
    }

    /** Emulador ELM327 por TCP (obd/emulador). No graba viajes: son datos de capturas, no del auto. */
    override fun usarEmulador(direccion: String) {
        val limpia = direccion.trim().ifEmpty { DIRECCION_EMULADOR }
        val host = limpia.substringBeforeLast(':')
        val puerto = limpia.substringAfterLast(':', "").toIntOrNull() ?: 35000
        prefs.edit().putString("fuente", TipoFuente.EMULADOR.name).putString("emulador", limpia).apply()
        _estado.update { it.copy(tipoFuente = TipoFuente.EMULADOR, direccionEmulador = limpia) }
        usarFuente(FuenteElm327("Emulador (TCP) $host:$puerto", TransporteTcp(host, puerto), grabaViajes = false))
    }

    /** Adaptador ELM327 real por BLE (el MOCATEC "OBDII"). Graba viajes: son datos del auto. */
    override fun usarBle(dispositivo: DispositivoBle) {
        prefs.edit()
            .putString("fuente", TipoFuente.BLE.name)
            .putString("ble_direccion", dispositivo.direccion)
            .putString("ble_nombre", dispositivo.nombre)
            .apply()
        _estado.update { it.copy(tipoFuente = TipoFuente.BLE, adaptadorBle = dispositivo) }
        usarFuente(FuenteElm327("ELM327 BLE ${dispositivo.nombre}", TransporteBle(getApplication(), dispositivo.direccion)))
    }

    override fun fijarTestigos(activos: Boolean) {
        prefs.edit().putBoolean("testigos", activos).apply()
        _estado.update { it.copy(testigos = activos) }
        fuenteActual?.fijarTestigos(activos)
    }

    fun usarFuente(fuente: FuenteDatos) {
        lectura?.cancel()
        vigilancia?.cancel()
        historial.limpiar()
        _estado.update {
            it.copy(fuente = fuente.nombre, esDemo = fuente is FuenteDemo, conexion = fuente.conexion?.value, valores = emptyMap(), alertas = emptyList(), viajeEnCursoKm = null, series = emptyMap(), odometroDelTablero = false)
        }
        fuenteActual = fuente
        fuente.fijarTestigos(_estado.value.testigos)
        _registro.value = emptyList()
        vigilancia = viewModelScope.launch {
            // Mientras reconecta no hay datos: se vacían los valores para no mostrar lecturas viejas.
            fuente.conexion?.let { c ->
                launch {
                    c.collect { con ->
                        _estado.update { if (con.fase == FaseConexion.CONECTADO) it.copy(conexion = con) else it.copy(conexion = con, valores = emptyMap(), alertas = emptyList()) }
                    }
                }
            }
            fuente.registro?.let { r -> launch { r.collect { _registro.value = it } } }
        }
        val reg = if (fuente.grabaViajes) {
            RegistradorViaje(Bitacora.ID_ACCENT, reloj = { Clock.System.now() }, nuevoId = { UUID.randomUUID().toString() })
        } else null
        registrador = reg
        odometroLeido = false
        lectura = viewModelScope.launch {
            fuente.lecturas()
                // Si el adaptador se desconecta (se apagó el auto) o se cambia de fuente, el viaje en curso se cierra.
                .onCompletion { withContext(NonCancellable) { reg?.cerrar()?.let { guardarViaje(it) } } }
                .catch { _estado.update { it.copy(fuente = "${fuente.nombre}: desconectado", viajeEnCursoKm = null) } }
                .collect { l ->
                    // The cluster's own odometer (frame 0x4F0): saved once per connection as a firm reading.
                    val tablero = l.valores[Carroceria.ODOMETRO]
                    if (reg != null && tablero != null) {
                        // Shown as read; saved once per connection so trips add up from the car's own number.
                        if (!odometroLeido) {
                            odometroLeido = true
                            withContext(Dispatchers.IO) { bitacora.guardarOdometroTablero(tablero) }
                        }
                        _estado.update { it.copy(odometroKm = tablero, odometroDelTablero = true) }
                    }
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
