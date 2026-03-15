package dev.hapax.watch.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.hapax.watch.R
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.data.SensorReading
import dev.hapax.watch.network.HapaxTransport
import java.time.Instant
import kotlin.random.Random

class SensorService : Service() {

    private lateinit var buffer: SensorBuffer
    private lateinit var transport: HapaxTransport
    private lateinit var handler: Handler

    private val sensorRunnable = object : Runnable {
        override fun run() {
            generateFakeReading()
            handler.postDelayed(this, SENSOR_INTERVAL_MS)
        }
    }

    private val flushRunnable = object : Runnable {
        override fun run() {
            Thread {
                transport.flush(buffer)
            }.start()
            handler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        buffer = SensorBuffer()
        // Default URL; in Sprint 3 this will come from DataStore/mDNS
        transport = HapaxTransport("http://10.0.2.2:8051")
        handler = Handler(Looper.getMainLooper())
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

        handler.post(sensorRunnable)
        handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)

        Log.i(TAG, "SensorService started")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(sensorRunnable)
        handler.removeCallbacks(flushRunnable)
        Log.i(TAG, "SensorService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun generateFakeReading() {
        val reading = SensorReading(
            type = "heart_rate",
            ts = Instant.now().toString(),
            bpm = Random.nextDouble(60.0, 80.0),
            confidence = "high",
        )
        buffer.add(reading)
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
        private const val SENSOR_INTERVAL_MS = 1_000L
        private const val FLUSH_INTERVAL_MS = 30_000L
    }
}
