package org.onekash.kashcal.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Tests keyed `remember` on standalone content: the block reruns when a key changes and not when
 * unrelated state does.
 *
 * AccountSettingsScreen memoizes lookups over its calendar list this way (`defaultCalendarName`,
 * `localCalendar`), but these tests don't render that screen.
 */
@RunWith(AndroidJUnit4::class)
class AccountSettingsScreenRecompositionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Tests that a block keyed on the calendar list doesn't rerun when unrelated state changes. */
    @Test
    fun visibleCalendarCount_notRecomputed_whenUnrelatedStateChanges() {
        var calculationCount = 0

        composeTestRule.setContent {
            // Simulated unrelated state that changes
            var counter by remember { mutableIntStateOf(0) }

            // Static calendars list
            val calendars = remember {
                listOf(
                    createTestCalendar(1, isVisible = true),
                    createTestCalendar(2, isVisible = false),
                    createTestCalendar(3, isVisible = true)
                )
            }

            // Runs once, on the initial composition.
            val visibleCount = remember(calendars) {
                calculationCount++
                calendars.count { it.isVisible }
            }

            // UI that triggers recomposition
            androidx.compose.material3.Button(onClick = { counter++ }) {
                androidx.compose.material3.Text("Count: $counter, Visible: $visibleCount")
            }
        }

        // Initial composition
        composeTestRule.waitForIdle()
        val initialCount = calculationCount

        // Trigger multiple recompositions by clicking button
        repeat(5) {
            composeTestRule.onNodeWithText("Count:", substring = true).performClick()
            composeTestRule.waitForIdle()
        }

        // No reruns after the initial composition.
        assertEquals(
            "Expected calculation to run only once, but ran $calculationCount times",
            initialCount,
            calculationCount
        )
    }

    /**
     * Tests that a block keyed on the list and a default calendar id reruns once when the id
     * changes and not when unrelated state does.
     */
    @Test
    fun defaultCalendar_recomputed_onlyWhenKeysChange() {
        var calculationCount = 0

        composeTestRule.setContent {
            var unrelatedState by remember { mutableIntStateOf(0) }
            var defaultCalendarId by remember { mutableStateOf<Long?>(1L) }

            val calendars = remember {
                listOf(
                    createTestCalendar(1, displayName = "Work"),
                    createTestCalendar(2, displayName = "Personal")
                )
            }

            val defaultCalendar = remember(calendars, defaultCalendarId) {
                calculationCount++
                calendars.find { it.id == defaultCalendarId }
            }

            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Button(onClick = { unrelatedState++ }) {
                    androidx.compose.material3.Text("Unrelated: $unrelatedState")
                }
                androidx.compose.material3.Button(onClick = { defaultCalendarId = 2L }) {
                    androidx.compose.material3.Text("Default: ${defaultCalendar?.displayName}")
                }
            }
        }

        composeTestRule.waitForIdle()
        val afterInitial = calculationCount

        // The unrelated button doesn't change a key, so no rerun.
        repeat(3) {
            composeTestRule.onNodeWithText("Unrelated:", substring = true).performClick()
            composeTestRule.waitForIdle()
        }

        assertEquals(
            "Unrelated state changes should not trigger recalculation",
            afterInitial,
            calculationCount
        )

        // The default calendar button changes a key, so one rerun.
        composeTestRule.onNodeWithText("Default:", substring = true).performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "Key change should trigger exactly one recalculation",
            calculationCount == afterInitial + 1
        )
    }

    /**
     * Tests that assigning a new calendar list reruns the keyed block, so the rendered count goes
     * from 2 to 1. It checks the rendered text, not recomposition timing (which is unreliable on
     * slow CI emulators).
     */
    @Test
    fun memoization_usesReferenceEquality() {
        composeTestRule.setContent {
            var calendars by remember {
                mutableStateOf(
                    listOf(
                        createTestCalendar(1, isVisible = true),
                        createTestCalendar(2, isVisible = true)
                    )
                )
            }

            val visibleCount = remember(calendars) {
                calendars.count { it.isVisible }
            }

            androidx.compose.material3.Button(
                onClick = {
                    // A new list with one hidden calendar, so the count drops to 1.
                    calendars = listOf(
                        createTestCalendar(1, isVisible = true),
                        createTestCalendar(2, isVisible = false)
                    )
                }
            ) {
                androidx.compose.material3.Text("Visible: $visibleCount")
            }
        }

        // Initially should show 2 visible
        assert(composeTestRule.onAllNodesWithText("Visible: 2").fetchSemanticsNodes().isNotEmpty()) {
            "Expected 'Visible: 2' to exist initially"
        }

        // Click to change list
        composeTestRule.onNodeWithText("Visible: 2").performClick()
        composeTestRule.waitForIdle()

        // "Visible: 1" shows the keyed block reran.
        assert(composeTestRule.onAllNodesWithText("Visible: 1").fetchSemanticsNodes().isNotEmpty()) {
            "Expected 'Visible: 1' after clicking button"
        }
    }

    private fun createTestCalendar(
        id: Long,
        displayName: String = "Calendar $id",
        isVisible: Boolean = true
    ) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = "https://test.com/$id",
        displayName = displayName,
        color = 0xFF0000FF.toInt(),
        isVisible = isVisible
    )
}
