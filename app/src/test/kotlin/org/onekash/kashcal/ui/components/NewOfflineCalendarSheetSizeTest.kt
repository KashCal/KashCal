package org.onekash.kashcal.ui.components

import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.Locale

/**
 * How the New calendar sheet sits on a phone-sized screen: open at full height, the title still
 * when an error line appears, and a drag down closes it, with a typed name too, but not while a
 * create runs. Run the class alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class NewOfflineCalendarSheetSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var dismissed = 0

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun render(onCreate: suspend (String, Int) -> Result<Long> = { _, _ -> Result.success(1L) }) {
        composeTestRule.setContent {
            MaterialTheme {
                NewOfflineCalendarSheet(
                    onCreate = onCreate,
                    onCreated = { _, _, _ -> },
                    onDismiss = { dismissed++ },
                    initialColor = 0xFFFF6347.toInt(),
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun titleTop() = composeTestRule.topFraction(composeTestRule.onNodeWithText("New calendar"))

    private fun dragDown() {
        composeTestRule.onNodeWithText("New calendar")
            .performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 200) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `opens at full height`() {
        render()
        assertTrue("title at ${titleTop()}", titleTop() < 0.2f)
    }

    @Test
    fun `an error line leaves the title where it was`() {
        render(onCreate = { _, _ -> Result.failure(LocalCalendarException(LocalCalendarRefusal.DUPLICATE_NAME)) })
        val before = titleTop()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Trips")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("An offline calendar named \"Trips\" already exists").assertExists()
        assertEquals(before, titleTop(), 0.001f)
    }

    @Test
    fun `a drag down closes it`() {
        render()
        dragDown()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a drag down closes it after a name was typed`() {
        render()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Trips")
        dragDown()
        assertEquals(1, dismissed)
    }

    @Test
    fun `while a create runs it doesn't close`() {
        val gate = CompletableDeferred<Result<Long>>()
        render(onCreate = { _, _ -> gate.await() })
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Trips")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        dragDown()

        assertEquals(0, dismissed)
        gate.complete(Result.success(1L))
    }

    @Test
    fun `Back while a create runs keeps the sheet on screen`() {
        val gate = CompletableDeferred<Result<Long>>()
        render(onCreate = { _, _ -> gate.await() })
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Trips")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(0, dismissed)
        composeTestRule.onNodeWithText("New calendar").assertIsDisplayed()
        gate.complete(Result.failure(LocalCalendarException(LocalCalendarRefusal.DUPLICATE_NAME)))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("An offline calendar named \"Trips\" already exists").assertIsDisplayed()
    }

    private fun openPalette() {
        composeTestRule.onNodeWithText("Color:").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("More colors").assertExists()
    }

    @Test
    fun `Back with the palette open closes only the palette`() {
        render()
        openPalette()

        composeTestRule.backInLatestDialog()
        assertEquals(0, dismissed)
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNodeWithText("New calendar").assertIsDisplayed()

        composeTestRule.backInLatestDialog()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a drag down with the palette open leaves the sheet open`() {
        render()
        openPalette()
        dragDown()
        assertEquals(0, dismissed)
        assertTrue("still full, title at ${titleTop()}", titleTop() < 0.2f)
    }
}
