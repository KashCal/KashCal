package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.category.colorForTag
import org.onekash.kashcal.ui.viewmodels.TagUiItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The rename sheet must run the same name rules as every other tag entry point:
 * a comma (the RFC 5545 CATEGORIES separator) or an over-long name is rejected
 * inline and Save stays disabled, so an invalid rename can't reach the cascade.
 * Driven through the public [TagsScreen] — the surface the user actually taps —
 * rather than the private sheet composable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class TagsScreenRenameValidationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tags = listOf(TagUiItem(name = "Work", color = colorForTag("Work"), hasCustomColor = false))

    private fun renderAndOpenRename(onRename: (String, String) -> Unit = { _, _ -> }) {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                TagsScreen(
                    tags = tags,
                    onNavigateBack = {},
                    onSetColor = { _, _ -> },
                    onRename = onRename,
                    onDelete = {},
                )
            }
        }
        // Open the per-tag action menu, then the Rename sheet.
        composeTestRule.onNodeWithContentDescription("Actions for Work").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()
    }

    @Test
    fun `a comma in the new name is rejected inline and Save stays disabled`() {
        var renamedTo: String? = null
        renderAndOpenRename { _, to -> renamedTo = to }

        val field = composeTestRule.onNodeWithText("Tag name")
        field.performTextClearance()
        field.performTextInput("Work, Play")

        composeTestRule.onNodeWithText("Tags can't contain commas").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        assertNull("an invalid rename must never reach the cascade", renamedTo)
    }

    @Test
    fun `the actions menu offers color rename and delete but not merge`() {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                TagsScreen(
                    tags = tags,
                    onNavigateBack = {},
                    onSetColor = { _, _ -> },
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Actions for Work").performClick()

        composeTestRule.onNodeWithText("Change color").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Merge into…").assertDoesNotExist()
    }

    @Test
    fun `a valid changed name enables Save`() {
        renderAndOpenRename()

        val field = composeTestRule.onNodeWithText("Tag name")
        field.performTextClearance()
        field.performTextInput("Job")

        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `a case-only change is a real edit and reaches the cascade`() {
        var renamedTo: String? = null
        renderAndOpenRename { _, to -> renamedTo = to }

        val field = composeTestRule.onNodeWithText("Tag name")
        field.performTextClearance()
        field.performTextInput("WORK")

        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("recasing must reach the rename cascade", "WORK", renamedTo)
    }
}
