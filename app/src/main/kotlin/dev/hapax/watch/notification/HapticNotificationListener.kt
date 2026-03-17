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
 * haptic patterns. For presence-check notifications, starts listening
 * for a wrist-raise gesture to respond with a voice trigger.
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

        Log.d(TAG, "KDE Connect notification: title='$title' text='$text'")

        when {
            isPresenceCheck(title, text) -> handlePresenceCheck()
            isUrgent(title, text) -> vibrate(HapticPatterns.URGENT)
            isBriefing(title, text) -> vibrate(HapticPatterns.BRIEFING)
            isVoiceReady(title, text) -> vibrate(HapticPatterns.VOICE_READY)
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

    private fun vibrate(pattern: LongArray) {
        val effect = VibrationEffect.createWaveform(pattern, -1)
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

        private fun isPresenceCheck(title: String, text: String): Boolean {
            val combined = "$title $text".lowercase()
            return combined.contains("hapax") && combined.contains("presence")
        }

        private fun isUrgent(title: String, text: String): Boolean {
            val combined = "$title $text".lowercase()
            return combined.contains("hapax") && combined.contains("urgent")
        }

        private fun isBriefing(title: String, text: String): Boolean {
            val combined = "$title $text".lowercase()
            return combined.contains("hapax") && combined.contains("briefing")
        }

        private fun isVoiceReady(title: String, text: String): Boolean {
            val combined = "$title $text".lowercase()
            return combined.contains("hapax") && combined.contains("voice")
        }
    }
}
