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
 * Unit tests for the Agenda week-bar expand/collapse DataStore preference.
 *
 * Covers: default (expanded/true when absent), round-trip both ways, and
 * stable key string identifier (so an upgrade doesn't silently reset the
 * user's choice).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AgendaWeekBarPreferenceTest {

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
        testDataStoreFile = File(context.filesDir, "test_agenda_weekbar_${System.nanoTime()}.preferences_pb")
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
    fun `agendaWeekBarExpanded defaults to true when key absent`() = runTest {
        dataStore.agendaWeekBarExpanded.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `agendaWeekBarExpanded round-trips both ways`() = runTest {
        dataStore.setAgendaWeekBarExpanded(false)
        assertFalse(dataStore.agendaWeekBarExpanded.first())

        dataStore.setAgendaWeekBarExpanded(true)
        assertTrue(dataStore.agendaWeekBarExpanded.first())
    }

    @Test
    fun `agendaWeekBarExpanded uses a stable key identifier`() = runTest {
        dataStore.setAgendaWeekBarExpanded(false)
        val prefs = dataStore.dataStore.data.first()
        assertEquals(false, prefs[booleanPreferencesKey("agenda_week_bar_expanded")])
    }
}
