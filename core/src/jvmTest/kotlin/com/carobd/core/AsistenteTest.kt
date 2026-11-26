package com.carobd.core

import com.carobd.core.datos.Bitacora
import com.carobd.core.model.Asistente
import com.carobd.core.model.CatalogoMantenimiento
import com.carobd.core.model.Estado
import com.carobd.core.model.EstadoMantenimiento
import com.carobd.core.model.RespuestaItem
import com.carobd.core.model.RespuestasAsistente
import com.carobd.core.sync.AlmacenPersistente
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsistenteTest {
    private val ahora = Instant.parse("2026-11-25T12:00:00Z")
    private var n = 0
    private fun bitacora() = Bitacora(AlmacenPersistente({ null }, { }), reloj = { ahora }, crearId = { "id-${n++}" })
    private val plan = CatalogoMantenimiento.plan("v", CatalogoMantenimiento.DIESEL_D4FB_MANUAL, ahora)
    private fun item(codigo: String, accion: String) = plan.first { it.codigo == codigo && it.accion == accion }

    @Test
    fun elHitoDe100000IncluyeLoQueTocaAhi() {
        val hito = Asistente.itemsDelHito(plan, 100_000).map { it.codigo to it.accion }.toSet()
        assertTrue(CatalogoMantenimiento.ACEITE_MOTOR to CatalogoMantenimiento.CAMBIAR in hito)
        assertTrue("CORREAS" to CatalogoMantenimiento.REVISAR in hito, "80.000 y luego cada 20.000")
        assertTrue("FILTRO_AIRE" to CatalogoMantenimiento.CAMBIAR in hito, "40.000 y luego cada 60.000")
        assertFalse("ACEITE_CAJA" to CatalogoMantenimiento.REVISAR in hito)
        assertFalse("REFRIGERANTE" to CatalogoMantenimiento.CAMBIAR in hito)
        assertFalse("LIQUIDO_FRENOS" to CatalogoMantenimiento.CAMBIAR in hito, "solo por tiempo")
    }

    @Test
    fun elAsistenteDejaElPlanSinDesconocidosDondeHuboRespuesta() = runTest {
        val b = bitacora()
        val g = b.garaje()
        val aceite = g.items.first { it.codigo == CatalogoMantenimiento.ACEITE_MOTOR }
        val refrigerante = g.items.first { it.codigo == "REFRIGERANTE" && it.accion == CatalogoMantenimiento.CAMBIAR }
        val caja = g.items.first { it.codigo == "ACEITE_CAJA" }
        val r = RespuestasAsistente(
            plantilla = CatalogoMantenimiento.DIESEL_D4FB_MANUAL,
            mesAlta = "2016-06",
            kmActual = 91_294,
            condiciones = listOf("F"),
            en590 = false,
            ultimoServiceKm = 90_000,
            ultimoServiceMes = "2026-06",
            items = mapOf(
                refrigerante.id to RespuestaItem(Asistente.NUNCA),
                caja.id to RespuestaItem(Asistente.APROX, km = 60_000, mes = "2022-01"),
            ),
            chequeo = mapOf("temperatura" to false, "testigo_motor" to false),
        )
        b.guardarAsistente(r)
        // Guardar dos veces no duplica nada.
        b.guardarAsistente(r)

        val g2 = b.garaje()
        assertEquals(91_294, g2.odometroKm, "el km actual corrige el odómetro")
        assertTrue(g2.vehiculo.usoSevero)
        val estados = EstadoMantenimiento.calcular(g2.items, g2.servicios, g2.vehiculo, g2.odometroKm, ahora).associateBy { it.item.id }
        assertEquals(Estado.VENCIDO, estados.getValue(refrigerante.id).estado, "nunca cambiado en 10 años")
        assertEquals(60_000, estados.getValue(caja.id).base!!.km)
        assertEquals(90_000, estados.getValue(aceite.id).base!!.km)
        assertEquals(Estado.PROXIMO, estados.getValue(aceite.id).estado, "uso severo: vence el 1 de diciembre")

        val avisos = b.avisos()
        assertEquals(1, avisos.size)
        assertEquals("chequeo:${Bitacora.ID_ACCENT}:temperatura", avisos.single().id)

        // Corregir una respuesta a "no sé" borra el servicio declarado.
        b.guardarAsistente(r.copy(items = r.items - caja.id, ultimoServiceKm = null))
        val g3 = b.garaje()
        assertTrue(g3.servicios.none { caja.id in it.itemsHechos })
        assertTrue(g3.servicios.none { aceite.id in it.itemsHechos })
        assertEquals(91_294, g3.odometroKm, "los declarados no cuentan para el odómetro")
    }
}
