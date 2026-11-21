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

    /**
     * PIDs que declara un bitmap de modo 01 (respuesta a 0100, 0120...): el bit más alto del primer byte es base+1.
     * Respuesta del Accent a 0100: "41 00 98 3B A0 13" → 01, 04, 05, 0B, 0C, 0D, 0F, 10, 11, 13, 1C, 1F, 20.
     */
    fun pidsSoportados(base: Int, respuesta: String): Set<Int>? {
        val limpio = respuesta.uppercase().replace(" ", "").replace("\r", "").replace("\n", "")
        val inicio = limpio.indexOf("41" + hex2(base))
        if (inicio < 0 || limpio.length < inicio + 12) return null
        val bits = limpio.substring(inicio + 4, inicio + 12).toLongOrNull(16) ?: return null
        return (0 until 32).filter { bits shr (31 - it) and 1L == 1L }.map { base + it + 1 }.toSet()
    }

    /**
     * Une una respuesta ISO-TP de varias tramas en los bytes del mensaje. Acepta el formato con ATH0
     * ("014", "0: 49 02 01 …", "1: …") y con ATH1 ("7E8 10 14 49 02 …", "7E8 21 …"); una sola trama también vale.
     */
    fun unirMultitrama(respuesta: String): List<Int>? {
        val lineas = respuesta.uppercase().split('\r', '\n').map { it.replace(" ", "") }.filter { it.isNotEmpty() && ">" != it }
        var largo: Int? = null
        val bytes = mutableListOf<Int>()
        for (linea in lineas) {
            val h0 = Regex("^([0-9A-F]):([0-9A-F]+)$").find(linea)
            val h1 = Regex("^7E[8-F]([0-9A-F]{2})([0-9A-F]*)$").find(linea)
            when {
                Regex("^[0-9A-F]{3}$").matches(linea) && largo == null && bytes.isEmpty() -> largo = linea.toInt(16)
                h0 != null -> bytes += aBytes(h0.groupValues[2])
                h1 != null -> {
                    val pci = h1.groupValues[1].toInt(16)
                    val resto = aBytes(h1.groupValues[2])
                    when (pci shr 4) {
                        0 -> { largo = pci and 0x0F; bytes += resto }
                        1 -> { largo = ((pci and 0x0F) shl 8) or (resto.firstOrNull() ?: 0); bytes += resto.drop(1) }
                        2 -> bytes += resto
                    }
                }
                linea.length % 2 == 0 && linea.all { it in HEX } -> bytes += aBytes(linea)
            }
        }
        if (bytes.isEmpty()) return null
        return largo?.let { bytes.take(it) } ?: bytes
    }

    /** VIN de la respuesta a 0902 (49 02 01 + 17 caracteres), o null si no llegó entero. */
    fun vin(respuesta: String): String? {
        val bytes = unirMultitrama(respuesta) ?: return null
        val inicio = bytes.windowed(2).indexOfFirst { it == listOf(0x49, 0x02) }
        if (inicio < 0) return null
        val texto = bytes.drop(inicio + 3).take(17).map { it.toChar() }.joinToString("")
        return texto.takeIf { it.length == 17 && it.all(Char::isLetterOrDigit) }
    }

    private fun aBytes(hex: String): List<Int> = hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }

    /**
     * Reply to a multi-PID request ("010C0D11" → 41 0C A B 0D A 11 A): one value per signal, or null if any is
     * missing, so the caller falls back to one PID per request.
     */
    fun variosPids(respuesta: String, senales: List<Senal>): Map<String, Double>? {
        val bytes = unirMultitrama(respuesta) ?: return null
        var i = bytes.indexOf(0x41) + 1
        if (i == 0) return null
        val porPid = senales.associateBy { it.pid?.toInt(16) }
        val valores = mutableMapOf<String, Double>()
        while (i < bytes.size) {
            val s = porPid[bytes[i]] ?: return null
            val datos = bytes.drop(i + 1).take(s.bytes)
            if (datos.size < s.bytes) return null
            decodificar("41" + s.pid + datos.joinToString("") { hex2(it) }, s)?.let { valores[s.id] = it }
            i += 1 + s.bytes
        }
        return valores.takeIf { it.size == senales.size }
    }

    fun decodificar(respuesta: String, senal: Senal): Double? {
        val bytes = bytesDeDatos(respuesta, senal) ?: return null
        senal.mascaraSoporte?.let { if (bytes.first() and it == 0) return null }
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

/** Evaluador mínimo de fórmulas aritméticas con variables: + - * / y paréntesis. `{A:7}` es el bit 7 de A, como en Torque. */
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
            if (i < s.length && s[i] == '{') {
                val cierre = s.indexOf('}', i)
                require(cierre > i) { "Falta '}' en $s" }
                val (nombre, bit) = s.substring(i + 1, cierre).split(':')
                i = cierre + 1
                val valor = vars[nombre] ?: error("Variable desconocida: $nombre")
                return ((valor.toInt() shr bit.toInt()) and 1).toDouble()
            }
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
