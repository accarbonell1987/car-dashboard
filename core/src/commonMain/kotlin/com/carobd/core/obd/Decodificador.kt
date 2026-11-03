package com.carobd.core.obd

/**
 * Convierte la respuesta cruda del ELM327 en un valor con las fórmulas del catálogo.
 * Respuesta típica a `010C`: "41 0C 1A F8" → bytes de datos [0x1A, 0xF8] → (26*256+248)/4 = 1726 rpm.
 */
object Decodificador {

    /** Extrae los bytes de datos de una respuesta de modo 01 o 22, sin cabeceras ni espacios. */
    fun bytesDeDatos(respuesta: String, senal: Senal): List<Int>? {
        val limpio = respuesta.uppercase().replace(" ", "").replace("\r", "").replace("\n", "").replace(">", "")
        if (limpio.isEmpty() || limpio.contains("NODATA") || limpio.contains("ERROR") || limpio.contains("UNABLE")) return null
        val modo = senal.modo ?: return null
        val pid = senal.pid ?: return null
        val modoRespuesta = (modo.toInt(16) + 0x40).toString(16).uppercase().padStart(2, '0')
        val prefijo = modoRespuesta + pid.uppercase()
        val inicio = limpio.indexOf(prefijo)
        if (inicio < 0) return null
        val datos = limpio.substring(inicio + prefijo.length)
        if (datos.length < senal.bytes * 2) return null
        return (0 until senal.bytes).map { datos.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    fun decodificar(respuesta: String, senal: Senal): Double? {
        val bytes = bytesDeDatos(respuesta, senal) ?: return null
        val formula = senal.formula ?: return bytes.first().toDouble()
        val vars = mapOf(
            "A" to (bytes.getOrNull(0) ?: 0).toDouble(),
            "B" to (bytes.getOrNull(1) ?: 0).toDouble(),
            "C" to (bytes.getOrNull(2) ?: 0).toDouble(),
            "D" to (bytes.getOrNull(3) ?: 0).toDouble(),
        )
        return Formula.evaluar(formula, vars)
    }
}

/** Evaluador mínimo de fórmulas aritméticas con variables: + - * / y paréntesis. */
object Formula {
    fun evaluar(expr: String, vars: Map<String, Double>): Double = Parser(expr.replace(" ", ""), vars).parse()

    private class Parser(val s: String, val vars: Map<String, Double>) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            require(i == s.length) { "Fórmula inválida: $s" }
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v * r else v / r
            }
            return v
        }

        private fun factor(): Double {
            if (i < s.length && s[i] == '-') { i++; return -factor() }
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                require(i < s.length && s[i] == ')') { "Falta ')' en $s" }
                i++
                return v
            }
            val inicio = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_')) i++
            val token = s.substring(inicio, i)
            require(token.isNotEmpty()) { "Token inesperado en $s posición $i" }
            return token.toDoubleOrNull() ?: vars[token] ?: error("Variable desconocida: $token")
        }
    }
}
