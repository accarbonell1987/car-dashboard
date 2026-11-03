package com.carobd.screen.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.carobd.core.obd.Catalogo
import com.carobd.core.obd.Decodificador
import com.carobd.core.obd.Marchas
import com.carobd.core.obd.Ritmo
import com.carobd.core.sim.Lectura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Adaptador ELM327 por Bluetooth clásico (SPP), p. ej. el Vgate vLinker BM.
 * Pide las señales rápidas en cada ciclo, las medias cada 10 y las lentas cada 50.
 * Se prueba en el auto cuando llegue el vLinker (fase 4); hasta entonces se usa FuenteDemo.
 */
@SuppressLint("MissingPermission")
class Elm327Bluetooth(private val dispositivo: BluetoothDevice) : FuenteDatos {
    override val nombre: String = dispositivo.name ?: dispositivo.address

    private val marchas = Marchas()

    override fun lecturas(): Flow<Lectura> = flow {
        val socket = conectar()
        socket.use { s ->
            val entrada = s.inputStream
            val salida = s.outputStream
            for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) comando(cmd, entrada, salida)

            val rapidas = Catalogo.estandar.filter { it.ritmo == Ritmo.RAPIDO && it.pid != null }
            val medias = Catalogo.estandar.filter { it.ritmo == Ritmo.MEDIO && it.pid != null }
            val lentas = Catalogo.estandar.filter { it.ritmo == Ritmo.LENTO && it.pid != null }
            val valores = mutableMapOf<String, Double>()
            val inicio = System.currentTimeMillis()
            var ciclo = 0L
            while (true) {
                val grupo = rapidas + (if (ciclo % 10 == 0L) medias else emptyList()) + (if (ciclo % 50 == 0L) lentas else emptyList())
                for (senal in grupo) {
                    val resp = comando(senal.modo + senal.pid, entrada, salida)
                    Decodificador.decodificar(resp, senal)?.let { valores[senal.id] = it }
                }
                derivadas(valores)
                emit(Lectura(System.currentTimeMillis() - inicio, valores.toMap()))
                ciclo++
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun derivadas(v: MutableMap<String, Double>) {
        val map = v["map"]; val baro = v["barometrica"] ?: 101.0
        if (map != null) v["boost"] = (map - baro) / 100.0
        val rpm = v["rpm"]; val kmh = v["velocidad"]
        if (rpm != null && kmh != null) v["marcha"] = marchas.marcha(rpm, kmh).toDouble()
        val lh = v["consumo_lh"]
        if (lh != null && kmh != null && kmh > 5) v["consumo_l100"] = lh / kmh * 100
    }

    private fun conectar(): BluetoothSocket {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val socket = dispositivo.createRfcommSocketToServiceRecord(SPP)
        socket.connect()
        return socket
    }

    /** Envía un comando y lee hasta el prompt '>' del ELM327. */
    private fun comando(cmd: String, entrada: InputStream, salida: OutputStream): String {
        salida.write("$cmd\r".toByteArray())
        salida.flush()
        val sb = StringBuilder()
        while (true) {
            val b = entrada.read()
            if (b < 0 || b == '>'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
