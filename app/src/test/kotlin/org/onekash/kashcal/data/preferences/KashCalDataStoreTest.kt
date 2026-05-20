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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    // ==================== First Day of Week Tests ====================

    @Test
    fun `firstDayOfWeek defaults to FIRST_DAY_SYSTEM (0) not Sunday`() = runTest {
        dataStore.firstDayOfWeek.test {
            // Must be 0 (system locale), not 1 (Calendar.SUNDAY)
            assertEquals(KashCalDataStore.FIRST_DAY_SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFirstDayOfWeek updates Flow`() = runTest {
        dataStore.firstDayOfWeek.test {
            assertEquals(KashCalDataStore.FIRST_DAY_SYSTEM, awaitItem())

            dataStore.setFirstDayOfWeek(java.util.Calendar.MONDAY)
            assertEquals(java.util.Calendar.MONDAY, awaitItem())

            dataStore.setFirstDayOfWeek(java.util.Calendar.SATURDAY)
            assertEquals(java.util.Calendar.SATURDAY, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FIRST_DAY_SYSTEM constant is 0`() {
        // Guard against accidental constant change
        assertEquals(0, KashCalDataStore.FIRST_DAY_SYSTEM)
    }

    // ==================== Default Calendar View Tests ====================

    @Test
    fun `defaultCalendarView emits VIEW_MONTH when unset`() = runTest {
        dataStore.defaultCalendarView.test {
            assertEquals(KashCalDataStore.VIEW_MONTH, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDefaultCalendarView updates Flow`() = runTest {
        dataStore.defaultCalendarView.test {
            assertEquals(KashCalDataStore.VIEW_MONTH, awaitItem())

            dataStore.setDefaultCalendarView(KashCalDataStore.VIEW_AGENDA)
            assertEquals(KashCalDataStore.VIEW_AGENDA, awaitItem())

            dataStore.setDefaultCalendarView(KashCalDataStore.VIEW_THREE_DAYS)
            assertEquals(KashCalDataStore.VIEW_THREE_DAYS, awaitItem())

            dataStore.setDefaultCalendarView(KashCalDataStore.VIEW_MONTH_FULL)
            assertEquals(KashCalDataStore.VIEW_MONTH_FULL, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDefaultCalendarView VIEW_WEEK round-trip`() = runTest {
        dataStore.defaultCalendarView.test {
            assertEquals(KashCalDataStore.VIEW_MONTH, awaitItem())

            dataStore.setDefaultCalendarView(KashCalDataStore.VIEW_WEEK)
            assertEquals(KashCalDataStore.VIEW_WEEK, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDefaultCalendarView rejects invalid values`() = runTest {
        try {
            dataStore.setDefaultCalendarView("invalid_view")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    /**
     * Locks the VALID_VIEWS allowlist to the ViewMode enum: every persistable
     * ViewMode key must round-trip through setDefaultCalendarView. Adding a new
     * ViewMode without updating VALID_VIEWS will fail this test.
     *
     * INSIGHTS is excluded — HomeViewModel.setViewMode() never persists it.
     */
    @Test
    fun `every persistable ViewMode key round-trips through setDefaultCalendarView`() = runTest {
        val persistableModes = ViewMode.entries.filter { it != ViewMode.INSIGHTS }

        for (mode in persistableModes) {
            dataStore.setDefaultCalendarView(mode.key)
            assertEquals(mode.key, dataStore.getDefaultCalendarView())
        }
    }
}
