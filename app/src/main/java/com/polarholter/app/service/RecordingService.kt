package com.polarholter.app.service

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.polarholter.app.analysis.EcgAnalyzer
import com.polarholter.app.analysis.EcgFlag
import com.polarholter.app.ble.BleEvent
import com.polarholter.app.ble.PolarBleManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground Service — единственный способ на Android держать активным
 * BLE-соединение и запись ЭКГ при выключенном экране / свёрнутом приложении.
 * Без него система убьёт процесс через несколько минут в фоне (Doze/App Standby).
 *
 * Ограничения, которые стоит показывать пользователю в UI, а не скрывать:
 *  - Аппаратный ресурс Polar H10 в режиме потоковой ECG заметно меньше,
 *    чем в режиме обычного пульсометра — датчик не расcчитан на сутки записи.
 *  - Производители Android (особенно не-Pixel/Samsung с агрессивной
 *    оптимизацией батареи — Xiaomi, Huawei и т.п.) могут всё равно убивать
 *    сервис. Нужно просить пользователя добавить приложение в исключения
 *    оптимизации батареи (см. MainActivity).
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "ecg_recording_channel"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.polarholter.app.action.START"
        const val ACTION_STOP = "com.polarholter.app.action.STOP"
        const val EXTRA_DEVICE = "extra_device"
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bleManager: PolarBleManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val analyzer = EcgAnalyzer(sampleRateHz = 130)

    private var recordFile: File? = null
    private var recordStream: FileOutputStream? = null
    private var recordStartMs: Long = 0L

    private val _bpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = _bpm
    private val _flagCount = MutableStateFlow(0)
    val flagCount: StateFlow<Int> = _flagCount
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(EXTRA_DEVICE) ?: return START_NOT_STICKY
                startForegroundCompat(buildNotification("Подключение к датчику…"))
                acquireWakeLock()
                startRecording(device)
            }
            ACTION_STOP -> {
                stopRecordingAndSelf()
            }
        }
        // START_STICKY: система попытается перезапустить сервис, если убьёт его,
        // но переподключение к устройству придётся инициировать заново из UI.
        return START_STICKY
    }

    /**
     * startForeground(id, notification, type) с типом сервиса появился в API 29.
     * На Android 8.0/8.1 (API 26/27, входят в наш minSdk) этого метода не
     * существует — вызов уронит сервис. Разветвляем по версии.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PolarHolter::RecordingWakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L /* защитный лимит 6 часов, чтобы не держать вечно при сбое */)
        }
    }

    private fun startRecording(device: BluetoothDevice) {
        analyzer.reset()
        recordStartMs = System.currentTimeMillis()
        val dir = File(filesDir, "sessions").apply { mkdirs() }
        recordFile = File(dir, "session_${recordStartMs}.csv")
        recordStream = FileOutputStream(recordFile).apply {
            write("sample_idx,time_sec,microvolts\n".toByteArray())
        }

        bleManager = PolarBleManager(applicationContext).also { mgr ->
            serviceScope.launch {
                mgr.events.collect { event -> handleEvent(event) }
            }
            mgr.connect(device)
        }
    }

    private var sampleCounter = 0L
    private fun handleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.Connected -> {
                _connected.value = true
                bleManager?.startEcgStream()
                updateNotification("Запись идёт · ${event.deviceName ?: "Polar H10"}")
            }
            is BleEvent.Disconnected -> {
                _connected.value = false
                updateNotification("Соединение потеряно — попытка переподключения…")
                // Простая политика переподключения: UI/MainActivity инициирует повторный connect,
                // сервис лишь отражает статус в уведомлении.
            }
            is BleEvent.HeartRate -> _bpm.value = event.bpm
            is BleEvent.EcgSample -> {
                val t = sampleCounter / 130.0
                recordStream?.write("$sampleCounter,${"%.4f".format(t)},${event.microVolts}\n".toByteArray())
                sampleCounter++
                val flag: EcgFlag? = analyzer.pushSample(event.microVolts)
                if (flag != null) {
                    _flagCount.value = analyzer.flags.size
                    updateNotification("Запись идёт · отмечено участков: ${analyzer.flags.size}")
                }
            }
            is BleEvent.Error -> updateNotification("Ошибка: ${event.message}")
        }
    }

    private fun stopRecordingAndSelf() {
        bleManager?.stopEcgStream()
        bleManager?.disconnect()
        recordStream?.flush()
        recordStream?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Запись ЭКГ", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Уведомление о фоновой записи ЭКГ с Polar H10" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Polar Holter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
