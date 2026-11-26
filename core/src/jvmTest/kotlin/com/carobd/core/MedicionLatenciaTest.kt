package com.carobd.core

import com.carobd.core.obd.Catalogo
import com.carobd.core.obd.Elm327
import com.carobd.core.obd.MotorExtendido
import com.carobd.core.obd.TransporteTcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Measures how fast the needles and the pedals follow the car, against obd/emulador with the BLE clone's timings
 * measured in the car (`--tiempos-ble`). Off by default (it takes minutes):
 *
 *     MEDIR_LATENCIA=60 ./gradlew :core:jvmTest --tests '*MedicionLatencia*'
 *
 * The value is the seconds per run; the report goes to core/build/medicion-latencia.txt.
 * Each step changes rpm and the brake in the emulator at a random moment and times until the app's readings show it.
 */
class MedicionLatenciaTest {
    @Test
    fun latenciaConYSinTestigos() = runBlocking<Unit> {
        val segundos = System.getenv("MEDIR_LATENCIA")?.toIntOrNull()
        assumeTrue(segundos != null)
        val informe = buildString {
            for (testigos in listOf(false, true)) appendLine(medir(segundos!!, testigos))
        }
        File("build/medicion-latencia.txt").writeText(informe)
        println(informe)
    }

    private suspend fun medir(segundos: Int, testigos: Boolean): String {
        val script = File("../obd/emulador/elm327_emulador.py")
        val proceso = ProcessBuilder("python3", script.path, "--puerto", "0", "--clon-ble", "--tiempos-ble", "--escenario", "carroceria")
            .redirectErrorStream(true).start()
        try {
            val salida = proceso.inputStream.bufferedReader()
            val puerto = Regex(":(\\d+)$").find(salida.readLine().trim())!!.groupValues[1].toInt()
            Thread { salida.forEachLine { } }.apply { isDaemon = true }.start()
            val consola = proceso.outputStream.bufferedWriter()
            fun orden(texto: String) = consola.run { write("$texto\n"); flush() }

            val elm = Elm327(TransporteTcp("127.0.0.1", puerto), excluir = Catalogo.noSoportadasAccent)
            elm.testigos = testigos
            val llegadas = mutableListOf<Long>() // when each reading reached the collector
            val ultimaRef = AtomicReference<Map<String, Double>>(emptyMap())
            fun ultima() = ultimaRef.get()
            val lector = CoroutineScope(Dispatchers.IO).launch {
                elm.lecturas().collect {
                    synchronized(llegadas) { llegadas += System.nanoTime() }
                    ultimaRef.set(it.valores)
                }
            }
            try {
                while ("rpm" !in ultima() || MotorExtendido.FRENO !in ultima()) delay(20)
                delay(3000) // past the start-up turns
                synchronized(llegadas) { llegadas.clear() }
                val rpm = mutableListOf<Double>()
                val freno = mutableListOf<Double>()
                val fin = System.nanoTime() + segundos * 1_000_000_000L
                var paso = 0
                val azar = Random(7)
                while (System.nanoTime() < fin) {
                    delay(azar.nextLong(150, 600))
                    val objetivo = 1000.0 + 100 * (paso % 25)
                    val pisado = if (paso % 2 == 0) 1.0 else 0.0
                    paso++
                    orden("rpm ${objetivo.toInt()}")
                    orden("freno ${if (pisado == 1.0) "on" else "off"}")
                    val inicio = System.nanoTime()
                    var tRpm: Double? = null
                    var tFreno: Double? = null
                    while ((tRpm == null || tFreno == null) && System.nanoTime() - inicio < 5_000_000_000L) {
                        val ms = (System.nanoTime() - inicio) / 1e6
                        if (tRpm == null && ultima()["rpm"] == objetivo) tRpm = ms
                        if (tFreno == null && ultima()[MotorExtendido.FRENO] == pisado) tFreno = ms
                        delay(2)
                    }
                    rpm += tRpm ?: 5000.0
                    freno += tFreno ?: 5000.0
                }
                val intervalos = synchronized(llegadas) { llegadas.zipWithNext { a, b -> (b - a) / 1e6 } }
                return "Testigos de carrocería ${if (testigos) "PRENDIDOS" else "apagados"} ($segundos s, $paso cambios)\n" +
                    "  rpm/vel/acelerador cada: ${resumen(intervalos)}  (${"%.1f".format(1000 / intervalos.average())} por segundo)\n" +
                    "  rpm reacciona en:        ${resumen(rpm)}\n" +
                    "  freno reacciona en:      ${resumen(freno)}\n" +
                    "  testigos activos al final: ${elm.estado.value.carroceria}"
            } finally {
                lector.cancel()
            }
        } finally {
            proceso.destroy()
            proceso.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun resumen(ms: List<Double>): String {
        val s = ms.sorted()
        fun p(q: Double) = s[((s.size - 1) * q).toInt()].toInt()
        return "media ${ms.average().toInt()} ms · p50 ${p(0.5)} · p95 ${p(0.95)} · máx ${s.last().toInt()} ms"
    }
}
