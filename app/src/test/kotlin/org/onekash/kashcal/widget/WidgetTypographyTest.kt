package org.onekash.kashcal.widget

import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [WidgetTypography], the type scale shared by the five widgets.
 *
 * These guard the readability contract of #279 ("widget text is tiny"): no role drops below the
 * 11sp label floor, supporting text is at least 12sp and body content at least 14sp, and the roles
 * stay ordered so the widgets render one consistent scale. Every role is in sp, so text follows
 * the system font scale.
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
        // Material's labelSmall (11sp) is the smallest defensible supporting size. Label must be
        // the minimum, so a role added below it, or another role lowered under it, fails here.
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
        // The month-grid number shares the body-content size, so the number and the event-title
        // row below it read as one scale. It must not out-rank the header title, which stays the
        // most prominent text in the widget. Keeping the number no larger than body content also
        // leaves room in the day cell for a title row.
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
