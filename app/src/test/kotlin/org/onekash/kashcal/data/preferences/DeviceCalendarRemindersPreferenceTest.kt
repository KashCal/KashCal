package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        dataStore = KashCalDataStore(context)
    }

    @After
    fun teardown() = runTest {
        Dispatchers.resetMain()
        dataStore.dataStore.edit { it.clear() }
    }

    @Test
    fun `deviceCalendarRemindersEnabled defaults to false`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDeviceCalendarRemindersEnabled stores true`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            // Default
            assertFalse(awaitItem())

            // Set to true
            dataStore.setDeviceCalendarRemindersEnabled(true)
            assertTrue(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDeviceCalendarRemindersEnabled stores false after true`() = runTest {
        // Set to true first
        dataStore.setDeviceCalendarRemindersEnabled(true)

        dataStore.deviceCalendarRemindersEnabled.test {
            assertTrue(awaitItem())

            // Set back to false
            dataStore.setDeviceCalendarRemindersEnabled(false)
            assertFalse(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deviceCalendarRemindersEnabled flow emits changes`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            assertFalse(awaitItem())

            dataStore.setDeviceCalendarRemindersEnabled(true)
            assertTrue(awaitItem())

            dataStore.setDeviceCalendarRemindersEnabled(false)
            assertFalse(awaitItem())

            dataStore.setDeviceCalendarRemindersEnabled(true)
            assertTrue(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deviceCalendarRemindersEnabled does NOT emit duplicate values`() = runTest {
        dataStore.deviceCalendarRemindersEnabled.test {
            assertFalse(awaitItem())

            // Set to same value (false) - should NOT emit
            dataStore.setDeviceCalendarRemindersEnabled(false)

            // Set to different value - SHOULD emit
            dataStore.setDeviceCalendarRemindersEnabled(true)
            assertTrue(awaitItem())

            // No intermediate emission
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getDeviceCalendarRemindersEnabled returns current value`() = runTest {
        // Default
        assertFalse(dataStore.getDeviceCalendarRemindersEnabled())

        // After setting true
        dataStore.setDeviceCalendarRemindersEnabled(true)
        assertTrue(dataStore.getDeviceCalendarRemindersEnabled())

        // After setting false
        dataStore.setDeviceCalendarRemindersEnabled(false)
        assertFalse(dataStore.getDeviceCalendarRemindersEnabled())
    }
}
