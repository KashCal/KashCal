package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class InlineConfirmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows the message and calls back once per tap`() {
        val taps = mutableListOf<String>()
        composeTestRule.setContent {
            MaterialTheme {
                InlineConfirm(
                    text = "Deletes 2 older copies. This can't be undone.",
                    confirmLabel = "Delete",
                    onCancel = { taps += "cancel" },
                    onConfirm = { taps += "confirm" },
                )
            }
        }
        composeTestRule.onNodeWithText("Deletes 2 older copies. This can't be undone.").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()
        assertEquals(listOf("cancel", "confirm"), taps)
    }
}
