package org.onekash.kashcal.calendarbackup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarBackupControllerTest {

    private lateinit var store: CalendarBackupStore
    private val folder = FakeBackupFolder()
    private val scheduler = FakeCalendarBackupScheduler()
    private val alerts = FakeCalendarBackupAlerts()
    private val source = FakeOfflineCalendarSource()
    private lateinit var controller: CalendarBackupController

    private val tree = FakeBackupFolder.TREE
    private val other = "content://test.documents/tree/other"

    @Before
    fun setup() {
        store = freshCalendarBackupStore()
        folder.hasGrant = false
        controller = CalendarBackupController(
            store, folder, scheduler, CalendarBackupReconciler(store, scheduler), alerts, source
        )
    }

    // ---------- choosing the folder ----------

    @Test
    fun `choosing a folder takes access, turns backup on, arms it and runs once`() = runBlocking {
        assertEquals(FolderChoice.Chosen, controller.chooseFolder(tree))

        assertEquals(listOf(tree), folder.takenGrants)
        val settings = store.settings.value
        assertTrue(settings.enabled)
        assertEquals(tree, settings.folderUri)
        assertEquals("KashCal backups", settings.folderName)
        assertEquals(listOf("ensure:12", "runNow"), scheduler.calls)
        assertEquals(1, alerts.cancels)
    }

    @Test
    fun `a new folder releases the old one and starts without results`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = other, folderName = "Old") }
        store.updateStatus { it.copy(lastCheckedAt = 5, folderProblem = FolderFailure.MISSING, failedRuns = 3) }

        controller.chooseFolder(tree)

        assertEquals(listOf(other), folder.releasedGrants)
        assertEquals(CalendarBackupStatus(), store.status.value)
    }

    @Test
    fun `choosing the same folder again keeps its grant and clears the problem`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        store.updateStatus { it.copy(lastCheckedAt = 5, folderProblem = FolderFailure.NO_ACCESS, failedRuns = 2, notifiedAlert = "FolderUnreachable") }

        controller.chooseFolder(tree)

        assertTrue(folder.releasedGrants.isEmpty())
        val status = store.status.value
        assertNull(status.folderProblem)
        assertEquals(0, status.failedRuns)
        assertNull(status.notifiedAlert)
        assertEquals(5L, status.lastCheckedAt)
    }

    @Test
    fun `a refused grant keeps the old folder`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = other, folderName = "Old") }
        folder.failures["take"] = FolderFailure.NO_ACCESS

        assertEquals(FolderChoice.Refused(FolderFailure.NO_ACCESS), controller.chooseFolder(tree))
        assertEquals(other, store.settings.value.folderUri)
        assertTrue(folder.releasedGrants.isEmpty())
        assertTrue(scheduler.calls.isEmpty())
    }

    @Test
    fun `a folder that can't hold files is refused and its new grant released`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = other) }
        folder.canCreate = false

        assertEquals(FolderChoice.Refused(FolderFailure.UNSUPPORTED), controller.chooseFolder(tree))
        assertEquals(listOf(tree), folder.releasedGrants)
        assertEquals(other, store.settings.value.folderUri)
    }

    @Test
    fun `re-picking the current folder when it can't hold files keeps its grant`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        folder.canCreate = false

        assertEquals(FolderChoice.Refused(FolderFailure.UNSUPPORTED), controller.chooseFolder(tree))
        assertTrue(folder.releasedGrants.isEmpty())
    }

    // ---------- on, off, interval, copies ----------

    @Test
    fun `off keeps the folder and its grant and removes the notification`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        store.updateStatus { it.copy(notifiedAlert = "FolderUnreachable") }

        controller.setEnabled(false)

        assertFalse(store.settings.value.enabled)
        assertEquals(tree, store.settings.value.folderUri)
        assertTrue(folder.releasedGrants.isEmpty())
        assertEquals(listOf("cancel"), scheduler.calls)
        assertEquals(1, alerts.cancels)
        assertNull(store.status.value.notifiedAlert)
    }

    @Test
    fun `on with a folder arms the job and runs once`() = runBlocking {
        store.updateSettings { it.copy(enabled = false, folderUri = tree, intervalHours = 24) }

        controller.setEnabled(true)

        assertEquals(listOf("ensure:24", "runNow"), scheduler.calls)
    }

    @Test
    fun `on without a folder does nothing`() = runBlocking {
        controller.setEnabled(true)
        assertFalse(store.settings.value.enabled)
        assertTrue(scheduler.calls.isEmpty())
    }

    @Test
    fun `a new interval moves the armed job`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        controller.setInterval(54)
        assertEquals(54, store.settings.value.intervalHours)
        assertEquals(listOf("ensure:54"), scheduler.calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an invalid interval is refused`() = runBlocking {
        controller.setInterval(0)
    }

    @Test
    fun `a new count runs a backup so the trim happens now`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        controller.setKeep(1)
        assertEquals(1, store.settings.value.keepCount)
        assertEquals(listOf("runNow"), scheduler.calls)
    }

    @Test
    fun `a new count while off only saves it`() = runBlocking {
        controller.setKeep(5)
        assertEquals(5, store.settings.value.keepCount)
        assertTrue(scheduler.calls.isEmpty())
    }

    @Test
    fun `removable copies counts what a lower count would delete`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        source.add(1, "Local", "x", builtIn = true)
        source.add(2, "Trips", "y")
        listOf(
            "Local.ics", "Local_20261020T000000Z.ics", "Local_20261021T000000Z.ics",
            "Trips.ics", "Trips_20261020T000000Z.ics", "notes.txt",
        ).forEach { folder.put(it, "z") }

        assertEquals(3, controller.removableCopies(1))
        assertEquals(1, controller.removableCopies(2))
        assertEquals(0, controller.removableCopies(3))
    }

    @Test
    fun `removable copies is unknown when the folder can't be read`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        folder.missing = true
        assertNull(controller.removableCopies(1))
    }

    @Test
    fun `removable copies leaves out files the folder can't delete`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        source.add(2, "Trips", "y")
        folder.put("Trips.ics", "z")
        folder.put("Trips_20261020T000000Z.ics", "z")
        folder.deleteSupported = false
        assertEquals(0, controller.removableCopies(1))
    }

    @Test
    fun `back up now runs only while on with a folder`() = runBlocking {
        controller.backUpNow()
        assertTrue(scheduler.calls.isEmpty())
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        controller.backUpNow()
        assertEquals(listOf("runNow"), scheduler.calls)
    }

    // ---------- the sheet's model ----------

    @Test
    fun `the model lists each offline calendar with its file and last result`() = runBlocking {
        source.add(1, "Lokal", "x", builtIn = true)
        source.add(17, "Work", "y")
        source.add(18, "Work!", "z")
        val result = CalendarBackupResult(CalendarBackupResult.Kind.UPDATED, 9, "Work-17.ics")
        store.updateStatus { it.copy(results = mapOf(17L to result)) }
        scheduler.runningFlow.value = true

        val model = controller.state.first()

        assertTrue(model.running)
        assertEquals(listOf("Local.ics", "Work-17.ics", "Work-18.ics"), model.calendars.map { it.fileName })
        assertEquals(listOf("Lokal", "Work", "Work!"), model.calendars.map { it.name })
        assertEquals(result, model.calendars[1].result)
        assertNull(model.calendars[2].result)
    }

    // ---------- the notifications hint ----------

    private fun newController() = CalendarBackupController(
        store, folder, scheduler, CalendarBackupReconciler(store, scheduler), alerts, source
    )

    private suspend fun hint() = controller.state.first().notificationsHint

    @Test
    fun `the hint shows only while backup is on and notifications are off`() = runBlocking {
        alerts.state = BackupNotificationState.APP_OFF
        controller = newController()
        assertNull(hint())
        store.updateSettings { it.copy(folderUri = tree) }
        assertNull(hint())
        store.updateSettings { it.copy(enabled = true) }
        assertEquals(BackupNotificationState.APP_OFF, hint())

        alerts.state = BackupNotificationState.CHANNEL_OFF
        controller.refreshNotifications()
        assertEquals(BackupNotificationState.CHANNEL_OFF, hint())

        alerts.state = BackupNotificationState.ON
        controller.refreshNotifications()
        assertNull(hint())
    }

    @Test
    fun `Not now hides the hint and is kept until notifications are on again`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        alerts.state = BackupNotificationState.APP_OFF
        controller = newController()

        controller.dismissNotificationsHint()
        assertNull(hint())
        assertEquals(BackupNotificationState.APP_OFF, store.dismissedHint.value)
        controller.refreshNotifications()
        assertNull(newController().state.first().notificationsHint)

        alerts.state = BackupNotificationState.ON
        controller.refreshNotifications()
        assertNull(store.dismissedHint.value)
        alerts.state = BackupNotificationState.APP_OFF
        controller.refreshNotifications()
        assertEquals(BackupNotificationState.APP_OFF, hint())
    }

    @Test
    fun `a dismissed app-level hint doesn't hide the channel one`() = runBlocking {
        store.updateSettings { it.copy(enabled = true, folderUri = tree) }
        alerts.state = BackupNotificationState.APP_OFF
        controller = newController()
        controller.dismissNotificationsHint()

        alerts.state = BackupNotificationState.CHANNEL_OFF
        controller.refreshNotifications()

        assertEquals(BackupNotificationState.CHANNEL_OFF, hint())
    }

    @Test
    fun `Not now with notifications on stores nothing`() = runBlocking {
        controller.dismissNotificationsHint()
        assertNull(store.dismissedHint.value)
    }
}
