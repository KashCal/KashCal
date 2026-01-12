package org.onekash.kashcal.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Compose UI tests verifying recomposition optimization.
 *
 * Tests that memoized calculations (using remember with keys) don't cause
 * unnecessary recalculations when unrelated state changes.
 *
 * These tests verify the pattern used in FlatSettingsContent:
 * - visibleCalendarCount = remember(calendars) { calendars.count { it.isVisible } }
 * - defaultCalendar = remember(calendars, defaultCalendarId) { calendars.find { ... } }
 */
class AccountSettingsScreenRecompositionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Test that visibleCalendarCount is memoized correctly.
     * When unrelated state changes, the calculation should NOT be repeated.
     */
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

            // Memoized calculation - should only run once
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

        // Calculation should only have run once (during initial composition)
        assertEquals(
            "Expected calculation to run only once, but ran $calculationCount times",
            initialCount,
            calculationCount
        )
    }

    /**
     * Test that defaultCalendar find is memoized with correct keys.
     * Should recalculate only when keys change.
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

        // Click unrelated button - should NOT recalculate
        repeat(3) {
            composeTestRule.onNodeWithText("Unrelated:", substring = true).performClick()
            composeTestRule.waitForIdle()
        }

        assertEquals(
            "Unrelated state changes should not trigger recalculation",
            afterInitial,
            calculationCount
        )

        // Click default calendar button - SHOULD recalculate (key changed)
        composeTestRule.onNodeWithText("Default:", substring = true).performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "Key change should trigger exactly one recalculation",
            calculationCount == afterInitial + 1
        )
    }

    /**
     * Test that calculations only happen when key reference changes.
     * Same values in a new list instance should trigger recalculation
     * (reference equality, not value equality).
     */
    @Test
    fun memoization_usesReferenceEquality() {
        var calculationCount = 0

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
                calculationCount++
                calendars.count { it.isVisible }
            }

            androidx.compose.material3.Button(
                onClick = {
                    // Create new list with same values - should trigger recalc
                    calendars = listOf(
                        createTestCalendar(1, isVisible = true),
                        createTestCalendar(2, isVisible = true)
                    )
                }
            ) {
                androidx.compose.material3.Text("Visible: $visibleCount")
            }
        }

        composeTestRule.waitForIdle()
        val initialCount = calculationCount

        // Click to create new list reference
        composeTestRule.onNodeWithText("Visible:", substring = true).performClick()
        composeTestRule.waitForIdle()

        // Should recalculate because list reference changed
        assertTrue(
            "New list reference should trigger recalculation",
            calculationCount > initialCount
        )
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
