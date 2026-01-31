package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.SyncLog
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for SyncLogsDao - sync operation logging for diagnostics.
 *
 * Critical for ensuring:
 * - Sync logs are properly inserted and retrieved
 * - Filtering by calendar, event, result type works
 * - Cleanup operations (deleteOldLogs, trimToCount) work correctly
 * - Range queries return correct results
 * - Log ordering (most recent first) is correct
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncLogsDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var syncLogsDao: SyncLogsDao
    private var testCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        syncLogsDao = database.syncLogsDao()

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.icloud.com/calendar/1",
                    displayName = "Test Calendar",
                    color = 0xFF2196F3.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private var logCounter = 0

    private fun createLog(
        timestamp: Long = System.currentTimeMillis() - (logCounter++ * 1000),
        calendarId: Long? = testCalendarId,
        eventUid: String? = null,
        action: String = SyncLog.ACTION_PULL,
        result: String = SyncLog.RESULT_SUCCESS,
        details: String? = null,
        httpStatus: Int? = 200
    ): SyncLog {
        return SyncLog(
            timestamp = timestamp,
            calendarId = calendarId,
            eventUid = eventUid,
            action = action,
            result = result,
            details = details,
            httpStatus = httpStatus
        )
    }

    // ==================== Insert Tests ====================

    @Test
    fun `insert creates log with generated ID`() = runTest {
        val log = createLog()
        val id = syncLogsDao.insert(log)

        assertTrue(id > 0)

        val count = syncLogsDao.getCount()
        assertEquals(1, count)
    }

    @Test
    fun `insertAll creates multiple logs`() = runTest {
        val logs = listOf(
            createLog(action = SyncLog.ACTION_PULL),
            createLog(action = SyncLog.ACTION_PUSH_CREATE),
            createLog(action = SyncLog.ACTION_PUSH_UPDATE)
        )

        syncLogsDao.insertAll(logs)

        val count = syncLogsDao.getCount()
        assertEquals(3, count)
    }

    // ==================== Query Tests ====================

    @Test
    fun `getRecentLogs returns reactive Flow ordered by timestamp DESC`() = runTest {
        syncLogsDao.getRecentLogs(100).test {
            // Initial state
            val initial = awaitItem()
            assertEquals(0, initial.size)

            // Add logs
            val now = System.currentTimeMillis()
            syncLogsDao.insert(createLog(timestamp = now - 2000, details = "Oldest"))
            val afterFirst = awaitItem()
            assertEquals(1, afterFirst.size)

            syncLogsDao.insert(createLog(timestamp = now, details = "Newest"))
            val afterSecond = awaitItem()
            assertEquals(2, afterSecond.size)
            // Most recent should be first
            assertEquals("Newest", afterSecond.first().details)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getRecentLogs respects limit`() = runTest {
        // Insert 10 logs
        repeat(10) {
            syncLogsDao.insert(createLog(timestamp = System.currentTimeMillis() - it * 1000))
        }

        val limited = syncLogsDao.getRecentLogsOnce(5)

        assertEquals(5, limited.size)
    }

    @Test
    fun `getRecentLogsOnce returns one-shot query`() = runTest {
        val now = System.currentTimeMillis()
        syncLogsDao.insert(createLog(timestamp = now - 1000, details = "First"))
        syncLogsDao.insert(createLog(timestamp = now, details = "Second"))
        syncLogsDao.insert(createLog(timestamp = now - 2000, details = "Third"))

        val logs = syncLogsDao.getRecentLogsOnce(100)

        assertEquals(3, logs.size)
        // Ordered by timestamp DESC
        assertEquals("Second", logs[0].details)
        assertEquals("First", logs[1].details)
        assertEquals("Third", logs[2].details)
    }

    @Test
    fun `getLogsForCalendar returns logs for specific calendar`() = runTest {
        // Create second calendar
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "local")
        )
        val calendar2Id = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "local://default",
                displayName = "Calendar 2",
                color = 0xFF4CAF50.toInt()
            )
        )

        syncLogsDao.insert(createLog(calendarId = testCalendarId))
        syncLogsDao.insert(createLog(calendarId = testCalendarId))
        syncLogsDao.insert(createLog(calendarId = calendar2Id))

        val calendar1Logs = syncLogsDao.getLogsForCalendar(testCalendarId)
        val calendar2Logs = syncLogsDao.getLogsForCalendar(calendar2Id)

        assertEquals(2, calendar1Logs.size)
        assertEquals(1, calendar2Logs.size)
    }

    @Test
    fun `getLogsForEvent returns logs for specific event UID`() = runTest {
        val eventUid1 = "event-uid-1@example.com"
        val eventUid2 = "event-uid-2@example.com"

        syncLogsDao.insert(createLog(eventUid = eventUid1, action = SyncLog.ACTION_PUSH_CREATE))
        syncLogsDao.insert(createLog(eventUid = eventUid1, action = SyncLog.ACTION_PUSH_UPDATE))
        syncLogsDao.insert(createLog(eventUid = eventUid2, action = SyncLog.ACTION_PUSH_DELETE))
        syncLogsDao.insert(createLog(eventUid = null)) // Calendar-level log

        val event1Logs = syncLogsDao.getLogsForEvent(eventUid1)
        val event2Logs = syncLogsDao.getLogsForEvent(eventUid2)

        assertEquals(2, event1Logs.size)
        assertEquals(1, event2Logs.size)
    }

    @Test
    fun `getErrorLogs returns only non-SUCCESS logs`() = runTest {
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_SUCCESS))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_SUCCESS))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_ERROR_NETWORK))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_ERROR_AUTH))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_ERROR_412))

        val errors = syncLogsDao.getErrorLogs()

        assertEquals(3, errors.size)
        assertTrue(errors.none { it.result == SyncLog.RESULT_SUCCESS })
    }

    @Test
    fun `getConflictLogs returns only HTTP 412 errors`() = runTest {
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_SUCCESS))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_ERROR_NETWORK))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_ERROR_412))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_ERROR_412))

        val conflicts = syncLogsDao.getConflictLogs()

        assertEquals(2, conflicts.size)
        assertTrue(conflicts.all { it.result == SyncLog.RESULT_ERROR_412 })
    }

    @Test
    fun `getLogsInRange returns logs within time window`() = runTest {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3600000
        val twoHoursAgo = now - 7200000
        val threeHoursAgo = now - 10800000

        syncLogsDao.insert(createLog(timestamp = now, details = "Now"))
        syncLogsDao.insert(createLog(timestamp = oneHourAgo, details = "1h ago"))
        syncLogsDao.insert(createLog(timestamp = twoHoursAgo, details = "2h ago"))
        syncLogsDao.insert(createLog(timestamp = threeHoursAgo, details = "3h ago"))

        // Query: 2.5 hours ago to 30 min ago
        val rangeStart = now - 9000000 // 2.5 hours ago
        val rangeEnd = now - 1800000    // 30 min ago

        val logsInRange = syncLogsDao.getLogsInRange(rangeStart, rangeEnd)

        assertEquals(2, logsInRange.size)
        assertTrue(logsInRange.any { it.details == "1h ago" })
        assertTrue(logsInRange.any { it.details == "2h ago" })
    }

    // ==================== Count Tests ====================

    @Test
    fun `getCount returns total log count`() = runTest {
        repeat(5) {
            syncLogsDao.insert(createLog())
        }

        val count = syncLogsDao.getCount()
        assertEquals(5, count)
    }

    @Test
    fun `getErrorCountSince returns error count after timestamp`() = runTest {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3600000
        val twoHoursAgo = now - 7200000

        // Errors after cutoff
        syncLogsDao.insert(createLog(timestamp = now, result = SyncLog.RESULT_ERROR_NETWORK))
        syncLogsDao.insert(createLog(timestamp = oneHourAgo - 1000, result = SyncLog.RESULT_ERROR_AUTH))

        // Success (shouldn't count)
        syncLogsDao.insert(createLog(timestamp = now, result = SyncLog.RESULT_SUCCESS))

        // Error before cutoff (shouldn't count)
        syncLogsDao.insert(createLog(timestamp = twoHoursAgo, result = SyncLog.RESULT_ERROR_PARSE))

        val errorCount = syncLogsDao.getErrorCountSince(oneHourAgo)

        assertEquals(1, errorCount)
    }

    // ==================== Cleanup Tests ====================

    @Test
    fun `deleteOldLogs removes logs before cutoff`() = runTest {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3600000

        syncLogsDao.insert(createLog(timestamp = now, details = "Recent"))
        syncLogsDao.insert(createLog(timestamp = oneHourAgo - 1000, details = "Old 1"))
        syncLogsDao.insert(createLog(timestamp = oneHourAgo - 2000, details = "Old 2"))

        val deletedCount = syncLogsDao.deleteOldLogs(oneHourAgo)

        assertEquals(2, deletedCount)

        val remaining = syncLogsDao.getRecentLogsOnce()
        assertEquals(1, remaining.size)
        assertEquals("Recent", remaining.first().details)
    }

    @Test
    fun `deleteLogsForCalendar removes all logs for calendar`() = runTest {
        // Create second calendar
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "local")
        )
        val calendar2Id = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "local://default2",
                displayName = "Calendar 2",
                color = 0xFF4CAF50.toInt()
            )
        )

        syncLogsDao.insert(createLog(calendarId = testCalendarId))
        syncLogsDao.insert(createLog(calendarId = testCalendarId))
        syncLogsDao.insert(createLog(calendarId = calendar2Id))

        syncLogsDao.deleteLogsForCalendar(testCalendarId)

        val remaining = syncLogsDao.getRecentLogsOnce()
        assertEquals(1, remaining.size)
        assertEquals(calendar2Id, remaining.first().calendarId)
    }

    @Test
    fun `deleteAll removes all logs`() = runTest {
        repeat(10) {
            syncLogsDao.insert(createLog())
        }

        syncLogsDao.deleteAll()

        val count = syncLogsDao.getCount()
        assertEquals(0, count)
    }

    @Test
    fun `trimToCount keeps only most recent N logs`() = runTest {
        val now = System.currentTimeMillis()

        // Insert logs with specific timestamps for ordering
        syncLogsDao.insert(createLog(timestamp = now - 4000, details = "Oldest"))
        syncLogsDao.insert(createLog(timestamp = now - 3000, details = "Old"))
        syncLogsDao.insert(createLog(timestamp = now - 2000, details = "Middle"))
        syncLogsDao.insert(createLog(timestamp = now - 1000, details = "Recent"))
        syncLogsDao.insert(createLog(timestamp = now, details = "Newest"))

        val deletedCount = syncLogsDao.trimToCount(3)

        assertEquals(2, deletedCount)

        val remaining = syncLogsDao.getRecentLogsOnce()
        assertEquals(3, remaining.size)

        // Should keep the 3 most recent
        val details = remaining.map { it.details }
        assertTrue(details.contains("Newest"))
        assertTrue(details.contains("Recent"))
        assertTrue(details.contains("Middle"))
        assertTrue(!details.contains("Old"))
        assertTrue(!details.contains("Oldest"))
    }

    // ==================== Factory Method Tests ====================

    @Test
    fun `SyncLog success factory creates correct log`() = runTest {
        val log = SyncLog.success(
            action = SyncLog.ACTION_PULL,
            calendarId = testCalendarId,
            eventUid = "test-uid",
            details = "Pulled 10 events",
            httpStatus = 200
        )

        val id = syncLogsDao.insert(log)
        val retrieved = syncLogsDao.getRecentLogsOnce().first()

        assertEquals(SyncLog.RESULT_SUCCESS, retrieved.result)
        assertEquals(SyncLog.ACTION_PULL, retrieved.action)
        assertEquals(testCalendarId, retrieved.calendarId)
        assertEquals("test-uid", retrieved.eventUid)
        assertEquals("Pulled 10 events", retrieved.details)
        assertEquals(200, retrieved.httpStatus)
    }

    @Test
    fun `SyncLog error factory creates correct log`() = runTest {
        val log = SyncLog.error(
            action = SyncLog.ACTION_PUSH_UPDATE,
            result = SyncLog.RESULT_ERROR_412,
            calendarId = testCalendarId,
            eventUid = "conflict-uid",
            details = "ETag mismatch",
            httpStatus = 412
        )

        syncLogsDao.insert(log)
        val retrieved = syncLogsDao.getRecentLogsOnce().first()

        assertEquals(SyncLog.RESULT_ERROR_412, retrieved.result)
        assertEquals(SyncLog.ACTION_PUSH_UPDATE, retrieved.action)
        assertEquals("ETag mismatch", retrieved.details)
        assertEquals(412, retrieved.httpStatus)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles null calendarId for account-level operations`() = runTest {
        val log = createLog(
            calendarId = null,
            action = SyncLog.ACTION_DISCOVERY,
            details = "Discovered 3 calendars"
        )

        syncLogsDao.insert(log)

        val logs = syncLogsDao.getRecentLogsOnce()
        assertEquals(1, logs.size)
        assertEquals(null, logs.first().calendarId)
    }

    @Test
    fun `handles null eventUid for calendar-level operations`() = runTest {
        val log = createLog(
            eventUid = null,
            action = SyncLog.ACTION_PULL,
            details = "Full calendar sync"
        )

        syncLogsDao.insert(log)

        val logs = syncLogsDao.getRecentLogsOnce()
        assertEquals(null, logs.first().eventUid)
    }

    @Test
    fun `handles long details string`() = runTest {
        val longDetails = "Error: " + "x".repeat(2000)
        val log = createLog(
            result = SyncLog.RESULT_ERROR_PARSE,
            details = longDetails
        )

        syncLogsDao.insert(log)

        val retrieved = syncLogsDao.getRecentLogsOnce().first()
        assertEquals(longDetails, retrieved.details)
    }

    @Test
    fun `handles unicode in details`() = runTest {
        val unicodeDetails = "Error parsing 日本語イベント: Invalid UTF-8 sequence"
        val log = createLog(
            result = SyncLog.RESULT_ERROR_PARSE,
            details = unicodeDetails
        )

        syncLogsDao.insert(log)

        val retrieved = syncLogsDao.getRecentLogsOnce().first()
        assertEquals(unicodeDetails, retrieved.details)
    }

    @Test
    fun `getErrorLogs includes SKIPPED result`() = runTest {
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_SUCCESS))
        syncLogsDao.insert(createLog(result = SyncLog.RESULT_SKIPPED))

        val errors = syncLogsDao.getErrorLogs()

        // SKIPPED is not SUCCESS, so should be included
        assertEquals(1, errors.size)
        assertEquals(SyncLog.RESULT_SKIPPED, errors.first().result)
    }

    @Test
    fun `multiple calendars have independent log counts`() = runTest {
        // Create multiple calendars
        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "local")
        )
        val cal2 = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://2", displayName = "Cal 2", color = 0xFF000000.toInt())
        )
        val cal3 = database.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = "local://3", displayName = "Cal 3", color = 0xFF000000.toInt())
        )

        // Insert different counts for each calendar
        repeat(5) { syncLogsDao.insert(createLog(calendarId = testCalendarId)) }
        repeat(3) { syncLogsDao.insert(createLog(calendarId = cal2)) }
        repeat(1) { syncLogsDao.insert(createLog(calendarId = cal3)) }

        assertEquals(5, syncLogsDao.getLogsForCalendar(testCalendarId).size)
        assertEquals(3, syncLogsDao.getLogsForCalendar(cal2).size)
        assertEquals(1, syncLogsDao.getLogsForCalendar(cal3).size)
        assertEquals(9, syncLogsDao.getCount())
    }

    @Test
    fun `default limit for getRecentLogs is 100`() = runTest {
        // Insert 150 logs
        repeat(150) {
            syncLogsDao.insert(createLog(timestamp = System.currentTimeMillis() - it * 100))
        }

        // Default limit should be 100
        val logs = syncLogsDao.getRecentLogsOnce()
        assertEquals(100, logs.size)
    }
}
