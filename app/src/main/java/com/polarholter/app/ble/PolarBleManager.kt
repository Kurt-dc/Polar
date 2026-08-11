package com.polarholter.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * Тонкая обёртка над Android BLE API для подключения к Polar H10 и работы
 * с сервисом PMD (потоковая ЭКГ) + стандартным Heart Rate Service.
 *
 * UUID перенесены из веб-версии приложения (подтверждены Polar BLE SDK
 * и независимыми reverse-engineering проектами):
 *   HR service:        0000180d-0000-1000-8000-00805f9b34fb
 *   PMD service:       fb005c80-02e7-f387-1cad-8acd2d8df0c8
 *   PMD control point: fb005c81-02e7-f387-1cad-8acd2d8df0c8
 *   PMD data:          fb005c82-02e7-f387-1cad-8acd2d8df0c8
 */
object PolarUuids {
    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    val PMD_SERVICE: UUID = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8")
    val PMD_CONTROL: UUID = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8")
    val PMD_DATA: UUID = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8")

    val CLIENT_CHAR_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Команда запуска потока ЭКГ (130 Гц, диапазон 0.4 мВ), формат подтверждён
    // тем же байтовым протоколом, что использовался в веб-версии.
    val ECG_START_CMD = byteArrayOf(
        0x02, 0x00, 0x00, 0x01, 0x82.toByte(), 0x00, 0x01, 0x01, 0x0E, 0x00
    )
    val ECG_STOP_CMD = byteArrayOf(0x03, 0x00)
}

sealed class BleEvent {
    data class Connected(val deviceName: String?) : BleEvent()
    data object Disconnected : BleEvent()
    data class HeartRate(val bpm: Int) : BleEvent()
    data class EcgSample(val microVolts: Int) : BleEvent()
    data class Error(val message: String) : BleEvent()
}

@SuppressLint("MissingPermission")
class PolarBleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gatt: BluetoothGatt? = null
    private var pmdControlChar: BluetoothGattCharacteristic? = null

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    /** device: результат сканирования, отфильтрованный по имени "Polar H10 ..." */
    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        try {
            val g = gatt
            val c = pmdControlChar
            if (g != null && c != null) {
                writeCharacteristicCompat(g, c, PolarUuids.ECG_STOP_CMD, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
        } catch (_: Exception) {}
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _events.tryEmit(BleEvent.Connected(g.device.name))
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _events.tryEmit(BleEvent.Disconnected)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit(BleEvent.Error("Ошибка обнаружения сервисов: $status"))
                return
            }
            // Подписка на стандартный Heart Rate (для отображения ЧСС когда ECG-поток выключен)
            g.getService(PolarUuids.HR_SERVICE)
                ?.getCharacteristic(PolarUuids.HR_MEASUREMENT)
                ?.let { enableNotify(g, it) }

            // Подписка на PMD data + получение control point для запуска ECG-стрима
            val pmd = g.getService(PolarUuids.PMD_SERVICE)
            pmdControlChar = pmd?.getCharacteristic(PolarUuids.PMD_CONTROL)
            pmd?.getCharacteristic(PolarUuids.PMD_DATA)?.let { enableNotify(g, it) }
        }

        // API 33+: новая сигнатура с явным value (не зависит от мутируемого characteristic.value).
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            routeCharacteristicValue(characteristic.uuid, value)
        }

        // До API 33 система вызывает именно этот, двухаргументный колбэк.
        // Без него на Android 8–12 ЭКГ-поток и ЧСС молча не доходили бы до анализатора.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                routeCharacteristicValue(characteristic.uuid, value)
            }
        }
    }

    private fun routeCharacteristicValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            PolarUuids.HR_MEASUREMENT -> parseHeartRate(value)
            PolarUuids.PMD_DATA -> parsePmdEcgFrame(value)
        }
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        c.getDescriptor(PolarUuids.CLIENT_CHAR_CONFIG)?.let { d ->
            writeDescriptorCompat(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    fun startEcgStream() {
        val g = gatt
        val c = pmdControlChar
        if (g != null && c != null) {
            writeCharacteristicCompat(g, c, PolarUuids.ECG_START_CMD, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            _events.tryEmit(BleEvent.Error("PMD control point недоступен — переподключитесь к датчику"))
        }
    }

    fun stopEcgStream() {
        val g = gatt
        val c = pmdControlChar
        if (g != null && c != null) {
            writeCharacteristicCompat(g, c, PolarUuids.ECG_STOP_CMD, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    /**
     * writeCharacteristic(characteristic, value, writeType) — новая сигнатура с API 33.
     * На более старых устройствах (входят в minSdk 26) этого метода не существует,
     * нужен старый путь через characteristic.value + writeCharacteristic(characteristic).
     */
    private fun writeCharacteristicCompat(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, writeType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, writeType)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = writeType
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun parseHeartRate(value: ByteArray) {
        if (value.isEmpty()) return
        val flags = value[0].toInt()
        val is16bit = (flags and 0x01) != 0
        val bpm = if (is16bit) {
            ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        } else {
            value[1].toInt() and 0xFF
        }
        _events.tryEmit(BleEvent.HeartRate(bpm))
    }

    /**
     * Разбор PMD data-кадра для ECG.
     * Формат кадра Polar PMD: [measType(1)][timestamp(8)][frameType(1)][samples...]
     * measType == 0x00 -> ECG; frameType == 0x00 -> "сырые" 24-битные little-endian отсчёты, мкВ.
     */
    private fun parsePmdEcgFrame(data: ByteArray) {
        if (data.isEmpty() || data[0].toInt() != 0x00) return // интересует только тип ECG
        if (data.size < 10) return
        val frameType = data[9].toInt()
        if (frameType != 0x00) return
        var i = 10
        while (i + 3 <= data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            var raw = (b2 shl 16) or (b1 shl 8) or b0
            // sign-extend 24-bit -> Int
            if (raw and 0x800000 != 0) raw = raw or -0x1000000
            _events.tryEmit(BleEvent.EcgSample(raw))
            i += 3
        }
    }
}
