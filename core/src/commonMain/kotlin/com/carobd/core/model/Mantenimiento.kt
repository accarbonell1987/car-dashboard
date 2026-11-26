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
}


/** Texto de lo que falta para un ítem: "faltan 9.900 km · faltan 365 días". */
fun Pendiente.texto(): String = buildList {
    faltanKm?.let { add(if (it <= 0) "vencido por ${com.carobd.core.miles(-it)} km" else "faltan ${com.carobd.core.miles(it)} km") }
    faltanDias?.let { add(if (it <= 0) "vencido hace ${-it} días" else "faltan $it días") }
    if (item.ultimoKm == null && item.ultimaFecha == null) add("sin registro previo")
}.joinToString(" · ")

/** Estado de un ítem del plan. Se calcula al vuelo; nunca se guarda ni se sincroniza. */
enum class Estado { AL_DIA, PROXIMO, VENCIDO, DESCONOCIDO }

/** Desde dónde se cuenta un ítem: el último servicio que lo incluye, o la declaración "nunca". */
data class Base(val km: Int, val fecha: Instant?, val nunca: Boolean, val aproximado: Boolean)

data class EstadoItem(
    val item: ItemMantenimiento,
    val estado: Estado,
    val base: Base?,
    /** Intervalo usado (primera vez, normal, severo o el del gasoil sin EN 590). */
    val intervaloKm: Int?,
    val intervaloMeses: Int?,
    val faltanKm: Int?,
    val faltanDias: Int?,
    /** Desconocido, pero el odómetro o la edad del auto ya pasaron la primera vez del manual. */
    val probablementeVencido: Boolean = false,
)

object EstadoMantenimiento {
    const val PROXIMO_KM = 1_000
    const val PROXIMO_DIAS = 30

    /**
     * Reglas de la sección "Reglas de cálculo" del análisis:
     * 1. Base: el servicio más reciente (por km) que incluye el ítem, registrado o declarado; si no, el último
     *    km y fecha guardados en el ítem (planes viejos). Sin base, el ítem es desconocido.
     * 2. Intervalo: primera vez si la base es "nunca"; si no, severo cuando corresponde, o el normal.
     * 3. Filtro de combustible sin EN 590: 7.500 km revisar, 15.000 km cambiar.
     * 4. Lo que falta, en km y en días de calendario.
     * 5. Vencido si algo llega a 0; próximo con 1.000 km o 30 días o menos; si no, al día.
     * 6. Un desconocido con primera vez ya pasada se marca "probablemente vencido".
     */
    fun calcular(
        items: List<ItemMantenimiento>,
        servicios: List<Servicio>,
        vehiculo: Vehiculo,
        odometroKm: Int,
        hoy: Instant,
        zona: TimeZone = TimeZone.UTC,
    ): List<EstadoItem> {
        val vivos = servicios.filter { it.deletedAt == null }
        val hoyFecha = hoy.toLocalDateTime(zona).date
        return items.filter { it.deletedAt == null && it.activo }.map { item ->
            val base = base(item, vivos, vehiculo)
            val (cadaKm, cadaMeses) = intervalo(item, base, vehiculo)
            if (base == null) {
                val pasoKm = item.primeraKm != null && odometroKm >= item.primeraKm
                val pasoTiempo = item.primeraMeses != null && vehiculo.fechaAlta != null &&
                    hoyFecha >= vehiculo.fechaAlta.toLocalDateTime(zona).date.plus(item.primeraMeses, DateTimeUnit.MONTH)
                return@map EstadoItem(item, Estado.DESCONOCIDO, null, cadaKm, cadaMeses, null, null, pasoKm || pasoTiempo)
            }
            val faltanKm = cadaKm?.let { base.km + it - odometroKm }
            val faltanDias = cadaMeses?.let { meses ->
                base.fecha?.let { hoyFecha.daysUntil(it.toLocalDateTime(zona).date.plus(meses, DateTimeUnit.MONTH)) }
            }
            val estado = when {
                (faltanKm != null && faltanKm <= 0) || (faltanDias != null && faltanDias <= 0) -> Estado.VENCIDO
                (faltanKm != null && faltanKm <= PROXIMO_KM) || (faltanDias != null && faltanDias <= PROXIMO_DIAS) -> Estado.PROXIMO
                faltanKm == null && faltanDias == null -> Estado.DESCONOCIDO
                else -> Estado.AL_DIA
            }
            EstadoItem(item, estado, base, cadaKm, cadaMeses, faltanKm, faltanDias)
        }.sortedWith(compareBy({ orden(it) }, { it.faltanKm ?: Int.MAX_VALUE }, { it.faltanDias ?: Int.MAX_VALUE }))
    }

