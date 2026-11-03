package com.carobd.core.tablero

import com.carobd.core.model.NivelAviso
import kotlinx.serialization.Serializable

/**
 * Regla de alerta sobre una señal. Salta si el valor pasa de `mayorQue` o baja de `menorQue`.
 * `conMotorEncendido` evita falsas alarmas con el contacto puesto y el motor parado (p. ej. voltaje de 12,4 V).
 */
@Serializable
data class ReglaAlerta(
    val senalId: String,
    val nivel: NivelAviso,
    val mensaje: String,
    val mayorQue: Double? = null,
    val menorQue: Double? = null,
    val conMotorEncendido: Boolean = false,
)

data class Alerta(val regla: ReglaAlerta, val valor: Double)

object Alertas {
    /** RPM a partir de las cuales consideramos el motor encendido. */
    const val RPM_ENCENDIDO = 400.0

    /** Umbrales para el Accent 1.6 CRDi. Se ajustan cuando tengamos lecturas reales del vLinker. */
    val accentCrdi: List<ReglaAlerta> = listOf(
        ReglaAlerta("temp_refrigerante", NivelAviso.CRITICO, "Motor sobrecalentado: detente y apaga", mayorQue = 112.0),
        ReglaAlerta("temp_refrigerante", NivelAviso.ATENCION, "Refrigerante alto", mayorQue = 105.0),
        ReglaAlerta("temp_aceite", NivelAviso.ATENCION, "Aceite muy caliente", mayorQue = 130.0),
        ReglaAlerta("voltaje", NivelAviso.CRITICO, "Sobrecarga del alternador", mayorQue = 15.2),
        ReglaAlerta("voltaje", NivelAviso.CRITICO, "El alternador no está cargando", menorQue = 12.4, conMotorEncendido = true),
        ReglaAlerta("voltaje", NivelAviso.ATENCION, "Carga baja del alternador", menorQue = 13.2, conMotorEncendido = true),
        ReglaAlerta("temp_dpf", NivelAviso.ATENCION, "Filtro de partículas muy caliente", mayorQue = 650.0),
        ReglaAlerta("combustible", NivelAviso.ATENCION, "Queda poco combustible", menorQue = 10.0),
    )

    /**
     * Alertas activas, una por señal (la más grave), ordenadas de más a menos grave.
     * Las señales sin lectura no disparan nada.
     */
    fun evaluar(valores: Map<String, Double>, reglas: List<ReglaAlerta> = accentCrdi): List<Alerta> {
        val encendido = (valores["rpm"] ?: 0.0) >= RPM_ENCENDIDO
        return reglas.mapNotNull { r ->
            val v = valores[r.senalId] ?: return@mapNotNull null
            if (r.conMotorEncendido && !encendido) return@mapNotNull null
            val salta = (r.mayorQue != null && v > r.mayorQue) || (r.menorQue != null && v < r.menorQue)
            if (salta) Alerta(r, v) else null
        }
            .groupBy { it.regla.senalId }
            .map { (_, deLaSenal) -> deLaSenal.maxBy { it.regla.nivel.ordinal } }
            .sortedByDescending { it.regla.nivel.ordinal }
    }

    /** Nivel de alerta de una señal concreta, para pintar su gauge. */
    fun nivelDe(senalId: String, activas: List<Alerta>): NivelAviso? =
        activas.firstOrNull { it.regla.senalId == senalId }?.regla?.nivel
}
