package dev.hapax.watch.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding

/**
 * Builds the ProtoLayout for the status tile.
 * Colors from the CGA/BBS palette for that retro feel.
 */
object StatusTileRenderer {

    // CGA palette colors
    private const val COLOR_GREEN = 0xFF55FF55.toInt()   // connected
    private const val COLOR_YELLOW = 0xFFFFFF55.toInt()  // buffering
    private const val COLOR_RED = 0xFFFF5555.toInt()     // disconnected
    private const val COLOR_CYAN = 0xFF55FFFF.toInt()    // labels
    private const val COLOR_WHITE = 0xFFFFFFFF.toInt()   // values
    private const val COLOR_GRAY = 0xFFAAAAAA.toInt()    // secondary

    fun buildLayout(
        connectionStatus: String,
        lastHr: String,
        batteryPct: String,
    ): LayoutElementBuilders.LayoutElement {
        val statusColor = when (connectionStatus) {
            "connected" -> COLOR_GREEN
            "buffering" -> COLOR_YELLOW
            else -> COLOR_RED
        }

        val statusLabel = when (connectionStatus) {
            "connected" -> "ONLINE"
            "buffering" -> "BUFFER"
            else -> "OFFLINE"
        }

        return Column.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setModifiers(
                Modifiers.Builder()
                    .setPadding(
                        Padding.Builder()
                            .setAll(dp(12f))
                            .build()
                    )
                    .build()
            )
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            // Status dot + label
            .addContent(
                Text.Builder()
                    .setText("\u25CF $statusLabel")
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(16f))
                            .setColor(argb(statusColor))
                            .build()
                    )
                    .build()
            )
            .addContent(spacer(8f))
            // HR value
            .addContent(
                Text.Builder()
                    .setText("HR")
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(10f))
                            .setColor(argb(COLOR_CYAN))
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText(lastHr)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(24f))
                            .setColor(argb(COLOR_WHITE))
                            .build()
                    )
                    .build()
            )
            .addContent(spacer(6f))
            // Battery
            .addContent(
                Text.Builder()
                    .setText("BAT $batteryPct")
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(COLOR_GRAY))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun spacer(heightDp: Float): Spacer =
        Spacer.Builder()
            .setHeight(dp(heightDp))
            .build()
}
