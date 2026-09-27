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
 * Tests [KashCalDataStore]: defaults and updates through [KashCalDataStore.getPreference] and
 * [KashCalDataStore.getOptionalPreference] (null when unset), no duplicate or cross-preference
 * emissions, and the defaults, clamps and validation of individual preferences (theme, accent,
 * reminders, auto sync, tags above notes, app lock, sync interval, week view scroll and zoom,
 * first day of week, default view).
 *
 * Uses Robolectric for the Context and Turbine for Flow assertions.
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
            assertEquals(KashCalDataStore.THEME_SYSTEM, awaitItem())

            dataStore.setTheme(KashCalDataStore.THEME_DARK)
            assertEquals(KashCalDataStore.THEME_DARK, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getPreference does NOT emit duplicate values`() = runTest {
        dataStore.theme.test {
            assertEquals(KashCalDataStore.THEME_SYSTEM, awaitItem())

            // Writing the current value emits nothing; only the change to dark emits.
            dataStore.setTheme(KashCalDataStore.THEME_SYSTEM)

            dataStore.setTheme(KashCalDataStore.THEME_DARK)
            assertEquals(KashCalDataStore.THEME_DARK, awaitItem())

            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== accent seed ====================

    @Test
    fun `accentSeed defaults to brand teal`() = runTest {
        dataStore.accentSeed.test {
            assertEquals(KashCalDataStore.ACCENT_SEED_DEFAULT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `accentSeed emits the value after set`() = runTest {
        dataStore.accentSeed.test {
            assertEquals(KashCalDataStore.ACCENT_SEED_DEFAULT, awaitItem())
            dataStore.setAccentSeed(0xFFFF6347.toInt())
            assertEquals(0xFFFF6347.toInt(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getPreference emits multiple distinct changes`() = runTest {
        dataStore.defaultReminderMinutes.test {
            assertEquals(KashCalDataStore.DEFAULT_REMINDER_MINUTES, awaitItem())

            dataStore.setDefaultReminderMinutes(30)
            assertEquals(30, awaitItem())

            dataStore.setDefaultReminderMinutes(60)
            assertEquals(60, awaitItem())

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
            assertNull(awaitItem())

            dataStore.setDefaultCalendarId(42L)
            assertEquals(42L, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getOptionalPreference does NOT emit duplicate values`() = runTest {
        dataStore.setDefaultCalendarId(42L)

        dataStore.defaultCalendarId.test {
            assertEquals(42L, awaitItem())

            // Writing the current value emits nothing; only the change to 99 emits.
            dataStore.setDefaultCalendarId(42L)

            dataStore.setDefaultCalendarId(99L)
            assertEquals(99L, awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== Cross-preference Isolation Tests ====================

    @Test
    fun `changing one preference does NOT trigger emission in another`() = runTest {
        dataStore.theme.test {
            assertEquals(KashCalDataStore.THEME_SYSTEM, awaitItem())

            // Another preference's write leaves theme's value unchanged, so theme doesn't emit.
            dataStore.setDefaultReminderMinutes(45)

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
            assertEquals(true, awaitItem())

            dataStore.setAutoSyncEnabled(false)
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `boolean preference does NOT emit duplicate values`() = runTest {
        dataStore.autoSyncEnabled.test {
            assertEquals(true, awaitItem())

            // Writing the current value emits nothing; only the change to false emits.
            dataStore.setAutoSyncEnabled(true)

            dataStore.setAutoSyncEnabled(false)
            assertEquals(false, awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tagsAboveNotes defaults to false and round-trips`() = runTest {
        dataStore.tagsAboveNotes.test {
            // Tags sit below the notes/attendees block by default.
            assertEquals(false, awaitItem())

            dataStore.setTagsAboveNotes(true)
            assertEquals(true, awaitItem())

            dataStore.setTagsAboveNotes(false)
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `appLockEnabled defaults to false and round-trips`() = runTest {
        dataStore.appLockEnabled.test {
            assertEquals(false, awaitItem())

            dataStore.setAppLockEnabled(true)
            assertEquals(true, awaitItem())

            dataStore.setAppLockEnabled(false)
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `appLockEnabled does NOT emit duplicate values`() = runTest {
        dataStore.appLockEnabled.test {
            assertEquals(false, awaitItem())

            // Writing the current value emits nothing; only the change to true emits.
            dataStore.setAppLockEnabled(false)

            dataStore.setAppLockEnabled(true)
            assertEquals(true, awaitItem())

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
        // Hourly, for battery life with lightweight ctag-based sync.
        assertEquals(60, KashCalDataStore.DEFAULT_SYNC_INTERVAL_MINUTES)
        assertEquals(1L * 60 * 60 * 1000, KashCalDataStore.DEFAULT_SYNC_INTERVAL_MS)
    }

    // ==================== Week View Scroll Restore Tests ====================

    @Test
    fun `weekViewScrollMinutes defaults to -1 sentinel when never saved`() = runTest {
        dataStore.weekViewScrollMinutes.test {
            assertEquals(-1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setWeekViewScrollMinutes round-trips a saved clock time`() = runTest {
        dataStore.weekViewScrollMinutes.test {
            assertEquals(-1, awaitItem())

            // 14:00 (2 PM) as minutes-of-day
            dataStore.setWeekViewScrollMinutes(840)
            assertEquals(840, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setWeekViewScrollMinutes clamps above end-of-day to 1439`() = runTest {
        dataStore.setWeekViewScrollMinutes(5000)
        assertEquals(1439, dataStore.getWeekViewScrollMinutes())
    }

    @Test
    fun `setWeekViewScrollMinutes clamps negative input to 0`() = runTest {
        // A negative input clamps to the start of the day, so the never-saved sentinel (-1) is
        // never persisted.
        dataStore.setWeekViewScrollMinutes(-99)
        assertEquals(0, dataStore.getWeekViewScrollMinutes())
    }

    @Test
    fun `getWeekViewScrollMinutes returns sentinel before any save`() = runTest {
        assertEquals(-1, dataStore.getWeekViewScrollMinutes())
    }

    // ==================== Week View Zoom (hour-height) Restore Tests ====================

    @Test
    fun `weekViewHourHeight defaults to 60 when never saved`() = runTest {
        dataStore.weekViewHourHeight.test {
            assertEquals(60f, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setWeekViewHourHeight round-trips a saved zoom level`() = runTest {
        dataStore.weekViewHourHeight.test {
            assertEquals(60f, awaitItem())

            dataStore.setWeekViewHourHeight(90f)
            assertEquals(90f, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getWeekViewHourHeight returns default before any save`() = runTest {
        assertEquals(60f, dataStore.getWeekViewHourHeight())
    }

    // ==================== First Day of Week Tests ====================

    @Test
    fun `firstDayOfWeek defaults to FIRST_DAY_SYSTEM (0) not Sunday`() = runTest {
        dataStore.firstDayOfWeek.test {
            // 0 means follow the system locale; 1 would be Calendar.SUNDAY.
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
     * Locks the `VALID_VIEWS` allowlist to [ViewMode]: every persistable key must round-trip
     * through setDefaultCalendarView, so adding a ViewMode without updating `VALID_VIEWS` fails
     * here.
     *
     * INSIGHTS is excluded: HomeViewModel.setViewMode() never persists it.
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
