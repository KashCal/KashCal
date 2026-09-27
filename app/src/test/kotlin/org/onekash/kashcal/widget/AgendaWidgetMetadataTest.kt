package org.onekash.kashcal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Locks the sizing, resize, category and update attributes in `agenda_widget_info.xml`.
 *
 * Reads the source XML as text, not through Android resources, so the assertions match the
 * file's literal values; Robolectric returns formatted dimension strings ("250.0dip"). Plain JUnit,
 * no Android runtime needed.
 */
class AgendaWidgetMetadataTest {

    private val xmlText: String =
        File(resolveProjectRoot(), "app/src/main/res/xml/agenda_widget_info.xml").readText()

    @Test
    fun `minWidth is 250dp`() {
        assertContainsAttr("minWidth", "250dp")
    }

    @Test
    fun `minHeight is 110dp`() {
        assertContainsAttr("minHeight", "110dp")
    }

    @Test
    fun `minResizeWidth is 180dp`() {
        assertContainsAttr("minResizeWidth", "180dp")
    }

    @Test
    fun `minResizeHeight is 80dp`() {
        assertContainsAttr("minResizeHeight", "80dp")
    }

    @Test
    fun `targetCellWidth stays 4`() {
        assertContainsAttr("targetCellWidth", "4")
    }

    @Test
    fun `targetCellHeight stays 2`() {
        assertContainsAttr("targetCellHeight", "2")
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
    fun `maxResizeHeight stays 300dp`() {
        assertContainsAttr("maxResizeHeight", "300dp")
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
            "Expected $needle in agenda_widget_info.xml",
            xmlText.contains(needle)
        )
    }
}
