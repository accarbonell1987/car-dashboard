package com.carobd.screen.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.carobd.core.obd.BufferRespuestas
import com.carobd.core.obd.Respuesta
import com.carobd.core.obd.TransporteElm
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * ELM327 por Bluetooth Low Energy, como el clon MOCATEC "OBDII": servicio FFF0, característica FFF1
 * (escritura + notify). FFF2 es solo escritura y no se usa. Probado solo con lógica de JVM y compilación:
 * todavía no contra el adaptador real.
 */
@SuppressLint("MissingPermission")
class TransporteBle(private val context: Context, private val direccion: String) : TransporteElm {
    private sealed interface Evento {
        data class Conexion(val status: Int, val conectado: Boolean) : Evento
        data class Mtu(val mtu: Int, val status: Int) : Evento
        data class Servicios(val status: Int) : Evento
        data class Descriptor(val status: Int) : Evento
        data class Escrito(val status: Int) : Evento
    }

    private var gatt: BluetoothGatt? = null
    private var caracteristica: BluetoothGattCharacteristic? = null
    private var buffer = BufferRespuestas()
    private var eventos = Channel<Evento>(Channel.UNLIMITED)
    private var tamanoFragmento = 20

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val conectado = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            if (!conectado) buffer.fallar(IOException("BLE desconectado (estado $newState, status $status)"))
            eventos.trySend(Evento.Conexion(status, conectado))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            eventos.trySend(Evento.Mtu(mtu, status))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            eventos.trySend(Evento.Servicios(status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            eventos.trySend(Evento.Descriptor(status))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            eventos.trySend(Evento.Escrito(status))
        }

        // Android 13+: el valor llega como parámetro.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == FFF1) buffer.recibir(value)
        }

        // Android 12 o anterior: el valor queda en la característica.
        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == FFF1) {
                characteristic.value?.let(buffer::recibir)
            }
        }
    }

    override suspend fun abrir() {
        buffer = BufferRespuestas()
        eventos = Channel(Channel.UNLIMITED)
        tamanoFragmento = 20
        val adaptador = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("el equipo no tiene Bluetooth")
        if (!adaptador.isEnabled) throw IOException("el Bluetooth está apagado")
        val g = adaptador.getRemoteDevice(direccion).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("no se pudo iniciar la conexión BLE")
        gatt = g
        val conexion = esperar<Evento.Conexion>(10_000) ?: throw IOException("el adaptador BLE no respondió")
        if (!conexion.conectado) throw IOException("no conectó por BLE (status ${conexion.status})")
        // MTU más grande = menos fragmentos; si el clon no lo acepta seguimos con 20 bytes.
        if (g.requestMtu(MTU_PEDIDO)) {
            esperar<Evento.Mtu>(3_000)?.takeIf { it.status == BluetoothGatt.GATT_SUCCESS }?.let { tamanoFragmento = (it.mtu - 3).coerceAtLeast(20) }
        }
        if (!g.discoverServices()) throw IOException("no se pudieron descubrir los servicios")
        val servicios = esperar<Evento.Servicios>(10_000)
        if (servicios?.status != BluetoothGatt.GATT_SUCCESS) throw IOException("falló el descubrimiento de servicios")
        val c = g.getService(FFF0)?.getCharacteristic(FFF1) ?: throw IOException("el adaptador no tiene FFF0/FFF1: ¿es un ELM327 BLE?")
        caracteristica = c
        // Shortest connection interval (7.5-15 ms instead of ~50 ms): each request is a write plus notifications,
        // so this is paid on every PID.
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!g.setCharacteristicNotification(c, true)) throw IOException("no se pudieron activar las notificaciones")
        val cccd = c.getDescriptor(CCCD) ?: throw IOException("FFF1 no tiene descriptor CCCD")
        val pedido = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) && g.writeDescriptor(cccd)
        }
        if (!pedido || esperar<Evento.Descriptor>(5_000)?.status != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("no se pudo escribir el descriptor de notificaciones")
        }
    }

    override suspend fun escribir(texto: String) {
        val g = gatt ?: throw IOException("transporte cerrado")
        val c = caracteristica ?: throw IOException("transporte cerrado")
        // Without response when the adapter allows it: no extra round trip waiting for the write acknowledgement.
        val tipo = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        for (trozo in BufferRespuestas.fragmentar(texto.toByteArray(Charsets.ISO_8859_1), tamanoFragmento)) {
            val pedido = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, trozo, tipo) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = tipo; c.value = trozo; g.writeCharacteristic(c) }
            }
            if (!pedido) throw IOException("no se pudo escribir en FFF1")
            // Una escritura por vez: Android descarta la siguiente si la anterior no terminó.
            val escrito = esperar<Evento.Escrito>(2_000)
            if (escrito?.status != BluetoothGatt.GATT_SUCCESS && tipo == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                throw IOException("la escritura BLE no se confirmó")
            }
        }
    }

    override suspend fun leer(timeoutMs: Long): Respuesta = buffer.leer(timeoutMs)

    override fun descartar() = buffer.descartar()

    override fun cerrar() {
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        caracteristica = null
        buffer.fallar(IOException("transporte cerrado"))
    }

    /** Espera el próximo evento de tipo [T]; una desconexión corta la espera con error. */
    private suspend inline fun <reified T : Evento> esperar(timeoutMs: Long): T? = withTimeoutOrNull(timeoutMs) {
        var encontrado: T? = null
        while (encontrado == null) {
            val e = eventos.receive()
            if (e is T) encontrado = e
            else if (e is Evento.Conexion && !e.conectado) throw IOException("BLE desconectado (status ${e.status})")
        }
        encontrado
    }

    companion object {
        val FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val FFF1: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NOMBRE_MOCATEC = "OBDII"
        private const val MTU_PEDIDO = 185
    }
}

/** Un adaptador encontrado al escanear. */
data class DispositivoBle(val direccion: String, val nombre: String, val rssi: Int)

/**
 * Busca adaptadores ELM327 BLE: los que anuncian el servicio FFF0 o se llaman "OBDII".
 * Emite cada dispositivo la primera vez que aparece y cada vez que cambia su señal; se detiene al cancelar.
 */
@SuppressLint("MissingPermission")
fun escanearBle(context: Context): Flow<DispositivoBle> = callbackFlow {
    val escaner = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    if (escaner == null) {
        close(IOException("el Bluetooth está apagado"))
        return@callbackFlow
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            trySend(DispositivoBle(d.address, d.name ?: result.scanRecord?.deviceName ?: d.address, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            close(IOException("el escaneo BLE falló (código $errorCode)"))
        }
    }
    // Filtros en OR: por servicio o por nombre (algunos clones no anuncian el servicio).
    val filtros = listOf(
        ScanFilter.Builder().setServiceUuid(ParcelUuid(TransporteBle.FFF0)).build(),
        ScanFilter.Builder().setDeviceName(TransporteBle.NOMBRE_MOCATEC).build(),
    )
    escaner.startScan(filtros, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    awaitClose { runCatching { escaner.stopScan(callback) } }
}
