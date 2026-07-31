package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
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
 * Unit tests for the all-day-strip expand/collapse DataStore preference used by
 * the DAY/3-DAY/WEEK time-grid views.
 *
 * Covers: default (collapsed/false when absent — preserving today's one-row
 * behavior), round-trip both ways, and a stable key string so an upgrade doesn't
 * silently reset the user's choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AllDayRowsExpandedPreferenceTest {

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
        testDataStoreFile = File(context.filesDir, "test_allday_rows_${System.nanoTime()}.preferences_pb")
        val testPrefsDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { testDataStoreFile }
        dataStore = KashCalDataStore(context, testPrefsDataStore)
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        testDataStoreFile.delete()
    }

    @Test
    fun `allDayRowsExpanded defaults to false when key absent`() = runTest {
        dataStore.allDayRowsExpanded.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `allDayRowsExpanded round-trips both ways`() = runTest {
        dataStore.setAllDayRowsExpanded(true)
        assertTrue(dataStore.allDayRowsExpanded.first())

        dataStore.setAllDayRowsExpanded(false)
        assertFalse(dataStore.allDayRowsExpanded.first())
    }

    @Test
    fun `allDayRowsExpanded uses a stable key identifier`() = runTest {
        dataStore.setAllDayRowsExpanded(true)
        val prefs = dataStore.dataStore.data.first()
        assertEquals(true, prefs[booleanPreferencesKey("all_day_rows_expanded")])
    }
}
