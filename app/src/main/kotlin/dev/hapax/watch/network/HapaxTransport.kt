package dev.hapax.watch.network

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hapax.watch.data.SensorBuffer
import dev.hapax.watch.data.SensorPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Transports sensor data to the hapax workstation.
 *
 * URL resolution order on flush:
 * 1. Manual IP override from DataStore
 * 2. Cached mDNS-discovered URL (valid for 5 minutes)
 * 3. Fresh mDNS discovery (5s timeout)
 * 4. If none available, skip flush and keep buffering
 */
class HapaxTransport(private val context: Context) {

    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { encodeDefaults = true }

    private var backoffMs: Long = INITIAL_BACKOFF_MS
    private val consecutiveFailures = AtomicInteger(0)
    private val cachedUrl = AtomicReference<String?>(null)
    private var cacheTimestamp: Long = 0L

    private val mdnsDiscovery by lazy { MdnsDiscovery(context) }

    /** Current connection status. */
    enum class Status { CONNECTED, BUFFERING, DISCONNECTED }

    @Volatile
    var status: Status = Status.DISCONNECTED
        private set

    /** Last successfully resolved base URL, for display. */
    @Volatile
    var resolvedUrl: String? = null
        private set

    /** Last mDNS-discovered address, for display. */
    @Volatile
    var mdnsAddress: String? = null
        private set

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

        val baseUrl = resolveUrl()
        if (baseUrl == null) {
            Log.w(TAG, "No server URL available — buffering ${readings.size} readings")
            // Put readings back
            for (reading in readings) {
                buffer.add(reading)
            }
            status = Status.BUFFERING
            return false
        }

        val payload = SensorPayload(
            ts = System.currentTimeMillis() / 1000,
            deviceId = Build.MODEL,
            batteryPct = null, // TODO: read actual battery
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
                    Log.i(TAG, "Flushed ${readings.size} readings to $baseUrl")
                    backoffMs = INITIAL_BACKOFF_MS
                    consecutiveFailures.set(0)
                    status = Status.CONNECTED
                    resolvedUrl = baseUrl
                    true
                } else {
                    Log.w(TAG, "Server returned ${response.code}")
                    onFailure()
                    // Put readings back on non-success
                    for (reading in readings) {
                        buffer.add(reading)
                    }
                    false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Flush failed: ${e.message}")
            onFailure()
            // Put readings back
            for (reading in readings) {
                buffer.add(reading)
            }
            false
        }
    }

    /**
     * Resolve the server URL.
     * Priority: manual override > cached mDNS > fresh mDNS discovery.
     */
    private fun resolveUrl(): String? {
        // 1. Check manual override from DataStore
        val manualIp = getManualIp()
        if (!manualIp.isNullOrBlank()) {
            val url = "http://$manualIp"
            resolvedUrl = url
            return url
        }

        // 2. Check cached mDNS URL (valid for 5 minutes)
        val cached = cachedUrl.get()
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }

        // 3. Try fresh mDNS discovery
        val discovered = mdnsDiscovery.discover(timeout = 5000)
        if (discovered != null) {
            val url = "http://$discovered"
            cachedUrl.set(url)
            cacheTimestamp = System.currentTimeMillis()
            mdnsAddress = discovered
            resolvedUrl = url
            Log.i(TAG, "mDNS discovered server: $url")
            return url
        }

        // 4. Fall back to DataStore-saved IP (from previous mDNS or manual)
        val savedIp = getSavedIp()
        if (!savedIp.isNullOrBlank()) {
            val url = "http://$savedIp"
            resolvedUrl = url
            return url
        }

        return null
    }

    private fun getManualIp(): String? {
        return try {
            runBlocking {
                context.dataStore.data.map { prefs ->
                    prefs[stringPreferencesKey("manual_ip")]
                }.first()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read manual_ip from DataStore: ${e.message}")
            null
        }
    }

    private fun getSavedIp(): String? {
        return try {
            runBlocking {
                context.dataStore.data.map { prefs ->
                    prefs[stringPreferencesKey("server_ip")]
                }.first()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read server_ip from DataStore: ${e.message}")
            null
        }
    }

    /** Invalidate cached URL (e.g. on network change). */
    fun invalidateCache() {
        cachedUrl.set(null)
        cacheTimestamp = 0L
        Log.d(TAG, "URL cache invalidated")
    }

    private fun onFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        status = if (failures >= 3) Status.DISCONNECTED else Status.BUFFERING
        applyBackoff()
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
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
