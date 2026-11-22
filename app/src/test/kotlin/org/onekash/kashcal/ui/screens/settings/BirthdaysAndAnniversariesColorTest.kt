package org.onekash.kashcal.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.activityBack
import org.onekash.kashcal.ui.components.dialogCount
import org.onekash.kashcal.ui.components.pickers.swatchTestTag
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The calendar color on the Birthdays & anniversaries screen opens a palette inside its card: a
 * different color is applied and closes it, the current one closes it without a change, Back
 * closes it before leaving the screen, and switching the section off closes it. Run the class
 * alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class BirthdaysAndAnniversariesColorTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val hotPink = 0xFFFF69B4.toInt()
    private val tomato = 0xFFFF6347.toInt()
    private val colorChanges = mutableListOf<Int>()
    private var hostBack = 0

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun render() {
        composeTestRule.setContent {
            var birthdaysOn by remember { mutableStateOf(true) }
            MaterialTheme {
                // Stands in for the settings route's own Back, registered before the screen's.
                BackHandler { hostBack++ }
                BirthdaysAndAnniversariesScreen(
                    birthdaysEnabled = birthdaysOn,
                    birthdaysColor = hotPink,
                    birthdaysReminder = 540,
                    birthdayCount = 3,
                    anniversariesEnabled = false,
                    anniversariesColor = tomato,
                    anniversariesReminder = 540,
                    anniversaryCount = 0,
                    hasPermission = true,
                    timeFormat = "24h",
                    onToggleBirthdays = { birthdaysOn = it },
                    onBirthdaysColorChange = { colorChanges += it },
                    onBirthdaysReminderChange = {},
                    onToggleAnniversaries = {},
                    onAnniversariesColorChange = {},
                    onAnniversariesReminderChange = {},
                    onNavigateBack = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun openPalette() {
        // The row is the first node with the color's name; an open grid repeats it in its footer.
        composeTestRule.onAllNodesWithText("Hot Pink")[0].performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the color row opens the palette in place`() {
        render()
        openPalette()
        composeTestRule.onNodeWithText("More colors").assertIsDisplayed()
        assertEquals(0, composeTestRule.dialogCount())
    }

    @Test
    fun `every swatch shows at full size in the card`() {
        render()
        openPalette()
        EventColorPalette.entries.forEach {
            composeTestRule.onNodeWithTag(swatchTestTag(it.argb)).assertWidthIsEqualTo(56.dp)
        }
    }

    @Test
    fun `a different color is applied and closes the palette`() {
        render()
        openPalette()
        composeTestRule.onNodeWithTag(swatchTestTag(tomato)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(tomato), colorChanges)
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
    }

    @Test
    fun `the current color closes the palette without a change`() {
        render()
        openPalette()
        composeTestRule.onNodeWithTag(swatchTestTag(hotPink)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(colorChanges.isEmpty())
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
    }

    @Test
    fun `Back closes the palette before it leaves the screen`() {
        render()
        openPalette()

        composeTestRule.activityBack()
        assertEquals(0, hostBack)
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()

        composeTestRule.activityBack()
        assertEquals(1, hostBack)
    }

    @Test
    fun `switching the section off closes its palette`() {
        render()
        openPalette()
        val toggle = composeTestRule.onAllNodes(isToggleable())[0]
        toggle.performClick()
        composeTestRule.waitForIdle()
        toggle.performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Hot Pink")[0].assertIsDisplayed()
        composeTestRule.onNodeWithText("More colors").assertDoesNotExist()
    }

    @Test
    fun `Default Reminder still opens its sheet`() {
        render()
        composeTestRule.onAllNodesWithText("Change")[1].performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, composeTestRule.dialogCount())
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h9999dp-mdpi")
    fun `on a narrow screen every swatch stays round and inside the card`() {
        render()
        openPalette()
        EventColorPalette.entries.forEach {
            val bounds = composeTestRule.onNodeWithTag(swatchTestTag(it.argb)).fetchSemanticsNode().boundsInRoot
            assertEquals("swatch ${it.name} is round", bounds.height, bounds.width, 0.5f)
        }
    }
}
