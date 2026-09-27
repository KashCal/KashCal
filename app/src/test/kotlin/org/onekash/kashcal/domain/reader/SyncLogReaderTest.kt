package org.onekash.kashcal.domain.reader

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.SyncLogsDao
import org.onekash.kashcal.data.db.entity.SyncLog

/**
 * Tests that [SyncLogReader.getRecentLogs] passes its limit (100 by default) to a mocked
 * [SyncLogsDao] and emits the DAO's rows unchanged, in the DAO's order.
 */
class SyncLogReaderTest {

    // Mocks
    private lateinit var database: KashCalDatabase
    private lateinit var syncLogsDao: SyncLogsDao

    // System under test
    private lateinit var syncLogReader: SyncLogReader

    // Test data
    private val testLogs = listOf(
        SyncLog(
            id = 1L,
            timestamp = 1704067200000L,
            calendarId = 1L,
            action = "PULL",
            result = "SUCCESS"
        ),
        SyncLog(
            id = 2L,
            timestamp = 1704070800000L,
            calendarId = 1L,
            action = "PUSH",
            result = "SUCCESS",
            eventUid = "test-event@kashcal.test"
        ),
        SyncLog(
            id = 3L,
            timestamp = 1704074400000L,
            calendarId = 2L,
            action = "PUSH",
            result = "ERROR_412",
            eventUid = "conflict-event@kashcal.test",
            details = "Precondition failed: ETag mismatch"
        )
    )

    @Before
    fun setup() {
        syncLogsDao = mockk(relaxed = true)
        database = mockk {
            every { syncLogsDao() } returns syncLogsDao
        }
        syncLogReader = SyncLogReader(database)
    }

    @Test
    fun `getRecentLogs delegates to dao`() = runTest {
        every { syncLogsDao.getRecentLogs(any()) } returns flowOf(testLogs)

        syncLogReader.getRecentLogs().test {
            val logs = awaitItem()
            assertEquals(3, logs.size)
            assertEquals("PULL", logs[0].action)
            assertEquals("PUSH", logs[1].action)
            assertEquals("ERROR_412", logs[2].result)
            awaitComplete()
        }

        verify { syncLogsDao.getRecentLogs(100) }
    }

    @Test
    fun `getRecentLogs uses default limit of 100`() = runTest {
        every { syncLogsDao.getRecentLogs(100) } returns flowOf(emptyList())

        syncLogReader.getRecentLogs()

        verify { syncLogsDao.getRecentLogs(100) }
    }

    @Test
    fun `getRecentLogs passes custom limit to dao`() = runTest {
        every { syncLogsDao.getRecentLogs(50) } returns flowOf(testLogs.take(2))

        syncLogReader.getRecentLogs(50).test {
            val logs = awaitItem()
            assertEquals(2, logs.size)
            awaitComplete()
        }

        verify { syncLogsDao.getRecentLogs(50) }
    }

    @Test
    fun `getRecentLogs returns empty flow when no logs`() = runTest {
        every { syncLogsDao.getRecentLogs(any()) } returns flowOf(emptyList())

        syncLogReader.getRecentLogs().test {
            val logs = awaitItem()
            assertEquals(0, logs.size)
            awaitComplete()
        }
    }

    @Test
    fun `getRecentLogs preserves log order from dao`() = runTest {
        // The DAO returns logs most recent first.
        val orderedLogs = testLogs.sortedByDescending { it.timestamp }
        every { syncLogsDao.getRecentLogs(any()) } returns flowOf(orderedLogs)

        syncLogReader.getRecentLogs().test {
            val logs = awaitItem()
            // The reader keeps that order.
            assertEquals(3L, logs[0].id) // Most recent
            assertEquals(2L, logs[1].id)
            assertEquals(1L, logs[2].id) // Oldest
            awaitComplete()
        }
    }
}