    /** Lo que conviene hacer en el próximo service: lo vencido, lo próximo y lo desconocido que ya pasó la primera vez. */
    fun proximoService(estados: List<EstadoItem>): List<EstadoItem> = estados.filter {
        it.estado == Estado.VENCIDO || it.estado == Estado.PROXIMO || it.probablementeVencido
    }

    private fun orden(e: EstadoItem): Int = when {
        e.estado == Estado.VENCIDO -> 0
        e.probablementeVencido -> 1
        e.estado == Estado.PROXIMO -> 2
        e.estado == Estado.DESCONOCIDO -> 3
        else -> 4
    }

    internal fun base(item: ItemMantenimiento, servicios: List<Servicio>, vehiculo: Vehiculo): Base? {
        val ultimo = servicios.filter { item.id in it.itemsHechos }.maxWithOrNull(compareBy({ it.km }, { it.fecha }))
        if (ultimo != null) {
            return if (ultimo.nunca) Base(0, vehiculo.fechaAlta, nunca = true, aproximado = vehiculo.fechaAlta == null)
            else Base(ultimo.km, ultimo.fecha, nunca = false, aproximado = ultimo.aproximado)
        }
        // Planes anteriores al catálogo: el último km y fecha vivían copiados en el ítem.
        if (item.ultimoKm != null || item.ultimaFecha != null) {
            return Base(item.ultimoKm ?: 0, item.ultimaFecha, nunca = false, aproximado = item.ultimoKm == null)
        }
        return null
    }

    internal fun intervalo(item: ItemMantenimiento, base: Base?, vehiculo: Vehiculo): Pair<Int?, Int?> {
        if (item.codigo == CatalogoMantenimiento.FILTRO_COMBUSTIBLE && vehiculo.combustibleEn590 != true && !item.personalizado) {
            val km = if (item.accion == CatalogoMantenimiento.REVISAR) CatalogoMantenimiento.COMBUSTIBLE_SIN_EN590_REVISAR_KM else CatalogoMantenimiento.COMBUSTIBLE_SIN_EN590_CAMBIAR_KM
            return km to item.cadaMeses
        }
        if (base?.nunca == true && (item.primeraKm != null || item.primeraMeses != null)) return item.primeraKm to item.primeraMeses
        if (vehiculo.usoSevero && (item.severoKm != null || item.severoMeses != null)) return item.severoKm to item.severoMeses
        return item.cadaKm to item.cadaMeses
    }
}

/** Texto corto del estado: "faltan 3.706 km · faltan 112 días", "vencido por 1.294 km", "sin datos". */
fun EstadoItem.texto(): String {
    if (estado == Estado.DESCONOCIDO) return if (probablementeVencido) "sin datos, probablemente vencido" else "sin datos"
    val km = faltanKm?.let { Pair(it <= 0, if (it <= 0) "vencido por ${com.carobd.core.miles(-it)} km" else "faltan ${com.carobd.core.miles(it)} km") }
    val dias = faltanDias?.let { Pair(it <= 0, if (it <= 0) "vencido hace ${-it} días" else "faltan $it días") }
    val partes = listOfNotNull(km, dias)
    // Si venció por una de las dos medidas, lo que falta por la otra confunde: se muestra solo lo vencido.
    val mostrar = partes.filter { it.first }.ifEmpty { partes }
    return (mostrar.map { it.second } + listOfNotNull("aprox.".takeIf { base?.aproximado == true })).joinToString(" · ")
}
