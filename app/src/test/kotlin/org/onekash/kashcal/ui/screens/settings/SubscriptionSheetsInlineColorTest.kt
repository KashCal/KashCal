package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.backInLatestDialog
import org.onekash.kashcal.ui.components.dialogCount
import org.onekash.kashcal.ui.components.pickers.swatchTestTag
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The color palette in the Add and Edit subscription sheets opens inside the sheet, a pick reaches
 * Save, the palette and the sync-interval list close each other, and Back closes the palette
 * before it reaches the sheet's own discard step. Run the class alone: Robolectric runs of more
 * than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class SubscriptionSheetsInlineColorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tomato = 0xFFFF6347.toInt()
    private val dodgerBlue = 0xFF1E90FF.toInt()
    private var dismissed = 0
    private var saved: Triple<String, Int, Int>? = null

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun renderAdd() {
        composeTestRule.setContent {
            MaterialTheme {
                AddSubscriptionDialog(onDismiss = { dismissed++ }, onAdd = { _, _, _ -> })
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun renderEdit() {
        composeTestRule.setContent {
            MaterialTheme {
                EditSubscriptionDialog(
                    subscription = IcsSubscriptionUiModel(
                        id = 1, url = "https://feeds.example.test/holidays.ics", name = "Holidays",
                        color = tomato, enabled = true, lastSync = 0, lastError = null,
                        syncIntervalHours = 24,
                    ),
                    onSave = { name, color, hours -> saved = Triple(name, color, hours) },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun openPalette() {
        composeTestRule.onNodeWithText("Color:").performClick()
        composeTestRule.waitForIdle()
    }

    // ---- Add ----

    @Test
    fun `Add opens the palette inside the sheet`() {
        renderAdd()
        val before = composeTestRule.dialogCount()
        openPalette()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        assertEquals(before, composeTestRule.dialogCount())
    }

    @Test
    fun `Add closes the palette on Back and the sheet on the next Back`() {
        renderAdd()
        openPalette()

        composeTestRule.backInLatestDialog()
        assertEquals(0, dismissed)
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNodeWithText("Add Subscription").assertIsDisplayed()

        composeTestRule.backInLatestDialog()
        assertEquals(1, dismissed)
    }

    // ---- Edit ----

    @Test
    fun `Edit opens the palette inside the sheet`() {
        renderEdit()
        val before = composeTestRule.dialogCount()
        openPalette()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        assertEquals(before, composeTestRule.dialogCount())
    }

    @Test
    fun `Edit saves the picked color`() {
        renderEdit()
        openPalette()
        composeTestRule.onNodeWithTag(swatchTestTag(dodgerBlue)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(Triple("Holidays", dodgerBlue, 24), saved)
    }

    @Test
    fun `opening the palette closes the sync interval list`() {
        renderEdit()
        composeTestRule.onNodeWithText("Daily").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Weekly").assertIsDisplayed()

        openPalette()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekly").assertDoesNotExist()
    }

    @Test
    fun `opening the sync interval list closes the palette`() {
        renderEdit()
        openPalette()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()

        composeTestRule.onNodeWithText("Daily").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Weekly").assertIsDisplayed()
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
    }

    @Test
    fun `Edit with a changed color closes the palette on Back, then asks to discard`() {
        renderEdit()
        openPalette()
        composeTestRule.onNodeWithTag(swatchTestTag(dodgerBlue)).performClick()
        composeTestRule.waitForIdle()
        openPalette()

        composeTestRule.backInLatestDialog()
        assertEquals(0, dismissed)
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNodeWithText("Discard").assertDoesNotExist()

        composeTestRule.backInLatestDialog()
        assertEquals(0, dismissed)
        composeTestRule.onNodeWithText("Discard").assertIsDisplayed()

        composeTestRule.backInLatestDialog()
        assertEquals(1, dismissed)
    }
}
