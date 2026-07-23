package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [SearchableSection] draws a divider *between* emitted groups only —
 * never above the first group or below the last. The decision is owned by the
 * section via the shared [SearchEmissionTracker], read before each section
 * records its own emission.
 *
 * Runs under Robolectric; run in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class SearchableSectionDividerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `two groups both render with a divider between them`() {
        composeTestRule.setContent {
            MaterialTheme {
                val tracker = remember { SearchEmissionTracker() }
                tracker.reset()
                Column {
                    SearchableSection(query = "", header = "First", tracker = tracker) {
                        row(label = "Row A", id = "a") {
                            SettingsRow(label = "Row A", onClick = {}, showDivider = false)
                        }
                    }
                    SearchableSection(query = "", header = "Second", tracker = tracker) {
                        row(label = "Row B", id = "b") {
                            SettingsRow(label = "Row B", onClick = {}, showDivider = false)
                        }
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("First").assertExists()
        composeTestRule.onNodeWithText("Second").assertExists()
        composeTestRule.onNodeWithText("Row A").assertExists()
        composeTestRule.onNodeWithText("Row B").assertExists()
        // Exactly one leading divider — before the second group, none above the first.
        composeTestRule
            .onAllNodesWithTag(SEARCHABLE_SECTION_LEADING_DIVIDER_TAG)
            .assertCountEquals(1)
    }

    @Test
    fun `single emitted group draws no divider`() {
        composeTestRule.setContent {
            MaterialTheme {
                val tracker = remember { SearchEmissionTracker() }
                tracker.reset()
                Column {
                    SearchableSection(query = "", header = "Only", tracker = tracker) {
                        row(label = "Solo", id = "s") {
                            SettingsRow(label = "Solo", onClick = {}, showDivider = false)
                        }
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("Only").assertExists()
        composeTestRule.onNodeWithText("Solo").assertExists()
        // A lone group is the first visible group: no leading divider.
        composeTestRule
            .onAllNodesWithTag(SEARCHABLE_SECTION_LEADING_DIVIDER_TAG)
            .assertCountEquals(0)
    }

    @Test
    fun `first section filtered out by search draws no leading divider on the surviving section`() {
        composeTestRule.setContent {
            MaterialTheme {
                val tracker = remember { SearchEmissionTracker() }
                tracker.reset()
                Column {
                    // Query matches only the second section's row.
                    SearchableSection(query = "beta", header = "First", tracker = tracker) {
                        row(label = "Alpha", id = "a") {
                            SettingsRow(label = "Alpha", onClick = {}, showDivider = false)
                        }
                    }
                    SearchableSection(query = "beta", header = "Second", tracker = tracker) {
                        row(label = "Beta", id = "b") {
                            SettingsRow(label = "Beta", onClick = {}, showDivider = false)
                        }
                    }
                }
            }
        }
        // First section produced no rows, so it never emitted; the surviving
        // section is the first *visible* group and gets no leading divider.
        composeTestRule.onNodeWithText("First").assertDoesNotExist()
        composeTestRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeTestRule.onNodeWithText("Second").assertExists()
        composeTestRule.onNodeWithText("Beta").assertExists()
        // The filtered-out first section never emitted, so the surviving section is
        // the first *visible* group and must not draw a leading divider.
        composeTestRule
            .onAllNodesWithTag(SEARCHABLE_SECTION_LEADING_DIVIDER_TAG)
            .assertCountEquals(0)
    }

    @Test
    fun `a query matching the header surfaces every row in that section`() {
        composeTestRule.setContent {
            MaterialTheme {
                val tracker = remember { SearchEmissionTracker() }
                tracker.reset()
                Column {
                    // "appearance" matches the header, not any row label.
                    SearchableSection(query = "appearance", header = "Appearance", tracker = tracker) {
                        row(label = "Time format", id = "tf") {
                            SettingsRow(label = "Time format", onClick = {}, showDivider = false)
                        }
                        row(label = "Start week on", id = "sw") {
                            SettingsRow(label = "Start week on", onClick = {}, showDivider = false)
                        }
                    }
                }
            }
        }
        // Header match shows the whole group even though no row label matches.
        composeTestRule.onNodeWithText("Appearance").assertExists()
        composeTestRule.onNodeWithText("Time format").assertExists()
        composeTestRule.onNodeWithText("Start week on").assertExists()
    }
}
