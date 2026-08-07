package org.onekash.kashcal.ui.viewmodels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.lock.AppLockStateMachine

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val grace = AppLockStateMachine.DEFAULT_GRACE_MS
    private lateinit var dataStore: KashCalDataStore

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dataStore = mockk(relaxed = false)
        every { dataStore.appLockEnabled } returns flowOf(false)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AppLockViewModel(dataStore)

    @Test
    fun `appLockEnabled surfaces the datastore value`() = runTest {
        every { dataStore.appLockEnabled } returns flowOf(true)
        val vm = viewModel()
        // Eager stateIn collection is queued on the test dispatcher; let it run.
        advanceUntilIdle()
        assertTrue(vm.appLockEnabled.value)
    }

    @Test
    fun `setAppLockEnabled true persists the flag`() = runTest {
        coEvery { dataStore.setAppLockEnabled(any()) } returns Unit
        val vm = viewModel()
        vm.setAppLockEnabled(true)
        advanceUntilIdle()
        coVerify { dataStore.setAppLockEnabled(true) }
    }

    @Test
    fun `setAppLockEnabled false persists the flag`() = runTest {
        coEvery { dataStore.setAppLockEnabled(any()) } returns Unit
        val vm = viewModel()
        vm.setAppLockEnabled(false)
        advanceUntilIdle()
        coVerify { dataStore.setAppLockEnabled(false) }
    }

    @Test
    fun `disabled never locks`() = runTest {
        val vm = viewModel()
        vm.onActivityCreated(enabled = false)
        advanceUntilIdle()
        assertFalse(vm.lockState.value)
    }

    @Test
    fun `enabled locks on first activity create`() = runTest {
        val vm = viewModel()
        vm.onActivityCreated(enabled = true)
        assertTrue(vm.lockState.value)
    }

    @Test
    fun `onActivityCreated is idempotent across rotation (does not re-lock after unlock)`() = runTest {
        val vm = viewModel()
        vm.onActivityCreated(enabled = true)
        vm.onUnlockSucceeded()
        assertFalse(vm.lockState.value)

        // Rotation recreates the Activity; the retained VM's second create is a no-op.
        vm.onActivityCreated(enabled = true)
        assertFalse(vm.lockState.value)
    }

    @Test
    fun `background then long return re-locks`() = runTest {
        val vm = viewModel()
        vm.onActivityCreated(enabled = true)
        vm.onUnlockSucceeded()
        assertFalse(vm.lockState.value)

        vm.onBackground(1_000L)
        vm.onForeground(enabled = true, nowElapsed = 1_000L + grace)
        assertTrue(vm.lockState.value)
    }

    @Test
    fun `quick switch within grace stays unlocked`() = runTest {
        val vm = viewModel()
        vm.onActivityCreated(enabled = true)
        vm.onUnlockSucceeded()

        vm.onBackground(1_000L)
        vm.onForeground(enabled = true, nowElapsed = 1_000L + (grace - 1))
        assertFalse(vm.lockState.value)
    }

    @Test
    fun `unlock success then error keeps state correct`() = runTest {
        val vm = viewModel()
        vm.onActivityCreated(enabled = true)
        assertTrue(vm.lockState.value)

        // A failed/cancelled prompt leaves it locked.
        vm.onUnlockError()
        assertTrue(vm.lockState.value)

        // A subsequent success reveals.
        vm.onUnlockSucceeded()
        assertFalse(vm.lockState.value)
    }

    @Test
    fun `internal-nav return does not re-lock past grace`() = runTest {
        val vm = viewModel()
        vm.onActivityCreated(enabled = true)
        vm.onUnlockSucceeded()

        vm.onBackground(1_000L)
        vm.onForeground(enabled = true, nowElapsed = 1_000L + grace * 4, suppressRelock = true)
        assertFalse(vm.lockState.value)
    }
}
