package org.onekash.kashcal.ui.components.pickers

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.activityBack
import org.onekash.kashcal.ui.components.backInLatestDialog
import org.onekash.kashcal.ui.components.enterTouchMode
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The inline palette: the grid and the wheel swap inside one panel, a pick or Done reports the
 * color, Back steps from the wheel to the grid and then closes the panel, and the tag color sheet
 * built on the same grid and wheel still closes on Back. Run the class alone: Robolectric runs of
 * more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class InlineColorPaletteTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val tomato = 0xFFFF6347.toInt()
    private val dodgerBlue = 0xFF1E90FF.toInt()
    private val picked = mutableListOf<Int?>()
    private var collapsed = 0

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun renderPanel(selected: Int = tomato, expanded: Boolean = true) {
        composeTestRule.setContent {
            MaterialTheme {
                ColorPalettePanel(
                    selectedArgb = selected,
                    expanded = expanded,
                    onColorSelected = { picked += it },
                    onCollapse = { collapsed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun swatch(argb: Int) = composeTestRule.onNodeWithTag(swatchTestTag(argb))

    // ---- Panel ----

    @Test
    fun `a swatch tap reports that color`() {
        renderPanel()
        swatch(dodgerBlue).performClick()
        assertEquals(listOf<Int?>(dodgerBlue), picked)
    }

    @Test
    fun `More colors swaps the grid for the wheel and Done reports the wheel color`() {
        renderPanel(selected = tomato)
        composeTestRule.onNodeWithText("More colors").performClick()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").performClick()
        assertEquals(listOf<Int?>(EventColorPalette.entryForArgbOrDefault(tomato).argb), picked)
    }

    @Test
    fun `the wheel's Back button returns to the grid without reporting`() {
        renderPanel()
        composeTestRule.onNodeWithText("More colors").performClick()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `system Back steps from the wheel to the grid and then closes the panel`() {
        renderPanel()
        composeTestRule.onNodeWithText("More colors").performClick()

        composeTestRule.activityBack()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        assertEquals(0, collapsed)

        composeTestRule.activityBack()
        assertEquals(1, collapsed)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `a closed panel leaves Back to the host`() {
        var hostBack = 0
        // Registered before the panel composes, so an enabled panel handler would win over it.
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.addCallback(
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { hostBack++ }
                },
            )
        }
        renderPanel(expanded = false)
        composeTestRule.activityBack()
        assertEquals(1, hostBack)
        assertEquals(0, collapsed)
    }

    @Test
    fun `the wheel survives a recreation`() {
        val restorer = StateRestorationTester(composeTestRule)
        restorer.setContent {
            MaterialTheme {
                ColorPalettePanel(selectedArgb = tomato, expanded = true, onColorSelected = {}, onCollapse = {})
            }
        }
        composeTestRule.onNodeWithText("More colors").performClick()
        restorer.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `every swatch fits a 296dp host at full size`() {
        composeTestRule.setContent {
            MaterialTheme {
                Column(Modifier.width(296.dp)) {
                    ColorPalettePanel(selectedArgb = tomato, expanded = true, onColorSelected = {}, onCollapse = {})
                }
            }
        }
        EventColorPalette.entries.forEach { swatch(it.argb).assertWidthIsEqualTo(56.dp) }
    }

    // ---- Event panel ----

    @Test
    fun `the event panel's first cell is the calendar default and reports null`() {
        composeTestRule.setContent {
            MaterialTheme {
                Column(Modifier.width(296.dp)) {
                    EventColorPalettePanel(
                        selectedArgb = dodgerBlue,
                        calendarDefaultArgb = tomato,
                        expanded = true,
                        onColorSelected = { picked += it },
                        onCollapse = { collapsed++ },
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag(DEFAULT_SWATCH_TEST_TAG).assertWidthIsEqualTo(56.dp)
        EventColorPalette.entries.drop(1).forEach { swatch(it.argb).assertWidthIsEqualTo(56.dp) }
        composeTestRule.onNodeWithTag(DEFAULT_SWATCH_TEST_TAG).performClick()
        assertEquals(listOf<Int?>(null), picked)
    }

    // ---- Field ----

    private fun renderField(start: Int = tomato) {
        composeTestRule.setContent {
            MaterialTheme {
                var color by rememberSaveable { mutableIntStateOf(start) }
                var expanded by rememberSaveable { mutableStateOf(false) }
                Column {
                    OutlinedTextField(value = "", onValueChange = {}, label = { Text("Name") })
                    InlineColorField(
                        selectedArgb = color,
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        onColorSelected = { color = it; picked += it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun header() = composeTestRule.onNodeWithText("Color:")

    private fun state(expected: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    @Test
    fun `the header opens and closes the palette and says which`() {
        renderField()
        composeTestRule.onNode(state("collapsed"), useUnmergedTree = false).assertExists()
        header().performClick()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        composeTestRule.onNode(state("expanded")).assertExists()
        header().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNode(state("collapsed")).assertExists()
    }

    @Test
    fun `opening the palette takes focus from the text field`() {
        renderField()
        composeTestRule.runOnUiThread { enterTouchMode(composeTestRule.activity.window.decorView) }
        val field = composeTestRule.onNode(hasSetTextAction())
        field.performClick()
        field.assertIsFocused()
        header().performClick()
        composeTestRule.waitForIdle()
        field.assertIsNotFocused()
    }

    @Test
    fun `a pick sets the color and closes the palette`() {
        renderField()
        header().performClick()
        swatch(dodgerBlue).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf<Int?>(dodgerBlue), picked)
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
        composeTestRule.onNodeWithText("Dodger Blue").assertIsDisplayed()
    }

    @Test
    fun `closing from the wheel drops its choice and the next opening shows the grid`() {
        renderField()
        header().performClick()
        composeTestRule.onNodeWithText("More colors").performClick()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        header().performClick()
        composeTestRule.waitForIdle()
        header().performClick()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `outside touch mode opening the palette leaves focus where it is`() {
        // A keyboard or D-pad user: clearing focus here would move it to the first field.
        composeTestRule.setContent {
            MaterialTheme {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Column {
                    OutlinedTextField(value = "", onValueChange = {}, label = { Text("First") })
                    OutlinedTextField(value = "", onValueChange = {}, label = { Text("Second") })
                    InlineColorField(
                        selectedArgb = tomato,
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        onColorSelected = {},
                    )
                }
            }
        }
        val first = composeTestRule.onNode(hasSetTextAction() and hasText("First"))
        val second = composeTestRule.onNode(hasSetTextAction() and hasText("Second"))
        second.performClick()
        second.assertIsFocused()
        header().performClick()
        composeTestRule.waitForIdle()
        first.assertIsNotFocused()
    }

    @Test
    fun `a panel restored after recreation keeps the host's scroll position`() {
        val restorer = StateRestorationTester(composeTestRule)
        lateinit var scroll: ScrollState
        restorer.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Column(Modifier.height(300.dp).verticalScroll(scroll)) {
                    Spacer(Modifier.height(2000.dp))
                    ColorPalettePanel(selectedArgb = tomato, expanded = true, onColorSelected = {}, onCollapse = {})
                }
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()
        assertTrue("opening brings the panel into view", scroll.value > 0)

        composeTestRule.runOnIdle { runBlocking { scroll.scrollTo(0) } }
        restorer.emulateSavedInstanceStateRestore()
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.waitForIdle()
        assertEquals(0, scroll.value)
    }

    // ---- The sheet on the same grid and wheel ----

    @Test
    fun `the palette sheet still closes on Back with the wheel open`() {
        var dismissed = 0
        composeTestRule.setContent {
            MaterialTheme {
                ColorPaletteSheet(selectedArgb = tomato, onColorSelected = { picked += it }, onDismiss = { dismissed++ })
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("More colors").performClick()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.backInLatestDialog()
        assertEquals(1, dismissed)
        assertTrue(picked.isEmpty())
    }
}
