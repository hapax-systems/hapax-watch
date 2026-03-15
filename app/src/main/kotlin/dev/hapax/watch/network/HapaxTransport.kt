package dev.hapax.watch.network

import android.os.Build
import android.util.Log
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.data.SensorPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HapaxTransport(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { encodeDefaults = true }

    private var backoffMs: Long = INITIAL_BACKOFF_MS

    /**
     * Drains the buffer and POSTs the payload to the server.
     * Returns true on success, false on failure (with exponential backoff applied).
     */
    fun flush(buffer: SensorBuffer): Boolean {
        val readings = buffer.drain()
        if (readings.isEmpty()) {
            Log.d(TAG, "Nothing to flush")
            return true
        }

        val payload = SensorPayload(
            ts = System.currentTimeMillis() / 1000,
            deviceId = Build.MODEL,
            batteryPct = null, // TODO: read actual battery in Sprint 2
            readings = readings,
        )

        val body = json.encodeToString(payload)
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/ingest/watch")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Flushed ${readings.size} readings")
                    backoffMs = INITIAL_BACKOFF_MS
                    true
                } else {
                    Log.w(TAG, "Server returned ${response.code}")
                    applyBackoff()
                    false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Flush failed: ${e.message}")
            applyBackoff()
            false
        }
    }

    private fun applyBackoff() {
        try {
            Thread.sleep(backoffMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    companion object {
        private const val TAG = "HapaxTransport"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val INITIAL_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 120_000L
    }
}
