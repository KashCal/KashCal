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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for KashCalDataStore.
 *
 * Tests cover:
 * - Default value emission for getPreference
 * - Value updates and emissions
 * - Optional preference handling (null values)
 * - Complex preference parsing (visibleCalendarIds)
 * - distinctUntilChanged behavior (no duplicate emissions)
 *
 * Uses Robolectric for Android Context and Turbine for Flow assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class KashCalDataStoreTest {

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
        // Clear DataStore to avoid state leakage between tests
        dataStore.dataStore.edit { it.clear() }
    }

    // ==================== getPreference Tests ====================

    @Test
    fun `getPreference returns default when key not set`() = runTest {
        dataStore.theme.test {
            assertEquals(KashCalDataStore.THEME_SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getPreference emits updated value after set`() = runTest {
        dataStore.theme.test {
            // Initial default value
            assertEquals(KashCalDataStore.THEME_SYSTEM, awaitItem())

            // Set new value
            dataStore.setTheme(KashCalDataStore.THEME_DARK)
            assertEquals(KashCalDataStore.THEME_DARK, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getPreference does NOT emit duplicate values`() = runTest {
        dataStore.theme.test {
            // Initial default value
            assertEquals(KashCalDataStore.THEME_SYSTEM, awaitItem())

            // Set to same value - should NOT emit after distinctUntilChanged is added
            dataStore.setTheme(KashCalDataStore.THEME_SYSTEM)

            // Set to different value - SHOULD emit
            dataStore.setTheme(KashCalDataStore.THEME_DARK)
            assertEquals(KashCalDataStore.THEME_DARK, awaitItem())

            // Verify no intermediate emission occurred
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getPreference emits multiple distinct changes`() = runTest {
        dataStore.defaultReminderMinutes.test {
            // Default value
            assertEquals(KashCalDataStore.DEFAULT_REMINDER_MINUTES, awaitItem())

            // Change to 30
            dataStore.setDefaultReminderMinutes(30)
            assertEquals(30, awaitItem())

            // Change to 60
            dataStore.setDefaultReminderMinutes(60)
            assertEquals(60, awaitItem())

            // Change back to 30
            dataStore.setDefaultReminderMinutes(30)
            assertEquals(30, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== getOptionalPreference Tests ====================

    @Test
    fun `getOptionalPreference returns null when not set`() = runTest {
        dataStore.defaultCalendarId.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getOptionalPreference emits value when set`() = runTest {
        dataStore.defaultCalendarId.test {
            // Initially null
            assertNull(awaitItem())

            // Set value
            dataStore.setDefaultCalendarId(42L)
            assertEquals(42L, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getOptionalPreference does NOT emit duplicate values`() = runTest {
        // First set a value
        dataStore.setDefaultCalendarId(42L)

        dataStore.defaultCalendarId.test {
            assertEquals(42L, awaitItem())

            // Set same value - should NOT emit after distinctUntilChanged is added
            dataStore.setDefaultCalendarId(42L)

            // Set different value - SHOULD emit
            dataStore.setDefaultCalendarId(99L)
            assertEquals(99L, awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== Cross-preference Isolation Tests ====================

    @Test
    fun `changing one preference does NOT trigger emission in another`() = runTest {
        // Start observing theme
        dataStore.theme.test {
            assertEquals(KashCalDataStore.THEME_SYSTEM, awaitItem())

            // Change a DIFFERENT preference (defaultReminderMinutes)
            dataStore.setDefaultReminderMinutes(45)

            // After distinctUntilChanged, theme should NOT emit again
            // (because its value didn't change, only another preference did)

            // Change theme - this SHOULD emit
            dataStore.setTheme(KashCalDataStore.THEME_DARK)
            assertEquals(KashCalDataStore.THEME_DARK, awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== Boolean Preference Tests ====================

    @Test
    fun `boolean preference returns default and updates`() = runTest {
        dataStore.autoSyncEnabled.test {
            // Default is true
            assertEquals(true, awaitItem())

            // Set to false
            dataStore.setAutoSyncEnabled(false)
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `boolean preference does NOT emit duplicate values`() = runTest {
        dataStore.autoSyncEnabled.test {
            assertEquals(true, awaitItem())

            // Set to same value - should NOT emit
            dataStore.setAutoSyncEnabled(true)

            // Set to different value - SHOULD emit
            dataStore.setAutoSyncEnabled(false)
            assertEquals(false, awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== Int Preference Tests ====================

    @Test
    fun `int preference returns default and updates`() = runTest {
        dataStore.syncIntervalMinutes.test {
            assertEquals(KashCalDataStore.DEFAULT_SYNC_INTERVAL_MINUTES, awaitItem())

            dataStore.setSyncIntervalMinutes(30)
            assertEquals(30, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `default sync interval is 1 hour`() {
        // Verify default sync interval is 1 hour (60 minutes) for efficient battery usage
        // with lightweight ctag-based incremental sync
        assertEquals(60, KashCalDataStore.DEFAULT_SYNC_INTERVAL_MINUTES)
        assertEquals(1L * 60 * 60 * 1000, KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS)
    }
}
