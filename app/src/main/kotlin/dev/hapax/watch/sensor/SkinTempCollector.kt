package dev.hapax.watch.sensor

import android.util.Log
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.data.SensorReading
import kotlinx.coroutines.guava.await

/**
 * Collects skin temperature via PassiveMonitoringClient.
 *
 * On Pixel Watch 4 the available type is typically SKIN_TEMPERATURE_DELTA
 * (deviation from baseline) rather than absolute. We store whatever
 * value is reported in temp_c -- the server handles interpretation.
 *
 * These data types may not be present as constants in all library versions,
 * so we construct them manually with the well-known runtime names.
 */
class SkinTempCollector(private val passiveClient: PassiveMonitoringClient) : SensorCollector {

    override val type = "skin_temp"

    private var buffer: SensorBuffer? = null
    private var activeDataType: DeltaDataType<*, *>? = null

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val buf = buffer ?: return
            // Check all interval data points for skin temp data
            for (point in dataPoints.intervalDataPoints) {
                val name = point.dataType.name
                if (name == SKIN_TEMP_NAME || name == SKIN_TEMP_DELTA_NAME) {
                    @Suppress("UNCHECKED_CAST")
                    val tempC = (point as IntervalDataPoint<Double>).value
                    val ts = System.currentTimeMillis().toString()
                    val reading = SensorReading(
                        type = "skin_temp",
                        ts = ts,
                        tempC = tempC,
                    )
                    buf.add(reading)
                    Log.d(TAG, "Skin temp: $tempC C (${point.dataType.name})")
                }
            }
        }
    }

    override suspend fun start(buffer: SensorBuffer) {
        this.buffer = buffer
        val dataType = activeDataType ?: return
        val config = PassiveListenerConfig.builder()
            .setDataTypes(setOf(dataType))
            .build()
        passiveClient.setPassiveListenerCallback(config, passiveCallback)
        Log.i(TAG, "Skin temp collector started (type=${dataType.name})")
    }

    override fun stop() {
        try {
            passiveClient.clearPassiveListenerCallbackAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing skin temp callback: ${e.message}")
        }
        buffer = null
        Log.i(TAG, "Skin temp collector stopped")
    }

    override suspend fun isAvailable(client: HealthServicesClient): Boolean {
        return try {
            val capabilities = passiveClient.getCapabilitiesAsync().await()
            val supportedNames = capabilities.supportedDataTypesPassiveMonitoring.map { it.name }
            when {
                SKIN_TEMP_NAME in supportedNames -> {
                    activeDataType = skinTempAbsolute
                    Log.i(TAG, "Skin temp available (absolute)")
                    true
                }
                SKIN_TEMP_DELTA_NAME in supportedNames -> {
                    activeDataType = skinTempDelta
                    Log.i(TAG, "Skin temp available (delta)")
                    true
                }
                else -> {
                    Log.i(TAG, "Skin temp not available. Supported: $supportedNames")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check skin temp capabilities: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "SkinTempCollector"
        private const val SKIN_TEMP_NAME = "SkinTemperature"
        private const val SKIN_TEMP_DELTA_NAME = "SkinTemperatureDelta"

        val skinTempAbsolute = DeltaDataType<Double, IntervalDataPoint<Double>>(
            SKIN_TEMP_NAME,
            DataType.TimeType.INTERVAL,
            Double::class.java,
        )

        val skinTempDelta = DeltaDataType<Double, IntervalDataPoint<Double>>(
            SKIN_TEMP_DELTA_NAME,
            DataType.TimeType.INTERVAL,
            Double::class.java,
        )
    }
}
