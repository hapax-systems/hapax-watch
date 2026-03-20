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
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.health.services.client.HealthServices
import dev.hapax.watch.R
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.gesture.GestureDetector
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
    private var gestureDetector: GestureDetector? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val collectors = mutableListOf<SensorCollector>()

    private var previousStatus: HapaxTransport.Status? = null

    private val flushRunnable = object : Runnable {
        override fun run() {
            if (connectivityHelper.isNetworkAvailable) {
                Thread {
                    transport.flush(buffer)
                    publishStatus()
                    updateNotification()
                    checkStatusChange()
                }.start()
            } else {
                Log.d(TAG, "Skipping flush — no network")
                publishStatus()
                updateNotification()
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
        val notification = buildNotification("Starting up...")

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
            publishStatus()

            // Start gesture detector once transport has a resolved URL
            val url = transport.resolvedUrl
            if (url != null) {
                gestureDetector = GestureDetector(this@SensorService, url).also { it.start() }
                Log.i(TAG, "GestureDetector started (url=$url)")
            } else {
                Log.w(TAG, "No server URL — gesture detector deferred")
            }
        }

        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)

        Log.i(TAG, "SensorService started")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(flushRunnable)
        gestureDetector?.stop()
        gestureDetector = null
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
            HeartRateCollector(measureClient, this@SensorService),
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

    /** Write current service status to SharedPreferences for SettingsActivity to read. */
    private fun publishStatus() {
        val prefs = getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        val lastHr = getLastHeartRate()
        prefs.edit()
            .putString("connection_status", transport.status.name.lowercase())
            .putString("server_url", transport.resolvedUrl ?: "")
            .putLong("last_flush_time", System.currentTimeMillis())
            .putInt("buffer_size", buffer.size)
            .putString("active_sensors", collectors.joinToString(",") { it.type })
            .putFloat("last_hr", lastHr)
            .apply()
    }

    /** Extract latest heart rate from the buffer (peek, don't drain). */
    private fun getLastHeartRate(): Float {
        // We read from shared prefs as a running value; updated from HeartRateCollector via buffer
        // For now, return 0 if no HR readings exist
        val prefs = getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat("last_hr", 0f)
    }

    /** Update the foreground notification with current status. */
    private fun updateNotification() {
        val text = when (transport.status) {
            HapaxTransport.Status.CONNECTED -> {
                val prefs = getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                val hr = prefs.getFloat("last_hr", 0f)
                if (hr > 0) "\u2665 ${hr.toInt()} bpm \u00b7 Connected"
                else "Connected"
            }
            HapaxTransport.Status.BUFFERING -> {
                "Buffering (${buffer.size} readings)"
            }
            HapaxTransport.Status.DISCONNECTED -> {
                "Disconnected"
            }
        }
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** Check if status changed and vibrate accordingly. */
    private fun checkStatusChange() {
        val currentStatus = transport.status
        val prev = previousStatus
        if (prev != null && prev != currentStatus) {
            when (currentStatus) {
                HapaxTransport.Status.CONNECTED -> vibrateConnected()
                HapaxTransport.Status.DISCONNECTED -> vibrateDisconnected()
                HapaxTransport.Status.BUFFERING -> {} // no haptic for buffering
            }
        }
        previousStatus = currentStatus
    }

    private fun getVibrator(): android.os.Vibrator? {
        val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        return manager?.defaultVibrator
    }

    private fun vibrateConnected() {
        val vibrator = getVibrator() ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateDisconnected() {
        val vibrator = getVibrator() ?: return
        // Two short taps: 50ms on, 100ms gap, 50ms on
        val timings = longArrayOf(0, 50, 100, 50)
        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Hapax Watch Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
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
        const val STATUS_PREFS = "hapax_service_status"
    }
}
