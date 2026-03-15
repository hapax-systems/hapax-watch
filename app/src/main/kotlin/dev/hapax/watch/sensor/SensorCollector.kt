package dev.hapax.watch.sensor

import androidx.health.services.client.HealthServicesClient
import dev.hapax.watch.data.SensorBuffer

/**
 * Abstraction for a single sensor data source.
 * Each collector registers with Health Services (or another API),
 * pushes SensorReading instances into the shared buffer, and
 * can be stopped cleanly on service destroy.
 */
interface SensorCollector {
    /** Sensor type string matching the server schema (e.g. "heart_rate", "hrv"). */
    val type: String

    /** Begin collecting data and pushing to [buffer]. */
    suspend fun start(buffer: SensorBuffer)

    /** Stop collecting. Safe to call multiple times. */
    fun stop()

    /** Check whether the underlying sensor is supported on this device. */
    suspend fun isAvailable(client: HealthServicesClient): Boolean
}
