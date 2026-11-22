package org.onekash.kashcal.calendarbackup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarBackupReconcilerTest {

    private lateinit var store: CalendarBackupStore
    private val scheduler = FakeCalendarBackupScheduler()
    private lateinit var reconciler: CalendarBackupReconciler

    @Before
    fun setup() {
        store = freshCalendarBackupStore()
        reconciler = CalendarBackupReconciler(store, scheduler)
    }

    @Test
    fun `on with a folder arms the job at the chosen interval`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = "content://t/tree/a", intervalHours = 54) }
        reconciler.reconcile()
        assertEquals(listOf("ensure:54"), scheduler.calls)
    }

    @Test
    fun `off or without a folder cancels the job`() = runBlocking {
        store.updateSettings { it.copy(enabled = false, folderUri = "content://t/tree/a") }
        reconciler.reconcile()
        store.updateSettings { it.copy(enabled = true, folderUri = null) }
        reconciler.reconcile()
        assertEquals(listOf("cancel", "cancel"), scheduler.calls)
    }

    @Test
    fun `a scheduler failure is logged, not thrown`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = "content://t/tree/a") }
        val failing = object : CalendarBackupScheduler by scheduler {
            override suspend fun ensurePeriodic(intervalHours: Int) = error("disk full")
        }
        CalendarBackupReconciler(store, failing).reconcile()
    }
}
