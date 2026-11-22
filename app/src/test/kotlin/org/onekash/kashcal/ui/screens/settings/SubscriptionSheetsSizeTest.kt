package org.onekash.kashcal.ui.screens.settings

import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.topFraction
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.Locale

/**
 * How the Add and Edit subscription sheets sit on a phone-sized screen: open at full height, and
 * a drag down closes them only while nothing has changed. After an edit a drag leaves the sheet
 * open, and Back first turns Cancel into Discard, then closes. Run the class alone: Robolectric
 * runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class SubscriptionSheetsSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var dismissed = 0

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun renderAdd(initialUrl: String? = null) {
        composeTestRule.setContent {
            MaterialTheme {
                AddSubscriptionDialog(initialUrl = initialUrl, onDismiss = { dismissed++ }, onAdd = { _, _, _ -> })
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
                        color = 0xFFFF6347.toInt(), enabled = true, lastSync = 0, lastError = null,
                        syncIntervalHours = 24,
                    ),
                    onSave = { _, _, _ -> },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun dragDown(title: String) {
        composeTestRule.onNodeWithText(title)
            .performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 200) }
        composeTestRule.waitForIdle()
    }

    private fun back() {
        // The sheet is its own dialog window; its back dispatcher is what a system back reaches.
        composeTestRule.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
    }

    private fun titleTop(title: String) = composeTestRule.topFraction(composeTestRule.onNodeWithText(title))

    // ---- Add ----

    @Test
    fun `Add subscription opens at full height`() {
        renderAdd()
        assertTrue("title at ${titleTop(ADD)}", titleTop(ADD) < 0.2f)
    }

    @Test
    fun `an unchanged Add sheet closes on a drag down`() {
        renderAdd()
        dragDown(ADD)
        assertEquals(1, dismissed)
    }

    @Test
    fun `an Add sheet opened from a link closes on a drag down while the link is untouched`() {
        renderAdd(initialUrl = "webcal://feeds.example.test/holidays.ics")
        dragDown(ADD)
        assertEquals(1, dismissed)
    }

    @Test
    fun `after typing a URL a drag leaves it open and Back asks to discard`() {
        renderAdd()
        composeTestRule.onNode(hasSetTextAction() and hasText("Calendar URL")).performTextInput("https://feeds.example.test/a.ics")

        dragDown(ADD)
        assertEquals(0, dismissed)
        composeTestRule.onNodeWithText(ADD).assertExists()
        assertTrue("still full, title at ${titleTop(ADD)}", titleTop(ADD) < 0.2f)

        back()
        assertEquals(0, dismissed)
        // On screen, not just composed: a sheet that slid away still holds its nodes.
        composeTestRule.onNodeWithText(ADD).assertIsDisplayed()
        composeTestRule.onNodeWithText("Discard").assertIsDisplayed()

        back()
        assertEquals(1, dismissed)
    }

    @Test
    fun `after typing a URL a tap outside asks to discard and keeps the sheet on screen`() {
        renderAdd()
        composeTestRule.onNode(hasSetTextAction() and hasText("Calendar URL")).performTextInput("https://feeds.example.test/a.ics")

        // The scrim shows above a full-height sheet's top edge, under the status bar, in the
        // sheet's own window: the tallest root.
        val roots = composeTestRule.onAllNodes(isRoot())
        val sheetWindow = roots.fetchSemanticsNodes().withIndex().maxBy { it.value.size.height }.index
        roots[sheetWindow].performTouchInput { click(Offset(centerX, 1f)) }
        composeTestRule.waitForIdle()

        assertEquals(0, dismissed)
        composeTestRule.onNodeWithText(ADD).assertIsDisplayed()
        composeTestRule.onNodeWithText("Discard").assertIsDisplayed()
    }

    // ---- Edit ----

    @Test
    fun `Edit subscription opens at full height`() {
        renderEdit()
        assertTrue("title at ${titleTop(EDIT)}", titleTop(EDIT) < 0.2f)
    }

    @Test
    fun `an unchanged Edit sheet closes on a drag down`() {
        renderEdit()
        dragDown(EDIT)
        assertEquals(1, dismissed)
    }

    @Test
    fun `after renaming a drag leaves the Edit sheet open and Back asks to discard`() {
        renderEdit()
        val field = composeTestRule.onNode(hasSetTextAction() and hasText("Holidays"))
        field.performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Days off")

        dragDown(EDIT)
        assertEquals(0, dismissed)

        back()
        composeTestRule.onNodeWithText("Discard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discard").performClick()
        assertEquals(1, dismissed)
    }

    private companion object {
        const val ADD = "Add Subscription"
        const val EDIT = "Edit Subscription"
    }

    @Test
    fun `with the palette open a drag down leaves an unchanged Add sheet open`() {
        renderAdd()
        composeTestRule.onNodeWithText("Color:").performClick()
        composeTestRule.waitForIdle()
        dragDown(ADD)
        assertEquals(0, dismissed)
    }

    @Test
    fun `with the palette open a drag down leaves an unchanged Edit sheet open`() {
        renderEdit()
        composeTestRule.onNodeWithText("Color:").performClick()
        composeTestRule.waitForIdle()
        dragDown(EDIT)
        assertEquals(0, dismissed)
    }
}
