package org.onekash.kashcal.ui.components.pickers

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.rrule.EndCondition
import org.onekash.kashcal.testutil.SplitZone
import org.onekash.kashcal.testutil.withSplitDeviceTimeZone
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar as JavaCalendar

/**
 * The repeat rule's "On date" end opens its grid on the UNTIL date's month, highlights that day
 * and saves UNTIL on the day the user taps, in zones where java.util and java.time disagree
 * about daylight saving time ([SplitZone], as Android reads dates after its cutoff year, #435).
 * The event is timed in Europe/Berlin, so UNTIL is the end of the picked day there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EndConditionUntilSplitZoneTest {

    @get:Rule
    val rule = createComposeRule()

    private val eventZone = "Europe/Berlin"
    private val onDate = ApplicationProvider.getApplicationContext<Context>().getString(R.string.label_recurrence_on_date)

    private fun endOfDayIn(date: LocalDate, zone: String): Long =
        date.plusDays(1).atStartOfDay(ZoneId.of(zone)).minusSeconds(1).toInstant().toEpochMilli()

    private fun untilDay(endCondition: EndCondition?): LocalDate? =
        (endCondition as? EndCondition.Until)?.let { Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.of(eventZone)).toLocalDate() }

    /** Shows the selector with UNTIL on [until], opens its grid and returns the latest end. */
    private fun showOpened(until: LocalDate): () -> EndCondition? {
        var latest: EndCondition? = null
        rule.setContent {
            MaterialTheme {
                var endCondition by remember { mutableStateOf<EndCondition>(EndCondition.Until(endOfDayIn(until, eventZone))) }
                EndConditionSelector(
                    endCondition = endCondition,
                    startDateMillis = endOfDayIn(until.minusMonths(1), eventZone),
                    onEndConditionChange = { endCondition = it; latest = it },
                    firstDayOfWeek = JavaCalendar.MONDAY,
                    isAllDay = false,
                    timezone = eventZone,
                )
            }
        }
        rule.onNodeWithText(onDate).performClick()
        rule.waitForIdle()
        return { latest }
    }

    private fun highlightsTheUntilDay(zone: SplitZone): Unit = withSplitDeviceTimeZone(zone) {
        showOpened(LocalDate.of(2026, 6, 20))
        rule.onNodeWithText("20").assertIsSelected()
        rule.onNodeWithText("19").assertIsNotSelected()
        // The label next to "On date" names the UNTIL day.
        rule.onNodeWithText("Jun 20, 2026").assertExists()
        Unit
    }

    private fun savesTheTappedDay(zone: SplitZone): Unit = withSplitDeviceTimeZone(zone) {
        val latest = showOpened(LocalDate.of(2026, 6, 20))
        rule.onNodeWithText("25").performClick()
        rule.waitForIdle()
        assertEquals("UNTIL day in $zone", LocalDate.of(2026, 6, 25), untilDay(latest()))
    }

    private fun opensOnTheUntilMonth(zone: SplitZone): Unit = withSplitDeviceTimeZone(zone) {
        showOpened(LocalDate.of(2026, 7, 1))
        rule.onNodeWithText("July 2026").assertExists()
        Unit
    }

    @Test
    fun `Until highlights its day in split Berlin`() = highlightsTheUntilDay(SplitZone.BERLIN)

    @Test
    fun `Until highlights its day in split New York`() = highlightsTheUntilDay(SplitZone.NEW_YORK)

    @Test
    fun `Until highlights its day in Tokyo`() = highlightsTheUntilDay(SplitZone.TOKYO)

    @Test
    fun `Until saves the tapped day in split Berlin`() = savesTheTappedDay(SplitZone.BERLIN)

    @Test
    fun `Until saves the tapped day in split New York`() = savesTheTappedDay(SplitZone.NEW_YORK)

    @Test
    fun `Until saves the tapped day in UTC`() = savesTheTappedDay(SplitZone.UTC)

    @Test
    fun `Until on July 1 opens the grid on July in split Berlin`() = opensOnTheUntilMonth(SplitZone.BERLIN)

    @Test
    fun `Until on July 1 opens the grid on July in UTC`() = opensOnTheUntilMonth(SplitZone.UTC)
}
