package org.onekash.kashcal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Locks the declared dimensions in `month_widget_info.xml`.
 *
 * Reads the source XML directly (not via Android resources) so the assertions
 * match the literal text developers see in the file — Robolectric returns
 * formatted dimension strings ("250.0dip") which would make these tests
 * brittle. Plain JUnit, no Android runtime needed.
 *
 * Mirrors the structure of `UpcomingWidgetMetadataTest` so that any future
 * change to either descriptor is caught alongside the others.
 */
class MonthWidgetMetadataTest {

    private val xmlText: String =
        File(resolveProjectRoot(), "app/src/main/res/xml/month_widget_info.xml").readText()

    @Test
    fun `minWidth is 250dp`() {
        assertContainsAttr("minWidth", "250dp")
    }

    @Test
    fun `minHeight is 304dp`() {
        assertContainsAttr("minHeight", "304dp")
    }

    @Test
    fun `minResizeWidth is 170dp`() {
        assertContainsAttr("minResizeWidth", "170dp")
    }

    @Test
    fun `minResizeHeight is 200dp`() {
        assertContainsAttr("minResizeHeight", "200dp")
    }

    /**
     * The month grid divides its height evenly among the week rows (the header
     * and day-of-week row are subtracted first), and each cell must keep at
     * least enough room for its day number or the number clips on-device. Assert
     * that at the declared minResizeHeight floor, a worst-case six-week month
     * still gives every cell at least the day-number block height — mirroring the
     * production cell-height math so a future chrome or block-height increase
     * that would clip the numbers fails here instead of silently on-device.
     */
    @Test
    fun `day numbers fit at minResizeHeight in a six-week month`() {
        val minResize = readDpAttr("minResizeHeight")
        val cellHeight =
            (minResize - MONTH_HEADER_HEIGHT_DP - MONTH_DOW_ROW_HEIGHT_DP).toFloat() /
                MONTH_GRID_WEEK_ROWS
        assertTrue(
            "At minResizeHeight ${minResize}dp a $MONTH_GRID_WEEK_ROWS-week month gives each " +
                "cell only ${cellHeight}dp after chrome, below the ${DAY_NUMBER_BLOCK_HEIGHT_DP}dp " +
                "day-number block; the numbers will clip. Raise minResizeHeight or shrink the chrome.",
            cellHeight >= DAY_NUMBER_BLOCK_HEIGHT_DP
        )
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
        // landscape) refused to resize the widget past the previous 500dp
        // cap. The Glance layout uses fillMaxWidth() and defaultWeight()
        // throughout, so it renders correctly at much wider sizes; the
        // limit was purely metadata. 1100dp ≈ tablet-landscape 8-cell
        // grid using the documented (142n - 15) formula. (issue #225)
        assertContainsAttr("maxResizeWidth", "1100dp")
    }

    @Test
    fun `maxResizeHeight stays 600dp`() {
        assertContainsAttr("maxResizeHeight", "600dp")
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
            "Expected $needle in month_widget_info.xml",
            xmlText.contains(needle)
        )
    }

    /** Parse the integer dp value of an `android:<name>="Ndp"` attribute. */
    private fun readDpAttr(name: String): Int {
        val match = Regex("android:$name=\"(\\d+)dp\"").find(xmlText)
            ?: error("No android:$name dp attribute in month_widget_info.xml")
        return match.groupValues[1].toInt()
    }
}
