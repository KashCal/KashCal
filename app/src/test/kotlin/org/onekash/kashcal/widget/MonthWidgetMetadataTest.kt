package org.onekash.kashcal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Locks the sizing, resize, category and update attributes in `month_widget_info.xml`.
 *
 * Reads the source XML as text, not through Android resources, so the assertions match the
 * file's literal values; Robolectric returns formatted dimension strings ("250.0dip"). Plain JUnit,
 * no Android runtime needed.
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
     * The month grid splits its height, less the header and day-of-week row, evenly among the
     * week rows, and each cell needs room for its day number or the number clips on the device.
     * At the declared minResizeHeight, a six-week month must still give each cell at least
     * [DAY_NUMBER_BLOCK_HEIGHT_DP]. The test copies the cell-height formula from
     * `MonthWidgetContent.kt` and compares at font scale 1.0, so a taller header, day-of-week row
     * or number block fails here.
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
        // Launchers with wide cell grids in tablet landscape, Lawnchair among them, won't
        // resize a widget past maxResizeWidth (#225). The Glance layout uses fillMaxWidth() and
        // defaultWeight() throughout, so it renders at wider sizes and the cap is metadata
        // only. 1100dp ≈ an 8-cell tablet-landscape grid by the documented (142n - 15) formula.
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

    /** Returns the integer dp value of an `android:<name>="Ndp"` attribute. */
    private fun readDpAttr(name: String): Int {
        val match = Regex("android:$name=\"(\\d+)dp\"").find(xmlText)
            ?: error("No android:$name dp attribute in month_widget_info.xml")
        return match.groupValues[1].toInt()
    }
}
