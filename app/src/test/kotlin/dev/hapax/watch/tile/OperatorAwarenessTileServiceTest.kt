package dev.hapax.watch.tile

import androidx.wear.protolayout.LayoutElementBuilders
import dev.hapax.watch.data.WatchSummary
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperatorAwarenessTileServiceTest {

    @Test
    fun `buildTimeline contains 3-glyph layout and no clickable actions`() {
        val service = OperatorAwarenessTileService()
        val summary = WatchSummary(
            stance = "speaking",
            live = true,
            stale = false,
            presence_decile = 8,
            timestamp = "2026-04-27T01:00:00Z"
        )
        
        val timeline = service.buildTimeline(summary)
        
        val entries = timeline.timelineEntries
        assertEquals(1, entries.size)
        
        val rootLayout = entries[0].layout?.root
        
        // The root might be wrapped if stale, but for this test stale=false, so it is just the Row.
        assertTrue(rootLayout is LayoutElementBuilders.Row)
        
        val row = rootLayout as LayoutElementBuilders.Row
        val contents = row.contents
        
        // Assert LayoutElement count = 3
        assertEquals(3, contents.size)
        
        // Check contents are Text elements and match our expected values
        val stanceElement = contents[0] as LayoutElementBuilders.Text
        assertEquals("speaking", stanceElement.text!!.value)
        
        val presenceElement = contents[1] as LayoutElementBuilders.Text
        assertEquals("8", presenceElement.text!!.value)
        
        val voiceDotElement = contents[2] as LayoutElementBuilders.Text
        assertEquals("●", voiceDotElement.text!!.value)
        
        // Assert no Clickable action (glance-only)
        for (element in contents) {
            val textElement = element as LayoutElementBuilders.Text
            assertNull(textElement.modifiers?.clickable)
        }
        
        assertNull(row.modifiers?.clickable)
    }

    @Test
    fun `buildTimeline(staleSummary) produces expected dim layout`() {
        val service = OperatorAwarenessTileService()
        val staleSummary = WatchSummary(
            stance = "unknown",
            live = false,
            stale = true,
            presence_decile = null,
            timestamp = null
        )
        val timeline = service.buildTimeline(staleSummary)
        val rootLayout = timeline.timelineEntries[0].layout?.root as LayoutElementBuilders.Row
        
        // Since stale = true, it should wrap the row with opacity 0.5f modifier
        assertNotNull(rootLayout.modifiers?.opacity)
        assertEquals(0.5f, rootLayout.modifiers!!.opacity!!.value)
    }

    @Test
    fun `buildTimeline(freshSummary) produces expected full-opacity layout`() {
        val service = OperatorAwarenessTileService()
        val freshSummary = WatchSummary(
            stance = "speaking",
            live = true,
            stale = false,
            presence_decile = 8,
            timestamp = "2026-04-27T01:00:00Z"
        )
        val timeline = service.buildTimeline(freshSummary)
        val rootLayout = timeline.timelineEntries[0].layout?.root as LayoutElementBuilders.Row
        
        // Since stale = false, no opacity modifier is added
        assertNull(rootLayout.modifiers?.opacity)
    }
}
