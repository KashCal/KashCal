package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.category.colorForTag
import org.onekash.kashcal.ui.viewmodels.TagUiItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A tag's menu on the Tags screen offers "Show events", which passes the tag's name on, and
 * leaves it out for a name no search text can hold or when the caller gives no handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class TagsScreenShowEventsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(name: String, onShowEvents: ((String) -> Unit)?) {
        Locale.setDefault(Locale.US)
        val tags = listOf(TagUiItem(name = name, color = colorForTag(name), hasCustomColor = false))
        composeTestRule.setContent {
            MaterialTheme {
                TagsScreen(
                    tags = tags,
                    onNavigateBack = {},
                    onSetColor = { _, _ -> },
                    onRename = { _, _ -> },
                    onDelete = {},
                    onShowEvents = onShowEvents,
                )
            }
        }
        composeTestRule.onNodeWithText(name).performClick()
    }

    @Test
    fun `Show events passes the tag name on`() {
        val shown = mutableListOf<String>()
        render("Team meeting") { shown += it }

        composeTestRule.onNodeWithText("Show events").assertIsDisplayed().performClick()

        assertEquals(listOf("Team meeting"), shown)
    }

    @Test
    fun `a name no search text can hold has no Show events`() {
        render("x\"b") {}

        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText("Show events").assertDoesNotExist()
    }

    @Test
    fun `without a handler there is no Show events`() {
        render("Work", onShowEvents = null)

        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText("Show events").assertDoesNotExist()
    }
}
