package dev.hapax.watch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SensorReading(
    val type: String,
    val ts: String,
    val bpm: Double? = null,
    val confidence: String? = null,
    @SerialName("rmssd_ms") val rmssdMs: Double? = null,
    @SerialName("temp_c") val tempC: Double? = null,
    val state: String? = null,
)
