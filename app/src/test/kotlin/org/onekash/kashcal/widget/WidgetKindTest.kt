package org.onekash.kashcal.widget

import androidx.glance.appwidget.GlanceAppWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the [WidgetKind] -> [GlanceAppWidget] routing used by [WidgetRefreshAction] to repaint the
 * tapped widget. Exhaustive over the enum so adding a kind without a `widget()` mapping fails here
 * rather than silently repainting the wrong (or no) widget.
 */
class WidgetKindTest {

    @Test
    fun `every widget kind maps to its own widget class`() {
        val expected = mapOf(
            WidgetKind.AGENDA to AgendaWidget::class.java,
            WidgetKind.WEEK to WeekWidget::class.java,
            WidgetKind.UPCOMING to UpcomingWidget::class.java,
        )
        // Fails if a new enum value is added without extending this test (and the `when`).
        assertEquals(expected.keys, WidgetKind.entries.toSet())

        for (kind in WidgetKind.entries) {
            assertEquals(
                "WidgetKind.$kind must repaint its matching widget class",
                expected[kind],
                kind.widget().javaClass,
            )
        }
    }

    @Test
    fun `refresh action kind parameter round-trips through its enum name`() {
        // WidgetRefreshButton passes kind.name; the action resolves it via WidgetKind.valueOf.
        for (kind in WidgetKind.entries) {
            assertEquals(kind, WidgetKind.valueOf(kind.name))
        }
        assertTrue(WidgetKind.entries.isNotEmpty())
    }
}
