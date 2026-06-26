package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * Unit tests for share-availability DataStore preferences.
 *
 * Covers:
 * - Defaults when keys are absent (days=7, work-start=540, work-end=1020, all-day=false)
 * - Round-trip set-then-read for each pref
 * - Write-side clamping (out-of-range coerced to default)
 * - Stable string identifiers for the four pref keys
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ShareAvailabilityPreferencesTest {

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
        testDataStoreFile = File(context.filesDir, "test_share_avail_${System.nanoTime()}.preferences_pb")
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

    // ========== Defaults ==========

    @Test
    fun `shareAvailabilityDays defaults to 7 when key absent`() = runTest {
        dataStore.shareAvailabilityDays.test {
            assertEquals(7, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shareAvailabilityWorkStartMinutes defaults to 540 when key absent`() = runTest {
        dataStore.shareAvailabilityWorkStartMinutes.test {
            assertEquals(540, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shareAvailabilityWorkEndMinutes defaults to 1020 when key absent`() = runTest {
        dataStore.shareAvailabilityWorkEndMinutes.test {
            assertEquals(1020, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shareAvailabilityIncludeAllDay defaults to false when key absent`() = runTest {
        dataStore.shareAvailabilityIncludeAllDay.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Round-trips ==========

    @Test
    fun `shareAvailabilityDays round-trips a valid value`() = runTest {
        dataStore.setShareAvailabilityDays(3)
        assertEquals(3, dataStore.shareAvailabilityDays.first())

        dataStore.setShareAvailabilityDays(14)
        assertEquals(14, dataStore.shareAvailabilityDays.first())

        dataStore.setShareAvailabilityDays(1)
        assertEquals(1, dataStore.shareAvailabilityDays.first())
    }

    @Test
    fun `work hours round-trip valid values`() = runTest {
        dataStore.setShareAvailabilityWorkStartMinutes(0)
        dataStore.setShareAvailabilityWorkEndMinutes(1440)
        assertEquals(0, dataStore.shareAvailabilityWorkStartMinutes.first())
        assertEquals(1440, dataStore.shareAvailabilityWorkEndMinutes.first())

        dataStore.setShareAvailabilityWorkStartMinutes(480)
        dataStore.setShareAvailabilityWorkEndMinutes(1080)
        assertEquals(480, dataStore.shareAvailabilityWorkStartMinutes.first())
        assertEquals(1080, dataStore.shareAvailabilityWorkEndMinutes.first())
    }

    @Test
    fun `shareAvailabilityIncludeAllDay round-trips`() = runTest {
        dataStore.setShareAvailabilityIncludeAllDay(true)
        assertTrue(dataStore.shareAvailabilityIncludeAllDay.first())

        dataStore.setShareAvailabilityIncludeAllDay(false)
        assertFalse(dataStore.shareAvailabilityIncludeAllDay.first())
    }

    // ========== Write-side clamping ==========

    @Test
    fun `shareAvailabilityDays clamps writes below 1 to default`() = runTest {
        dataStore.setShareAvailabilityDays(0)
        assertEquals(7, dataStore.shareAvailabilityDays.first())

        dataStore.setShareAvailabilityDays(-5)
        assertEquals(7, dataStore.shareAvailabilityDays.first())
    }

    @Test
    fun `shareAvailabilityDays clamps writes above 14 to default`() = runTest {
        dataStore.setShareAvailabilityDays(15)
        assertEquals(7, dataStore.shareAvailabilityDays.first())

        dataStore.setShareAvailabilityDays(100)
        assertEquals(7, dataStore.shareAvailabilityDays.first())
    }

    @Test
    fun `work hour writes clamp out-of-range to default`() = runTest {
        dataStore.setShareAvailabilityWorkStartMinutes(-1)
        assertEquals(540, dataStore.shareAvailabilityWorkStartMinutes.first())

        dataStore.setShareAvailabilityWorkStartMinutes(1441)
        assertEquals(540, dataStore.shareAvailabilityWorkStartMinutes.first())

        dataStore.setShareAvailabilityWorkEndMinutes(-1)
        assertEquals(1020, dataStore.shareAvailabilityWorkEndMinutes.first())

        dataStore.setShareAvailabilityWorkEndMinutes(2000)
        assertEquals(1020, dataStore.shareAvailabilityWorkEndMinutes.first())
    }

    // ========== Key name stability ==========

    @Test
    fun `pref keys use stable string identifiers`() = runTest {
        // Write through public API.
        dataStore.setShareAvailabilityDays(5)
        dataStore.setShareAvailabilityWorkStartMinutes(600)
        dataStore.setShareAvailabilityWorkEndMinutes(1200)
        dataStore.setShareAvailabilityIncludeAllDay(true)

        // Read raw preferences via the documented key strings — if a refactor
        // accidentally renamed the key, this assertion fails before user prefs
        // would silently disappear on upgrade.
        val prefs = dataStore.dataStore.data.first()
        assertEquals(5, prefs[intPreferencesKey("share_availability_days")])
        assertEquals(600, prefs[intPreferencesKey("share_availability_work_start_min")])
        assertEquals(1200, prefs[intPreferencesKey("share_availability_work_end_min")])
        assertEquals(true, prefs[booleanPreferencesKey("share_availability_include_all_day")])
    }
}
