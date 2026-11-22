package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.ui.components.pickers.swatchTestTag
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The New calendar sheet: Create stays off for a blank name, a duplicate name or any other failure
 * shows inline and keeps the sheet open, a success reports the new calendar with the trimmed name
 * and the chosen color, and a second tap while the create runs sends nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class NewOfflineCalendarSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tomato = 0xFFFF6347.toInt()

    private data class Created(val id: Long, val name: String, val color: Int)

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun render(
        onCreate: suspend (String, Int) -> Result<Long>,
        onCreated: (Long, String, Int) -> Unit = { _, _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                NewOfflineCalendarSheet(
                    onCreate = onCreate,
                    onCreated = onCreated,
                    onDismiss = onDismiss,
                    initialColor = tomato,
                )
            }
        }
    }

    @Test
    fun `Create stays off while the name is blank`() {
        render(onCreate = { _, _ -> error("not called") })
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Name").performTextInput("   ")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun `it starts on the given color and names it`() {
        render(onCreate = { _, _ -> error("not called") })
        composeTestRule.onNodeWithText("Tomato").assertIsDisplayed()
    }

    @Test
    fun `a duplicate name shows inline and the sheet stays open`() {
        var created: Created? = null
        render(
            onCreate = { _, _ -> Result.failure(LocalCalendarException(LocalCalendarRefusal.DUPLICATE_NAME)) },
            onCreated = { id, name, color -> created = Created(id, name, color) },
        )
        composeTestRule.onNodeWithText("Name").performTextInput("Work ")
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.onNodeWithText("An offline calendar named \"Work\" already exists").assertIsDisplayed()
        composeTestRule.onNodeWithText("New calendar").assertIsDisplayed()
        assertNull(created)
    }

    @Test
    fun `a name too similar to another shows which calendar`() {
        render(
            onCreate = { _, _ ->
                Result.failure(LocalCalendarException(LocalCalendarRefusal.SIMILAR_NAME, conflictingName = "Work"))
            },
        )
        composeTestRule.onNodeWithText("Name").performTextInput("Work!")
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.onNodeWithText("Too similar to \"Work\". Choose a different name.").assertIsDisplayed()
        composeTestRule.onNodeWithText("New calendar").assertIsDisplayed()
    }

    @Test
    fun `any other failure shows the general error`() {
        render(onCreate = { _, _ -> Result.failure(IllegalStateException("disk full")) })
        composeTestRule.onNodeWithText("Name").performTextInput("Work")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.onNodeWithText("Something went wrong. Please try again.").assertIsDisplayed()
    }

    @Test
    fun `a success reports the new calendar with the trimmed name and the color`() {
        var sent: Pair<String, Int>? = null
        var created: Created? = null
        render(
            onCreate = { name, color -> sent = name to color; Result.success(42L) },
            onCreated = { id, name, color -> created = Created(id, name, color) },
        )
        composeTestRule.onNodeWithText("Name").performTextInput("  Trips ")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        assertEquals("Trips" to tomato, sent)
        assertEquals(Created(42L, "Trips", tomato), created)
    }

    @Test
    fun `a second tap while the create runs sends nothing`() {
        val gate = CompletableDeferred<Result<Long>>()
        var calls = 0
        render(onCreate = { _, _ -> calls++; gate.await() })
        composeTestRule.onNodeWithText("Name").performTextInput("Work")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Create").performClick()
        assertEquals(1, calls)

        gate.complete(Result.failure(LocalCalendarException(LocalCalendarRefusal.DUPLICATE_NAME)))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun `the name field is focused once the sheet has opened`() {
        render(onCreate = { _, _ -> error("not called") })
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `keyboard Done creates with the trimmed name`() {
        var sent: String? = null
        render(onCreate = { name, _ -> sent = name; Result.success(5L) })
        composeTestRule.onNodeWithText("Name").performTextInput(" Trips ")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.waitForIdle()
        assertEquals("Trips", sent)
    }

    @Test
    fun `keyboard Done does nothing while the name is blank`() {
        var calls = 0
        render(onCreate = { _, _ -> calls++; Result.success(5L) })
        composeTestRule.onNodeWithText("Name").performTextInput("   ")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.waitForIdle()
        assertEquals(0, calls)
    }

    // ---- Color ----

    private val dodgerBlue = 0xFF1E90FF.toInt()

    private fun openPalette() {
        composeTestRule.onNodeWithText("Color:").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the palette opens inside the sheet`() {
        render(onCreate = { _, _ -> error("not called") })
        composeTestRule.runOnUiThread { enterTouchModeInShownDialogs() }
        val before = composeTestRule.dialogCount()
        openPalette()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        assertEquals(before, composeTestRule.dialogCount())
    }

    @Test
    fun `a picked color closes the palette and is what Create sends`() {
        var sent: Pair<String, Int>? = null
        render(onCreate = { name, color -> sent = name to color; Result.success(7L) })
        openPalette()
        composeTestRule.onNodeWithTag(swatchTestTag(dodgerBlue)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNodeWithText("Dodger Blue").assertIsDisplayed()

        composeTestRule.onNodeWithText("Name").performTextInput("Trips")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Trips" to dodgerBlue, sent)
    }

    @Test
    fun `opening the palette takes focus from the name`() {
        render(onCreate = { _, _ -> error("not called") })
        composeTestRule.runOnUiThread { enterTouchModeInShownDialogs() }
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
        openPalette()
        composeTestRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `every swatch shows at full size in the sheet`() {
        render(onCreate = { _, _ -> error("not called") })
        openPalette()
        EventColorPalette.entries.forEach {
            composeTestRule.onNodeWithTag(swatchTestTag(it.argb)).assertWidthIsEqualTo(56.dp)
        }
    }

    @Test
    fun `a color picked while the create runs doesn't change the reported color`() {
        val gate = CompletableDeferred<Result<Long>>()
        var sent: Int? = null
        var created: Created? = null
        render(
            onCreate = { _, color -> sent = color; gate.await() },
            onCreated = { id, name, color -> created = Created(id, name, color) },
        )
        composeTestRule.onNodeWithText("Name").performTextInput("Trips")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()
        openPalette()
        composeTestRule.onNodeWithTag(swatchTestTag(dodgerBlue)).performClick()
        composeTestRule.waitForIdle()

        gate.complete(Result.success(3L))
        composeTestRule.waitForIdle()
        assertEquals(tomato, sent)
        assertEquals(Created(3L, "Trips", tomato), created)
    }
}
