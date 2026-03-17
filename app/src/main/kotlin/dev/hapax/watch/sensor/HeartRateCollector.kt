package dev.hapax.watch.sensor

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.HeartRateAccuracy
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.data.SensorReading
import kotlinx.coroutines.guava.await

class HeartRateCollector(
    private val measureClient: MeasureClient,
    private val context: Context? = null,
) : SensorCollector {

    override val type = "heart_rate"

    private var buffer: SensorBuffer? = null

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability,
        ) {
            Log.d(TAG, "HR availability changed: $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            val buf = buffer ?: return
            for (point in data.getData(DataType.HEART_RATE_BPM)) {
                val bpm = point.value
                val accuracy = (point.accuracy as? HeartRateAccuracy)
                    ?.sensorStatus?.name ?: "UNKNOWN"
                val ts = System.currentTimeMillis().toString()
                val reading = SensorReading(
                    type = "heart_rate",
                    ts = ts,
                    bpm = bpm,
                    confidence = accuracy,
                )
                buf.add(reading)
                // Publish latest HR for notification display
                context?.getSharedPreferences(SensorService.STATUS_PREFS, Context.MODE_PRIVATE)
                    ?.edit()?.putFloat("last_hr", bpm.toFloat())?.apply()
                Log.d(TAG, "HR: $bpm bpm ($accuracy)")
            }
        }
    }

    override suspend fun start(buffer: SensorBuffer) {
        this.buffer = buffer
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        Log.i(TAG, "Heart rate collector started")
    }

    override fun stop() {
        try {
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering HR callback: ${e.message}")
        }
        buffer = null
        Log.i(TAG, "Heart rate collector stopped")
    }

    override suspend fun isAvailable(client: HealthServicesClient): Boolean {
        return try {
            val capabilities = measureClient.getCapabilitiesAsync().await()
            val supported = DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure
            Log.i(TAG, "Heart rate available: $supported")
            supported
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check HR capabilities: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "HeartRateCollector"
    }
}
