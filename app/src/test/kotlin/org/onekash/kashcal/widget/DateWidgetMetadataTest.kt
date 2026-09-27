package org.onekash.kashcal.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Locks the sizing, resize, category and update attributes in `date_widget_info.xml`.
 *
 * Reads the source XML as text, not through Android resources, so the assertions match the
 * file's literal values; Robolectric returns formatted dimension strings ("57.0dip"). Plain JUnit,
 * no Android runtime needed.
 *
 * The date widget is size-responsive: it shrinks to a ~1x1 circular icon and grows into a
 * rounded date-card. The dimensions encode that: a small resize floor keeps the icon reachable,
 * a larger resize ceiling lets the card grow past one cell, and a 1x1 default placement lands
 * the widget as the icon, which the user drags larger into the card.
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
        // targetCell drives the default placement and, through the preview-size formula,
        // what the picker composes. 1x1 lands the widget as the icon.
        assertContainsAttr("targetCellWidth", "1")
        assertContainsAttr("targetCellHeight", "1")
    }

    @Test
    fun `resize floor stays 40dp so the icon remains reachable`() {
        // Below the 57dp default size, so the user can shrink the card back to the ~1x1
        // circular icon.
        assertContainsAttr("minResizeWidth", "40dp")
        assertContainsAttr("minResizeHeight", "40dp")
    }

    @Test
    fun `maxResizeWidth lets the card grow well past one cell`() {
        // The Glance layout fills its box, so the ceiling is metadata only. 250dp ≈ a 4-cell
        // width by the documented (70n - 30) formula.
        assertContainsAttr("maxResizeWidth", "250dp")
    }

    @Test
    fun `maxResizeHeight lets the card grow taller than one cell`() {
        // Date-only content doesn't need height; 110dp ≈ 2 cells fits the two-line card.
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
