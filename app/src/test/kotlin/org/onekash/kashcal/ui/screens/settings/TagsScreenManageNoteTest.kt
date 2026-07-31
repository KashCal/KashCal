package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.category.colorForTag
import org.onekash.kashcal.ui.viewmodels.TagUiItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The tag-management screen carries a short intro at the top plus an info button
 * that reveals the detail inline: that removing a tag here is a local change
 * (events keep their labels) and how tags travel over CalDAV sync. The intro is
 * always visible; the detail is hidden until the info button is tapped. Driven
 * through the public [TagsScreen] — the surface the user taps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class TagsScreenManageNoteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tags = listOf(TagUiItem(name = "Work", color = colorForTag("Work"), hasCustomColor = false))

    private fun render() {
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
    }

    @Test
    fun `the intro is shown and the sync detail is hidden until the info button is tapped`() {
        render()

        // Short intro: always visible.
        composeTestRule.onNodeWithText("Color, rename, or remove your tags", substring = true)
            .assertIsDisplayed()
        // Removal + sync detail: hidden until the info button is tapped.
        composeTestRule.onNodeWithText("your events keep their labels", substring = true)
            .assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("About tags and sync").performClick()

        composeTestRule.onNodeWithText("your events keep their labels", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("some device calendars may not keep them", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the intro shows even when there are no tags yet`() {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                TagsScreen(
                    tags = emptyList(),
                    onNavigateBack = {},
                    onSetColor = { _, _ -> },
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Color, rename, or remove your tags", substring = true)
            .assertIsDisplayed()
    }
}
