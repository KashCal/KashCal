package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
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
 * Unit tests for device calendar preferences in KashCalDataStore.
 *
 * Tests default values, enable/disable toggle, and Set<Long> <-> Set<String> round-trip
 * for enabledDeviceCalendarIds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarPreferencesTest {

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

    // ========== deviceCalendarsEnabled ==========

    @Test
    fun `deviceCalendarsEnabled defaults to false`() = runTest {
        assertFalse(dataStore.getDeviceCalendarsEnabled())
    }

    @Test
    fun `setDeviceCalendarsEnabled true then read returns true`() = runTest {
        dataStore.setDeviceCalendarsEnabled(true)
        assertTrue(dataStore.getDeviceCalendarsEnabled())
    }

    @Test
    fun `setDeviceCalendarsEnabled false after true returns false`() = runTest {
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setDeviceCalendarsEnabled(false)
        assertFalse(dataStore.getDeviceCalendarsEnabled())
    }

    @Test
    fun `deviceCalendarsEnabled flow emits default`() = runTest {
        val value = dataStore.deviceCalendarsEnabled.first()
        assertFalse(value)
    }

    // ========== enabledDeviceCalendarIds ==========

    @Test
    fun `enabledDeviceCalendarIds defaults to empty set`() = runTest {
        val ids = dataStore.getEnabledDeviceCalendarIds()
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `setEnabledDeviceCalendarIds round-trip with single id`() = runTest {
        dataStore.setEnabledDeviceCalendarIds(setOf(42L))
        assertEquals(setOf(42L), dataStore.getEnabledDeviceCalendarIds())
    }

    @Test
    fun `setEnabledDeviceCalendarIds round-trip with multiple ids`() = runTest {
        val ids = setOf(1L, 2L, 100L, 999999L)
        dataStore.setEnabledDeviceCalendarIds(ids)
        assertEquals(ids, dataStore.getEnabledDeviceCalendarIds())
    }

    @Test
    fun `setEnabledDeviceCalendarIds empty set clears ids`() = runTest {
        dataStore.setEnabledDeviceCalendarIds(setOf(1L, 2L, 3L))
        dataStore.setEnabledDeviceCalendarIds(emptySet())
        assertTrue(dataStore.getEnabledDeviceCalendarIds().isEmpty())
    }

    @Test
    fun `enabledDeviceCalendarIds flow emits empty default`() = runTest {
        val ids = dataStore.enabledDeviceCalendarIds.first()
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `enabledDeviceCalendarIds preserves large calendar ids`() = runTest {
        val largeIds = setOf(Long.MAX_VALUE, Long.MAX_VALUE - 1)
        dataStore.setEnabledDeviceCalendarIds(largeIds)
        assertEquals(largeIds, dataStore.getEnabledDeviceCalendarIds())
    }
}
