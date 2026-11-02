package com.carobd.core.sync

import com.carobd.core.model.LecturaOdometro

/**
 * El odómetro es el único dato que escriben la pantalla (calculado) y el móvil (manual o por servicio).
 * Manda la lectura con mayor km; si varias tienen el mismo km, la más reciente.
 */
fun odometroVigente(lecturas: Collection<LecturaOdometro>): LecturaOdometro? =
    lecturas.filter { it.deletedAt == null }
        .maxWithOrNull(compareBy<LecturaOdometro> { it.km }.thenBy { it.fecha })
