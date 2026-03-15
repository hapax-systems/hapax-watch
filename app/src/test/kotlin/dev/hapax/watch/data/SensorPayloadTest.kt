package dev.hapax.watch.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensorPayloadTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `serialization produces expected JSON keys`() {
        val payload = SensorPayload(
            ts = 1700000000,
            deviceId = "Pixel Watch 2",
            batteryPct = 85,
            readings = listOf(
                SensorReading(
                    type = "heart_rate",
                    ts = "2025-01-01T00:00:00Z",
                    bpm = 72.0,
                    confidence = "high",
                ),
            ),
        )

        val encoded = json.encodeToString(payload)
        val obj = json.decodeFromString<JsonObject>(encoded)

        // Check snake_case keys from @SerialName
        assertTrue(obj.containsKey("device_id"))
        assertTrue(obj.containsKey("battery_pct"))
        assertEquals("Pixel Watch 2", obj["device_id"]?.jsonPrimitive?.content)
        assertEquals(85, obj["battery_pct"]?.jsonPrimitive?.content?.toInt())

        val readings = obj["readings"]?.jsonArray
        assertEquals(1, readings?.size)
        val reading = readings?.get(0)?.jsonObject
        assertEquals("heart_rate", reading?.get("type")?.jsonPrimitive?.content)
        assertEquals(72.0, reading?.get("bpm")?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `null fields are serialized as null`() {
        val payload = SensorPayload(
            ts = 1700000000,
            deviceId = "test",
            batteryPct = null,
            readings = listOf(
                SensorReading(
                    type = "heart_rate",
                    ts = "2025-01-01T00:00:00Z",
                    bpm = 72.0,
                ),
            ),
        )

        val encoded = json.encodeToString(payload)
        val obj = json.decodeFromString<JsonObject>(encoded)
        assertTrue(obj.containsKey("battery_pct"))

        val reading = obj["readings"]?.jsonArray?.get(0)?.jsonObject
        assertTrue(reading?.containsKey("rmssd_ms") == true)
        assertTrue(reading?.containsKey("temp_c") == true)
        assertTrue(reading?.containsKey("state") == true)
    }

    @Test
    fun `deserialization round-trips correctly`() {
        val original = SensorPayload(
            ts = 1700000000,
            deviceId = "test-device",
            batteryPct = 50,
            readings = listOf(
                SensorReading(
                    type = "hrv",
                    ts = "2025-01-01T00:00:00Z",
                    rmssdMs = 45.5,
                ),
                SensorReading(
                    type = "skin_temp",
                    ts = "2025-01-01T00:00:01Z",
                    tempC = 33.2,
                ),
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SensorPayload>(encoded)

        assertEquals(original.ts, decoded.ts)
        assertEquals(original.deviceId, decoded.deviceId)
        assertEquals(original.batteryPct, decoded.batteryPct)
        assertEquals(original.readings.size, decoded.readings.size)
        assertEquals(45.5, decoded.readings[0].rmssdMs)
        assertEquals(33.2, decoded.readings[1].tempC)
        assertNull(decoded.readings[0].bpm)
    }
}
