package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for device calendar reminders preference (Phase 4).
 *
 * Tests cover:
 * - Default value is false (opt-in feature)
 * - Setting true/false persists correctly
 * - Flow emits changes
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarRemindersPreferenceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var testDataStoreFile: File

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        testDataStoreFile.delete()
    }

    @Test
    fun `deviceCalendarRemindersEnabled defaults to true`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDeviceCalendarRemindersEnabled stores false`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            // Default
            assertTrue(awaitItem())

            // Set to false
            dataStore.setDeviceCalendarRemindersEnabled(false)
            assertFalse(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDeviceCalendarRemindersEnabled stores true after false`() = runTest {
        // Set to false first
        dataStore.setDeviceCalendarRemindersEnabled(false)

        dataStore.deviceCalendarRemindersEnabled.test {
            assertFalse(awaitItem())

            // Set back to true
            dataStore.setDeviceCalendarRemindersEnabled(true)
            assertTrue(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deviceCalendarRemindersEnabled flow emits changes`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            assertTrue(awaitItem())

            dataStore.setDeviceCalendarRemindersEnabled(false)
            assertFalse(awaitItem())

            dataStore.setDeviceCalendarRemindersEnabled(true)
            assertTrue(awaitItem())

            dataStore.setDeviceCalendarRemindersEnabled(false)
            assertFalse(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deviceCalendarRemindersEnabled does NOT emit duplicate values`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            assertTrue(awaitItem())

            // Set to same value (true) - should NOT emit
            dataStore.setDeviceCalendarRemindersEnabled(true)

            // Set to different value - SHOULD emit
            dataStore.setDeviceCalendarRemindersEnabled(false)
            assertFalse(awaitItem())

            // No intermediate emission
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getDeviceCalendarRemindersEnabled returns current value`() = runTest {
        // Default
        assertTrue(dataStore.getDeviceCalendarRemindersEnabled())

        // After setting false
        dataStore.setDeviceCalendarRemindersEnabled(false)
        assertFalse(dataStore.getDeviceCalendarRemindersEnabled())

        // After setting true
        dataStore.setDeviceCalendarRemindersEnabled(true)
        assertTrue(dataStore.getDeviceCalendarRemindersEnabled())
    }
}
