package dev.hapax.watch.sensor

import android.util.Log
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.data.SensorReading
import kotlinx.coroutines.guava.await

/**
 * Tracks activity state changes via PassiveMonitoringClient.
 * Only emits a reading when the state actually changes to avoid
 * flooding the buffer with repeated "STILL" readings.
 */
class ActivityCollector(private val passiveClient: PassiveMonitoringClient) : SensorCollector {

    override val type = "activity"

    private var buffer: SensorBuffer? = null

    @Volatile
    private var lastState: String? = null

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onUserActivityInfoReceived(info: UserActivityInfo) {
            val buf = buffer ?: return
            val state = mapActivityState(info.userActivityState.name)
            // Only emit on state change
            if (state != lastState) {
                lastState = state
                val reading = SensorReading(
                    type = "activity",
                    ts = info.stateChangeTime.toEpochMilli().toString(),
                    state = state,
                )
                buf.add(reading)
                Log.d(TAG, "Activity state changed: $state")
            }
        }

        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            // Not used for activity — we rely on onUserActivityInfoReceived
        }
    }

    override suspend fun start(buffer: SensorBuffer) {
        this.buffer = buffer
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.STEPS_DAILY))
            .setShouldUserActivityInfoBeRequested(true)
            .build()
        passiveClient.setPassiveListenerCallback(config, passiveCallback)
        Log.i(TAG, "Activity collector started")
    }

    override fun stop() {
        try {
            passiveClient.clearPassiveListenerCallbackAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing activity callback: ${e.message}")
        }
        buffer = null
        lastState = null
        Log.i(TAG, "Activity collector stopped")
    }

    override suspend fun isAvailable(client: HealthServicesClient): Boolean {
        return try {
            val capabilities = passiveClient.getCapabilitiesAsync().await()
            // Activity recognition is available if the passive client
            // reports supported user activity states
            val hasStates = capabilities.supportedUserActivityStates.isNotEmpty()
            Log.i(TAG, "Activity recognition available: $hasStates " +
                "(states: ${capabilities.supportedUserActivityStates.map { it.name }})")
            hasStates
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check activity capabilities: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "ActivityCollector"

        /** Map Health Services activity state names to our simplified states. */
        fun mapActivityState(rawState: String): String = when {
            rawState.contains("EXERCISE", ignoreCase = true) -> "RUNNING"
            rawState.contains("PASSIVE", ignoreCase = true) -> "WALKING"
            rawState.contains("ASLEEP", ignoreCase = true) ||
                rawState.contains("UNKNOWN", ignoreCase = true) -> "STILL"
            else -> rawState.uppercase()
        }
    }
}
