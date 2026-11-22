package org.onekash.kashcal.ui.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.calendarbackup.BackupNotificationState
import org.onekash.kashcal.calendarbackup.CalendarBackupController
import org.onekash.kashcal.calendarbackup.CalendarBackupReconciler
import org.onekash.kashcal.calendarbackup.CalendarBackupStore
import org.onekash.kashcal.calendarbackup.FakeBackupFolder
import org.onekash.kashcal.calendarbackup.FakeCalendarBackupAlerts
import org.onekash.kashcal.calendarbackup.FakeCalendarBackupScheduler
import org.onekash.kashcal.calendarbackup.FakeOfflineCalendarSource
import org.onekash.kashcal.calendarbackup.FolderFailure
import org.onekash.kashcal.calendarbackup.freshCalendarBackupStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarBackupViewModelTest {

    private lateinit var store: CalendarBackupStore
    private val folder = FakeBackupFolder()
    private val scheduler = FakeCalendarBackupScheduler()
    private val source = FakeOfflineCalendarSource()
    private val alerts = FakeCalendarBackupAlerts()
    private lateinit var viewModel: CalendarBackupViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = freshCalendarBackupStore()
        val controller = CalendarBackupController(
            store, folder, scheduler, CalendarBackupReconciler(store, scheduler), alerts, source
        )
        viewModel = CalendarBackupViewModel(controller)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun setUpWithCopies() {
        store.updateSettings { it.copy(enabled = true, folderUri = FakeBackupFolder.TREE, keepCount = 3) }
        source.add(2, "Trips", "x")
        listOf("Trips.ics", "Trips_20261020T000000Z.ics", "Trips_20261021T000000Z.ics").forEach { folder.put(it, "z") }
    }

    @Test
    fun `a picked folder is chosen and a cancelled picker does nothing`() {
        viewModel.onFolderPicked(null)
        assertNull(store.settings.value.folderUri)
        viewModel.onFolderPicked(FakeBackupFolder.TREE)
        assertEquals(FakeBackupFolder.TREE, store.settings.value.folderUri)
        assertNull(viewModel.pickError.value)
    }

    @Test
    fun `a refused folder shows until the sheet closes`() {
        folder.failures["take"] = FolderFailure.NO_ACCESS
        viewModel.onFolderPicked(FakeBackupFolder.TREE)
        assertEquals(FolderFailure.NO_ACCESS, viewModel.pickError.value)
        viewModel.clearTransient()
        assertNull(viewModel.pickError.value)
    }

    @Test
    fun `more copies apply at once`() {
        setUpWithCopies()
        viewModel.requestKeep(4)
        assertEquals(4, store.settings.value.keepCount)
        assertNull(viewModel.keepAsk.value)
    }

    @Test
    fun `fewer copies that remove files wait for confirm`() {
        setUpWithCopies()
        viewModel.requestKeep(1)
        assertEquals(KeepAsk(next = 1, removed = 2), viewModel.keepAsk.value)
        assertEquals(3, store.settings.value.keepCount)

        viewModel.confirmKeep()
        assertEquals(1, store.settings.value.keepCount)
        assertNull(viewModel.keepAsk.value)
    }

    @Test
    fun `fewer copies ask even when the folder can't be read to count them`() {
        setUpWithCopies()
        folder.missing = true
        viewModel.requestKeep(1)
        assertEquals(KeepAsk(next = 1, removed = null), viewModel.keepAsk.value)
        assertEquals(3, store.settings.value.keepCount)
    }

    @Test
    fun `cancel keeps the count`() {
        setUpWithCopies()
        viewModel.requestKeep(1)
        viewModel.cancelKeep()
        assertNull(viewModel.keepAsk.value)
        assertEquals(3, store.settings.value.keepCount)
    }

    @Test
    fun `fewer copies that remove nothing apply at once`() {
        setUpWithCopies()
        viewModel.requestKeep(2)
        assertEquals(KeepAsk(next = 2, removed = 1), viewModel.keepAsk.value)
        folder.files.remove("Trips_20261020T000000Z.ics")
        viewModel.cancelKeep()
        viewModel.requestKeep(2)
        assertNull(viewModel.keepAsk.value)
        assertEquals(2, store.settings.value.keepCount)
    }

    @Test
    fun `interval, on and off, and back up now reach the controller`() {
        store.updateSettings { it.copy(enabled = true, folderUri = FakeBackupFolder.TREE) }
        viewModel.setInterval(54)
        viewModel.backUpNow()
        viewModel.setEnabled(false)
        assertEquals(listOf("ensure:54", "runNow", "cancel"), scheduler.calls)
    }

    @Test
    fun `a request to open the sheet waits until the screen takes it`() {
        assertEquals(false, viewModel.sheetRequested.value)
        viewModel.requestSheet()
        assertEquals(true, viewModel.sheetRequested.value)
        viewModel.consumeSheetRequest()
        assertEquals(false, viewModel.sheetRequested.value)
    }

    @Test
    fun `refresh and Not now reach the controller`() {
        alerts.state = BackupNotificationState.CHANNEL_OFF
        viewModel.refreshNotifications()
        viewModel.dismissNotificationsHint()
        assertEquals(BackupNotificationState.CHANNEL_OFF, store.dismissedHint.value)
        alerts.state = BackupNotificationState.ON
        viewModel.refreshNotifications()
        assertNull(store.dismissedHint.value)
    }
}
