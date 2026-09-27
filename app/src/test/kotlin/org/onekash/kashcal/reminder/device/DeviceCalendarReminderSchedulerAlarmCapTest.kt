package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.UpcomingDeviceReminder
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * Verifies device-calendar reminders and snoozes survive the platform refusing
 * their alarm.
 *
 * AlarmManager caps each app at 500 pending alarms and throws IllegalStateException
 * for every further set call. These alarms are set from the calendar observer, the
 * settings toggle, boot recovery, time-zone changes, the refresh worker, the alarm
 * receiver and the snooze action, where an escaping exception crashes the app, so
 * the refusal is skipped and logged once, with a masked event id, where the alarm is
 * set; a refusal at the limit is never retried as inexact. A snooze whose exact set
 * throws SecurityException isn't retried as inexact either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarReminderSchedulerAlarmCapTest {

    private companion object {
        const val TAG = "DeviceCalReminderSched"
        const val EVENT_ID = 987654L
        const val TITLE = "Dentist appointment"
    }

    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var fakeRepository: FakeCalendarProviderRepository
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: DeviceCalendarReminderScheduler

    private val alarmLimitRefusal =
        IllegalStateException("Maximum limit of concurrent alarms 500 reached")
    private val triggerTime = System.currentTimeMillis() + 60_000

    @Before
    fun setup() {
        ShadowLog.clear()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(appContext as Application).grantPermissions(Manifest.permission.READ_CALENDAR)

        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(appContext.filesDir, "alarm_cap_prefs_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(
            appContext,
            PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile }
        )
        fakeRepository = FakeCalendarProviderRepository()
        fakeRepository.nextUpcomingReminder = UpcomingDeviceReminder(
            eventId = EVENT_ID,
            occurrenceStartTs = triggerTime + 15 * 60_000,
            title = TITLE,
            location = null,
            isAllDay = false,
            reminderMinutes = 15,
            triggerTime = triggerTime,
            calendarColor = 0xFF0000,
            calendarId = 1L
        )

        alarmManager = mockk()
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal
        every { alarmManager.setAndAllowWhileIdle(any(), any(), any()) } throws alarmLimitRefusal

        val context = spyk(appContext)
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        scheduler = DeviceCalendarReminderScheduler(context, fakeRepository, dataStore)
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    private suspend fun enableDeviceReminders() {
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(1L))
    }

    private fun snooze() = scheduler.scheduleSnooze(
        eventId = EVENT_ID,
        occurrenceTs = triggerTime,
        title = TITLE,
        location = null,
        isAllDay = false,
        calendarColor = 0xFF0000,
        calendarId = 1L
    )

    private fun notArmedLogs() = ShadowLog.getLogsForTag(TAG)
        .filter { it.type == Log.ERROR && it.msg.contains("not armed") }

    // ===== Next reminder =====

    @Test
    fun `exact device reminder refused at the alarm limit is skipped without retrying inexact`() = runTest {
        enableDeviceReminders()

        scheduler.scheduleNextReminder()

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), triggerTime, any()) }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `inexact device reminder refused at the alarm limit is skipped`() = runTest {
        enableDeviceReminders()
        every { alarmManager.canScheduleExactAlarms() } returns false

        scheduler.scheduleNextReminder()

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `inexact fallback for a denied device reminder refused at the alarm limit is skipped`() = runTest {
        enableDeviceReminders()
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        scheduler.scheduleNextReminder()

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `rescheduling after a reminder fires survives the alarm limit`() = runTest {
        enableDeviceReminders()

        scheduler.rescheduleAfterFire()

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), triggerTime, any()) }
    }

    @Test
    fun `refused device reminder is logged once with a masked event id and no title`() = runTest {
        enableDeviceReminders()

        scheduler.scheduleNextReminder()

        val logs = notArmedLogs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].msg.contains("9876***"))
        assertFalse(logs[0].msg.contains(EVENT_ID.toString()))
        assertFalse(logs[0].msg.contains(TITLE))
    }

    // ===== Snooze =====

    @Test
    fun `exact snooze refused at the alarm limit is skipped`() {
        snooze()

        verify(exactly = 1) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        assertEquals(1, notArmedLogs().size)
    }

    @Test
    fun `inexact snooze refused at the alarm limit is skipped`() {
        every { alarmManager.canScheduleExactAlarms() } returns false

        snooze()

        verify(exactly = 1) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `denied exact snooze is not retried as inexact`() {
        every { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        snooze()

        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
        val errors = ShadowLog.getLogsForTag(TAG).filter { it.type == Log.ERROR }
        assertEquals(1, errors.size)
        assertTrue(errors[0].msg.contains("cannot set alarm"))
    }
}
