package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.AgendaWeekBarLogic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone

/**
 * Drives the real [WeekViewContent] through three edge cases: the phone's date moving on while
 * the grid is shown (the now line stays in the column dated today), a block that moved into a
 * cascade after an earlier touch (its drag starts from where it is drawn), and a pasted title
 * far wider than any screen (it draws without crashing). mdpi, so 1dp is 1px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1700dp-mdpi")
class WeekViewEdgeCaseTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(savedZone)

    private val weekStart = WeekViewUtils.weekPageToStartDate(WeekViewUtils.CENTER_WEEK_PAGE, java.util.Calendar.SUNDAY)

    @Test
    fun `after the date changes the now line is drawn in today's column`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        rule.setContent {
            MaterialTheme {
                WeekViewContent(
                    timedEvents = persistentListOf(), allDayEvents = persistentListOf(), isLoading = false, error = null,
                    scrollPosition = 0, savedScrollMinutes = 0, hourHeight = 30f, visibleDays = 3,
                    onDatePickerRequest = {}, onEventClick = {}, onScrollPositionChange = {},
                    clock = MutableTestClock(Instant.now()), modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-14"))
        rule.mainClock.advanceTimeBy(61_000)
        rule.waitForIdle()
        val newToday = LocalDate.now()
        val todayColumn = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 200f && it.boundsInRoot.width > 20f && it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= 361f }
            .single { n -> n.config[SemanticsProperties.ContentDescription].single().endsWith(", Today") }
            .boundsInRoot
        // The now line is the error colour: find the columns holding red pixels.
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val gridTop = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.top.toInt()
        // The line: a row in the grid with a run of 40 or more red pixels.
        val redXs = mutableSetOf<Int>()
        for (y in gridTop until pixels.height) {
            val row = (0 until pixels.width).filter { x -> pixels[x, y].let { it.red > 0.6f && it.green < 0.3f && it.blue < 0.3f } }
            if (row.size >= 40) redXs += row
        }
        assertTrue("a now line is drawn", redXs.isNotEmpty())
        val inToday = redXs.count { it >= todayColumn.left && it <= todayColumn.right }
        assertTrue("now line x ${redXs.min()}..${redXs.max()}, today's column ${todayColumn.left}..${todayColumn.right}", inToday > redXs.size / 2)
    }

    @Test
    fun `a block that moved into a cascade after a touch still changes day at the column edge`() {
        var events by mutableStateOf(listOf<DisplayEvent>(roomDisplayEvent(id = 1, title = "Lunch", date = weekStart, hour = 12)))
        val drops = mutableListOf<LocalDate>()
        rule.setContent {
            MaterialTheme {
                WeekViewContent(
                    timedEvents = events.toImmutableList(), allDayEvents = persistentListOf(), isLoading = false, error = null,
                    scrollPosition = 0, savedScrollMinutes = 10 * 60, visibleDays = 7,
                    onDatePickerRequest = {}, onEventClick = {}, onScrollPositionChange = {},
                    onReschedule = { _, day, _ -> drops += day },
                    clock = MutableTestClock.at(weekStart, LocalTime.of(0, 1)), modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        fun lunch() = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .first { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains("Lunch") } }.boundsInRoot
        // A tap starts the block's gesture handling with Lunch alone.
        rule.onRoot().performTouchInput { click(lunch().center) }
        rule.waitForIdle()
        events = listOf(
            roomDisplayEvent(id = 2, title = "Call", date = weekStart, hour = 11, minute = 30),
            roomDisplayEvent(id = 1, title = "Lunch", date = weekStart, hour = 12),
        )
        rule.waitForIdle()
        val block = lunch()
        val hourColumnRight = rule.onNodeWithTag(TEST_TAG_FIRST_TIME_LABEL).fetchSemanticsNode().boundsInRoot.right
        val columnWidth = (360f - hourColumnRight) / 7
        assertTrue("Lunch is indented: ${block.left} vs column ${hourColumnRight}", block.left > hourColumnRight + 5f)
        val start = Offset(block.left + 4f, block.top + 4f)
        val edge = hourColumnRight + columnWidth
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            down(start)
            advanceEventTime(700)
            moveBy(Offset((edge + 3f - start.x) / 2, 0f))
            moveBy(Offset((edge + 3f - start.x) / 2, 0f))
            up()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf(weekStart.plusDays(1)), drops)
    }

    @Test
    fun `a pasted 84000-character title draws without crashing`() {
        val title = "Meeting notes ".repeat(6_000)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme {
                    WeekViewContent(
                        timedEvents = persistentListOf(),
                        allDayEvents = listOf(allDayDisplayEvent(id = 1, title = title, date = LocalDate.now())).toImmutableList(),
                        isLoading = false, error = null, scrollPosition = 0, visibleDays = 3,
                        onDatePickerRequest = {}, onEventClick = {}, onScrollPositionChange = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
    }
}
