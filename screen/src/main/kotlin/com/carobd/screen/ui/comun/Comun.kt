package com.carobd.screen.ui.comun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carobd.screen.gauges.LocalPaleta

/** Bloque con fondo de celda y bordes redondeados: la unidad de todas las pantallas. */
@Composable
fun Tarjeta(modifier: Modifier = Modifier, titulo: String? = null, contenido: @Composable ColumnScope.() -> Unit) {
    val p = LocalPaleta.current
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(p.celda).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (titulo != null) Text(titulo.uppercase(), color = p.tenue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        contenido()
    }
}

/** Botón de 48dp de alto como mínimo: se toca con el auto andando. */
@Composable
fun Boton(texto: String, modifier: Modifier = Modifier, principal: Boolean = false, activo: Boolean = true, onClick: () -> Unit) {
    val p = LocalPaleta.current
    val fondo = if (principal) p.normal else p.pista
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (activo) fondo else fondo.copy(alpha = 0.4f))
            .clickable(enabled = activo, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = if (activo) p.texto else p.tenue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Opción de un grupo (Auto / Día / Noche, Demo / vLinker...). */
@Composable
fun Opcion(texto: String, elegida: Boolean, modifier: Modifier = Modifier, activo: Boolean = true, onClick: () -> Unit) {
    val p = LocalPaleta.current
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (elegida) p.pista else Color.Transparent)
            .border(1.dp, if (elegida) p.normal else p.linea, RoundedCornerShape(12.dp))
            .clickable(enabled = activo, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = if (!activo) p.tenue.copy(alpha = 0.6f) else if (elegida) p.texto else p.tenue, fontSize = 16.sp)
    }
}

/** Fila etiqueta / valor con una línea abajo. */
@Composable
fun Fila(etiqueta: String, valor: String, colorValor: Color? = null) {
    val p = LocalPaleta.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(etiqueta, color = p.tenue, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valor, color = colorValor ?: p.texto, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.linea))
    }
}

/** Íconos de trazo dibujados en una grilla de 24x24, sin depender de una librería de íconos. */
enum class Icono(val trazo: String) {
    INICIO("M3 11 L12 4 L21 11 M5 10 L5 20 L19 20 L19 10 M10 20 L10 14 L14 14 L14 20"),
    TABLERO("M4 4 L10 4 L10 10 L4 10 Z M14 4 L20 4 L20 10 L14 10 Z M4 14 L10 14 L10 20 L4 20 Z M14 14 L20 14 L20 20 L14 20 Z"),
    DIAGNOSTICO("M3 12 L7 12 L10 5 L14 19 L17 12 L21 12"),
    VEHICULO("M3 16 L3 12 L6 7 L18 7 L21 12 L21 16 Z M3 12 L21 12 M7 16 L7 19 M17 16 L17 19"),
    AJUSTES("M4 6 L20 6 M4 12 L20 12 M4 18 L20 18 M9 4 L9 8 M15 10 L15 14 M7 16 L7 20"),
    NAVEGACION("M12 3 L19 20 L12 16 L5 20 Z"),
    MUSICA("M9 18 L9 5 L20 3 L20 16 M9 18 A3 3 0 1 1 3 18 A3 3 0 1 1 9 18 M20 16 A3 3 0 1 1 14 16 A3 3 0 1 1 20 16"),
    ADAPTADOR("M7 3 L17 3 L17 13 L7 13 Z M10 13 L10 17 M14 13 L14 17 M8 21 L16 21 M12 17 L12 21"),
    NUBE("M7 18 L17 18 A4 4 0 0 0 17 10 A6 6 0 0 0 6 11 A3.5 3.5 0 0 0 7 18 Z"),
    ANTERIOR("M18 6 L10 12 L18 18 Z M6 6 L6 18"),
    PLAY("M8 5 L19 12 L8 19 Z"),
    SIGUIENTE("M6 6 L14 12 L6 18 Z M18 6 L18 18"),
    FLECHA("M9 5 L16 12 L9 19"),
}

@Composable
fun IconoTrazo(icono: Icono, color: Color, tam: Dp = 24.dp, modifier: Modifier = Modifier) {
    val path = remember(icono) { PathParser().parsePathString(icono.trazo).toPath() }
    Canvas(modifier.size(tam)) {
        val k = size.minDimension / 24f
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Un decimal con coma, como se escribe en español. */
fun dec(x: Double, decimales: Int = 1): String = "%.${decimales}f".format(java.util.Locale("es"), x)
