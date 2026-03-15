package dev.hapax.watch.notification

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for a wrist-raise gesture after a presence-check vibration
 * and POSTs a voice trigger to the workstation.
 */
class VoiceTriggerResponder(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val listening = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true }

    private var serverUrl: String? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (listening.compareAndSet(true, false)) {
                Log.i(TAG, "Wrist raise detected — sending voice trigger")
                unregister()
                sendVoiceTrigger()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    /**
     * Start listening for a wrist-raise gesture.
     * Will auto-unregister after [timeoutMs] milliseconds.
     *
     * @param url base URL of the workstation (e.g. "http://10.0.0.1:8042")
     * @param timeoutMs how long to wait for a wrist raise
     */
    fun startListening(url: String, timeoutMs: Long = 3000) {
        if (listening.get()) {
            Log.d(TAG, "Already listening for wrist raise")
            return
        }

        serverUrl = url

        // Sensor type 26 = TYPE_WRIST_TILT_GESTURE (not a public constant on all API levels)
        // Fall back to TYPE_SIGNIFICANT_MOTION (17)
        val sensor = sensorManager.getDefaultSensor(26)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        if (sensor == null) {
            Log.w(TAG, "No wrist-tilt or tilt sensor available — sending trigger immediately")
            sendVoiceTrigger()
            return
        }

        listening.set(true)
        sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "Listening for wrist raise (timeout ${timeoutMs}ms)")

        // Unregister after timeout
        handler.postDelayed({
            if (listening.compareAndSet(true, false)) {
                Log.d(TAG, "Wrist raise timeout — no gesture detected")
                unregister()
            }
        }, timeoutMs)
    }

    private fun unregister() {
        try {
            sensorManager.unregisterListener(sensorListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensor listener: ${e.message}")
        }
    }

    private fun sendVoiceTrigger() {
        val url = serverUrl ?: return
        Thread {
            try {
                val payload = """{"device_id":"pw4","ts":${System.currentTimeMillis()}}"""
                val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("$url/watch/voice-trigger")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Voice trigger sent successfully")
                    } else {
                        Log.w(TAG, "Voice trigger returned ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send voice trigger: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val TAG = "VoiceTriggerResponder"
    }
}
