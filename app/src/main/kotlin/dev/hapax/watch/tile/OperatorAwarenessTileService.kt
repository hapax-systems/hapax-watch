package dev.hapax.watch.tile

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.hapax.watch.network.LogosApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext

/**
 * Operator-awareness tile — three-glyph compact view of stance,
 * presence, and voice activity.
 *
 * `awareness-watch-tile-001` ships this skeleton: the service is
 * registered, polls the council `/api/awareness/watch-summary`
 * endpoint via [LogosApiClient], and returns a placeholder layout
 * containing the raw stance string. The 3-glyph timeline layout
 * (stance icon + presence decile + voice dot) lands in
 * `awareness-watch-tile-002`; stale-state visual + lint in `-003`;
 * on-device smoke in `-004`.
 *
 * Per drop §3.9: this tile is glance-only. No `setOnClick`, no
 * PendingIntent, no tap affordance — Wear OS shell behavior on tap
 * (open app launcher) is fine because we never add ours.
 *
 * `FreshnessIntervalMillis = 60_000` — one poll per minute per the
 * constitutional bound on tile-render cost.
 */
class OperatorAwarenessTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val apiClient = LogosApiClient()

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = serviceScope.future {
        val summary = withContext(Dispatchers.IO) { apiClient.fetchWatchSummary() }

        val text = if (summary == null) {
            "?"
        } else {
            // Placeholder until -002 lands the 3-glyph layout. The stance
            // string is the most informative single field for skeleton
            // verification on-device.
            summary.stance
        }

        val root = LayoutElementBuilders.Text.Builder()
            .setText(text)
            .build()

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(root)
            .build()

        val entry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(layout)
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(entry)
            .build()

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS)
            .setTileTimeline(timeline)
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = serviceScope.future {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val REFRESH_INTERVAL_MS: Long = 60_000
    }
}
