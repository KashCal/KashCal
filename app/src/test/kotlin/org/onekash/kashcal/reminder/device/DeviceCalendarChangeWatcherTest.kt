package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.util.JobSchedulerIds
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests when [DeviceCalendarChangeWatcher] keeps the calendar-change job scheduled, and the job's
 * shape: a content trigger on the whole calendar provider, never persisted or periodic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarChangeWatcherTest {

    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var jobScheduler: JobScheduler

    private val jobId = JobSchedulerIds.DEVICE_CALENDAR_CHANGE

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "watcher_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setDeviceCalendarRemindersEnabled(true)
        jobScheduler = context.getSystemService(JobScheduler::class.java)
        jobScheduler.cancelAll()
    }

    @After
    fun tearDown() {
        jobScheduler.cancelAll()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    private fun watcher(jobs: DeviceCalendarChangeWatcher.Jobs = DeviceCalendarChangeWatcher.PlatformJobs(context)) =
        DeviceCalendarChangeWatcher(context, dataStore, jobs)

    @Test
    fun `reconcile schedules a content trigger on the whole calendar provider`() = runTest {
        watcher().reconcile()

        val job = jobScheduler.getPendingJob(jobId)
        assertNotNull("watcher job must be pending", job)
        job!!
        assertEquals(ComponentName(context, DeviceCalendarChangeJobService::class.java), job.service)
        val trigger = job.triggerContentUris!!.single()
        assertEquals(CalendarContract.CONTENT_URI, trigger.uri)
        assertEquals(JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS, trigger.flags)
        assertEquals(DeviceCalendarChangeWatcher.UPDATE_DELAY_MS, job.triggerContentUpdateDelay)
        assertEquals(DeviceCalendarChangeWatcher.MAX_DELAY_MS, job.triggerContentMaxDelay)
        assertFalse(job.isPersisted)
        assertFalse(job.isPeriodic)
    }

    @Test
    fun `reconcile leaves a pending job of the current shape untouched`() = runTest {
        val jobs = FakeChangeJobs(DeviceCalendarChangeWatcher.PlatformJobs(context))
        val watcher = watcher(jobs)
        watcher.reconcile()
        val first = jobScheduler.getPendingJob(jobId)

        watcher.reconcile()

        assertEquals("only the first reconcile schedules", 1, jobs.scheduled.size)
        assertSame(first, jobScheduler.getPendingJob(jobId))
    }

    @Test
    fun `reconcile replaces a pending job of another shape`() = runTest {
        // A job left by an older build: same id, other component, no delays.
        val stale = JobInfo.Builder(jobId, ComponentName(context, "org.onekash.kashcal.OldJobService"))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)
            )
            .build()
        jobScheduler.schedule(stale)

        watcher().reconcile()

        val job = jobScheduler.getPendingJob(jobId)!!
        assertEquals(ComponentName(context, DeviceCalendarChangeJobService::class.java), job.service)
        assertEquals(DeviceCalendarChangeWatcher.UPDATE_DELAY_MS, job.triggerContentUpdateDelay)
    }

    @Test
    fun `reconcile cancels the job when device calendars are off`() = runTest {
        val watcher = watcher()
        watcher.reconcile()
        dataStore.setDeviceCalendarsEnabled(false)

        watcher.reconcile()

        assertNull(jobScheduler.getPendingJob(jobId))
    }

    @Test
    fun `reconcile cancels the job when device calendar reminders are off`() = runTest {
        val watcher = watcher()
        watcher.reconcile()
        dataStore.setDeviceCalendarRemindersEnabled(false)

        watcher.reconcile()

        assertNull(jobScheduler.getPendingJob(jobId))
    }

    @Test
    fun `reconcile cancels the job without READ_CALENDAR`() = runTest {
        val watcher = watcher()
        watcher.reconcile()
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR)

        watcher.reconcile()

        assertNull(jobScheduler.getPendingJob(jobId))
    }

    @Test
    fun `reconcile logs and swallows a JobScheduler failure`() = runTest {
        val jobs = FakeChangeJobs().apply { failWith = IllegalStateException("Apps may not schedule more than 150 distinct jobs") }
        val watcher = watcher(jobs)

        watcher.reconcile()
        dataStore.setDeviceCalendarsEnabled(false)
        watcher.reconcile()
        assertFalse(watcher.scheduleNext())
    }

    @Test
    fun `reconcile and refresh survive JobScheduler refusing the job`() = runTest {
        val jobs = FakeChangeJobs().apply { refuse = true }
        val watcher = watcher(jobs)

        watcher.reconcile()
        watcher.refresh()

        assertFalse(watcher.scheduleNext())
        assertEquals(0, jobs.scheduled.size)
    }

    @Test
    fun `scheduleNext schedules even while the job is pending`() = runTest {
        val jobs = FakeChangeJobs(DeviceCalendarChangeWatcher.PlatformJobs(context))
        val watcher = watcher(jobs)
        watcher.reconcile()

        assertTrue(watcher.scheduleNext())

        assertEquals("reconcile and scheduleNext each schedule", 2, jobs.scheduled.size)
    }

    @Test
    fun `shouldWatch needs both switches and READ_CALENDAR`() = runTest {
        val watcher = watcher()
        assertTrue(watcher.shouldWatch())
        dataStore.setDeviceCalendarRemindersEnabled(false)
        assertFalse(watcher.shouldWatch())
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(false)
        assertFalse(watcher.shouldWatch())
        dataStore.setDeviceCalendarsEnabled(true)
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR)
        assertFalse(watcher.shouldWatch())
    }

    @Test
    fun `refresh replaces a pending job of the current shape`() = runTest {
        // A process start can find a failure-retry copy that looks current but no longer runs.
        val watcher = watcher()
        watcher.reconcile()
        val seeded = jobScheduler.getPendingJob(jobId)

        watcher.refresh()

        val now = jobScheduler.getPendingJob(jobId)
        assertNotNull(now)
        assertNotSame(seeded, now)
    }

    @Test
    fun `refresh cancels the job when device calendar reminders are off`() = runTest {
        val jobs = FakeChangeJobs(DeviceCalendarChangeWatcher.PlatformJobs(context))
        val watcher = watcher(jobs)
        watcher.reconcile()
        dataStore.setDeviceCalendarRemindersEnabled(false)

        watcher.refresh()

        assertNull(jobScheduler.getPendingJob(jobId))
        assertEquals("only the first reconcile scheduled", 1, jobs.scheduled.size)
    }

    @Test
    fun `refresh logs and swallows a JobScheduler failure`() = runTest {
        val jobs = FakeChangeJobs().apply { failWith = IllegalStateException("binder died") }
        val watcher = watcher(jobs)

        watcher.refresh()
        dataStore.setDeviceCalendarsEnabled(false)
        watcher.refresh()
    }
}
