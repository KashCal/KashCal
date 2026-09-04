package org.onekash.kashcal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Locks the declared dimensions in `date_widget_info.xml`.
 *
 * Reads the source XML directly (not via Android resources) so the assertions
 * match the literal text developers see in the file — Robolectric returns
 * formatted dimension strings ("57.0dip") which would make these tests brittle.
 * Plain JUnit, no Android runtime needed.
 *
 * Mirrors the structure of `MonthWidgetMetadataTest` so any future change to
 * either descriptor is caught alongside the others.
 *
 * The date widget is size-responsive: it shrinks to a ~1x1 circular icon and
 * grows into a rounded date-card. The dimensions below encode that — a small
 * resize floor (the icon stays reachable) with a much larger resize ceiling
 * (the card can grow well past one cell), and a 1x1 default placement so the
 * widget lands as the familiar icon; the user grows it into the card.
 */
class DateWidgetMetadataTest {

    private val xmlText: String =
        File(resolveProjectRoot(), "app/src/main/res/xml/date_widget_info.xml").readText()

    @Test
    fun `minWidth is 57dp`() {
        assertContainsAttr("minWidth", "57dp")
    }

    @Test
    fun `minHeight is 57dp`() {
        assertContainsAttr("minHeight", "57dp")
    }

    @Test
    fun `default placement is the 1x1 icon`() {
        // targetCell drives the default placement (and, via the preview-size
        // formula, what the picker composes) — 1x1 lands the widget as the
        // familiar icon; the user grows it into the card by dragging larger.
        assertContainsAttr("targetCellWidth", "1")
        assertContainsAttr("targetCellHeight", "1")
    }

    @Test
    fun `resize floor stays 40dp so the icon remains reachable`() {
        // Below the 57dp default cell — this is what lets the user shrink the
        // card back to the ~1x1 circular icon.
        assertContainsAttr("minResizeWidth", "40dp")
        assertContainsAttr("minResizeHeight", "40dp")
    }

    @Test
    fun `maxResizeWidth lets the card grow well past one cell`() {
        // The old 80dp cap kept the widget locked to ~1 cell. The Glance layout
        // fills its box, so growing the ceiling is purely metadata. 250dp ≈ a
        // 4-cell width using the documented (70n - 30) formula.
        assertContainsAttr("maxResizeWidth", "250dp")
    }

    @Test
    fun `maxResizeHeight lets the card grow taller than one cell`() {
        // Date-only content doesn't need to be tall; 110dp ≈ 2 cells is enough
        // room for the two-line card while still lifting the old 80dp cap.
        assertContainsAttr("maxResizeHeight", "110dp")
    }

    @Test
    fun `resizeMode stays horizontal vertical`() {
        assertContainsAttr("resizeMode", "horizontal|vertical")
    }

    @Test
    fun `widgetCategory stays home_screen`() {
        assertContainsAttr("widgetCategory", "home_screen")
    }

    @Test
    fun `updatePeriodMillis stays 1800000`() {
        assertContainsAttr("updatePeriodMillis", "1800000")
    }

    private fun assertContainsAttr(name: String, value: String) {
        val needle = "android:$name=\"$value\""
        assertTrue(
            "Expected $needle in date_widget_info.xml",
            xmlText.contains(needle)
        )
    }
}
