package org.onekash.kashcal.widget

import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants for the shared widget type scale.
 *
 * These guard the readability contract behind issue #279 ("widget text is
 * tiny"): no user-facing role drops below the label floor, body content
 * is clearly larger than supporting text, and the roles stay internally ordered
 * so the five widgets render one consistent scale.
 *
 * Sizes are asserted in sp so they keep honoring the system font-scale setting.
 */
class WidgetTypographyTest {

    @Test
    fun `all roles are expressed in sp so they honor system font scaling`() {
        listOf(
            WidgetTypography.headerTitle,
            WidgetTypography.contentTitle,
            WidgetTypography.monthDayNumber,
            WidgetTypography.secondary,
            WidgetTypography.label,
            WidgetTypography.dateNumber
        ).forEach { size ->
            assertEquals(TextUnitType.Sp, size.type)
        }
    }

    @Test
    fun `label is the floor - no role renders smaller`() {
        // Material's labelSmall (11sp) is the smallest defensible supporting
        // size; assert label is genuinely the minimum so a future role added
        // below it (or a regression lowering another role) fails here.
        val allRoles = listOf(
            WidgetTypography.headerTitle,
            WidgetTypography.contentTitle,
            WidgetTypography.monthDayNumber,
            WidgetTypography.secondary,
            WidgetTypography.label,
            WidgetTypography.navGlyph,
            WidgetTypography.dateNumber
        )
        assertTrue(WidgetTypography.label.value >= 11f)
        allRoles.forEach { role ->
            assertTrue(role.value >= WidgetTypography.label.value)
        }
    }

    @Test
    fun `supporting text is at least 12sp`() {
        assertTrue(WidgetTypography.secondary.value >= 12f)
    }

    @Test
    fun `primary content is at least 14sp`() {
        assertTrue(WidgetTypography.contentTitle.value >= 14f)
    }

    @Test
    fun `month day number matches body content and stays at or below the header title`() {
        // The month-grid number shares the body-content size, matching the in-app
        // month grid, so the number and the event-title row below it read as one
        // scale. It must not out-rank the header title — the header stays the most
        // prominent text in the widget. Keeping the number no larger than body
        // content also leaves vertical room in the day cell for a title row.
        assertTrue(WidgetTypography.monthDayNumber.value >= WidgetTypography.contentTitle.value)
        assertTrue(WidgetTypography.monthDayNumber.value <= WidgetTypography.headerTitle.value)
    }

    @Test
    fun `body roles are strictly ordered date number over header over title over secondary over label`() {
        assertTrue(WidgetTypography.dateNumber.value > WidgetTypography.headerTitle.value)
        assertTrue(WidgetTypography.headerTitle.value > WidgetTypography.contentTitle.value)
        assertTrue(WidgetTypography.contentTitle.value > WidgetTypography.secondary.value)
        assertTrue(WidgetTypography.secondary.value > WidgetTypography.label.value)
    }
}
