package org.onekash.kashcal.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactEmail
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.ui.components.pickers.DateTimeDisplayRow
import org.onekash.kashcal.ui.components.pickers.ZonePick
import org.onekash.kashcal.util.zone.ZoneCatalog
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/**
 * Tests the event form's time zone row text ("Berlin (CET)") and the start and end
 * rows' zone suffix, in en-US on a device in America/Chicago.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EventFormTimeZoneRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val january = Instant.parse("2026-01-15T15:00:00Z")
    private val july = Instant.parse("2026-07-15T15:00:00Z")

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private fun rowLabel(id: String, at: Instant = january) =
        timeZoneRowLabel(context.resources, ZoneCatalog.label(id, at, Locale.US)!!)

    @Test
    fun `row reads city and abbreviation`() {
        assertEquals("Berlin (CET)", rowLabel("Europe/Berlin").text)
        assertEquals("New York (EST)", rowLabel("America/New_York").text)
    }

    @Test
    fun `row without an abbreviation reads city and offset`() {
        assertEquals("Tokyo (GMT+09:00)", rowLabel("Asia/Tokyo").text)
    }

    @Test
    fun `Etc UCT reads as UTC`() {
        assertEquals("UTC (GMT+00:00)", rowLabel("Etc/UCT").text)
    }

    @Test
    fun `an offset id reads as its offset alone`() {
        assertEquals("GMT-05:00", rowLabel("Etc/GMT+5").text)
    }

    @Test
    fun `accessibility text names the zone without separators`() {
        assertEquals("Berlin, Central European Time, GMT+01:00", rowLabel("Europe/Berlin").description)
        assertEquals("GMT-05:00", rowLabel("Etc/GMT+5").description)
    }

    @Test
    fun `date rows show the zone only when it is not the device zone`() {
        assertNull(compactZoneLabel("America/Chicago", "America/Chicago", january))
        assertNull(compactZoneLabel("Asia/Calcutta", "Asia/Kolkata", january))
        assertNull(compactZoneLabel(null, "America/Chicago", january))
        assertEquals("CET", compactZoneLabel("Europe/Berlin", "America/Chicago", january))
        assertEquals("CEST", compactZoneLabel("Europe/Berlin", "America/Chicago", july))
        assertEquals("GMT+09:00", compactZoneLabel("Asia/Tokyo", "America/Chicago", january))
    }

    @Test
    fun `date rows use each row's own date`() {
        composeTestRule.setContent {
            MaterialTheme {
                DateTimeDisplayRow(
                    startDateMillis = deviceMidnight(LocalDate.of(2026, 1, 15)),
                    startHour = 9,
                    startMinute = 0,
                    endDateMillis = deviceMidnight(LocalDate.of(2026, 7, 15)),
                    endHour = 10,
                    endMinute = 0,
                    isAllDay = false,
                    onAllDayToggle = {},
                    onStartClick = {},
                    onEndClick = {},
                    timezone = "Europe/Berlin",
                )
            }
        }
        composeTestRule.onNodeWithText("CET", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("CEST", substring = true).assertIsDisplayed()
    }

    @Test
    fun `form row shows the event zone at the event's start`() {
        val event = Event(
            id = 1L,
            uid = "tz-row@test",
            calendarId = 1L,
            title = "Call",
            startTs = january.toEpochMilli(),
            endTs = january.plusSeconds(3600).toEpochMilli(),
            timezone = "Europe/Berlin",
            dtstamp = 0L,
        )
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    eventId = 1L,
                    onLoadEvent = { event },
                    calendars = listOf(
                        Calendar(id = 1L, accountId = 1L, caldavUrl = "https://caldav.example.test/c", displayName = "Personal", color = 0),
                    ),
                    calendarGroups = emptyList(),
                    deviceCalendarGroups = emptyList(),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(event) },
                    onQueryContacts = { emptyList<ContactEmail>() },
                    isSchedulable = true,
                    onSetTagsAboveNotes = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Berlin (CET)").assertExists()
        composeTestRule.onNodeWithContentDescription("Time zone").assertExists()
    }

    @Test
    fun `date rows follow the saved instant in a repeated hour`() {
        // 2026-11-01 01:30 happens twice in New York; 06:30Z is the second time, in EST.
        val secondOneThirty = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        composeTestRule.setContent {
            MaterialTheme {
                DateTimeDisplayRow(
                    startDateMillis = deviceMidnight(LocalDate.of(2026, 11, 1)),
                    startHour = 1,
                    startMinute = 30,
                    endDateMillis = deviceMidnight(LocalDate.of(2026, 11, 1)),
                    endHour = 2,
                    endMinute = 30,
                    isAllDay = false,
                    onAllDayToggle = {},
                    onStartClick = {},
                    onEndClick = {},
                    timezone = "America/New_York",
                    startTs = secondOneThirty,
                    endTs = secondOneThirty + 3_600_000L,
                )
            }
        }
        composeTestRule.onNodeWithText("EDT", substring = true).assertDoesNotExist()
        composeTestRule.onAllNodesWithText("EST", substring = true).assertCountEquals(2)
    }

    // ---- what a pick in the sheet does to the form ----

    private fun berlinForm() = EventFormState().copy(
        dateMillis = deviceMidnight(LocalDate.of(2026, 1, 15)),
        endDateMillis = deviceMidnight(LocalDate.of(2026, 1, 15)),
        startHour = 3,
        startMinute = 0,
        endHour = 4,
        endMinute = 0,
        timezone = "Europe/Berlin",
    )

    @Test
    fun `Device on an existing event stores the device zone and keeps the clock time`() {
        val before = berlinForm()
        val after = before.withZonePick(ZonePick.Device, "America/Chicago")
        assertEquals("America/Chicago", after.timezone)
        assertEquals(before.dateMillis, after.dateMillis)
        assertEquals(before.startHour to before.startMinute, after.startHour to after.startMinute)
    }

    @Test
    fun `Device on an unreadable zone drops the original name`() {
        val after = EventFormState().copy(sourceTimezoneId = "Somewhere Standard Time")
            .withZonePick(ZonePick.Device, "America/Chicago")
        assertEquals("America/Chicago", after.timezone)
        assertNull(after.sourceTimezoneId)
    }

    @Test
    fun `Keep leaves the form and a zone pick sets it`() {
        val before = berlinForm()
        assertEquals(before, before.withZonePick(ZonePick.Keep, "America/Chicago"))
        assertEquals("Asia/Tokyo", before.withZonePick(ZonePick.Set("Asia/Tokyo"), "America/Chicago").timezone)
    }

    @Test
    fun `picking a zone and then Device on a new event is no change`() {
        val initial = EventFormState()
        val current = initial.withZonePick(ZonePick.Set("Asia/Tokyo"), "America/Chicago")
            .withZonePick(ZonePick.Device, "America/Chicago")
        assertFalse(eventFormHasUnsavedChanges(initial, current))
    }
}
