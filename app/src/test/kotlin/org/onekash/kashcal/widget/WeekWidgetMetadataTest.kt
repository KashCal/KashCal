package org.onekash.kashcal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Locks the declared dimensions in `week_widget_info.xml`.
 *
 * Reads the source XML directly (not via Android resources) so the assertions
 * match the literal text developers see in the file — Robolectric returns
 * formatted dimension strings ("250.0dip") which would make these tests
 * brittle. Plain JUnit, no Android runtime needed.
 *
 * Mirrors the structure of `UpcomingWidgetMetadataTest` so that any future
 * change to either descriptor is caught alongside the others.
 */
class WeekWidgetMetadataTest {

    private val xmlText: String =
        File(resolveProjectRoot(), "app/src/main/res/xml/week_widget_info.xml").readText()

    @Test
    fun `minWidth is 250dp`() {
        assertContainsAttr("minWidth", "250dp")
    }

    @Test
    fun `minHeight is 250dp`() {
        assertContainsAttr("minHeight", "250dp")
    }

    @Test
    fun `minResizeWidth is 180dp`() {
        assertContainsAttr("minResizeWidth", "180dp")
    }

    @Test
    fun `minResizeHeight is 180dp`() {
        assertContainsAttr("minResizeHeight", "180dp")
    }

    @Test
    fun `targetCellWidth stays 4`() {
        assertContainsAttr("targetCellWidth", "4")
    }

    @Test
    fun `targetCellHeight stays 4`() {
        assertContainsAttr("targetCellHeight", "4")
    }

    @Test
    fun `maxResizeWidth is 1100dp`() {
        // Lawnchair (and other launchers with wide cell grids on tablet
        // landscape) refused to resize the widget past the previous 400dp
        // cap. The Glance layout uses fillMaxWidth() and defaultWeight()
        // throughout, so it renders correctly at much wider sizes; the
        // limit was purely metadata. 1100dp ≈ tablet-landscape 8-cell
        // grid using the documented (142n - 15) formula. (issue #225)
        assertContainsAttr("maxResizeWidth", "1100dp")
    }

    @Test
    fun `maxResizeHeight stays 500dp`() {
        assertContainsAttr("maxResizeHeight", "500dp")
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
            "Expected $needle in week_widget_info.xml",
            xmlText.contains(needle)
        )
    }
}
