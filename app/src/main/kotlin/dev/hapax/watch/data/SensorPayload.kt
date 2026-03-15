package dev.hapax.watch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SensorPayload(
    val ts: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("battery_pct") val batteryPct: Int?,
    val readings: List<SensorReading>,
)
