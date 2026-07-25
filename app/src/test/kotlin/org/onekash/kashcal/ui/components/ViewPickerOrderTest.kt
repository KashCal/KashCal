package org.onekash.kashcal.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the view picker's entry order to shortest-span-first, so a future edit to
 * [viewOptions] cannot silently scramble what the user sees.
 *
 * The order is asserted twice on purpose: once on the list itself, and once on the
 * rendered dropdown by comparing each row's top bound. [viewOptions] feeds both the
 * dropdown here and the navigation drawer's view rows, so the list-level assertion
 * covers the drawer too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class ViewPickerOrderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Ascending by span: one day through a full year. */
    private val expectedOrder = listOf(
        ViewMode.AGENDA,
        ViewMode.DAY,
        ViewMode.THREE_DAYS,
        ViewMode.WEEK,
        ViewMode.MONTH,
        ViewMode.MONTH_FULL,
        ViewMode.YEAR,
    )

    @Test
    fun `view options run from shortest span to longest`() {
        assertEquals(expectedOrder, viewOptions.map { it.mode })
    }

    @Test
    fun `view options offer every selectable view exactly once`() {
        // INSIGHTS is reached from the drawer's own row, not the view picker.
        val selectable = ViewMode.entries.filter { it != ViewMode.INSIGHTS }
        assertEquals(selectable.toSet(), viewOptions.map { it.mode }.toSet())
        assertEquals(viewOptions.size, viewOptions.map { it.mode }.toSet().size)
    }

    @Test
    fun `every view option carries a distinct icon`() {
        val icons = viewOptions.map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
    }

    @Test
    fun `icon lookup resolves by mode rather than list position`() {
        // Guards the reorder: a positional lookup would hand back a neighbour's icon.
        viewOptions.forEach { option ->
            assertEquals(
                "Icon for ${option.mode} must match its own entry",
                option.icon,
                iconForMode(option.mode),
            )
        }
    }

    @Test
    fun `dropdown renders the view entries in shortest-span-first order`() {
        composeTestRule.setContent {
            ViewPickerButton(currentView = ViewMode.MONTH, onViewSelect = {})
        }

        composeTestRule.onNodeWithContentDescription("Calendar view").performClick()

        val labels = listOf("Agenda", "Day", "3 Days", "Week", "Month", "Month (Full)", "Year")
        val tops = labels.map { label ->
            composeTestRule.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.top
        }

        tops.zipWithNext().forEachIndexed { index, (above, below) ->
            assertTrue(
                "${labels[index]} must render above ${labels[index + 1]}",
                above < below,
            )
        }
    }
}
