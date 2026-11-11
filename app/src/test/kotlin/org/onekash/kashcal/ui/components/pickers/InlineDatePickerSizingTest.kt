package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Compose tests that [InlineDatePickerContent], the month grid of the home-screen, search and
 * repeat-end date pickers, keeps its size and look: 32dp day cells, a 288dp grid, 32dp arrows with
 * 48dp touch areas, and the selected day read as selected. The date and time sheet has its own
 * grid, covered by DateTimeSheetGridTest. Run the class alone: Robolectric runs of more than
 * one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h914dp-mdpi")
class InlineDatePickerSizingTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()
    private val savedZone = TimeZone.getDefault()

    @Before
    fun pin() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restore() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private fun millis(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun render(
        selected: LocalDate = LocalDate.of(2026, 10, 27),
        shown: YearMonth = YearMonth.of(2026, 10),
    ) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.testTag("grid")) {
                    InlineDatePickerContent(
                        selectedDateMillis = millis(selected),
                        displayedMonth = shown,
                        onDateSelect = {},
                        onMonthChange = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun gridHeight(): Dp = rule.onNodeWithTag("grid").getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun height(text: String): Dp = rule.onNodeWithText(text).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun `the default call keeps today's day cell and grid height`() {
        render()

        assertEquals(32.dp, height("15"))
        assertEquals(288.dp, gridHeight())
    }

    // Material3 already widens the 32dp arrow buttons to a 48dp touch area.
    @Test
    fun `the default call keeps today's 32dp month arrows`() {
        render()

        val bounds = rule.onNodeWithContentDescription("Previous month").getUnclippedBoundsInRoot()
        assertEquals(32.dp, bounds.bottom - bounds.top)
        rule.onNodeWithContentDescription("Previous month").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun `the selected day reads as selected`() {
        render()

        rule.onNodeWithText("27").assertIsSelected()
    }
}
