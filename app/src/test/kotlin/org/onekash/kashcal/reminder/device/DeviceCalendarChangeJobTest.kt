package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.job.JobInfo
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.JobSchedulerIds
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests one run of the calendar-change job with KashCal's process otherwise dead: no
 * ContentObserver is registered, so the run is the only thing that sees what another app or a sync
 * adapter wrote. Each run re-picks the one device reminder alarm from the provider and then
 * schedules its successor in place of finishing, the content-trigger pattern from the
 * JobInfo.Builder.addTriggerContentUri docs.
 *
 * Real [DeviceCalendarReminderScheduler], DataStore and [AndroidCalendarProviderRepository] over
 * [SqliteCalendarProvider]; JobScheduler calls go to [FakeChangeJobs].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarChangeJobTest {

    private companion object {
        const val CAL_A = 1L
        const val CAL_B = 2L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }

    private lateinit var context: Context
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: DeviceCalendarReminderScheduler
    private lateinit var jobs: FakeChangeJobs
    private lateinit var watcher: DeviceCalendarChangeWatcher
    private var finished = 0
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        provider = SqliteCalendarProvider.install()
        provider.seedCalendar(CAL_A, accountName = "sync-a@example.test")
        provider.seedCalendar(CAL_B, accountName = "sync-b@example.test")
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "changejob_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(CAL_A, CAL_B))
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { it.operation }.forEach { alarmManager.cancel(it) }
        scheduler = DeviceCalendarReminderScheduler(context, SqliteCalendarProvider.repository(context), dataStore)
        jobs = FakeChangeJobs()
        watcher = DeviceCalendarChangeWatcher(context, dataStore, jobs)
        // The running job counts as pending on the platform, so start with one: a run that ended
        // through reconcile's pending check would schedule nothing and fail the tests below.
        watcher.reconcile()
        jobs.scheduled.clear()
    }

    @After
    fun tearDown() {
        provider.db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    private fun run(repick: DeviceCalendarReminderScheduler = scheduler, timeoutMs: Long = 10_000) = runBlocking {
        DeviceCalendarChangeJobService.handleChange(repick, watcher, timeoutMs) { finished++ }
    }

    private fun insertEvent(calendarId: Long, start: Long, title: String = "Event"): Long =
        provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to calendarId, Events.TITLE to title, Events.DTSTART to start,
            Events.DTEND to start + HOUR, Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
            Events.STATUS to Events.STATUS_CONFIRMED,
        )

    private fun insertReminder(eventId: Long, minutes: Int): Long =
        provider.insertRow(
            SqliteCalendarProvider.REMINDERS,
            Reminders.EVENT_ID to eventId, Reminders.MINUTES to minutes, Reminders.METHOD to Reminders.METHOD_ALERT,
        )

    /** Device reminder alarms currently set, as event id to trigger time. */
    private fun deviceAlarms(): List<Pair<Long, Long>> =
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = alarm.operation?.let { Shadows.shadowOf(it).savedIntent } ?: return@mapNotNull null
            if (intent.component?.className != DeviceCalendarAlarmReceiver::class.java.name) return@mapNotNull null
            intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, -1L) to alarm.triggerAtMs
        }

    private fun assertRescheduledNotFinished() {
        assertEquals("the run schedules its successor once", 1, jobs.scheduled.size)
        assertEquals(JobSchedulerIds.DEVICE_CALENDAR_CHANGE, jobs.scheduled.single().id)
        assertEquals("scheduling the successor ends the run, so it doesn't finish", 0, finished)
    }

    @Test
    fun `an event a sync adapter wrote while KashCal was dead gets its reminder armed`() {
        val start = now + 2 * HOUR
        val id = insertEvent(CAL_A, start)
        insertReminder(id, 15)

        run()

        assertEquals(listOf(id to start - 15 * MINUTE), deviceAlarms())
        assertRescheduledNotFinished()
    }

    @Test
    fun `a reminder added to an existing event gets armed`() {
        val start = now + 2 * HOUR
        val id = insertEvent(CAL_A, start)
        run()
        assertEquals(emptyList<Pair<Long, Long>>(), deviceAlarms())

        insertReminder(id, 30)
        run()

        assertEquals(listOf(id to start - 30 * MINUTE), deviceAlarms())
    }

    @Test
    fun `removing the armed reminder moves the alarm to the next one`() {
        val first = insertEvent(CAL_A, now + 2 * HOUR)
        val firstReminder = insertReminder(first, 10)
        val second = insertEvent(CAL_B, now + 3 * HOUR)
        insertReminder(second, 10)
        run()
        assertEquals(first, deviceAlarms().single().first)

        provider.db.delete(SqliteCalendarProvider.REMINDERS, "_id = ?", arrayOf(firstReminder.toString()))
        run()

        assertEquals(listOf(second to now + 3 * HOUR - 10 * MINUTE), deviceAlarms())
    }

    @Test
    fun `deleting the only event with a reminder cancels the alarm`() {
        val id = insertEvent(CAL_A, now + 2 * HOUR)
        insertReminder(id, 10)
        run()
        assertEquals(1, deviceAlarms().size)

        provider.db.delete(SqliteCalendarProvider.REMINDERS, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
        provider.db.delete(SqliteCalendarProvider.EVENTS, "_id = ?", arrayOf(id.toString()))
        run()

        assertEquals(emptyList<Pair<Long, Long>>(), deviceAlarms())
    }

    @Test
    fun `moving an event earlier moves its alarm`() {
        val id = insertEvent(CAL_A, now + 3 * HOUR)
        insertReminder(id, 10)
        run()

        val earlier = now + 90 * MINUTE
        provider.updateRow(SqliteCalendarProvider.EVENTS, id, Events.DTSTART to earlier, Events.DTEND to earlier + HOUR)
        run()

        assertEquals(listOf(id to earlier - 10 * MINUTE), deviceAlarms())
    }

    @Test
    fun `an event on an unticked calendar arms nothing`() = runBlocking {
        dataStore.setEnabledDeviceCalendarIds(setOf(CAL_B))
        insertReminder(insertEvent(CAL_A, now + 2 * HOUR), 10)

        run()

        assertEquals(emptyList<Pair<Long, Long>>(), deviceAlarms())
        assertRescheduledNotFinished()
    }

    @Test
    fun `an event on a muted calendar arms nothing`() = runBlocking {
        dataStore.setDeviceCalendarMuted(CAL_A, muted = true)
        insertReminder(insertEvent(CAL_A, now + 2 * HOUR), 10)

        run()

        assertEquals(emptyList<Pair<Long, Long>>(), deviceAlarms())
    }

    @Test
    fun `with device reminders off the run arms nothing and stops watching`() = runBlocking {
        dataStore.setDeviceCalendarRemindersEnabled(false)
        insertReminder(insertEvent(CAL_A, now + 2 * HOUR), 10)

        run()

        assertEquals(emptyList<Pair<Long, Long>>(), deviceAlarms())
        assertEquals("no successor is scheduled", 0, jobs.scheduled.size)
        assertEquals(1, finished)
    }

    @Test
    fun `a failing re-pick still schedules the successor`() {
        val failing = mockk<DeviceCalendarReminderScheduler> {
            coEvery { scheduleNextReminder() } throws IllegalStateException("provider died")
        }

        run(repick = failing)

        assertRescheduledNotFinished()
    }

    @Test
    fun `a re-pick that times out still schedules the successor`() {
        val hanging = mockk<DeviceCalendarReminderScheduler> {
            coEvery { scheduleNextReminder() } coAnswers { awaitCancellation() }
        }

        run(repick = hanging, timeoutMs = 50)

        assertRescheduledNotFinished()
    }

    @Test
    fun `when the successor can't be scheduled the run finishes`() {
        jobs.refuse = true
        insertReminder(insertEvent(CAL_A, now + 2 * HOUR), 10)

        run()

        assertEquals(1, deviceAlarms().size)
        assertEquals(1, finished)
    }

    @Test
    fun `a run writes nothing to the calendar provider`() {
        insertReminder(insertEvent(CAL_A, now + 2 * HOUR), 10)
        insertReminder(insertEvent(CAL_B, now + 3 * HOUR), 5)
        val events = provider.rows(SqliteCalendarProvider.EVENTS)
        val reminders = provider.rows(SqliteCalendarProvider.REMINDERS)
        val calendars = provider.rows(SqliteCalendarProvider.CALENDARS)

        run()

        assertEquals(events, provider.rows(SqliteCalendarProvider.EVENTS))
        assertEquals(reminders, provider.rows(SqliteCalendarProvider.REMINDERS))
        assertEquals(calendars, provider.rows(SqliteCalendarProvider.CALENDARS))
        assertTrue(provider.appEventUpdates.isEmpty())
    }
}

/**
 * Guards the manifest entry for [DeviceCalendarChangeJobService]: JobScheduler only binds a
 * service protected by BIND_JOB_SERVICE, and nothing outside the system should start it.
 */
class DeviceCalendarChangeJobManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun services(): Map<String, Element> {
        val relative = "src/main/AndroidManifest.xml"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("service")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associateBy { it.getAttributeNS(androidNs, "name") }
    }

    @Test
    fun `the change job service is declared, protected and not exported`() {
        val service = services()[".reminder.device.DeviceCalendarChangeJobService"]
            ?: error("DeviceCalendarChangeJobService is not declared in the manifest")
        assertEquals("android.permission.BIND_JOB_SERVICE", service.getAttributeNS(androidNs, "permission"))
        assertEquals("false", service.getAttributeNS(androidNs, "exported"))
        Class.forName("org.onekash.kashcal.reminder.device.DeviceCalendarChangeJobService")
    }
}
