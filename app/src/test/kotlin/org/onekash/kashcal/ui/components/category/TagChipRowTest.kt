package org.onekash.kashcal.ui.components.category

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [TagChipRow] (Robolectric, unit-test source set).
 * Verifies the collapsed/engaged contract: resting affordances, chip removal,
 * type-to-filter picker, "Create" commit + validation error, and read-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class TagChipRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun resting_empty_shows_add_tag_affordance() {
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = listOf("Personal"),
                    onToggle = {},
                    onAdd = {},
                )
            }
        }
        // At rest with no tags, only the add affordance shows — suggestions
        // stay hidden until the row is engaged.
        composeTestRule.onNodeWithText("New tag").assertIsDisplayed()
        assertEquals(0, composeTestRule.countNodesWithText("Personal"))
    }

    @Test
    fun resting_with_tags_shows_applied_chips() {
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = setOf("Work"),
                    suggestions = listOf("Personal"),
                    onToggle = {},
                    onAdd = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Work").assertIsDisplayed()
        // A resting suggestion is not shown as a chip.
        assertEquals(0, composeTestRule.countNodesWithText("Personal"))
    }

    @Test
    fun tapping_chip_invokes_onToggle_to_remove() {
        var toggled: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = setOf("Work"),
                    suggestions = emptyList(),
                    onToggle = { toggled = it },
                    onAdd = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Work").performClick()
        assertEquals("Work", toggled)
    }

    @Test
    fun engaging_reveals_field_and_shows_suggestions() {
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = listOf("Personal"),
                    onToggle = {},
                    onAdd = {},
                )
            }
        }
        composeTestRule.onNodeWithText("New tag").performClick()
        // Field revealed and the suggestion now appears in the picker.
        composeTestRule.onNodeWithText("Personal").assertIsDisplayed()
    }

    @Test
    fun typing_filters_suggestions_by_prefix() {
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = listOf("Family", "Fasting", "Work"),
                    onToggle = {},
                    onAdd = {},
                )
            }
        }
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("New tag").performTextInput("fa")
        // Prefix "fa" keeps Family + Fasting, drops Work.
        composeTestRule.onNodeWithText("Family").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fasting").assertIsDisplayed()
        assertEquals(0, composeTestRule.countNodesWithText("Work"))
    }

    @Test
    fun tapping_suggestion_in_picker_invokes_onToggle() {
        var toggled: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = listOf("Personal"),
                    onToggle = { toggled = it },
                    onAdd = {},
                )
            }
        }
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("Personal").performClick()
        assertEquals("Personal", toggled)
    }

    @Test
    fun create_row_commits_a_new_tag() {
        var added: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = emptyList(),
                    onToggle = {},
                    onAdd = { added = it },
                )
            }
        }
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("New tag").performTextInput("Travel")
        // The "Create" row is offered for the typed name; tap it to commit.
        composeTestRule.onNodeWithText("Create \"Travel\"").performClick()
        assertEquals("Travel", added)
    }

    @Test
    fun done_action_commits_a_valid_typed_tag() {
        var added: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = emptyList(),
                    onToggle = {},
                    onAdd = { added = it },
                )
            }
        }
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("New tag").performTextInput("Travel")
        composeTestRule.onNodeWithText("Travel").performImeAction()
        assertEquals("Travel", added)
    }

    @Test
    fun comma_name_shows_inline_error_and_does_not_commit() {
        var added: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = emptyList(),
                    onToggle = {},
                    onAdd = { added = it },
                )
            }
        }
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("New tag").performTextInput("foo,bar")
        composeTestRule.onNodeWithText("foo,bar").performImeAction()
        composeTestRule.onNodeWithText("Tags can't contain commas").assertIsDisplayed()
        assertEquals(null, added)
    }

    @Test
    fun empty_user_engaged_shows_only_create_as_you_type() {
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = emptySet(),
                    suggestions = emptyList(),
                    onToggle = {},
                    onAdd = {},
                )
            }
        }
        composeTestRule.onNodeWithText("New tag").performClick()
        composeTestRule.onNodeWithText("New tag").performTextInput("gym")
        // With no suggestions, only the Create row shows — never a bare empty
        // dropdown.
        composeTestRule.onNodeWithText("Create \"gym\"").assertIsDisplayed()
    }

    @Test
    fun readOnly_hides_add_affordance() {
        composeTestRule.setContent {
            MaterialTheme {
                TagChipRow(
                    selected = setOf("Work"),
                    suggestions = emptyList(),
                    onToggle = {},
                    onAdd = {},
                    readOnly = true,
                )
            }
        }
        composeTestRule.onNodeWithText("Work").assertIsDisplayed()
        assertTrue(composeTestRule.countNodesWithText("New tag") == 0)
    }
}

/** Count matching nodes without throwing when there are none. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.countNodesWithText(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
