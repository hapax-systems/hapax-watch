package dev.hapax.watch.notification

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.hapax.watch.data.dataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit


/**
 * Listens for notifications from KDE Connect and triggers appropriate
 * haptic patterns. Supports both operational patterns (presence, urgent,
 * briefing, voice) and Stimmung patterns that encode system state into
 * nuanced vibrations.
 *
 * Stimmung keywords sent by the workstation:
 *   "hapax stimmung calm"     → gentle tap (nominal)
 *   "hapax stimmung cautious" → double tap (working harder)
 *   "hapax stimmung degraded" → accelerating triple tap (stressed)
 *   "hapax stimmung flow"     → silence (flow state, zero interruption)
 *   "hapax stress ack"        → long low buzz (stress acknowledgment)
 *   "hapax transition"        → rising sweep (activity transition)
 */
class HapticNotificationListener : NotificationListenerService() {

    private lateinit var vibrator: Vibrator
    private lateinit var voiceTriggerResponder: VoiceTriggerResponder

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibrator = vibratorManager.defaultVibrator
        voiceTriggerResponder = VoiceTriggerResponder(this, httpClient)
        Log.i(TAG, "HapticNotificationListener created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Only handle KDE Connect notifications
        if (sbn.packageName != KDE_CONNECT_PACKAGE) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val combined = "$title $text".lowercase()

        Log.d(TAG, "KDE Connect notification: title='$title' text='$text'")

        if (!combined.contains("hapax")) {
            Log.d(TAG, "Not a hapax notification — ignoring")
            return
        }

        when {
            // ── Stimmung patterns (check before operational to avoid overlap) ──
            combined.contains("stimmung calm") -> {
                Log.i(TAG, "Stimmung: calm")
                vibrateWithAmplitude(HapticPatterns.STIMMUNG_CALM, HapticPatterns.STIMMUNG_CALM_AMPLITUDES)
            }
            combined.contains("stimmung cautious") -> {
                Log.i(TAG, "Stimmung: cautious")
                vibrateWithAmplitude(HapticPatterns.STIMMUNG_CAUTIOUS, HapticPatterns.STIMMUNG_CAUTIOUS_AMPLITUDES)
            }
            combined.contains("stimmung degraded") -> {
                Log.i(TAG, "Stimmung: degraded")
                vibrateWithAmplitude(HapticPatterns.STIMMUNG_DEGRADED, HapticPatterns.STIMMUNG_DEGRADED_AMPLITUDES)
            }
            combined.contains("stimmung flow") -> {
                // Silence IS the signal — do not vibrate
                Log.i(TAG, "Stimmung: flow — suppressing haptics")
            }
            combined.contains("stress ack") -> {
                Log.i(TAG, "Stress acknowledgment")
                vibrateWithAmplitude(HapticPatterns.STRESS_ACK, HapticPatterns.STRESS_ACK_AMPLITUDES)
            }
            combined.contains("transition") && !combined.contains("presence") -> {
                Log.i(TAG, "Activity transition")
                vibrateWithAmplitude(HapticPatterns.TRANSITION, HapticPatterns.TRANSITION_AMPLITUDES)
            }

            // ── Operational patterns ──
            combined.contains("presence") -> handlePresenceCheck()
            combined.contains("urgent") -> vibrate(HapticPatterns.URGENT)
            combined.contains("briefing") -> vibrate(HapticPatterns.BRIEFING)
            combined.contains("voice") -> vibrate(HapticPatterns.VOICE_READY)

            else -> Log.d(TAG, "Unrecognized hapax notification — ignoring")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not needed
    }

    private fun handlePresenceCheck() {
        Log.i(TAG, "Presence check received — vibrating and listening for wrist raise")
        vibrate(HapticPatterns.PRESENCE_CHECK)

        // Determine server URL for the voice trigger response
        val serverUrl = resolveServerUrl()
        if (serverUrl != null) {
            voiceTriggerResponder.startListening(serverUrl, timeoutMs = 3000)
        } else {
            Log.w(TAG, "No server URL available — cannot respond to presence check")
        }
    }

    /** Vibrate with a simple timing pattern (default amplitude). */
    private fun vibrate(pattern: LongArray) {
        val effect = VibrationEffect.createWaveform(pattern, -1)
        vibrator.vibrate(effect)
    }

    /** Vibrate with amplitude-modulated pattern for nuanced Stimmung feedback. */
    private fun vibrateWithAmplitude(timings: LongArray, amplitudes: IntArray) {
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        vibrator.vibrate(effect)
    }

    private fun resolveServerUrl(): String? {
        return try {
            runBlocking {
                val manualIp = dataStore.data.map { prefs ->
                    prefs[stringPreferencesKey("manual_ip")]
                }.first()
                if (!manualIp.isNullOrBlank()) {
                    "http://$manualIp"
                } else {
                    val serverIp = dataStore.data.map { prefs ->
                        prefs[stringPreferencesKey("server_ip")]
                    }.first()
                    if (!serverIp.isNullOrBlank()) "http://$serverIp" else null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve server URL: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "HapticNotifListener"
        private const val KDE_CONNECT_PACKAGE = "org.kde.kdeconnect_tp"
    }
}
