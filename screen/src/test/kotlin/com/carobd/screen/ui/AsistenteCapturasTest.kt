package com.carobd.screen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.carobd.core.model.Asistente
import com.carobd.core.model.Garaje
import com.carobd.core.model.RespuestaItem
import com.carobd.core.model.RespuestasAsistente
import com.carobd.core.tablero.Tableros
import com.carobd.screen.EstadoTablero
import com.carobd.screen.gauges.LocalPaleta
import com.carobd.screen.gauges.Paleta
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas de cada pantalla del asistente, del tamaño del área central de la pantalla del auto (1280x592,
 * entre la barra de estado y las pestañas). Quedan en screen/build/capturas/asistente.
 * Correr con: ./gradlew :screen:testDebugUnitTest --tests '*AsistenteCapturasTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h592dp-land-mdpi")
class AsistenteCapturasTest {
    @get:Rule val compose = createComposeRule()

    private val garaje = Garaje.inicialAccent(Clock.System.now())
    private val items = garaje.items.filter { it.codigo != null }
    private val base = EstadoTablero(tableros = Tableros.predeterminados, garaje = garaje, odometroKm = 91_294.0)
    private val nada = object : AccionesAsistente {
        override fun abrirAsistente() {}
        override fun responderAsistente(r: RespuestasAsistente) {}
        override fun cerrarAsistente() {}
        override fun terminarAsistente(r: RespuestasAsistente) {}
    }

    /** Respuestas de ejemplo, como si el usuario hubiera llegado al final. */
    private val respuestas = RespuestasAsistente(
        plantilla = Asistente.motores.first().plantilla,
        transmision = "MANUAL",
        mesAlta = "2016-06",
        kmActual = 91_294,
        condiciones = listOf("F"),
        usoSevero = true,
        en590 = false,
        tieneDpf = false,
        ultimoServiceKm = 90_000,
        ultimoServiceMes = "2026-06",
        items = mapOf(items.first { it.codigo == "REFRIGERANTE" && it.accion == "CAMBIAR" }.id to RespuestaItem(Asistente.NUNCA)),
        chequeo = mapOf("temperatura" to true),
    )

    // La regla deja llamar a setContent una sola vez: las capturas cambian este estado.
    private var actual by mutableStateOf<Pair<RespuestasAsistente, EstadoTablero>?>(null)

    private fun captura(nombre: String, r: RespuestasAsistente, e: EstadoTablero = base) {
        if (actual == null) {
            compose.setContent {
                val (resp, est) = actual ?: return@setContent
                CompositionLocalProvider(LocalPaleta provides Paleta.dia, LocalEnMarcha provides ((est.valores["velocidad"] ?: 0.0) > 0)) {
                    Box(Modifier.fillMaxSize().background(Paleta.dia.fondo).padding(12.dp)) {
                        // key: cada pregunta arranca con su estado interno limpio, como al navegar en la app.
                        key(resp.paso, est.valores) { AsistenteScreen(est, resp, nada) }
                    }
                }
            }
        }
        actual = r to e
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage("build/capturas/asistente/$nombre.png")
    }

    private fun indice(r: RespuestasAsistente, cual: (Pregunta) -> Boolean) = preguntas(r, items, false).indexOfFirst(cual)

    @Test
    fun capturas() {
        val vacio = RespuestasAsistente()
        captura("01-motor", vacio.copy(paso = indice(vacio) { it == Pregunta.Motor }))
        captura("02-caja", respuestas.copy(paso = indice(respuestas) { it == Pregunta.Caja }))
        captura("03-fecha-de-alta", respuestas.copy(paso = indice(respuestas) { it == Pregunta.MesAlta }))
        captura("04-km-actual", respuestas.copy(paso = indice(respuestas) { it == Pregunta.KmActual }))
        captura("05-uso-severo", respuestas.copy(paso = indice(respuestas) { it == Pregunta.Condiciones }))
        captura("06-gasoil", respuestas.copy(paso = indice(respuestas) { it == Pregunta.En590 }))
        captura("07-dpf", respuestas.copy(paso = indice(respuestas) { it == Pregunta.Dpf }))
        captura("08-ultimo-service", respuestas.copy(paso = indice(respuestas) { it == Pregunta.UltimoService }))
        val caja = items.first { it.codigo == "ACEITE_CAJA" }
        captura("09-item", respuestas.copy(paso = indice(respuestas) { it is Pregunta.Item && it.item.id == caja.id }))
        val conKm = respuestas.copy(items = respuestas.items + (caja.id to RespuestaItem(Asistente.EXACTO, 60_000, "2022-01")))
        captura("10-item-con-km", conKm.copy(paso = indice(conKm) { it is Pregunta.Item && it.item.id == caja.id }))
        captura("11-chequeo", respuestas.copy(paso = indice(respuestas) { it is Pregunta.Chequeo }))
        captura("12-resultado", respuestas.copy(paso = indice(respuestas) { it == Pregunta.Resultado }))
        captura("13-en-marcha", respuestas.copy(paso = 3), base.copy(valores = mapOf("velocidad" to 30.0)))
    }
}
