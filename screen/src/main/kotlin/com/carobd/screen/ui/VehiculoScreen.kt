package com.carobd.screen.ui

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.core.miles
import com.carobd.screen.EstadoTablero
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.ui.comun.Fila
import com.carobd.screen.ui.comun.Tarjeta
import kotlin.math.floor

/** El vehículo de la pantalla. Por ahora es fijo; el selector de marca, modelo y año llega con multi-vehículo. */
@Composable
fun VehiculoScreen(e: EstadoTablero, acciones: AccionesTablero) {
    val p = LocalPaleta.current
    var editando by remember { mutableStateOf(false) }
    val v = e.garaje?.vehiculo
    if (editando) DialogoOdometro(floor(e.odometroKm).toInt(), onGuardar = { acciones.fijarOdometro(it); editando = false }, onCerrar = { editando = false })
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tarjeta(Modifier.weight(1.4f).fillMaxHeight()) {
            Text(nombreVehiculo(e), color = p.texto, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("${v?.motor ?: "1.6 CRDi"} · diésel · manual", color = p.tenue, fontSize = 15.sp)
            Silueta(Color(0xFFB8BEC6), Modifier.fillMaxWidth().weight(1f))
        }
        Tarjeta(Modifier.weight(1f).fillMaxHeight(), titulo = "Perfil") {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Fila("Marca", v?.marca ?: "Hyundai")
                Fila("Modelo", v?.modelo ?: "Accent")
                Fila("Año", (v?.anio ?: 2016).toString())
                Fila("Motor", v?.motor ?: "1.6 CRDi")
                Box(Modifier.clickable(role = Role.Button) { editando = true }) {
                    Fila("Odómetro", "${miles(floor(e.odometroKm).toInt())} km · editar", p.normal)
                }
                Fila("Señales", "Perfil Accent 1.6 CRDi")
            }
            Text(
                "Elegir otro vehículo (marca, modelo, año y color) llega con la fase de varios vehículos. " +
                    "Los PIDs propios de Hyundai (modo 22) se suman al perfil cuando los descubramos con el vLinker.",
                color = p.tenue,
                fontSize = 14.sp,
            )
        }
    }
}

/** OBD can't read the Accent's odometer: the driver types what the car's dashboard shows, and trips add on top. */
@Composable
private fun DialogoOdometro(actual: Int, onGuardar: (Int) -> Unit, onCerrar: () -> Unit) {
    // Empty with the current estimate as a hint: the driver types the new number straight away.
    var texto by remember { mutableStateOf("") }
    val km = texto.filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 0..2_000_000 }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Odómetro del auto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Escribe los km que marca el tablero del auto. Desde ahí la app suma los viajes.")
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it.filter { c -> c.isDigit() }.take(7) },
                    label = { Text("km") },
                    placeholder = { Text(miles(actual)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = { TextButton(onClick = { km?.let(onGuardar) }, enabled = km != null) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}
