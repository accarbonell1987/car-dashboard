package com.carobd.screen.ui

import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import java.io.File
import androidx.core.content.FileProvider
import android.content.Intent
import android.content.Context
import com.carobd.screen.ui.comun.ItemRiel
import com.carobd.screen.ui.auto.AjustesLucesAuto
import com.carobd.screen.ui.comun.Interruptor
import com.carobd.screen.ui.comun.IconoTrazo
import com.carobd.screen.ui.comun.Icono
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

private enum class SeccionAjustes(val nombre: String, val corto: String, val icono: Icono) {
    PANTALLA("Pantalla", "Pantalla", Icono.SOL),
    ADAPTADOR("Adaptador", "Adaptador", Icono.ADAPTADOR),
    LUCES("Luces del auto", "Luces", Icono.VEHICULO),
    CUENTA("Cuenta y sync", "Sync", Icono.NUBE),
    ASISTENTE("Asistente de voz", "Asistente", Icono.MICROFONO),
    ACERCA("Acerca de", "Acerca", Icono.INFO),
}

@Composable
fun AjustesScreen(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var seccion by rememberSaveable { mutableStateOf(SeccionAjustes.PANTALLA) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Same side rail as the dashboard, so the whole app is driven the same way.
        Column(Modifier.width(88.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SeccionAjustes.entries) ItemRiel(s.icono, s.corto, activo = s == seccion) { seccion = s }
        }
        // Each section lays out its own cards to use the whole screen; Luces keeps a scrolling card.
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (seccion) {
                SeccionAjustes.PANTALLA -> AjustesPantalla(e, acciones)
                SeccionAjustes.ADAPTADOR -> AjustesAdaptador(e, acciones)
                SeccionAjustes.LUCES -> Tarjeta(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(seccion.nombre, color = p.texto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        AjustesLucesAuto(e)
                    }
                }
                SeccionAjustes.CUENTA -> AjustesCuenta(e, acciones)
                SeccionAjustes.ASISTENTE -> AjustesVoz(acciones.voz)
                SeccionAjustes.ACERCA -> AjustesAcerca(e)
            }
        }
    }
}

