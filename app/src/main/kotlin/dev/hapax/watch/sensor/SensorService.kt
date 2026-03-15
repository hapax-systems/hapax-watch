package dev.hapax.watch.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.health.services.client.HealthServices
import dev.hapax.watch.R
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.network.ConnectivityHelper
import dev.hapax.watch.network.HapaxTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SensorService : Service() {

    private lateinit var buffer: SensorBuffer
    private lateinit var transport: HapaxTransport
    private lateinit var handler: Handler
    private lateinit var connectivityHelper: ConnectivityHelper

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val collectors = mutableListOf<SensorCollector>()

    private val flushRunnable = object : Runnable {
        override fun run() {
            if (connectivityHelper.isNetworkAvailable) {
                Thread {
                    transport.flush(buffer)
                }.start()
            } else {
                Log.d(TAG, "Skipping flush — no network")
            }
            handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        buffer = SensorBuffer()
        transport = HapaxTransport(this)
        handler = Handler(Looper.getMainLooper())
        connectivityHelper = ConnectivityHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Hapax Watch Active")
            .setContentText("Streaming sensor data")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Log device info
        logDeviceInfo()

        // Request WiFi to stay active; invalidate mDNS cache on network change
        connectivityHelper.requestWifi { available ->
            Log.i(TAG, "Network available: $available")
            if (!available) {
                transport.invalidateCache()
            }
        }

        // Discover and start available sensor collectors
        serviceScope.launch {
            startCollectors()
        }

        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)

        Log.i(TAG, "SensorService started")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(flushRunnable)
        for (collector in collectors) {
            try {
                collector.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping ${collector.type}: ${e.message}")
            }
        }
        collectors.clear()
        connectivityHelper.release()
        serviceScope.cancel()
        Log.i(TAG, "SensorService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startCollectors() {
        val healthClient = HealthServices.getClient(this)
        val measureClient = healthClient.measureClient
        val passiveClient = healthClient.passiveMonitoringClient

        val candidates: List<SensorCollector> = listOf(
            HeartRateCollector(measureClient),
            HrvCollector(measureClient),
            SkinTempCollector(passiveClient),
            ActivityCollector(passiveClient),
        )

        for (collector in candidates) {
            try {
                val available = collector.isAvailable(healthClient)
                if (available) {
                    collector.start(buffer)
                    collectors.add(collector)
                    Log.i(TAG, "Started ${collector.type}")
                } else {
                    Log.i(TAG, "${collector.type} — not available on this device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ${collector.type}: ${e.message}", e)
            }
        }

        Log.i(TAG, "Active collectors: ${collectors.map { it.type }}")
    }

    private fun logDeviceInfo() {
        val batteryPct = getBatteryPercentage()
        val btMac = getBluetoothMac()
        Log.i(TAG, "Battery: $batteryPct%, BT MAC: $btMac")
    }

    fun getBatteryPercentage(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun getBluetoothMac(): String {
        return try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            // On API 34+ the address is randomized; this may return "02:00:00:00:00:00"
            bluetoothManager?.adapter?.address ?: "unknown"
        } catch (e: SecurityException) {
            Log.w(TAG, "No BLUETOOTH_CONNECT permission: ${e.message}")
            "no_permission"
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hapax Watch Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SensorService"
        private const val CHANNEL_ID = "hapax_watch_service"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_INTERVAL_MS = 30_000L
    }
}
