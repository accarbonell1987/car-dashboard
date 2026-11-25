package com.carobd.core

import com.carobd.core.model.CatalogoMantenimiento
import com.carobd.core.model.Combustible
import com.carobd.core.model.Estado
import com.carobd.core.model.EstadoMantenimiento
import com.carobd.core.model.ItemMantenimiento
import com.carobd.core.model.ORIGEN_DECLARADO
import com.carobd.core.model.Servicio
import com.carobd.core.model.TipoVehiculo
import com.carobd.core.model.Vehiculo
import com.carobd.core.model.texto
import com.carobd.core.sync.SyncJson
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Los siete casos del ejemplo de "Reglas de cálculo" del análisis: el auto a 91.294 km el 25/11/2026. */
class EstadoMantenimientoTest {
    private val hoy = Instant.parse("2026-11-25T12:00:00Z")
    private val odometro = 91_294
    private val vid = "accent-2016-crdi"
    private val vehiculo = Vehiculo(
        id = vid, usuarioId = "", tipo = TipoVehiculo.AUTO, marca = "Hyundai", modelo = "Accent", anio = 2016,
        combustible = Combustible.DIESEL, odometroInicialKm = 90_600, odometroInicialFecha = hoy, tieneObd = true,
        plantilla = CatalogoMantenimiento.DIESEL_D4FB_MANUAL, fechaAlta = Instant.parse("2016-06-01T12:00:00Z"), updatedAt = hoy,
    )
    private val plan = CatalogoMantenimiento.plan(vid, CatalogoMantenimiento.DIESEL_D4FB_MANUAL, hoy)

    private fun item(codigo: String, accion: String): ItemMantenimiento = plan.first { it.codigo == codigo && it.accion == accion }

    private fun declarado(item: ItemMantenimiento, km: Int, fecha: String, aproximado: Boolean = false, nunca: Boolean = false) =
        Servicio(
            id = "s-${item.id}-$km", vehiculoId = vid, fecha = Instant.parse(fecha), km = km, tipo = "Declarado",
            itemsHechos = listOf(item.id), origen = ORIGEN_DECLARADO, aproximado = aproximado, nunca = nunca, updatedAt = hoy,
        )

    private val aceite = item("ACEITE_MOTOR", "CAMBIAR")
    private val combustible = item("FILTRO_COMBUSTIBLE", "CAMBIAR")
    private val correas = item("CORREAS", "REVISAR")
    private val refrigerante = item("REFRIGERANTE", "CAMBIAR")
    private val caja = item("ACEITE_CAJA", "REVISAR")
    private val frenos = item("LIQUIDO_FRENOS", "CAMBIAR")

    private val servicios = listOf(
        declarado(aceite, 85_000, "2026-03-15T12:00:00Z"),
        declarado(combustible, 80_000, "2025-10-01T12:00:00Z"),
        declarado(refrigerante, 0, "2016-06-01T12:00:00Z", nunca = true),
        declarado(caja, 60_000, "2022-01-01T12:00:00Z"),
        declarado(frenos, 70_000, "2023-11-25T12:00:00Z", aproximado = true),
    )

    private fun estados(v: Vehiculo = vehiculo) =
        EstadoMantenimiento.calcular(plan, servicios, v, odometro, hoy).associateBy { it.item.id }

    @Test
    fun aceiteAlDia() {
        val e = estados().getValue(aceite.id)
        assertEquals(Estado.AL_DIA, e.estado)
        assertEquals(3_706, e.faltanKm)
    }

    @Test
    fun aceiteEnUsoSeveroVencido() {
        val e = estados(vehiculo.copy(usoSevero = true)).getValue(aceite.id)
        assertEquals(Estado.VENCIDO, e.estado)
        assertEquals(5_000, e.intervaloKm)
        assertEquals(-1_294, e.faltanKm)
    }

    @Test
    fun filtroDeCombustibleSinEn590Cada15000() {
        val e = estados().getValue(combustible.id)
        assertEquals(15_000, e.intervaloKm)
        assertEquals(3_706, e.faltanKm)
        assertEquals(Estado.AL_DIA, e.estado)
        // Con EN 590 vuelve al intervalo del manual.
        assertEquals(60_000, estados(vehiculo.copy(combustibleEn590 = true)).getValue(combustible.id).intervaloKm)
    }

    @Test
    fun correasSinDatosProbablementeVencidas() {
        val e = estados().getValue(correas.id)
        assertEquals(Estado.DESCONOCIDO, e.estado)
        assertTrue(e.probablementeVencido)
        assertEquals("sin datos, probablemente vencido", e.texto())
    }

    @Test
    fun refrigeranteNuncaCambiadoVencidoPorTiempo() {
        val e = estados().getValue(refrigerante.id)
        assertEquals(210_000, e.intervaloKm)
        assertEquals(120, e.intervaloMeses)
        assertEquals(Estado.VENCIDO, e.estado)
        assertTrue(e.faltanDias!! < 0)
    }

    @Test
    fun aceiteDeCajaAlDia() {
        val e = estados().getValue(caja.id)
        assertEquals(Estado.AL_DIA, e.estado)
        assertEquals(28_706, e.faltanKm)
    }

    @Test
    fun liquidoDeFrenosVencidoAproximado() {
        val e = estados().getValue(frenos.id)
        assertEquals(Estado.VENCIDO, e.estado)
        assertTrue(e.texto().endsWith("aprox."))
    }

    @Test
    fun ningunItemSinDatosSaleVencido() {
        val todos = EstadoMantenimiento.calcular(plan, emptyList(), vehiculo, odometro, hoy)
        assertTrue(todos.all { it.estado == Estado.DESCONOCIDO })
    }

    @Test
    fun unServicioViejoCargadoDespuesNoHaceRetrocederElItem() {
        val nuevo = declarado(aceite, 85_000, "2026-03-15T12:00:00Z")
        val viejo = declarado(aceite, 75_000, "2025-03-15T12:00:00Z").copy(updatedAt = hoy.plus(kotlin.time.Duration.parse("1h")))
        val e = EstadoMantenimiento.calcular(plan, listOf(nuevo, viejo), vehiculo, odometro, hoy).first { it.item.id == aceite.id }
        assertEquals(85_000, e.base!!.km)
    }

    @Test
    fun elPlanTieneIdsFijos() {
        val otro = CatalogoMantenimiento.plan(vid, CatalogoMantenimiento.DIESEL_D4FB_MANUAL, hoy.plus(kotlin.time.Duration.parse("1d")))
        assertEquals(plan.map { it.id }, otro.map { it.id })
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
        assertEquals("accent-2016-crdi:aceite_motor:cambiar", aceite.id)
    }

    @Test
    fun unaAppViejaLeeLosCamposNuevosSinFallar() {
        // Lo que manda una app nueva, leído con la forma vieja de las clases: los campos de más se ignoran.
        val json = SyncJson.json.encodeToString(ItemMantenimiento.serializer(), aceite)
        assertTrue(json.contains("\"codigo\""))
        val leido = SyncJson.json.decodeFromString(ItemMantenimiento.serializer(), json.dropLast(1) + ",\"campoFuturo\":1}")
        assertEquals(aceite, leido)
    }
}
