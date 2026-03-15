package dev.hapax.watch.tile

import android.content.Context
import android.os.BatteryManager
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Status tile showing connection status, last HR, and battery.
 * Uses ProtoLayout builders (not Compose) as required by the Tiles API.
 */
class StatusTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = serviceScope.future {
        val batteryPct = getBatteryPercentage()
        val connectionStatus = "disconnected"
        val lastHr = "--"

        val root = StatusTileRenderer.buildLayout(
            connectionStatus = connectionStatus,
            lastHr = lastHr,
            batteryPct = "$batteryPct%",
        )

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

    private fun getBatteryPercentage(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
