package com.carobd.core.model

import kotlinx.datetime.Instant

/** Una fila del plan del manual: qué se hace, cada cuánto y en qué condiciones. */
data class FilaCatalogo(
    val codigo: String,
    val accion: String,
    val nombre: String,
    val cadaKm: Int?,
    val cadaMeses: Int?,
    val primeraKm: Int? = null,
    val primeraMeses: Int? = null,
    val severoKm: Int? = null,
    val severoMeses: Int? = null,
    val recomendacion: Boolean = false,
    val notas: String? = null,
)

/** Una plantilla de mantenimiento por motor y caja. */
data class Plantilla(val id: String, val nombre: String, val filas: List<FilaCatalogo>)

/**
 * Planes de mantenimiento del Accent, sacados del manual del propietario (columna "Excepto Europa").
 * Ver `investigacion/manual-mantenimiento-rangos.md` en los archivos del proyecto.
 */
object CatalogoMantenimiento {
    const val REVISAR = "REVISAR"
    const val CAMBIAR = "CAMBIAR"

    const val DIESEL_D4FB_MANUAL = "accent-rb-diesel-d4fb-manual"

    /** Códigos que la app usa por su nombre. */
    const val ACEITE_MOTOR = "ACEITE_MOTOR"
    const val FILTRO_COMBUSTIBLE = "FILTRO_COMBUSTIBLE"

    /** Filtro de combustible cuando el gasoil no asegura EN 590 (manual del propietario). */
    const val COMBUSTIBLE_SIN_EN590_REVISAR_KM = 7_500
    const val COMBUSTIBLE_SIN_EN590_CAMBIAR_KM = 15_000

    private val dieselD4fbManual = Plantilla(
        id = DIESEL_D4FB_MANUAL,
        nombre = "Accent RB 1.6 CRDi diésel, caja manual",
        filas = listOf(
            FilaCatalogo(ACEITE_MOTOR, CAMBIAR, "Aceite de motor y filtro", 10_000, 12, severoKm = 5_000, severoMeses = 6,
                notas = "5,3 L. ACEA C2 o C3 con DPF; ACEA B4 sin DPF. 5W-30 cubre casi todo el año."),
            FilaCatalogo("FILTRO_AIRE", REVISAR, "Filtro de aire", 20_000, 12),
            FilaCatalogo("FILTRO_AIRE", CAMBIAR, "Filtro de aire", 60_000, 36, primeraKm = 40_000, primeraMeses = 24,
                severoKm = 20_000, severoMeses = 12, notas = "Más seguido en tierra o arena."),
            FilaCatalogo(FILTRO_COMBUSTIBLE, REVISAR, "Filtro de combustible y conductos", 20_000, 12,
                notas = "Sin gasoil EN 590: revisar cada 7.500 km."),
            FilaCatalogo(FILTRO_COMBUSTIBLE, CAMBIAR, "Filtro de combustible", 60_000, null, primeraKm = 40_000,
                notas = "Sin gasoil EN 590: cambiar cada 15.000 km. Purgar el agua cuando se enciende su testigo."),
            FilaCatalogo("FILTRO_HABITACULO", CAMBIAR, "Filtro del habitáculo", 20_000, 12, severoKm = 10_000, severoMeses = 6),
            FilaCatalogo("CORREAS", REVISAR, "Correas de accesorios", 20_000, 12, primeraKm = 80_000, primeraMeses = 48,
                notas = "Tensor, polea loca y alternador. El D4FB tiene cadena de distribución, no correa."),
            FilaCatalogo("REFRIGERACION", REVISAR, "Sistema de refrigeración", 20_000, 12, primeraKm = 60_000, primeraMeses = 48,
                notas = "Radiador, depósito y mangueras."),
            FilaCatalogo("REFRIGERANTE", CAMBIAR, "Refrigerante", 30_000, 24, primeraKm = 210_000, primeraMeses = 120,
                notas = "6,3 L. Rellenar solo con agua desionizada o blanda."),
            FilaCatalogo("LIQUIDO_FRENOS", REVISAR, "Líquido de frenos y embrague", 20_000, 12),
            FilaCatalogo("LIQUIDO_FRENOS", CAMBIAR, "Líquido de frenos y embrague", null, 24, recomendacion = true,
                notas = "Hyundai no lo pide fuera de Europa; la app lo recomienda. DOT 3 o DOT 4."),
            FilaCatalogo("FRENOS", REVISAR, "Pastillas, discos y conductos de freno", 20_000, 12, severoKm = 10_000, severoMeses = 6),
            FilaCatalogo("FRENO_ESTACIONAMIENTO", REVISAR, "Freno de estacionamiento", 20_000, 12, severoKm = 10_000, severoMeses = 6),
            FilaCatalogo("ACEITE_CAJA", REVISAR, "Aceite de caja manual", 60_000, null,
                notas = "1,7 a 1,8 L de 70W GL-4. En uso severo, cambiarlo cada 120.000 km."),
            FilaCatalogo("NEUMATICOS", REVISAR, "Neumáticos", 20_000, 12, notas = "La presión, mucho más seguido."),
            FilaCatalogo("BATERIA", REVISAR, "Batería", 20_000, 12),
            FilaCatalogo("AIRE_ACONDICIONADO", REVISAR, "Aire acondicionado", 20_000, 12),
            FilaCatalogo("TREN_DELANTERO", REVISAR, "Escape, palieres, rótulas y dirección", 20_000, 12,
                severoKm = 10_000, severoMeses = 6),
            FilaCatalogo("TANQUE", REVISAR, "Manguera de vapor y tapa del tanque", 60_000, null, primeraKm = 40_000),
        ),
    )

