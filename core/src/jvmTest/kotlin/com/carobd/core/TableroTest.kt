package com.carobd.core

import com.carobd.core.model.NivelAviso
import com.carobd.core.obd.Catalogo
import com.carobd.core.sim.SimuladorAccent
import com.carobd.core.sync.SyncJson
import com.carobd.core.tablero.Alertas
import com.carobd.core.tablero.Celda
import com.carobd.core.tablero.DefinicionTablero
import com.carobd.core.tablero.Grilla
import com.carobd.core.tablero.Tableros
import com.carobd.core.tablero.TipoGauge
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TableroTest {
    // Fixed 6x3 layout for the grid rules, so redesigning the default dashboards doesn't move these tests.
    private val manejo = DefinicionTablero(
        id = "manejo",
        nombre = "Manejo",
        celdas = listOf(
            Celda("m1", "rpm", TipoGauge.AGUJA, 0, 0, 2, 2),
            Celda("m2", "velocidad", TipoGauge.AGUJA, 2, 0, 2, 2),
            Celda("m3", "marcha", TipoGauge.MARCHA, 4, 0),
            Celda("m4", "boost", TipoGauge.ARCO, 5, 0),
            Celda("m5", "temp_refrigerante", TipoGauge.BARRA, 4, 1),
            Celda("m6", "consumo_l100", TipoGauge.NUMERO, 5, 1),
            Celda("m7", "carga", TipoGauge.ARCO, 0, 2),
            Celda("m8", "acelerador", TipoGauge.ARCO, 1, 2),
            Celda("m9", "temp_admision", TipoGauge.NUMERO, 2, 2),
            Celda("m10", "presion_riel", TipoGauge.BARRA, 3, 2),
            Celda("m11", "voltaje", TipoGauge.BARRA, 4, 2),
            Celda("m12", "temp_ambiente", TipoGauge.NUMERO, 5, 2),
        ),
    )

    @Test
    fun tablerosPredeterminadosSonValidos() {
        for (t in Tableros.predeterminados) {
            for (c in t.celdas) {
                assertTrue(Grilla.cabe(t, c), "${t.nombre}: ${c.id} no cabe o choca")
                assertTrue(Catalogo.porId(c.senalId) != null, "${t.nombre}: señal desconocida ${c.senalId}")
            }
            assertEquals(t.celdas.size, t.celdas.map { it.id }.toSet().size, "${t.nombre}: ids repetidos")
        }
        assertEquals(3, Tableros.predeterminados.map { it.id }.toSet().size)
    }

    @Test
    fun moverAUnHuecoOcupadoNoCambiaNada() {
        // m3 (marcha) está en 4,0; mover a 0,0 pisaría las RPM.
        assertSame(manejo, Grilla.mover(manejo, "m3", 0, 0))
        // Fuera de la grilla tampoco.
        assertSame(manejo, Grilla.mover(manejo, "m3", 6, 0))
    }

    @Test
    fun moverAUnHuecoLibreFunciona() {
        val sinM4 = Grilla.quitar(manejo, "m4")
        val movido = Grilla.mover(sinM4, "m3", 5, 0)
        assertEquals(5, movido.celdas.first { it.id == "m3" }.col)
    }

    @Test
    fun redimensionarRespetaVecinosYLimites() {
        assertSame(manejo, Grilla.redimensionar(manejo, "m1", 3, 2)) // pisaría la velocidad
        assertSame(manejo, Grilla.redimensionar(manejo, "m1", 0, 1))
        val sinVel = Grilla.quitar(manejo, "m2")
        assertEquals(4, Grilla.redimensionar(sinVel, "m1", 4, 2).celdas.first { it.id == "m1" }.ancho)
    }

    @Test
    fun agregarUsaElPrimerHuecoYRespetaTableroLleno() {
        assertSame(manejo, Grilla.agregar(manejo, "x", "rpm", TipoGauge.NUMERO)) // 18 celdas ocupadas
        val conHueco = Grilla.quitar(manejo, "m10")
        val agregado = Grilla.agregar(conHueco, "x", "map", TipoGauge.NUMERO)
        val nueva = agregado.celdas.first { it.id == "x" }
        assertEquals(3 to 2, nueva.col to nueva.fila)
    }

    @Test
    fun arrastreRedondeaACeldas() {
        assertEquals(1, Grilla.aCeldas(130f, 200f))
        assertEquals(0, Grilla.aCeldas(90f, 200f))
        assertEquals(-2, Grilla.aCeldas(-410f, 200f))
    }

    @Test
    fun tableroSobreviveAlJson() {
        val texto = SyncJson.json.encodeToString(DefinicionTablero.serializer(), Tableros.diesel)
        assertEquals(Tableros.diesel, SyncJson.json.decodeFromString(DefinicionTablero.serializer(), texto))
    }

    @Test
    fun alertasEligenLaMasGravePorSenal() {
        val activas = Alertas.evaluar(mapOf("temp_refrigerante" to 114.0, "rpm" to 900.0, "voltaje" to 14.0))
        assertEquals(1, activas.size)
        assertEquals(NivelAviso.CRITICO, activas.single().regla.nivel)
        assertEquals(NivelAviso.CRITICO, Alertas.nivelDe("temp_refrigerante", activas))
        assertNull(Alertas.nivelDe("voltaje", activas))
    }

    @Test
    fun voltajeBajoSoloCuentaConMotorEncendido() {
        assertTrue(Alertas.evaluar(mapOf("voltaje" to 12.3, "rpm" to 0.0)).isEmpty())
        val enMarcha = Alertas.evaluar(mapOf("voltaje" to 12.3, "rpm" to 850.0))
        assertEquals(NivelAviso.CRITICO, enMarcha.single().regla.nivel)
        val cargaFloja = Alertas.evaluar(mapOf("voltaje" to 13.0, "rpm" to 850.0))
        assertEquals(NivelAviso.ATENCION, cargaFloja.single().regla.nivel)
    }

    @Test
    fun elSimuladorNoDisparaAlertasEnUnCicloNormal() = runTest {
        val lecturas = SimuladorAccent(periodoMs = 0).lecturas().take(600).toList()
        val conAlerta = lecturas.filter { l -> Alertas.evaluar(l.valores).any { it.regla.senalId != "combustible" } }
        assertTrue(conAlerta.isEmpty(), "alertas en modo demo: ${conAlerta.firstOrNull()?.let { Alertas.evaluar(it.valores) }}")
    }

    @Test
    fun rangoNormalSaleDeLasAlertas() {
        // Voltage: above the highest "menorQue" (13.2) and below the "mayorQue" (15.2).
        assertEquals(13.2..15.2, Alertas.rangoNormal("voltaje"))
        // Coolant only has upper limits: the lowest one wins.
        assertEquals(Double.NEGATIVE_INFINITY..105.0, Alertas.rangoNormal("temp_refrigerante"))
        // No rules, no band.
        assertNull(Alertas.rangoNormal("rpm"))
    }
}
