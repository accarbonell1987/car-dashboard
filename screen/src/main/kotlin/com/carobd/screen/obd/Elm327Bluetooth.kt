package com.carobd.screen.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.carobd.core.obd.TransporteFlujos
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Transporte ELM327 por Bluetooth clásico (SPP/RFCOMM), p. ej. el Vgate vLinker BM.
 * El protocolo es el mismo que por TCP: `FuenteElm327(nombre, TransporteRfcomm(dispositivo))`.
 */
@SuppressLint("MissingPermission")
class TransporteRfcomm(private val dispositivo: BluetoothDevice) : TransporteFlujos() {
    val nombre: String = dispositivo.name ?: dispositivo.address
    private var socket: BluetoothSocket? = null

    override fun conectar(): Pair<InputStream, OutputStream> {
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val s = dispositivo.createRfcommSocketToServiceRecord(SPP)
        s.connect()
        socket = s
        return s.inputStream to s.outputStream
    }

    override fun desconectar() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