    val plantillas: List<Plantilla> = listOf(dieselD4fbManual)

    fun plantilla(id: String?): Plantilla = plantillas.firstOrNull { it.id == id } ?: dieselD4fbManual

    /** Id fijo de un ítem del catálogo: el móvil y la pantalla llegan al mismo sin hablarse. */
    fun idItem(vehiculoId: String, codigo: String, accion: String): String =
        "$vehiculoId:${codigo.lowercase()}:${accion.lowercase()}"

    fun fila(item: ItemMantenimiento, plantillaId: String?): FilaCatalogo? =
        plantilla(plantillaId).filas.firstOrNull { it.codigo == item.codigo && it.accion == item.accion }

    fun item(fila: FilaCatalogo, vehiculoId: String, ahora: Instant): ItemMantenimiento = ItemMantenimiento(
        id = idItem(vehiculoId, fila.codigo, fila.accion),
        vehiculoId = vehiculoId,
        nombre = if (fila.accion == REVISAR) "${fila.nombre} (revisar)" else fila.nombre,
        cadaKm = fila.cadaKm,
        cadaMeses = fila.cadaMeses,
        codigo = fila.codigo,
        accion = fila.accion,
        primeraKm = fila.primeraKm,
        primeraMeses = fila.primeraMeses,
        severoKm = fila.severoKm,
        severoMeses = fila.severoMeses,
        recomendacion = fila.recomendacion,
        notas = fila.notas,
        updatedAt = ahora,
    )

    /** El plan completo de una plantilla, con ids fijos. */
    fun plan(vehiculoId: String, plantillaId: String?, ahora: Instant): List<ItemMantenimiento> =
        plantilla(plantillaId).filas.map { item(it, vehiculoId, ahora) }

    /** Vuelve un ítem a los intervalos del manual, sin tocar su historial. */
    fun restaurar(item: ItemMantenimiento, plantillaId: String?, ahora: Instant): ItemMantenimiento {
        val f = fila(item, plantillaId) ?: return item
        return item.copy(
            cadaKm = f.cadaKm, cadaMeses = f.cadaMeses, primeraKm = f.primeraKm, primeraMeses = f.primeraMeses,
            severoKm = f.severoKm, severoMeses = f.severoMeses, personalizado = false, updatedAt = ahora,
        )
    }
}

