package dev.hapax.watch.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects 3 gesture patterns from watch sensors and POSTs intent signals
 * to the workstation.
 *
 * Gestures:
 *   - **Double-tap**: Two sharp Z-axis accelerometer peaks within 400ms.
 *     Intent: "acknowledge" — dismiss nudge, confirm notification.
 *   - **Wrist twist**: Gyroscope Y-axis rotation >90° within 500ms.
 *     Intent: "attention" — surface informational display.
 *   - **Cover**: Proximity=near + ambient light <10 lux for >1s.
 *     Intent: "not_now" — suppress haptics for 15 minutes.
 *
 * Battery constraint: only runs when SensorService is already active
 * (foreground service). Uses SENSOR_DELAY_NORMAL (~200ms batching).
 */
class GestureDetector(
    private val context: Context,
    private val serverUrl: String,
) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val running = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // ── Double-tap state ──────────────────────────────────────────────────

    private var lastTapTime = 0L
    private var tapCount = 0
    private val TAP_THRESHOLD = 20.0f  // m/s² Z-axis spike
    private val TAP_WINDOW_MS = 400L

    // ── Wrist-twist state ─────────────────────────────────────────────────

    private var twistStartTime = 0L
    private var twistAccumulated = 0.0f
    private val TWIST_THRESHOLD_DEG = 90.0f
    private val TWIST_WINDOW_MS = 500L

    // ── Cover state ───────────────────────────────────────────────────────

    private var proximityNear = false
    private var lightLow = false
    private var coverStartTime = 0L
    private val COVER_HOLD_MS = 1000L
    private val LIGHT_THRESHOLD_LUX = 10.0f

    // ── Cooldown (prevent gesture spam) ───────────────────────────────────

    private var lastGestureTime = 0L
    private val GESTURE_COOLDOWN_MS = 2000L

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running.get()) return
            val z = event.values[2]
            val now = System.currentTimeMillis()

            // Detect sharp Z-axis peak (tap on watch face)
            if (kotlin.math.abs(z) > TAP_THRESHOLD) {
                if (now - lastTapTime < TAP_WINDOW_MS) {
                    tapCount++
                    if (tapCount >= 2) {
                        tapCount = 0
                        onGesture("double_tap")
                    }
                } else {
                    tapCount = 1
                }
                lastTapTime = now
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running.get()) return
            val yRotation = event.values[1]  // Y-axis = wrist twist
            val now = System.currentTimeMillis()

            if (twistStartTime == 0L || now - twistStartTime > TWIST_WINDOW_MS) {
                twistStartTime = now
                twistAccumulated = 0.0f
            }

            // Accumulate rotation (rad/s → degrees, ~200ms sample interval)
            twistAccumulated += kotlin.math.abs(yRotation) * 0.2f * (180f / Math.PI.toFloat())

            if (twistAccumulated > TWIST_THRESHOLD_DEG) {
                twistStartTime = 0L
                twistAccumulated = 0.0f
                onGesture("wrist_twist")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running.get()) return
            proximityNear = event.values[0] < event.sensor.maximumRange
            checkCover()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running.get()) return
            lightLow = event.values[0] < LIGHT_THRESHOLD_LUX
            checkCover()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private fun checkCover() {
        val now = System.currentTimeMillis()
        if (proximityNear && lightLow) {
            if (coverStartTime == 0L) {
                coverStartTime = now
            } else if (now - coverStartTime > COVER_HOLD_MS) {
                coverStartTime = 0L
                onGesture("cover")
            }
        } else {
            coverStartTime = 0L
        }
    }

    private fun onGesture(gesture: String) {
        val now = System.currentTimeMillis()
        if (now - lastGestureTime < GESTURE_COOLDOWN_MS) return
        lastGestureTime = now

        Log.i(TAG, "Gesture detected: $gesture")
        sendGesture(gesture)
    }

    private fun sendGesture(gesture: String) {
        Thread {
            try {
                val payload = """{"device_id":"pw4","gesture":"$gesture","timestamp":"${System.currentTimeMillis()}"}"""
                val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("$serverUrl/watch/gesture")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Gesture '$gesture' sent successfully")
                    } else {
                        Log.w(TAG, "Gesture POST returned ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send gesture: ${e.message}")
            }
        }.start()
    }

    fun start() {
        if (running.getAndSet(true)) return

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        accel?.let {
            sensorManager.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Accelerometer registered (double-tap)")
        }
        gyro?.let {
            sensorManager.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Gyroscope registered (wrist-twist)")
        }
        proximity?.let {
            sensorManager.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Proximity registered (cover)")
        }
        light?.let {
            sensorManager.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Light sensor registered (cover)")
        }

        Log.i(TAG, "GestureDetector started")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(gyroListener)
        sensorManager.unregisterListener(proximityListener)
        sensorManager.unregisterListener(lightListener)
        Log.i(TAG, "GestureDetector stopped")
    }

    companion object {
        private const val TAG = "GestureDetector"
    }
}
