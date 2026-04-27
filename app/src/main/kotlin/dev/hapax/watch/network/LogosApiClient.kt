package dev.hapax.watch.network

import android.util.Log
import dev.hapax.watch.data.WatchSummary
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reads the council Logos API from the watch.
 *
 * Distinct from `HapaxTransport`, which writes biometric SensorPayloads
 * to the watch-receiver on port 8042. This client only reads — no
 * mutating affordances per the operator-awareness constitutional
 * refusal (no tap-to-act).
 *
 * Base URL resolution mirrors `HapaxTransport`'s policy: explicit
 * override → mDNS-discovered host → Tailscale fallback. For the
 * skeleton (`awareness-watch-tile-001`) we hardcode the Tailscale
 * fallback only — the mDNS path can be added in `-002` once the
 * 3-glyph layout work proves the API integration is sound.
 *
 * The Tailscale address `100.117.1.83` is the canonical workstation
 * pin per `project_tailscale_network` memory.
 */
class LogosApiClient(
    private val client: OkHttpClient = defaultClient,
    private val baseUrl: String = TAILSCALE_FALLBACK,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Fetch the compact watch-summary payload.
     *
     * @return parsed [WatchSummary], or `null` on any I/O / parse / HTTP-5xx
     *         failure. The caller (TileService) should render its
     *         stale-state visual on null.
     */
    fun fetchWatchSummary(): WatchSummary? {
        val request = Request.Builder()
            .url("$baseUrl$WATCH_SUMMARY_PATH")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (response.code in 500..599) {
                    Log.w(TAG, "watch-summary 5xx: code=${response.code}")
                    return null
                }
                json.decodeFromString(WatchSummary.serializer(), body)
            }
        } catch (e: IOException) {
            Log.w(TAG, "watch-summary fetch failed: ${e.message}")
            null
        } catch (e: kotlinx.serialization.SerializationException) {
            Log.w(TAG, "watch-summary parse failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "LogosApiClient"
        const val TAILSCALE_FALLBACK = "http://100.117.1.83:8051"
        const val WATCH_SUMMARY_PATH = "/api/awareness/watch-summary"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