/**
 * Pasa el plan al catálogo de la plantilla. Se puede correr en el móvil y en la pantalla, las veces que sea:
 * los ids son fijos, así que dos dispositivos que migran por separado llegan a los mismos ítems.
 * Devuelve solo lo que hay que guardar.
 */
object MigracionPlan {
    /** El plan de 8 ítems de antes del catálogo: nombre → código, acción e intervalos que traía. */
    private val viejos = mapOf(
        "Aceite y filtro de aceite" to Viejo(CatalogoMantenimiento.ACEITE_MOTOR, CatalogoMantenimiento.CAMBIAR, 10_000, 12),
        "Filtro de aire" to Viejo("FILTRO_AIRE", CatalogoMantenimiento.CAMBIAR, 20_000, 24),
        "Filtro de combustible" to Viejo(CatalogoMantenimiento.FILTRO_COMBUSTIBLE, CatalogoMantenimiento.CAMBIAR, 40_000, null),
        "Líquido de frenos" to Viejo("LIQUIDO_FRENOS", CatalogoMantenimiento.CAMBIAR, null, 24),
        "Correa de accesorios" to Viejo("CORREAS", CatalogoMantenimiento.REVISAR, 60_000, null),
        "Refrigerante" to Viejo("REFRIGERANTE", CatalogoMantenimiento.CAMBIAR, 120_000, 60),
        "Pastillas de freno (revisión)" to Viejo("FRENOS", CatalogoMantenimiento.REVISAR, 10_000, null),
        "Rotación de neumáticos" to Viejo("NEUMATICOS", CatalogoMantenimiento.REVISAR, 10_000, null),
    )

    private data class Viejo(val codigo: String, val accion: String, val cadaKm: Int?, val cadaMeses: Int?)

    fun migrar(items: List<ItemMantenimiento>, vehiculo: Vehiculo, ahora: Instant): List<ItemMantenimiento> {
        val propios = items.filter { it.vehiculoId == vehiculo.id }
        val porId = propios.associateBy { it.id }
        val cambios = mutableListOf<ItemMantenimiento>()
        for (nuevo in CatalogoMantenimiento.plan(vehiculo.id, vehiculo.plantilla, ahora)) {
            val ya = porId[nuevo.id]
            if (ya != null) {
                // Ya migrado: si el manual cambió y el usuario no tocó los intervalos, se actualizan.
                if (ya.deletedAt == null && !ya.personalizado) {
                    val f = CatalogoMantenimiento.restaurar(ya, vehiculo.plantilla, ahora)
                    if (f.copy(updatedAt = ya.updatedAt) != ya) cambios += f
                }
                continue
            }
            val anterior = propios.firstOrNull { v ->
                v.codigo == null && v.deletedAt == null && viejos[v.nombre]?.let { it.codigo == nuevo.codigo && it.accion == nuevo.accion } == true
            }
            cambios += if (anterior == null) nuevo else {
                val viejo = viejos.getValue(anterior.nombre)
                val tocado = anterior.cadaKm != viejo.cadaKm || anterior.cadaMeses != viejo.cadaMeses
                nuevo.copy(
                    ultimoKm = anterior.ultimoKm,
                    ultimaFecha = anterior.ultimaFecha,
                    cadaKm = if (tocado) anterior.cadaKm else nuevo.cadaKm,
                    cadaMeses = if (tocado) anterior.cadaMeses else nuevo.cadaMeses,
                    personalizado = tocado,
                )
            }
        }
        // Los ítems viejos que pasaron al catálogo se borran (borrado lógico, como todo lo sincronizable).
        cambios += propios.filter { it.codigo == null && it.deletedAt == null && it.nombre in viejos }
            .map { it.copy(deletedAt = ahora, updatedAt = ahora) }
        return cambios
    }
}
