package org.onekash.kashcal.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for parse failure retry tracking in KashCalDataStore.
 *
 * Tests the retry count mechanism used to hold sync tokens when parse failures occur.
 * This prevents permanent data loss by allowing retries before advancing the sync token.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ParseFailureRetryDataStoreTest {

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

    // ==================== incrementParseFailureRetry Tests ====================

    @Test
    fun `incrementParseFailureRetry first time returns 1`() = runTest {
        val calendarId = 100L

        val newCount = dataStore.incrementParseFailureRetry(calendarId)

        assertEquals(1, newCount)
    }

    @Test
    fun `incrementParseFailureRetry subsequent calls increment correctly`() = runTest {
        val calendarId = 100L

        val count1 = dataStore.incrementParseFailureRetry(calendarId)
        val count2 = dataStore.incrementParseFailureRetry(calendarId)
        val count3 = dataStore.incrementParseFailureRetry(calendarId)

        assertEquals(1, count1)
        assertEquals(2, count2)
        assertEquals(3, count3)
    }

    @Test
    fun `incrementParseFailureRetry different calendars are independent`() = runTest {
        val calendarA = 100L
        val calendarB = 200L

        dataStore.incrementParseFailureRetry(calendarA)
        dataStore.incrementParseFailureRetry(calendarA)
        val countA = dataStore.incrementParseFailureRetry(calendarA)

        val countB = dataStore.incrementParseFailureRetry(calendarB)

        assertEquals(3, countA)
        assertEquals(1, countB)
    }

    // ==================== resetParseFailureRetry Tests ====================

    @Test
    fun `resetParseFailureRetry clears count for calendar`() = runTest {
        val calendarId = 100L

        // Increment a few times
        dataStore.incrementParseFailureRetry(calendarId)
        dataStore.incrementParseFailureRetry(calendarId)

        // Reset
        dataStore.resetParseFailureRetry(calendarId)

        // Verify reset
        val count = dataStore.getParseFailureRetryCount(calendarId)
        assertEquals(0, count)
    }

    @Test
    fun `resetParseFailureRetry does not affect other calendars`() = runTest {
        val calendarA = 100L
        val calendarB = 200L

        dataStore.incrementParseFailureRetry(calendarA)
        dataStore.incrementParseFailureRetry(calendarA)
        dataStore.incrementParseFailureRetry(calendarB)
        dataStore.incrementParseFailureRetry(calendarB)

        // Reset only calendar A
        dataStore.resetParseFailureRetry(calendarA)

        // Verify
        assertEquals(0, dataStore.getParseFailureRetryCount(calendarA))
        assertEquals(2, dataStore.getParseFailureRetryCount(calendarB))
    }

    @Test
    fun `resetParseFailureRetry on unknown calendar is safe`() = runTest {
        // Should not throw
        dataStore.resetParseFailureRetry(999L)

        // Verify still returns 0
        assertEquals(0, dataStore.getParseFailureRetryCount(999L))
    }

    // ==================== clearAllParseFailureRetries Tests ====================

    @Test
    fun `clearAllParseFailureRetries clears all calendars`() = runTest {
        val calendarA = 100L
        val calendarB = 200L
        val calendarC = 300L

        dataStore.incrementParseFailureRetry(calendarA)
        dataStore.incrementParseFailureRetry(calendarB)
        dataStore.incrementParseFailureRetry(calendarB)
        dataStore.incrementParseFailureRetry(calendarC)
        dataStore.incrementParseFailureRetry(calendarC)
        dataStore.incrementParseFailureRetry(calendarC)

        // Clear all
        dataStore.clearAllParseFailureRetries()

        // Verify all reset
        assertEquals(0, dataStore.getParseFailureRetryCount(calendarA))
        assertEquals(0, dataStore.getParseFailureRetryCount(calendarB))
        assertEquals(0, dataStore.getParseFailureRetryCount(calendarC))
    }

    // ==================== getParseFailureRetryCount Tests ====================

    @Test
    fun `getParseFailureRetryCount unknown calendar returns 0`() = runTest {
        val count = dataStore.getParseFailureRetryCount(999L)

        assertEquals(0, count)
    }

    @Test
    fun `getParseFailureRetryCount returns correct count after increments`() = runTest {
        val calendarId = 100L

        dataStore.incrementParseFailureRetry(calendarId)
        dataStore.incrementParseFailureRetry(calendarId)

        val count = dataStore.getParseFailureRetryCount(calendarId)

        assertEquals(2, count)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `incrementParseFailureRetry handles Long max value calendar ID`() = runTest {
        val calendarId = Long.MAX_VALUE

        val count = dataStore.incrementParseFailureRetry(calendarId)

        assertEquals(1, count)
    }

    @Test
    fun `incrementParseFailureRetry handles zero calendar ID`() = runTest {
        val calendarId = 0L

        val count = dataStore.incrementParseFailureRetry(calendarId)

        assertEquals(1, count)
    }

    // ==================== Persistence Tests ====================

    @Test
    fun `retry count persists after reloading DataStore`() = runTest {
        val calendarId = 100L

        // Increment
        dataStore.incrementParseFailureRetry(calendarId)
        dataStore.incrementParseFailureRetry(calendarId)

        // Create new DataStore instance (simulates app restart)
        val newDataStore = KashCalDataStore(context)

        // Verify persistence
        val count = newDataStore.getParseFailureRetryCount(calendarId)
        assertEquals(2, count)
    }
}
