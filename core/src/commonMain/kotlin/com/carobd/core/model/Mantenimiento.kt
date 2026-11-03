package com.carobd.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.daysUntil

/** Lo que falta para un ítem del plan: por kilómetros, por fecha, o ambos. */
data class Pendiente(
    val item: ItemMantenimiento,
    val faltanKm: Int?,
    val faltanDias: Int?,
) {
    val vencido: Boolean get() = (faltanKm != null && faltanKm <= 0) || (faltanDias != null && faltanDias <= 0)
}

object Mantenimiento {

    /** Calcula "faltan N km / N días" para cada ítem, dado el odómetro y la fecha de hoy. */
    fun pendientes(
        items: List<ItemMantenimiento>,
        odometroKm: Int,
        hoy: Instant,
        zona: TimeZone = TimeZone.UTC,
    ): List<Pendiente> = items.filter { it.deletedAt == null }.map { item ->
        val faltanKm = item.cadaKm?.let { cada -> (item.ultimoKm ?: 0) + cada - odometroKm }
        val faltanDias = item.cadaMeses?.let { meses ->
            val desde = item.ultimaFecha ?: return@let null
            // Días de calendario entre hoy y la fecha de vencimiento, sin importar la hora del día.
            val vence = desde.toLocalDateTime(zona).date.plus(meses, DateTimeUnit.MONTH)
            hoy.toLocalDateTime(zona).date.daysUntil(vence)
        }
        Pendiente(item, faltanKm, faltanDias)
    }.sortedWith(compareBy({ it.faltanKm ?: Int.MAX_VALUE }, { it.faltanDias ?: Int.MAX_VALUE }))

    /**
     * Plantilla del manual del Hyundai Accent 1.6 CRDi. Se ajusta con las tarjetas del mecánico.
     * `nuevoId` genera UUIDs en el dispositivo que la crea.
     */
    fun plantillaAccentCrdi(vehiculoId: String, ahora: Instant, nuevoId: () -> String): List<ItemMantenimiento> =
        listOf(
            Triple("Aceite y filtro de aceite", 10_000, 12),
            Triple("Filtro de aire", 20_000, 24),
            Triple("Filtro de combustible", 40_000, null),
            Triple("Líquido de frenos", null, 24),
            Triple("Correa de accesorios", 60_000, null),
            Triple("Refrigerante", 120_000, 60),
            Triple("Pastillas de freno (revisión)", 10_000, null),
            Triple("Rotación de neumáticos", 10_000, null),
        ).map { (nombre, km, meses) ->
            ItemMantenimiento(
                id = nuevoId(),
                vehiculoId = vehiculoId,
                nombre = nombre,
                cadaKm = km,
                cadaMeses = meses,
                updatedAt = ahora,
            )
        }
}


/** Texto de lo que falta para un ítem: "faltan 9.900 km · faltan 365 días". */
fun Pendiente.texto(): String = buildList {
    faltanKm?.let { add(if (it <= 0) "vencido por ${com.carobd.core.miles(-it)} km" else "faltan ${com.carobd.core.miles(it)} km") }
    faltanDias?.let { add(if (it <= 0) "vencido hace ${-it} días" else "faltan $it días") }
    if (item.ultimoKm == null && item.ultimaFecha == null) add("sin registro previo")
}.joinToString(" · ")
