package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.widget.WidgetColorSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A drag down closes the tag, app accent and widget accent color sheets from the grid, which
 * shows a drag handle, but not from the "More colors" wheel, where a fling picks a color and no
 * handle shows. Run the class alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class ColorSheetSwipeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var dismissed = 0
    private val teal = 0xFF008080.toInt()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun render(sheet: @Composable (onDismiss: () -> Unit) -> Unit) {
        composeTestRule.setContent { MaterialTheme { sheet { dismissed++ } } }
        composeTestRule.waitForIdle()
    }

    private val tagSheet: @Composable (() -> Unit) -> Unit = { onDismiss ->
        ColorPaletteSheet(selectedArgb = teal, onColorSelected = {}, onDismiss = onDismiss)
    }
    private val accentSheet: @Composable (() -> Unit) -> Unit = { onDismiss ->
        AccentColorSheet(
            selectedArgb = teal,
            useDynamic = false,
            onColorSelected = {},
            onUseDynamic = {},
            onDismiss = onDismiss,
        )
    }
    private val widgetAccentSheet: @Composable (() -> Unit) -> Unit = { onDismiss ->
        WidgetAccentColorSheet(
            source = WidgetColorSource.SEED,
            selectedArgb = teal,
            onFollowApp = {},
            onUseDynamic = {},
            onColorSelected = {},
            onDismiss = onDismiss,
        )
    }

    private fun dragDown(node: SemanticsNodeInteraction) {
        node.performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 200) }
        composeTestRule.waitForIdle()
    }

    private fun dragHandle() = composeTestRule.onNodeWithContentDescription("Drag handle")

    private fun assertGridSwipesAway(sheet: @Composable (() -> Unit) -> Unit) {
        render(sheet)
        dragHandle().assertExists()
        dragDown(composeTestRule.onNodeWithText("More colors"))
        assertEquals("a drag down on the grid closes the sheet", 1, dismissed)
    }

    private fun assertWheelStays(sheet: @Composable (() -> Unit) -> Unit) {
        render(sheet)
        composeTestRule.onNodeWithText("More colors").performClick()
        composeTestRule.waitForIdle()
        dragHandle().assertDoesNotExist()
        dragDown(composeTestRule.onNodeWithText("Done"))
        assertEquals("a drag down on the wheel leaves the sheet open", 0, dismissed)
        composeTestRule.onNodeWithText("Done").assertExists()
    }

    @Test
    fun `the tag color grid closes on a drag down`() = assertGridSwipesAway(tagSheet)

    @Test
    fun `the tag color wheel stays on a drag down`() = assertWheelStays(tagSheet)

    @Test
    fun `the app accent grid closes on a drag down`() = assertGridSwipesAway(accentSheet)

    @Test
    fun `the app accent wheel stays on a drag down`() = assertWheelStays(accentSheet)

    @Test
    fun `the widget accent grid closes on a drag down`() = assertGridSwipesAway(widgetAccentSheet)

    @Test
    fun `the widget accent wheel stays on a drag down`() = assertWheelStays(widgetAccentSheet)
}
