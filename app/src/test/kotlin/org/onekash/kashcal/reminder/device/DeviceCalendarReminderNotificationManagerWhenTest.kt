package org.onekash.kashcal.reminder.device

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarReminderNotificationManagerWhenTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var context: Context
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var dataStore: KashCalDataStore
    private lateinit var manager: DeviceCalendarReminderNotificationManager
    private lateinit var testDataStoreFile: File
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setup() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        context = RuntimeEnvironment.getApplication()
        channels = ReminderNotificationChannels(context)
        channels.createChannels()

        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
        manager = DeviceCalendarReminderNotificationManager(context, channels, dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        TimeZone.setDefault(originalTimeZone)
        unmockkAll()
        testDataStoreFile.delete()
    }

    @Test
    fun `buildNotification sets when to occurrenceTs plus 30s rounding offset`() = runBlocking {
        val occurrenceTs = System.currentTimeMillis() + 900_000L
        val triggerTime = occurrenceTs - 900_000L

        val notification = manager.buildNotification(
            eventId = 100L,
            occurrenceTs = occurrenceTs,
            title = "Test Meeting",
            location = null,
            isAllDay = false,
            calendarColor = 0xFF6200EE.toInt(),
            calendarId = 1L,
            triggerTime = triggerTime,
            notificationId = 20001
        )

        assertEquals(
            "setWhen should include 30s rounding offset to convert Android's floor to round",
            occurrenceTs + 30_000L,
            notification.`when`
        )
    }

    // ========== All-day: Today / Tomorrow / In N days (parity with Room path) ==========

    private fun buildAllDay(eventDate: LocalDate, fireDateTime: ZonedDateTime): Notification = runBlocking {
        val occurrenceUtcMidnight = ZonedDateTime.of(
            eventDate, LocalTime.MIDNIGHT, ZoneId.of("UTC")
        ).toInstant().toEpochMilli()
        manager.buildNotification(
            eventId = 100L,
            occurrenceTs = occurrenceUtcMidnight,
            title = "All Day Event",
            location = null,
            isAllDay = true,
            calendarColor = 0xFF6200EE.toInt(),
            calendarId = 1L,
            triggerTime = fireDateTime.toInstant().toEpochMilli(),
            notificationId = 20001
        )
    }

    @Test
    fun `all-day reminder hides header time`() {
        // occurrenceTs is UTC midnight for all-day events; surfacing it in the
        // header renders a misleading shifted clock time, so suppress it.
        val zone = ZoneId.of("America/New_York")
        val notification = buildAllDay(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.of(9, 0), zone)
        )

        assertEquals(
            "All-day reminder must not show a header timestamp",
            false,
            notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)
        )
    }

    @Test
    fun `timed reminder shows header time`() {
        val occurrenceTs = System.currentTimeMillis() + 900_000L
        val notification = runBlocking {
            manager.buildNotification(
                eventId = 100L,
                occurrenceTs = occurrenceTs,
                title = "Test Meeting",
                location = null,
                isAllDay = false,
                calendarColor = 0xFF6200EE.toInt(),
                calendarId = 1L,
                triggerTime = occurrenceTs - 900_000L,
                notificationId = 20002
            )
        }

        assertEquals(
            "Timed reminder should keep the header countdown",
            true,
            notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, false)
        )
    }

    @Test
    fun `all-day day-before reminder shows Tomorrow`() {
        val zone = ZoneId.of("America/New_York")
        val notification = buildAllDay(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 5), LocalTime.of(9, 0), zone)
        )

        assertEquals("Tomorrow", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `all-day day-of reminder shows Today`() {
        val zone = ZoneId.of("America/New_York")
        val notification = buildAllDay(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 6), LocalTime.of(9, 0), zone)
        )

        assertEquals("Today", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `all-day two-days-before reminder shows In 2 days`() {
        val zone = ZoneId.of("America/New_York")
        val notification = buildAllDay(
            LocalDate.of(2026, 1, 6),
            ZonedDateTime.of(LocalDate.of(2026, 1, 4), LocalTime.of(9, 0), zone)
        )

        assertEquals("In 2 days", notification.extras.getString(Notification.EXTRA_TEXT))
    }
}
