package dev.hapax.watch.tile

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.hapax.watch.network.LogosApiClient
import dev.hapax.watch.data.WatchSummary
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
        buildTile(summary)
    }

    private fun buildTile(summary: WatchSummary?): TileBuilders.Tile {
        val timeline = buildTimeline(summary)
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS)
            .setTileTimeline(timeline)
            .build()
    }

    internal fun buildTimeline(summary: WatchSummary?): TimelineBuilders.Timeline {
        val textStance = summary?.stance ?: "?"
        val isLive = summary?.live ?: false
        val isStale = summary?.stale ?: true
        val presenceDecile = summary?.presence_decile?.toString() ?: "-"

        val stanceElement = LayoutElementBuilders.Text.Builder()
            .setText(textStance)
            .build()
            
        val presenceElement = LayoutElementBuilders.Text.Builder()
            .setText(presenceDecile)
            .build()
            
        val voiceDot = LayoutElementBuilders.Text.Builder()
            .setText(if (isLive) "●" else "○")
            .build()

        val row = LayoutElementBuilders.Row.Builder()
            .addContent(stanceElement)
            .addContent(presenceElement)
            .addContent(voiceDot)
            .build()

        val rootBuilder = LayoutElementBuilders.Layout.Builder().setRoot(row)

        if (isStale) {
            val modifiers = androidx.wear.protolayout.ModifiersBuilders.Modifiers.Builder()
                .setOpacity(androidx.wear.protolayout.TypeBuilders.FloatProp.Builder(0.5f).build())
                .build()
            
            // Re-wrap the row with the modifier
            val wrappedRow = LayoutElementBuilders.Row.Builder()
                .addContent(stanceElement)
                .addContent(presenceElement)
                .addContent(voiceDot)
                .setModifiers(modifiers)
                .build()
            
            rootBuilder.setRoot(wrappedRow)
        }

        val entry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(rootBuilder.build())
            .build()

        return TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(entry)
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
