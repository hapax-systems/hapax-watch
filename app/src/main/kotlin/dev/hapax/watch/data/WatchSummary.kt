package dev.hapax.watch.data

import kotlinx.serialization.Serializable

/**
 * Compact tile-friendly view of operator awareness.
 *
 * Schema mirrors the council `GET /api/awareness/watch-summary`
 * endpoint (logos/api/routes/awareness.py). Payload size stays narrow
 * on purpose — adding fields here costs every tile-render across the
 * operator's day.
 *
 * The endpoint returns 503 with a stale-flagged payload when the
 * awareness state file is missing or unreadable; callers should treat
 * `stale = true` as the cue to render a stale-state visual rather
 * than retrying.
 */
@Serializable
data class WatchSummary(
    val stance: String,
    val live: Boolean,
    val stale: Boolean,
    val presence_decile: Int? = null,
    val timestamp: String? = null,
)