/** Big selectable tile: icon on top, name and a short line under it. Used for modes and data sources. */
@Composable
private fun Baldosa(icono: Icono, titulo: String, detalle: String, elegida: Boolean, modifier: Modifier = Modifier, horizontal: Boolean = false, onClick: () -> Unit) {
    val p = LocalPaleta.current
    val forma = RoundedCornerShape(16.dp)
    val mod = modifier
        .clip(forma)
        .background(if (elegida) p.normal.copy(alpha = 0.12f) else p.pista)
        .border(2.dp, if (elegida) p.normal else Color.Transparent, forma)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { selected = elegida }
        .padding(14.dp)
    val icon = @Composable { tam: androidx.compose.ui.unit.Dp -> IconoTrazo(icono, if (elegida) p.normal else p.tenue, tam) }
    if (horizontal) {
        Row(mod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon(30.dp)
            Column {
                Text(titulo, color = p.texto, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(detalle, color = p.tenue, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        Column(mod, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            icon(52.dp)
            Text(titulo, color = p.texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(detalle, color = p.tenue, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}

/** A labelled value on its own small tile. */
@Composable
private fun Dato(etiqueta: String, valor: String, modifier: Modifier = Modifier, color: Color? = null) {
    val p = LocalPaleta.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(p.pista).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(etiqueta, color = p.tenue, fontSize = 14.sp)
        Text(valor, color = color ?: p.texto, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A pill with a coloured dot: connection or sync state at a glance. */
@Composable
private fun Estado(texto: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(texto, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Big icon in a tinted rounded square, the lead of a status card. */
@Composable
private fun IconoGrande(icono: Icono, color: Color, tam: androidx.compose.ui.unit.Dp = 86.dp) {
    Box(Modifier.size(tam).clip(RoundedCornerShape(22.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        IconoTrazo(icono, color, tam * 0.56f)
    }
}

@Composable
private fun AjustesPantalla(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Tarjeta(Modifier.fillMaxWidth().weight(1.4f)) {
            TituloConIcono(Icono.LUNA, "Modo noche")
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (m in ModoNoche.entries) {
                    val (icono, detalle) = when (m) {
                        ModoNoche.AUTO -> Icono.AUTO to "Noche de 19:00 a 7:00"
                        ModoNoche.DIA -> Icono.SOL to "Colores normales"
                        ModoNoche.NOCHE -> Icono.LUNA to "Todo más tenue, no encandila"
                    }
                    Baldosa(icono, m.etiqueta, detalle, m == e.modoNoche, Modifier.weight(1f).fillMaxHeight()) { acciones.elegirModoNoche(m) }
                }
            }
        }
        Tarjeta(Modifier.fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TituloConIcono(Icono.SOL, "Brillo") }
                Opcion("Usar el del sistema", e.brillo == null) { acciones.fijarBrillo(null) }
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    e.brillo?.let { "${(it * 100).toInt()}%" } ?: "Sistema",
                    color = p.texto,
                    fontSize = if (e.brillo == null) 28.sp else 44.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(150.dp),
                )
                Slider(
                    value = e.brillo ?: 0.8f,
                    onValueChange = { acciones.fijarBrillo(it.coerceIn(0.1f, 1f)) },
                    valueRange = 0.1f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = p.normal, activeTrackColor = p.normal, inactiveTrackColor = p.pista),
                )
            }
        }
    }
}

@Composable
private fun AjustesAdaptador(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var direccion by remember(e.direccionEmulador) { mutableStateOf(e.direccionEmulador) }
    var eligiendoBle by rememberSaveable { mutableStateOf(false) }
    var verRegistro by rememberSaveable { mutableStateOf(false) }
    val c = e.conexion
    val conectado = c?.fase == FaseConexion.CONECTADO
    val (estado, colorEstado) = when {
        e.esDemo -> "Demo" to p.atencion
        c == null -> "Sin conexión" to p.atencion
        else -> when (c.fase) {
            FaseConexion.CONECTADO -> "Conectado" to p.ok
            FaseConexion.CONECTANDO -> "Conectando…" to p.atencion
            FaseConexion.RECONECTANDO -> "Reconectando (intento ${c.intento})" to p.atencion
            FaseConexion.DESCONECTADO -> "Desconectado" to p.peligro
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Connection first: it is what you come here to check.
        Tarjeta(Modifier.weight(1.6f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TituloConIcono(Icono.ADAPTADOR, "Conexión") }
                Estado(estado, colorEstado)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconoGrande(
                    when (e.tipoFuente) { TipoFuente.BLE -> Icono.BLUETOOTH; TipoFuente.EMULADOR -> Icono.MONITOR; TipoFuente.DEMO -> Icono.PLAY },
                    colorEstado,
                )
                Column(Modifier.weight(1f)) {
                    Text(if (e.esDemo) "Simulador del Accent" else e.fuente, color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detalle = if (e.esDemo) "Datos simulados, para probar sin el auto" else c?.detalle?.takeIf { it.isNotBlank() && !conectado } ?: c?.adaptador ?: "Conectando…"
                    Text(detalle, color = if (c?.detalle?.isNotBlank() == true && !conectado) p.atencion else p.tenue, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (verRegistro) {
                Registro(acciones, Modifier.fillMaxWidth().weight(1f))
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dato("Adaptador", c?.adaptador ?: "—", Modifier.weight(1f))
                    Dato("PIDs del motor", if (conectado) c!!.pidsSoportados.toString() else "—", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dato("VIN", c?.vin ?: "Sin leer", Modifier.weight(1.4f))
                    Dato("Testigos", if (!e.testigos) "Apagados" else if (conectado && c!!.carroceria) "Escuchando" else "Esperando", Modifier.weight(1f))
                }
                Spacer(Modifier.weight(1f))
            }
            val lineas by acciones.registro.collectAsState()
            val context = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Boton(if (verRegistro) "Ocultar registro" else "Ver registro", Modifier.weight(1f), activo = !e.esDemo) { verRegistro = !verRegistro }
                Boton("Compartir registro", Modifier.weight(1f), activo = lineas.isNotEmpty()) { compartirRegistro(context, lineas) }
            }
        }
        // Source and options on the side; scrolls because the BLE search can list several adapters.
        Tarjeta(Modifier.weight(1f).fillMaxHeight(), titulo = "Fuente de datos") {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Baldosa(Icono.BLUETOOTH, "ELM327 BLE", e.adaptadorBle?.nombre ?: "El adaptador del auto", e.tipoFuente == TipoFuente.BLE || eligiendoBle, Modifier.fillMaxWidth(), horizontal = true) {
                    val elegido = e.adaptadorBle
                    if (elegido != null && !eligiendoBle) acciones.usarBle(elegido) else eligiendoBle = true
                }
                Baldosa(Icono.MONITOR, "Emulador", direccion, e.tipoFuente == TipoFuente.EMULADOR && !eligiendoBle, Modifier.fillMaxWidth(), horizontal = true) {
                    eligiendoBle = false
                    acciones.usarEmulador(direccion)
                }
                Baldosa(Icono.PLAY, "Demo", "Simulador del Accent", e.tipoFuente == TipoFuente.DEMO && !eligiendoBle, Modifier.fillMaxWidth(), horizontal = true) {
                    eligiendoBle = false
                    acciones.usarDemo()
                }
                if (e.tipoFuente == TipoFuente.BLE || eligiendoBle) {
                    SelectorBle(e, acciones)
                    if (eligiendoBle) Boton("Cancelar", Modifier.fillMaxWidth()) { eligiendoBle = false }
                }
                if (e.tipoFuente == TipoFuente.EMULADOR && !eligiendoBle) {
                    OutlinedTextField(
                        value = direccion,
                        onValueChange = { direccion = it },
                        label = { Text("Emulador (host:puerto)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        colors = coloresCampo(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Boton("Conectar", Modifier.fillMaxWidth(), principal = true, activo = direccion.isNotBlank()) { acciones.usarEmulador(direccion) }
                }
            }
            Interruptor(
                "Testigos de carrocería",
                e.testigos,
                detalle = if (!conectado) "Puertas, cinturón, A/C y odómetro" else if (c!!.carroceria) "Escuchando el bus" else "Desactivados",
            ) { acciones.fijarTestigos(it) }
        }
    }
}

@Composable
internal fun coloresCampo() = LocalPaleta.current.let { p ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = p.texto,
        unfocusedTextColor = p.texto,
        focusedBorderColor = p.normal,
        unfocusedBorderColor = p.linea,
        focusedLabelColor = p.normal,
        unfocusedLabelColor = p.tenue,
    )
}

/** Latest raw commands and replies, newest first, for diagnosing in the car. The full log is shared as a file. */
@Composable
private fun Registro(acciones: AccionesTablero, modifier: Modifier) {
    val p = LocalPaleta.current
    val lineas by acciones.registro.collectAsState()
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(p.fondo).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (lineas.isEmpty()) Text("Sin comandos todavía.", color = p.tenue, fontSize = 15.sp)
        for (linea in lineas.takeLast(LINEAS_EN_PANTALLA).asReversed()) {
            Text(linea, color = if ("✕" in linea || "sin prompt" in linea) p.atencion else p.texto, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private const val LINEAS_EN_PANTALLA = 50

/** Writes the log to a cache file and opens the share sheet (WhatsApp, Drive, mail...) to send it off the car. */
private fun compartirRegistro(context: Context, lineas: List<String>) {
    val carpeta = File(context.cacheDir, "registros").apply { mkdirs() }
    carpeta.listFiles()?.forEach { it.delete() }
    val archivo = File(carpeta, "carobd-registro-${System.currentTimeMillis() / 1000}.txt")
    archivo.writeText(
        "CAROBD ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()} · Android ${Build.VERSION.RELEASE}\n" +
            "segundos-desde-el-arranque comando → respuesta\n\n" + lineas.joinToString("\n"),
    )
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.archivos", archivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, archivo.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(envio, "Compartir registro").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        fontSize = 16.sp,
    )
    aviso?.let { Text(it, color = p.atencion, fontSize = 16.sp) }
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
    val ultima = n.ultimoSync?.toLocalDateTime(TimeZone.currentSystemDefault())?.let {
        "${it.dayOfMonth}/${it.monthNumber} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
    }
    val (titulo, detalle, color) = when {
        !e.config.lista -> Triple("Sin nube configurada", "Tus viajes y el mantenimiento quedan solo en esta pantalla.", p.atencion)
        n.error != null -> Triple("No se pudo sincronizar", n.error!!, p.peligro)
        ultima != null -> Triple("Sincronizado", "Última vez: $ultima", p.ok)
        else -> Triple("Lista para sincronizar", "Todavía no se subió nada.", p.normal)
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tarjeta(Modifier.weight(1.4f).fillMaxHeight()) {
            TituloConIcono(Icono.NUBE, "Cuenta y sync")
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconoGrande(Icono.NUBE, color, 96.dp)
                Column(Modifier.weight(1f)) {
                    Text(titulo, color = p.texto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(detalle, color = if (n.error != null) p.peligro else p.tenue, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dato("Cambios por enviar", n.pendientes.toString(), Modifier.weight(1f))
                Dato("Última sincronización", ultima ?: "Nunca", Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Boton("Sincronizar ahora", Modifier.fillMaxWidth(), principal = true, activo = e.config.lista) { acciones.sincronizarAhora() }
        }
        // Set once, like on the phone: out of the way of the status.
        Tarjeta(Modifier.weight(1f).fillMaxHeight(), titulo = "Servidor") {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Dirección (https://…)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    colors = coloresCampo(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = coloresCampo(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Se configura una vez, con los mismos datos que en el celular.", color = p.tenue, fontSize = 15.sp)
            }
            val cambio = url.trim() != e.config.url || token.trim() != e.config.token
            Boton("Guardar y sincronizar", Modifier.fillMaxWidth(), activo = cambio && url.isNotBlank() && token.isNotBlank()) { acciones.configurarNube(url, token) }
        }
    }
}

@Composable
private fun AjustesAcerca(e: EstadoTablero) {
    val p = LocalPaleta.current
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val icono = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(240, 240).asImageBitmap() }.getOrNull()
    }
    val alcance = rememberCoroutineScope()
    val estado by Actualizador.estado.collectAsState()
    val s = estado
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The update is what you come here for: app identity and the button, big and centred.
        Tarjeta(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                icono?.let { Image(it, contentDescription = null, modifier = Modifier.size(112.dp).clip(RoundedCornerShape(28.dp))) }
                Text("CAROBD", color = p.texto, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                when (s) {
                    EstadoActualizacion.AlDia -> Estado("Al día · versión $version", p.ok)
                    is EstadoActualizacion.Disponible -> Estado("Hay una versión nueva", p.normal)
                    is EstadoActualizacion.Error -> Estado("No se pudo revisar", p.atencion)
                    else -> Estado("Versión $version", p.tenue)
                }
                Text(
                    when (s) {
                        EstadoActualizacion.AlDia -> "La app revisa sola al abrir y cada 6 horas."
                        EstadoActualizacion.Buscando -> "Buscando en GitHub…"
                        is EstadoActualizacion.Disponible -> "Disponible: ${s.apk.nombre}."
                        is EstadoActualizacion.Descargando -> "Descargando… ${(s.progreso * 100).toInt()} %"
                        is EstadoActualizacion.Error -> s.mensaje
                    },
                    color = if (s is EstadoActualizacion.Error) p.atencion else p.tenue,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                if (s is EstadoActualizacion.Disponible) {
                    Boton("Actualizar", Modifier.width(320.dp), principal = true) { alcance.launch { Actualizador.instalar(context, s.apk) } }
                } else {
                    Boton("Buscar actualización", Modifier.width(320.dp), activo = s !is EstadoActualizacion.Buscando && s !is EstadoActualizacion.Descargando) {
                        alcance.launch { Actualizador.buscar(context) }
                    }
                }
            }
        }
        Tarjeta(Modifier.weight(1f).fillMaxHeight()) {
            TituloConIcono(Icono.INFO, "Datos")
            val pantalla = context.resources.displayMetrics.let { "${maxOf(it.widthPixels, it.heightPixels)}×${minOf(it.widthPixels, it.heightPixels)}" }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dato("Versión", version, Modifier.weight(1f))
                Dato("Android", Build.VERSION.RELEASE, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dato("Pantalla", pantalla, Modifier.weight(1f))
                Dato("Adaptador", e.conexion?.adaptador ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            TituloConIcono(Icono.VEHICULO, "Créditos")
            Text("Modelo 3D: \"hyundai accent 2013\" de L95XP (Sketchfab), licencia CC BY 4.0. Simplificado y repintado.", color = p.tenue, fontSize = 15.sp)
        }
    }
}
