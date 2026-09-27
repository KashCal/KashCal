package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.reader.DeviceEventReader
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.DeviceEventWriter
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.testutil.TestDataStoreFactory

/**
 * A [HomeViewModel] for device-calendar tests, wired to the device reader and
 * writer the test supplies (over the shared fake or the real repository).
 *
 * Every data-bearing collaborator is a strict mock stubbed with exactly what
 * the ViewModel reads while starting up, reloading and saving device events:
 * no Room events, calendars or accounts. A read outside that set throws, so a
 * test can't pass on a value nobody chose. Only side-effect collaborators
 * (sync scheduling, network state, the attendee backfill) are relaxed, with
 * the flows the ViewModel collects stubbed; the context is relaxed for strings
 * and answers the snackbar strings with recognisable placeholders.
 *
 * A test of Room saves passes the real [EventCoordinator] and [EventReader]
 * over its own database in place of the strict Room mocks.
 */
class DeviceHomeViewModelTestFactory(
    eventCoordinator: EventCoordinator? = null,
    eventReader: EventReader? = null,
) {

    val eventCoordinator: EventCoordinator = eventCoordinator ?: mockk {
        every { getAllCalendars() } returns MutableStateFlow(emptyList())
        every { getAllAccounts() } returns MutableStateFlow(emptyList())
        coEvery { extendOccurrencesIfNeeded(any(), any()) } returns 0
        coEvery { extendPastOccurrencesIfNeeded(any(), any()) } returns 0
        coEvery { repairMissingOccurrences() } returns 0
        coJustRun { recordTagUsage(any()) }
    }

    val eventReader: EventReader = eventReader ?: mockk {
        every { getVisibleOccurrencesInRange(any(), any()) } returns MutableStateFlow(emptyList())
        every { getVisibleOccurrencesForDay(any()) } returns MutableStateFlow(emptyList())
        every { getVisibleOccurrencesWithEventsForDay(any()) } returns MutableStateFlow(emptyList())
        every { getPendingInvitations(any()) } returns MutableStateFlow(emptyList())
        every { observeTagColors() } returns MutableStateFlow(emptyMap())
        every { getRecentCategories() } returns MutableStateFlow(emptyList())
    }

    val displayEventRepository: DisplayEventRepository = mockk {
        every { deviceCalendarChangeSignal } returns MutableStateFlow(0)
        every { getDisplayEventsForRange(any(), any()) } returns MutableStateFlow(persistentListOf())
        every { getDisplayEventsForDayRange(any()) } returns MutableStateFlow(persistentMapOf())
        every { getDisplayEventsForDateRange(any(), any()) } returns MutableStateFlow(persistentMapOf())
        coEvery { getDisplayEventsGroupedByDayOnce(any(), any()) } returns emptyMap()
    }

    val accountRepository: AccountRepository = mockk {
        coEvery { getAccountsByProvider(any()) } returns emptyList()
        coEvery { hasCredentials(any()) } returns false
    }

    val syncScheduler: SyncScheduler = mockk(relaxed = true) {
        every { observeImmediateSyncStatus() } returns MutableStateFlow(SyncStatus.Idle)
        every { lastSyncChanges } returns MutableStateFlow(emptyList())
        every { showBannerForSync } returns MutableStateFlow(false)
    }

    val networkMonitor: NetworkMonitor = mockk(relaxed = true) {
        every { isOnline } returns MutableStateFlow(true)
        every { isMetered } returns MutableStateFlow(false)
    }

    val context: Context = mockk(relaxed = true) {
        every { getString(R.string.snackbar_event_rescheduled) } returns RESCHEDULED_MESSAGE
        every { getString(R.string.snackbar_reschedule_failed) } returns RESCHEDULE_FAILED_MESSAGE
    }

    val dataStore: KashCalDataStore = TestDataStoreFactory.createStrictForHomeViewModel()

    fun create(
        deviceEventReader: DeviceEventReader,
        deviceEventWriter: DeviceEventWriter,
        ioDispatcher: CoroutineDispatcher,
    ): HomeViewModel = HomeViewModel(
        eventCoordinator = eventCoordinator,
        eventReader = eventReader,
        displayEventRepository = displayEventRepository,
        dataStore = dataStore,
        accountRepository = accountRepository,
        syncScheduler = syncScheduler,
        networkMonitor = networkMonitor,
        deviceEventReader = deviceEventReader,
        deviceEventWriter = deviceEventWriter,
        attendeeBackfill = mockk(relaxed = true),
        // Strict: the device paths never look up contacts.
        contactEmailReader = mockk(),
        context = context,
        ioDispatcher = ioDispatcher,
    )

    companion object {
        const val RESCHEDULED_MESSAGE = "res:snackbar_event_rescheduled"
        const val RESCHEDULE_FAILED_MESSAGE = "res:snackbar_reschedule_failed"
    }
}
