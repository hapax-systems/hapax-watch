package dev.hapax.watch.sensor

import android.util.Log
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.SampleDataPoint
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.data.SensorReading
import kotlinx.coroutines.guava.await

/**
 * Collects HRV (RMSSD) via MeasureClient.
 *
 * The HEART_RATE_VARIABILITY_RMSSD data type may not be present in
 * all versions of the Health Services client library. We construct
 * the DeltaDataType manually with the well-known name used by the
 * runtime. If the device doesn't support it, isAvailable returns false
 * and the collector is never started.
 */
class HrvCollector(private val measureClient: MeasureClient) : SensorCollector {

    override val type = "hrv"

    private var buffer: SensorBuffer? = null

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability,
        ) {
            Log.d(TAG, "HRV availability changed: $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            val buf = buffer ?: return
            // Use sampleDataPoints and filter by our data type name
            for (point in data.sampleDataPoints) {
                if (point.dataType.name == HRV_DATA_TYPE_NAME) {
                    @Suppress("UNCHECKED_CAST")
                    val rmssd = (point as SampleDataPoint<Double>).value
                    val ts = System.currentTimeMillis().toString()
                    val reading = SensorReading(
                        type = "hrv",
                        ts = ts,
                        rmssdMs = rmssd,
                    )
                    buf.add(reading)
                    Log.d(TAG, "HRV: $rmssd ms")
                }
            }
        }
    }

    override suspend fun start(buffer: SensorBuffer) {
        this.buffer = buffer
        measureClient.registerMeasureCallback(hrvDataType, callback)
        Log.i(TAG, "HRV collector started")
    }

    override fun stop() {
        try {
            measureClient.unregisterMeasureCallbackAsync(hrvDataType, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering HRV callback: ${e.message}")
        }
        buffer = null
        Log.i(TAG, "HRV collector stopped")
    }

    override suspend fun isAvailable(client: HealthServicesClient): Boolean {
        return try {
            val capabilities = measureClient.getCapabilitiesAsync().await()
            val supported = capabilities.supportedDataTypesMeasure.any {
                it.name == HRV_DATA_TYPE_NAME
            }
            Log.i(TAG, "HRV available: $supported")
            supported
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check HRV capabilities: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "HrvCollector"
        private const val HRV_DATA_TYPE_NAME = "HeartRateVariability"

        /** Manually constructed DeltaDataType for HRV RMSSD. */
        val hrvDataType = DeltaDataType<Double, SampleDataPoint<Double>>(
            HRV_DATA_TYPE_NAME,
            DataType.TimeType.SAMPLE,
            Double::class.java,
        )
    }
}
