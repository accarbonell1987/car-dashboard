package com.carobd.screen.ui

import kotlinx.coroutines.launch
import com.carobd.screen.EstadoActualizacion
import com.carobd.screen.Actualizador
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.screen.EstadoTablero
import com.carobd.screen.ModoNoche
import com.carobd.screen.TipoFuente
import com.carobd.screen.obd.DispositivoBle
import com.carobd.screen.obd.escanearBle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.carobd.core.obd.FaseConexion
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Boton
import com.carobd.screen.ui.comun.Fila
import com.carobd.screen.ui.comun.Opcion
import com.carobd.screen.ui.comun.Tarjeta
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private enum class SeccionAjustes(val nombre: String) {
    PANTALLA("Pantalla"), ADAPTADOR("Adaptador"), CUENTA("Cuenta y sync"), ACERCA("Acerca de")
}

@Composable
fun AjustesScreen(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var seccion by rememberSaveable { mutableStateOf(SeccionAjustes.PANTALLA) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(0.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SeccionAjustes.entries) {
                val activa = s == seccion
                Text(
                    s.nombre,
                    color = if (activa) p.texto else p.tenue,
                    fontSize = 17.sp,
                    fontWeight = if (activa) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (activa) p.pista else p.fondo)
                        .semantics { selected = activa }
                        .clickable(role = Role.Tab) { seccion = s }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                )
            }
        }
        // Se desplaza: en horizontal el teclado tapa la mitad de la pantalla.
        Tarjeta(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(seccion.nombre, color = p.texto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                when (seccion) {
                    SeccionAjustes.PANTALLA -> AjustesPantalla(e, acciones)
                    SeccionAjustes.ADAPTADOR -> AjustesAdaptador(e, acciones)
                    SeccionAjustes.CUENTA -> AjustesCuenta(e, acciones)
                    SeccionAjustes.ACERCA -> AjustesAcerca()
                }
            }
        }
    }
}

@Composable
private fun AjustesPantalla(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    Text("Modo noche", color = p.texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text("Baja el brillo de todos los colores para no encandilar. En Auto cambia solo de 19:00 a 7:00.", color = p.tenue, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in ModoNoche.entries) Opcion(m.etiqueta, m == e.modoNoche, Modifier.weight(1f)) { acciones.elegirModoNoche(m) }
    }
    Spacer(Modifier.height(8.dp))
    Text("Brillo", color = p.texto, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Opcion("Del sistema", e.brillo == null) { acciones.fijarBrillo(null) }
        Slider(
            value = e.brillo ?: 0.8f,
            onValueChange = { acciones.fijarBrillo(it.coerceIn(0.1f, 1f)) },
            valueRange = 0.1f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = p.normal, activeTrackColor = p.normal, inactiveTrackColor = p.pista),
        )
    }
}

