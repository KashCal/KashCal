package org.onekash.kashcal.widget

import androidx.glance.appwidget.GlanceAppWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the [WidgetKind] to [GlanceAppWidget] routing [WidgetRefreshAction] uses to repaint the
 * tapped widget. The compiler forces a `widget()` branch for every kind; this test checks each
 * branch returns that kind's widget class, so a wrong mapping can't silently repaint another.
 */
class WidgetKindTest {

    @Test
    fun `every widget kind maps to its own widget class`() {
        val expected = mapOf(
            WidgetKind.AGENDA to AgendaWidget::class.java,
            WidgetKind.WEEK to WeekWidget::class.java,
            WidgetKind.UPCOMING to UpcomingWidget::class.java,
        )
        // Fails if a new enum value is added without extending this map.
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
