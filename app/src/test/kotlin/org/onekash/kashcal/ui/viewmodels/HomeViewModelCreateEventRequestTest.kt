package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [HomeViewModel.requestCreateEvent], the create request from a widget, the New Event
 * shortcut or the Quick Settings tile (#403). The stored Quick Add setting arrives the way
 * DataStore delivers it on a cold start: a cold flow that emits only after a disk read, with
 * nothing yet subscribed to [HomeViewModel.quickAddEnabled]. Collaborators are the strict mocks
 * of [DeviceHomeViewModelTestFactory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelCreateEventRequestTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        factory = DeviceHomeViewModelTestFactory()
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Builds the ViewModel over a stored Quick Add setting that takes [READ_DELAY_MS] to read. */
    private fun createViewModel(storedQuickAdd: Boolean): HomeViewModel {
        // Stubbed before construction: quickAddEnabled's stateIn captures the flow then.
        every { factory.dataStore.quickAddEnabled } returns flow {
            delay(READ_DELAY_MS)
            emit(storedQuickAdd)
        }
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    @Test
    fun `on a cold start a create request opens Quick Add when it is stored on`() = runTest {
        val vm = createViewModel(storedQuickAdd = true)
        advanceUntilIdle()

        vm.requestCreateEvent(startTs = null)
        advanceUntilIdle()

        assertEquals(PendingAction.OpenQuickAdd, vm.uiState.value.pendingAction)
        // The UI-facing value is still its placeholder: deciding on it would open the form.
        assertFalse(vm.quickAddEnabled.value)
    }

    @Test
    fun `a create request opens the full form when Quick Add is stored off`() = runTest {
        val vm = createViewModel(storedQuickAdd = false)
        advanceUntilIdle()

        vm.requestCreateEvent(startTs = null)
        advanceUntilIdle()

        assertEquals(PendingAction.CreateEvent(startTs = null), vm.uiState.value.pendingAction)
    }

    @Test
    fun `a create request with a start time opens the form at once`() = runTest {
        val vm = createViewModel(storedQuickAdd = true)
        advanceUntilIdle()

        vm.requestCreateEvent(startTs = START_TS)

        assertEquals(PendingAction.CreateEvent(startTs = START_TS), vm.uiState.value.pendingAction)
        advanceUntilIdle()
        assertEquals(PendingAction.CreateEvent(startTs = START_TS), vm.uiState.value.pendingAction)
    }

    @Test
    fun `an action set while the setting is read is not overwritten`() = runTest {
        val vm = createViewModel(storedQuickAdd = true)
        advanceUntilIdle()

        vm.requestCreateEvent(startTs = null)
        vm.setPendingAction(PendingAction.OpenSearch)
        advanceUntilIdle()

        assertEquals(PendingAction.OpenSearch, vm.uiState.value.pendingAction)
    }

    @Test
    fun `a second create request replaces one still reading the setting`() = runTest {
        val vm = createViewModel(storedQuickAdd = true)
        advanceUntilIdle()

        vm.requestCreateEvent(startTs = null)
        vm.requestCreateEvent(startTs = START_TS)
        advanceUntilIdle()

        assertEquals(PendingAction.CreateEvent(startTs = START_TS), vm.uiState.value.pendingAction)
    }

    @Test
    fun `clearing an earlier action keeps a create request that is reading the setting`() = runTest {
        val vm = createViewModel(storedQuickAdd = true)
        advanceUntilIdle()

        vm.requestCreateEvent(startTs = null)
        vm.clearPendingAction()
        advanceUntilIdle()

        assertEquals(PendingAction.OpenQuickAdd, vm.uiState.value.pendingAction)
    }

    private companion object {
        const val READ_DELAY_MS = 100L
        const val START_TS = 1_767_225_600_000L
    }
}
