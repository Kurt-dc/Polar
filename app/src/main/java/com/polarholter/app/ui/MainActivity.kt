package com.polarholter.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.polarholter.app.service.RecordingService

/**
 * ВНИМАНИЕ: это MVP-каркас, а не готовое к публикации приложение.
 * Перед реальным использованием на людях нужно как минимум:
 *  - экран информированного согласия / дисклеймер о том, что это НЕ диагностика,
 *  - реальный экспорт в EDF+ (см. export/ — сейчас только CSV как основа),
 *  - обработку edge-cases пересоединения BLE,
 *  - тестирование на конкретных версиях Android (агрессивная оптимизация
 *    батареи у Xiaomi/Huawei/Samsung по-разному ведёт себя с foreground service).
 */
@SuppressLint("MissingPermission") // разрешения запрашиваются в requestNeededPermissions() до любых BLE-вызовов
class MainActivity : ComponentActivity() {

    private var discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var isScanning = mutableStateOf(false)
    private var permissionsGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> permissionsGranted.value = results.values.all { it } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        devices = discoveredDevices,
                        isScanning = isScanning.value,
                        onScan = { startScan() },
                        onConnect = { device -> startRecordingService(device) },
                        onStop = { stopRecordingService() },
                        onRequestBatteryExemption = { requestIgnoreBatteryOptimizations() }
                    )
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsGranted.value = true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScan() {
        if (!permissionsGranted.value) { requestNeededPermissions(); return }
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = btManager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        discoveredDevices.clear()
        isScanning.value = true
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.contains("Polar", ignoreCase = true) && discoveredDevices.none { it.address == result.device.address }) {
                    discoveredDevices.add(result.device)
                }
            }
        })
        // Останавливаем сканирование через 8с — датчик BLE рекламирует себя часто, этого достаточно
        window.decorView.postDelayed({ isScanning.value = false; scanner.stopScan(object : ScanCallback() {}) }, 8000)
    }

    private fun startRecordingService(device: BluetoothDevice) {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_DEVICE, device)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

@Composable
fun MainScreen(
    devices: List<BluetoothDevice>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onStop: () -> Unit,
    onRequestBatteryExemption: () -> Unit
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text("Polar Holter", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Скрининговые пометки на ленте — не диагноз. Тип блокады и ЭОС " +
            "по одному отведению не определяются, для точной оценки нужна 12-канальная ЭКГ у врача.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        Row {
            Button(onClick = onScan) { Text(if (isScanning) "Сканирование…" else "Найти датчик") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRequestBatteryExemption) { Text("Разрешить фон") }
        }

        Spacer(Modifier.height(16.dp))
        Text("Найденные устройства:", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                ListItem(
                    headlineContent = { Text(device.name ?: device.address) },
                    supportingContent = { Text(device.address) },
                    trailingContent = { Button(onClick = { onConnect(device) }) { Text("Записывать") } }
                )
                Divider()
            }
        }

        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Остановить запись")
        }
    }
}
