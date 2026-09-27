package org.onekash.kashcal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Locks the sizing, resize, category and update attributes in `week_widget_info.xml`.
 *
 * Reads the source XML as text, not through Android resources, so the assertions match the
 * file's literal values; Robolectric returns formatted dimension strings ("250.0dip"). Plain JUnit,
 * no Android runtime needed.
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
    fun `minResizeHeight is 120dp`() {
        // Floor sits below the 250dp default so users can shrink this
        // scrolling LazyColumn widget; the list shows fewer rows.
        assertContainsAttr("minResizeHeight", "120dp")
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