@Composable
private fun AjustesAdaptador(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var direccion by remember(e.direccionEmulador) { mutableStateOf(e.direccionEmulador) }
    var eligiendoBle by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Opcion("Modo demo", e.tipoFuente == TipoFuente.DEMO && !eligiendoBle, Modifier.weight(1f)) { eligiendoBle = false; acciones.usarDemo() }
        Opcion("Emulador (TCP)", e.tipoFuente == TipoFuente.EMULADOR && !eligiendoBle, Modifier.weight(1f)) { eligiendoBle = false; acciones.usarEmulador(direccion) }
        Opcion("ELM327 BLE (MOCATEC)", e.tipoFuente == TipoFuente.BLE || eligiendoBle, Modifier.weight(1f)) {
            val elegido = e.adaptadorBle
            if (elegido != null) acciones.usarBle(elegido) else eligiendoBle = true
        }
    }
    Fila("Fuente de datos", if (e.esDemo) "Simulador del Accent" else e.fuente)
    if (e.tipoFuente == TipoFuente.BLE || eligiendoBle) SelectorBle(e, acciones)
    if (e.tipoFuente == TipoFuente.EMULADOR) {
        OutlinedTextField(
            value = direccion,
            onValueChange = { direccion = it },
            label = { Text("Emulador (host:puerto)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.texto,
                unfocusedTextColor = p.texto,
                focusedBorderColor = p.normal,
                unfocusedBorderColor = p.linea,
                focusedLabelColor = p.normal,
                unfocusedLabelColor = p.tenue,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Boton("Conectar", principal = true, activo = direccion.isNotBlank()) { acciones.usarEmulador(direccion) }
        Text(
            "Arranca el emulador en el ordenador con python3 obd/emulador/elm327_emulador.py. Desde el emulador de Android, 10.0.2.2 es el ordenador.",
            color = p.tenue,
            fontSize = 14.sp,
        )
    }
    e.conexion?.let { c ->
        Fila(
            "Estado",
            when (c.fase) {
                FaseConexion.CONECTADO -> "Conectado"
                FaseConexion.CONECTANDO -> "Conectando…"
                FaseConexion.RECONECTANDO -> "Reconectando (intento ${c.intento})" + c.detalle.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                FaseConexion.DESCONECTADO -> "Desconectado"
            },
            if (c.fase == FaseConexion.CONECTADO) null else p.atencion,
        )
        c.adaptador?.let { Fila("Adaptador", it) }
        if (c.fase == FaseConexion.CONECTADO) {
            Fila("PIDs soportados", c.pidsSoportados.toString())
            c.vin?.let { Fila("VIN", it) }
            Fila("Testigos de carrocería", if (c.carroceria) "Escuchando el bus" else "Desactivados")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Opcion("Testigos activados", e.testigos, Modifier.weight(1f)) { acciones.fijarTestigos(true) }
            Opcion("Sin testigos", !e.testigos, Modifier.weight(1f)) { acciones.fijarTestigos(false) }
        }
        Registro(acciones)
    }
}

/** Últimos comandos y respuestas en crudo, para diagnosticar en el auto. Plegado por defecto. */
@Composable
private fun Registro(acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var abierto by rememberSaveable { mutableStateOf(false) }
    Boton(if (abierto) "Ocultar registro" else "Ver registro") { abierto = !abierto }
    if (!abierto) return
    val lineas by acciones.registro.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.fondo).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (lineas.isEmpty()) Text("Sin comandos todavía.", color = p.tenue, fontSize = 13.sp)
        for (linea in lineas.asReversed()) {
            Text(linea, color = if ("✕" in linea || "sin prompt" in linea) p.atencion else p.texto, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Permisos para escanear y conectar por BLE: Android 12+ tiene los suyos; antes, escanear exigía ubicación. */
private fun permisosBle(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Busca adaptadores ELM327 BLE durante 10 s y deja elegir uno; la elección se guarda y se reconecta al abrir la app. */
@Composable
private fun SelectorBle(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    val context = LocalContext.current
    val encontrados = remember { mutableStateListOf<DispositivoBle>() }
    var escaneando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val pedirPermisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) {
            aviso = null
            escaneando = true
        } else {
            aviso = "Sin el permiso de Bluetooth" + (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " (y ubicación)" else "") +
                " no puedo buscar ni conectar el adaptador. Puedes darlo en los ajustes de Android."
        }
    }
    LaunchedEffect(escaneando) {
        if (!escaneando) return@LaunchedEffect
        encontrados.clear()
        try {
            withTimeoutOrNull(10_000) {
                escanearBle(context).collect { d ->
                    val i = encontrados.indexOfFirst { it.direccion == d.direccion }
                    if (i >= 0) encontrados[i] = d else encontrados += d
                }
            }
            if (encontrados.isEmpty()) aviso = "No encontré ningún adaptador. Revisa que esté enchufado y con el contacto puesto."
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            aviso = ex.message ?: "Falló la búsqueda por Bluetooth"
        }
        escaneando = false
    }
    e.adaptadorBle?.let { Fila("Adaptador elegido", "${it.nombre} (${it.direccion})") }
    Boton(if (escaneando) "Buscando…" else "Buscar adaptadores", principal = true, activo = !escaneando) {
        val faltan = permisosBle().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (faltan.isEmpty()) {
            aviso = null
            escaneando = true
        } else {
            pedirPermisos.launch(faltan.toTypedArray())
        }
    }
    Text(
        "Busca adaptadores que se anuncian como \"OBDII\" o con el servicio FFF0. Android pide permiso de Bluetooth" +
            (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " y de ubicación (en Android 11 o anterior lo exige para buscar)" else "") +
            ". La app no usa tu ubicación.",
        color = p.tenue,
        fontSize = 14.sp,
    )
    aviso?.let { Text(it, color = p.atencion, fontSize = 14.sp) }
    for (d in encontrados.sortedByDescending { it.rssi }) {
        Opcion("${d.nombre} · ${d.direccion} · ${d.rssi} dBm", e.tipoFuente == TipoFuente.BLE && d.direccion == e.adaptadorBle?.direccion, Modifier.fillMaxWidth()) {
            acciones.usarBle(d)
        }
    }
}

@Composable
private fun AjustesCuenta(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var url by remember(e.config) { mutableStateOf(e.config.url) }
    var token by remember(e.config) { mutableStateOf(e.config.token) }
    val n = e.nube
    Fila("Cambios por enviar", n.pendientes.toString())
    Fila(
        "Última sincronización",
        n.ultimoSync?.toLocalDateTime(TimeZone.currentSystemDefault())?.let {
            "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        } ?: "Nunca",
    )
    n.error?.let { Text("Error: $it", color = p.atencion, fontSize = 14.sp) }
    val colores = OutlinedTextFieldDefaults.colors(
        focusedTextColor = p.texto,
        unfocusedTextColor = p.texto,
        focusedBorderColor = p.normal,
        unfocusedBorderColor = p.linea,
        focusedLabelColor = p.normal,
        unfocusedLabelColor = p.tenue,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Servidor (https://…)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        colors = colores,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        colors = colores,
        modifier = Modifier.fillMaxWidth(),
    )
    val cambio = url.trim() != e.config.url || token.trim() != e.config.token
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Boton(if (cambio) "Guardar y sincronizar" else "Sincronizar ahora", principal = true, activo = url.isNotBlank() && token.isNotBlank()) {
            if (cambio) acciones.configurarNube(url, token) else acciones.sincronizarAhora()
        }
    }
}

@Composable
private fun AjustesAcerca() {
    val p = LocalPaleta.current
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Fila("Versión de la app", version)
    val alcance = rememberCoroutineScope()
    val estado by Actualizador.estado.collectAsState()
    Text(
        when (val s = estado) {
            EstadoActualizacion.AlDia -> "Tienes la última versión. La app revisa sola al abrir y cada 6 horas."
            EstadoActualizacion.Buscando -> "Buscando en GitHub…"
            is EstadoActualizacion.Disponible -> "Hay una versión nueva: ${s.apk.nombre}."
            is EstadoActualizacion.Descargando -> "Descargando… ${(s.progreso * 100).toInt()} %"
            is EstadoActualizacion.Error -> s.mensaje
        },
        color = if (estado is EstadoActualizacion.Error) p.atencion else p.tenue,
        fontSize = 14.sp,
    )
    val s = estado
    if (s is EstadoActualizacion.Disponible) {
        Boton("Actualizar", Modifier.fillMaxWidth(), principal = true) { alcance.launch { Actualizador.instalar(context, s.apk) } }
    } else {
        Boton("Buscar actualización", Modifier.fillMaxWidth(), activo = s !is EstadoActualizacion.Buscando && s !is EstadoActualizacion.Descargando) {
            alcance.launch { Actualizador.buscar(context) }
        }
    }
}
