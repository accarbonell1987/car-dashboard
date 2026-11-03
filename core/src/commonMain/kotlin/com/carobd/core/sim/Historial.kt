package com.carobd.core.sim

/**
 * Las últimas lecturas de algunas señales, una muestra por segundo, para la gráfica del último minuto.
 * Se guarda una muestra cuando pasa al menos un segundo desde la anterior, para no depender de la frecuencia del OBD.
 */
class Historial(private val senales: List<String>, private val segundos: Int = 60) {
    private val datos = senales.associateWith { ArrayDeque<Double>() }
    private var ultimoMs: Long? = null

    /** Devuelve true si agregó una muestra (la gráfica solo cambia entonces). */
    fun agregar(l: Lectura): Boolean {
        val u = ultimoMs
        if (u != null && l.tMs - u < 1000 && l.tMs >= u) return false
        ultimoMs = l.tMs
        for (id in senales) {
            val v = l.valores[id] ?: continue
            val cola = datos.getValue(id)
            cola.addLast(v)
            while (cola.size > segundos) cola.removeFirst()
        }
        return true
    }

    fun serie(id: String): List<Double> = datos[id]?.toList().orEmpty()

    fun limpiar() {
        datos.values.forEach { it.clear() }
        ultimoMs = null
    }
}
