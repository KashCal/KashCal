package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.util.LocalDisplayZone
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Tests that an open [DayColumn] moves an event to its new time when the device zone changes
 * and the event list stays equal (#422). A device event is used because its equality doesn't
 * change with the zone, so only the [LocalDisplayZone] key can re-run the cached layout; the
 * layout itself reads the device zone, as in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1600dp-mdpi")
class DayColumnZoneChangeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var savedZone: TimeZone

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun an_open_column_moves_an_unchanged_event_to_its_time_in_the_new_zone() {
        // 2026-11-04T01:00Z is 10:00 in Tokyo and 06:30 in Kolkata, Nov 4 in both.
        val start = LocalDate.of(2026, 11, 4).atStartOfDay(ZoneId.of("UTC")).plusHours(1).toInstant().toEpochMilli()
        val event = DisplayEvent.Device(deviceInstance(start, start + 3_600_000L))
        TimeZone.setDefault(TimeZone.getTimeZone(TOKYO))
        var zone by mutableStateOf(TOKYO)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDisplayZone provides zone) {
                MaterialTheme {
                    DayColumn(
                        date = LocalDate.of(2026, 11, 4),
                        events = listOf(event),
                        now = { 0L },
                        onEventClick = {},
                        onOverflowClick = {},
                        modifier = Modifier.size(200.dp, 1500.dp),
                    )
                }
            }
        }
        val tokyoTop = composeTestRule.onNodeWithText(TITLE).getBoundsInRoot().top

        TimeZone.setDefault(TimeZone.getTimeZone(KOLKATA))
        zone = KOLKATA
        composeTestRule.waitForIdle()
        val kolkataTop = composeTestRule.onNodeWithText(TITLE).getBoundsInRoot().top

        assertTrue("block moves up from 10:00 to 06:30 ($tokyoTop -> $kolkataTop)", kolkataTop < tokyoTop)
    }

    private fun deviceInstance(startTs: Long, endTs: Long) = DeviceCalendarInstance(
        instanceId = 1L,
        eventId = 1L,
        title = TITLE,
        description = "",
        location = "",
        startTs = startTs,
        endTs = endTs,
        startDay = 20261104,
        endDay = 20261104,
        isAllDay = false,
        hasRrule = false,
        rrule = null,
        reminders = emptyList(),
        calendarId = 5L,
        calendarDisplayName = "Device",
        calendarColor = 0xFF2196F3.toInt(),
        eventColor = null,
        status = 1,
        availability = 0,
        hasAlarm = false,
        selfAttendeeStatus = 0,
        isWritable = true,
        originalId = null,
        originalInstanceTime = null,
        timezone = "UTC",
        eventStartTs = startTs,
    )

    private companion object {
        const val TITLE = "Standup"
        val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")
        val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}
