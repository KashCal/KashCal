package org.onekash.kashcal.data.calendar_provider

import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.reader.DeviceEventReader
import org.onekash.kashcal.domain.writer.DeviceEventDraft
import org.onekash.kashcal.domain.writer.DeviceEventWriter
import java.util.TimeZone

/**
 * The real device read/write stack over [SqliteCalendarProvider]: the app's
 * [DeviceEventWriter] and [DeviceEventReader] on the real
 * [AndroidCalendarProviderRepository], with two calendars (one owned by a
 * syncing account, one local) and the phone's zone pinned to one that differs
 * from the events' zone, so zone mix-ups show.
 *
 * Call [setUp] from `@Before` and [tearDown] from `@After` in a Robolectric test, for example
 * with `@Config(manifest = Config.NONE, sdk = [34])`.
 */
class DeviceRoundTripFixture(private val deviceZone: String = "Europe/Berlin") {

    lateinit var context: Context
    lateinit var provider: SqliteCalendarProvider
    lateinit var repository: AndroidCalendarProviderRepository
    lateinit var writer: DeviceEventWriter
    lateinit var reader: DeviceEventReader
    private lateinit var savedZone: TimeZone

    /** Accepts only the change signal; anything else the writer asks of it throws. */
    val manager: CalendarProviderManager = deviceChangeNotifier()

    /** The two stored import defaults the writer reads; anything else throws. */
    val dataStore: KashCalDataStore = mockk {
        every { defaultReminderMinutes } returns MutableStateFlow(DEFAULT_TIMED_REMINDER)
        every { defaultAllDayReminder } returns MutableStateFlow(DEFAULT_ALL_DAY_REMINDER)
    }

    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteCalendarProvider.install()
        repository = SqliteCalendarProvider.repository(context)
        writer = repository.deviceEventWriter(dataStore, manager)
        reader = repository.deviceEventReader()
        provider.seedCalendar(
            SYNCED_CAL, accountName = OWNER, accountType = "com.example.sync",
            displayName = "Work", color = WORK_COLOR,
        )
        provider.seedCalendar(
            LOCAL_CAL, accountName = LOCAL_OWNER, accountType = CalendarContract.ACCOUNT_TYPE_LOCAL,
            displayName = "Personal", color = PERSONAL_COLOR,
        )
    }

    fun tearDown() {
        // Zone first, so a failed setUp can't leave the pinned zone for later classes.
        if (::savedZone.isInitialized) TimeZone.setDefault(savedZone)
        if (::provider.isInitialized) provider.db.close()
    }

    /**
     * Records a row as synced, the way a sync adapter does after an upload. As on the platform,
     * exceptions already linked to it by id pick up its sync id too.
     */
    fun markSynced(eventId: Long, syncId: String = "sync-$eventId") {
        provider.updateRow(SqliteCalendarProvider.EVENTS, eventId, Events._SYNC_ID to syncId)
    }

    /** The stored Events row, as the provider holds it. */
    fun eventRow(eventId: Long): Map<String, String?>? = provider.row(SqliteCalendarProvider.EVENTS, eventId)

    /** Occurrences the grid would load for [startDay]..[endDay] (YYYYMMDD) in both calendars. */
    suspend fun occurrences(startDay: Int, endDay: Int = startDay, hideDeclined: Boolean = false) =
        repository.getInstancesForDayRange(startDay, endDay, setOf(SYNCED_CAL, LOCAL_CAL), hideDeclined)

    /** Creates [draft] through the device writer and returns the new row's id. */
    suspend fun create(draft: DeviceEventDraft = draft()): Long = writer.createEvent(draft).getOrThrow().eventId

    /** Every occurrence in March 2024 across both calendars, in start order. */
    suspend fun inMarch(calendarIds: Set<Long> = setOf(SYNCED_CAL, LOCAL_CAL)) =
        repository.getInstancesForDayRange(20240301, 20240331, calendarIds).sortedBy { it.startTs }

    /** Every occurrence in March and April 2024, for series that could spill past March. */
    suspend fun inMarchAndApril() =
        repository.getInstancesForDayRange(20240301, 20240430, setOf(SYNCED_CAL, LOCAL_CAL)).sortedBy { it.startTs }

    /** A guest row the way the app's guest picker hands it over. */
    fun guest(email: String, name: String? = null) = DeviceAttendee(
        id = 0L, name = name, email = email,
        relationship = CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
        status = CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
    )

    /** A timed one-off draft with every field set; override what a test varies. */
    fun draft(
        calendarId: Long = SYNCED_CAL,
        title: String = "Design review",
        description: String? = "Agenda: roadmap",
        location: String? = "Room 4",
        startTs: Long = T0,
        endTs: Long = T0 + 90 * MINUTE,
        isAllDay: Boolean = false,
        rrule: String? = null,
        timezone: String = EVENT_ZONE,
        reminders: List<Int>? = listOf(10, 60),
        availability: Int = Events.AVAILABILITY_FREE,
        eventColor: Int? = EVENT_COLOR,
        attendees: List<DeviceAttendee>? = null,
        categories: List<String>? = null,
    ) = DeviceEventDraft(
        calendarId = calendarId, title = title, description = description, location = location,
        startTs = startTs, endTs = endTs, isAllDay = isAllDay, rrule = rrule, timezone = timezone,
        reminders = reminders, availability = availability, eventColor = eventColor,
        attendees = attendees, categories = categories,
    )

    companion object {
        const val SYNCED_CAL = 11L
        const val LOCAL_CAL = 12L
        const val OWNER = "me@example.test"
        const val LOCAL_OWNER = "Personal"
        const val WORK_COLOR = 0xFF3366CC.toInt()
        const val PERSONAL_COLOR = 0xFF33AA66.toInt()
        const val EVENT_COLOR = 0xFF112233.toInt()
        const val EVENT_ZONE = "America/New_York"
        // Differ from the app's built-in defaults (15 and 900), so a test can tell the stored
        // setting from a hard-coded fallback.
        const val DEFAULT_TIMED_REMINDER = 25
        const val DEFAULT_ALL_DAY_REMINDER = 600

        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY

        /** 2024-03-05 10:00 New York = 15:00Z = 16:00 Berlin. */
        const val T0 = 1_709_650_800_000L

        /**
         * Start of the i-th (0-based) weekly occurrence at 10:00 New York from
         * [T0]: New York moves its clocks forward on 2024-03-10, so from the
         * second occurrence on the same local time is an hour earlier in UTC.
         */
        fun occ(i: Int): Long = T0 + i * WEEK - if (i >= 1) HOUR else 0L

        /** 2024-03-05T00:00Z, an all-day start. */
        const val DAY0 = 1_709_596_800_000L
        const val DAY_CODE = 20240305
    }
}
