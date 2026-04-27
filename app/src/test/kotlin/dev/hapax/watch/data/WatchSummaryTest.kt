package dev.hapax.watch.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchSummaryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses live healthy payload`() {
        val body = """
            {"stance":"speaking","live":true,"stale":false,"presence_decile":7,"timestamp":"2026-04-27T01:00:00Z"}
        """.trimIndent()
        val summary = json.decodeFromString(WatchSummary.serializer(), body)
        assertEquals("speaking", summary.stance)
        assertTrue(summary.live)
        assertFalse(summary.stale)
        assertEquals(7, summary.presence_decile)
        assertEquals("2026-04-27T01:00:00Z", summary.timestamp)
    }

    @Test
    fun `parses 503 stale payload (no timestamp or decile)`() {
        // Mirrors the council 503 fallback in awareness.py:179-183.
        val body = """{"stance":"unknown","live":false,"stale":true}"""
        val summary = json.decodeFromString(WatchSummary.serializer(), body)
        assertEquals("unknown", summary.stance)
        assertFalse(summary.live)
        assertTrue(summary.stale)
        assertNull(summary.presence_decile)
        assertNull(summary.timestamp)
    }

    @Test
    fun `ignores unknown fields when configured to`() {
        // Keeps the data class forward-compatible with future council
        // additions (e.g. voice_active) without requiring a watch-app rebuild.
        val body = """
            {"stance":"silent","live":false,"stale":false,"presence_decile":7,"voice_active":false}
        """.trimIndent()
        val summary = json.decodeFromString(WatchSummary.serializer(), body)
        assertEquals("silent", summary.stance)
        assertEquals(7, summary.presence_decile)
    }

    @Test
    fun `strict json rejects unknown fields by default`() {
        // Sanity check: confirms the LogosApiClient's
        // ignoreUnknownKeys=true is doing real work.
        val body = """{"stance":"silent","live":false,"stale":false,"future_field":1}"""
        val strict = Json { ignoreUnknownKeys = false }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            strict.decodeFromString(WatchSummary.serializer(), body)
        }
    }
}
