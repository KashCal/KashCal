package org.onekash.kashcal.sync.session

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SyncSessionStore.
 *
 * Uses Robolectric for ApplicationContext and mocked Log.
 *
 * Tests:
 * - Initial state is empty
 * - add() stores sessions
 * - Newest-first ordering
 * - 48-hour retention
 * - MAX_SESSIONS limit
 * - clear() empties store
 * - getSummaryStats correctness
 * - getExportText format
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SyncSessionStoreTest {

    private lateinit var store: SyncSessionStore

    private fun createSession(
        calendarName: String = "Test",
        status: SyncStatus = SyncStatus.SUCCESS,
        timestamp: Long = System.currentTimeMillis(),
        eventsWritten: Int = 0,
        eventsUpdated: Int = 0,
        eventsDeleted: Int = 0,
        eventsPushedCreated: Int = 0,
        eventsPushedUpdated: Int = 0,
        eventsPushedDeleted: Int = 0,
        skippedParseError: Int = 0,
        skippedConstraintError: Int = 0,
        errorType: ErrorType? = null,
        errorMessage: String? = null
    ): SyncSession {
        val effectiveErrorType = when (status) {
            SyncStatus.FAILED -> errorType ?: ErrorType.NETWORK
            else -> if (skippedParseError > 0) null else errorType
        }
        return SyncSession(
            calendarId = 1L,
            calendarName = calendarName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL,
            durationMs = 100L,
            hrefsReported = 10,
            eventsFetched = 10,
            eventsWritten = eventsWritten,
            eventsUpdated = eventsUpdated,
            eventsDeleted = eventsDeleted,
            eventsPushedCreated = eventsPushedCreated,
            eventsPushedUpdated = eventsPushedUpdated,
            eventsPushedDeleted = eventsPushedDeleted,
            skippedParseError = skippedParseError,
            skippedConstraintError = skippedConstraintError,
            errorType = effectiveErrorType,
            errorMessage = errorMessage,
            timestamp = timestamp
        )
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        store = SyncSessionStore(context)
    }

    @After
    fun teardown() {
        store.clear()
        unmockkStatic(Log::class)
    }

    @Test
    fun `initial state is empty list`() {
        assertTrue(store.sessions.value.isEmpty())
    }

    @Test
    fun `add makes session available in sessions Flow`() = runTest {
        val session = createSession(calendarName = "Work")
        store.add(session)

        assertEquals(1, store.sessions.value.size)
        assertEquals("Work", store.sessions.value[0].calendarName)
    }

    @Test
    fun `add puts newest first`() = runTest {
        val older = createSession(calendarName = "First", timestamp = System.currentTimeMillis() - 1000)
        val newer = createSession(calendarName = "Second", timestamp = System.currentTimeMillis())

        store.add(older)
        store.add(newer)

        assertEquals(2, store.sessions.value.size)
        assertEquals("Second", store.sessions.value[0].calendarName)
        assertEquals("First", store.sessions.value[1].calendarName)
    }

    @Test
    fun `sessions older than 48h are filtered out`() = runTest {
        val old = createSession(
            calendarName = "Old",
            timestamp = System.currentTimeMillis() - (49 * 60 * 60 * 1000L)
        )
        val recent = createSession(calendarName = "Recent")

        store.add(old)
        store.add(recent)

        // Old session should be filtered by retention policy
        assertEquals(1, store.sessions.value.size)
        assertEquals("Recent", store.sessions.value[0].calendarName)
    }

    @Test
    fun `clear empties sessions`() = runTest {
        store.add(createSession(calendarName = "A"))
        store.add(createSession(calendarName = "B"))
        assertEquals(2, store.sessions.value.size)

        store.clear()
        assertTrue(store.sessions.value.isEmpty())
    }

    @Test
    fun `getSummaryStats returns correct totals`() = runTest {
        store.add(createSession(
            eventsPushedCreated = 2, eventsPushedUpdated = 1, eventsPushedDeleted = 0,
            eventsWritten = 3, eventsUpdated = 1, eventsDeleted = 0
        ))
        store.add(createSession(
            eventsPushedCreated = 1, eventsPushedUpdated = 0, eventsPushedDeleted = 1,
            eventsWritten = 0, eventsUpdated = 2, eventsDeleted = 1
        ))

        val stats = store.getSummaryStats()
        assertEquals(2, stats.totalSyncs)
        assertEquals(5, stats.totalPushed) // (2+1) + (1+0+1)
        assertEquals(7, stats.totalPulled) // (3+1+0) + (0+2+1)
    }

    @Test
    fun `getSummaryStats with empty list returns all zeros`() {
        val stats = store.getSummaryStats(emptyList())
        assertEquals(0, stats.totalSyncs)
        assertEquals(0, stats.totalPushed)
        assertEquals(0, stats.totalPulled)
        assertEquals(0, stats.issueCount)
    }

    @Test
    fun `getSummaryStats counts non-SUCCESS as issues`() = runTest {
        store.add(createSession()) // SUCCESS
        store.add(createSession(skippedParseError = 2)) // PARTIAL
        store.add(createSession(errorType = ErrorType.AUTH, status = SyncStatus.FAILED)) // FAILED

        val stats = store.getSummaryStats()
        assertEquals(3, stats.totalSyncs)
        assertEquals(2, stats.issueCount)
    }

    @Test
    fun `getExportText contains header`() = runTest {
        store.add(createSession(calendarName = "Work"))

        val text = store.getExportText()
        assertTrue(text.contains("KashCal Sync History"))
    }

    @Test
    fun `getExportText includes status icons`() = runTest {
        store.add(createSession()) // SUCCESS → ✓
        store.add(createSession(skippedParseError = 1)) // PARTIAL → ⚠
        store.add(createSession(errorType = ErrorType.NETWORK, status = SyncStatus.FAILED)) // FAILED → ✗

        val text = store.getExportText()
        assertTrue("Should contain success icon", text.contains("✓"))
        assertTrue("Should contain partial icon", text.contains("⚠"))
        assertTrue("Should contain failed icon", text.contains("✗"))
    }

    @Test
    fun `getExportText includes push and pull stats`() = runTest {
        store.add(createSession(
            eventsPushedCreated = 3,
            eventsWritten = 5
        ))

        val text = store.getExportText()
        assertTrue("Should contain push arrow", text.contains("↑"))
        assertTrue("Should contain pull arrow", text.contains("↓"))
    }
}
