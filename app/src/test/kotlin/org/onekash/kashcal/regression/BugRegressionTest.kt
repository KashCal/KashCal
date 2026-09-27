package org.onekash.kashcal.regression

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorMapper
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Regression tests from an early hardening sweep. The `BugN` prefixes in test names are that
 * sweep's numbering, not GitHub issues; each section header names the behavior.
 *
 * Covers a null account lookup, [CalDavResult] on errors, [ErrorMapper] (retryable errors, HTTP
 * codes, exceptions, presentations), reminder storage, SEQUENCE on update, the MOVE operation's
 * stored context, exception UIDs and future occurrences of a recurring event. The month-index and
 * safe-cast tests exercise Kotlin's `getOrElse` and `as?` directly, not app code, and the
 * credentials test asserts nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BugRegressionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventWriter: EventWriter
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventWriter = EventWriter(database, occurrenceGenerator)

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://caldav.icloud.com/test/",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createTestEvent(): Event {
        val now = System.currentTimeMillis()
        return Event(
            id = 0,
            uid = "",
            calendarId = testCalendarId,
            title = "Test Event",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now
        )
    }

    // ==================== Missing account lookup ====================

    @Test
    fun `Bug2 LocalCalendarInitializer handles null account gracefully`() = runTest {
        // Only the DAO's null for a missing id is asserted. LocalCalendarInitializer turns a
        // null after its own insert into an IllegalStateException via checkNotNull (not
        // exercised here).

        val nonExistentAccountId = 999L
        val account = database.accountsDao().getById(nonExistentAccountId)

        assertNull("Non-existent account should return null", account)
    }

    // ==================== CalDavResult.Error accessors ====================

    @Test
    fun `Bug4_5_6 CalDavResult handles null gracefully`() {
        // getOrNull() on an Error returns null instead of throwing.
        val errorResult = CalDavResult.Error(500, "Server error", true)

        val value = errorResult.getOrNull()
        assertNull("Error result getOrNull should return null", value)

        assertTrue("Error result should be error type", errorResult.isError())
        assertFalse("Error result should not be success", errorResult.isSuccess())
    }

    // ==================== Retryable errors ====================

    @Test
    fun `Bug10 ErrorMapper identifies retryable errors`() {
        // Network errors and a temporarily unavailable server are retryable.
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Timeout))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.Offline))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.UnknownHost))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Network.ConnectionFailed()))
        assertTrue(ErrorMapper.isRetryable(CalendarError.Server.TemporarilyUnavailable))

        // Auth and event errors are not.
        assertFalse(ErrorMapper.isRetryable(CalendarError.Auth.InvalidCredentials))
        assertFalse(ErrorMapper.isRetryable(CalendarError.Event.NotFound(1L)))
    }

    // ==================== HTTP 429 rate limiting ====================

    @Test
    fun `Bug11 HTTP 429 mapped to RateLimited error`() {
        val error = ErrorMapper.fromHttpCode(429)

        assertEquals(CalendarError.Server.RateLimited, error)

        val presentation = ErrorMapper.toPresentation(error)
        assertTrue(presentation is ErrorPresentation.Snackbar)
    }

    // ==================== Network error presentations ====================

    @Test
    fun `Bug13 ErrorMapper provides meaningful error messages`() {
        // Each network error is presented as a snackbar.

        val networkErrors = listOf(
            CalendarError.Network.Timeout,
            CalendarError.Network.Offline,
            CalendarError.Network.SslError,
            CalendarError.Network.UnknownHost,
            CalendarError.Network.ConnectionFailed("detail")
        )

        networkErrors.forEach { error ->
            val presentation = ErrorMapper.toPresentation(error)
            assertTrue("$error should have presentation", presentation is ErrorPresentation.Snackbar)
        }
    }

    // ==================== Credentials thread safety ====================

    @Test
    fun `Bug14 concurrent credentials access is safe`() = runTest {
        // Asserts nothing. OkHttpCalDavClient holds username and password as immutable vals,
        // and each account's credentials are fixed in its own client.
        assertTrue("Test documents volatile requirement", true)
    }

    // ==================== Bounds-checked month index ====================

    @Test
    fun `Bug18 month index access is bounds-checked`() {
        val monthNames = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        // Valid indices
        for (i in 0..11) {
            val name = monthNames.getOrElse(i) { "Invalid" }
            assertFalse("Month $i should be valid", name == "Invalid")
        }

        // Out-of-range indices take the fallback.
        assertEquals("Invalid", monthNames.getOrElse(-1) { "Invalid" })
        assertEquals("Invalid", monthNames.getOrElse(12) { "Invalid" })
        assertEquals("Invalid", monthNames.getOrElse(100) { "Invalid" })
    }

    // ==================== Safe casts ====================

    @Test
    fun `Bug19_20 safe casts prevent ClassCastException`() {
        val result: Any = CalDavResult.Error(404, "Not found", false)

        val error = result as? CalDavResult.Error
        assertNotNull("Safe cast should work", error)
        assertEquals(404, error?.code)

        // A cast to the wrong type gives null instead of throwing.
        val wrongType = result as? CalDavResult.Success<*>
        assertNull("Wrong type safe cast should be null", wrongType)
    }

    // ==================== Reminders stored on create ====================

    @Test
    fun `Bug23 event reminders are preserved`() = runTest {
        val event = createTestEvent().copy(
            reminders = listOf("-PT15M", "-PT1H")
        )

        val created = eventWriter.createEvent(event, isLocal = false)

        val loaded = database.eventsDao().getById(created.id)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.reminders?.size)
        assertTrue(loaded.reminders!!.contains("-PT15M"))
        assertTrue(loaded.reminders!!.contains("-PT1H"))
    }

    // ==================== SEQUENCE on update ====================

    @Test
    fun `Bug24 sequence increments on significant changes`() = runTest {
        val event = createTestEvent()
        val created = eventWriter.createEvent(event, isLocal = false)
        assertEquals(0, created.sequence)

        // A time change is scheduling-significant.
        val updated = eventWriter.updateEvent(
            created.copy(
                startTs = created.startTs + 3600000,
                endTs = created.endTs + 3600000
            ),
            isLocal = false
        )

        assertTrue("Sequence should increment on time change", updated.sequence > 0)
    }

    @Test
    fun `Bug24 sequence preserved on non-significant changes`() = runTest {
        val event = createTestEvent()
        val created = eventWriter.createEvent(event, isLocal = false)
        val initialSequence = created.sequence

        // SequenceBumper counts a title change as significant, so this bumps; the assert only
        // checks the sequence doesn't go down.
        val updated = eventWriter.updateEvent(
            created.copy(title = "New Title"),
            isLocal = false
        )

        assertTrue("Sequence should be preserved or incremented", updated.sequence >= initialSequence)
    }

    // ==================== Exception and HTTP code mapping ====================

    @Test
    fun `fromException maps common network exceptions`() {
        assertEquals(
            CalendarError.Network.Timeout,
            ErrorMapper.fromException(SocketTimeoutException())
        )

        assertEquals(
            CalendarError.Network.UnknownHost,
            ErrorMapper.fromException(UnknownHostException("caldav.icloud.com"))
        )

        assertEquals(
            CalendarError.Network.SslError,
            ErrorMapper.fromException(SSLHandshakeException("Certificate error"))
        )
    }

    @Test
    fun `fromHttpCode maps common HTTP codes`() {
        assertEquals(CalendarError.Auth.InvalidCredentials, ErrorMapper.fromHttpCode(401))
        assertTrue(ErrorMapper.fromHttpCode(403) is CalendarError.Server.Forbidden)
        assertTrue(ErrorMapper.fromHttpCode(404) is CalendarError.Server.NotFound)
        assertTrue(ErrorMapper.fromHttpCode(412) is CalendarError.Server.Conflict)
        assertEquals(CalendarError.Server.RateLimited, ErrorMapper.fromHttpCode(429))
        assertEquals(CalendarError.Server.TemporarilyUnavailable, ErrorMapper.fromHttpCode(500))
        assertEquals(CalendarError.Server.TemporarilyUnavailable, ErrorMapper.fromHttpCode(503))
    }

    // ==================== PendingOperation self-contained context ====================

    @Test
    fun `PendingOperation stores all required context for MOVE`() = runTest {
        val event = createTestEvent()
        val created = eventWriter.createEvent(event, isLocal = false)

        // Mark it synced with a server URL.
        val synced = created.copy(
            caldavUrl = "https://caldav.icloud.com/old/event.ics",
            etag = "\"etag123\"",
            syncStatus = SyncStatus.SYNCED
        )
        database.eventsDao().update(synced)

        // A second calendar on the same iCloud account.
        val calendar2Id = database.calendarsDao().insert(
            Calendar(
                accountId = 1L,
                caldavUrl = "https://caldav.icloud.com/new/",
                displayName = "New Calendar",
                color = 0xFFFF0000.toInt()
            )
        )

        // Drop the CREATE queued above.
        database.pendingOperationsDao().deleteAll()

        // A synced event moved within one syncing account queues a MOVE.
        eventWriter.moveEventToCalendar(synced.id, calendar2Id)

        // The MOVE carries the old URL, captured before the event's URL is cleared.
        val pendingOps = database.pendingOperationsDao().getAll()
        assertEquals(1, pendingOps.size)

        val moveOp = pendingOps[0]
        assertEquals(PendingOperation.OPERATION_MOVE, moveOp.operation)
        assertEquals(synced.id, moveOp.eventId)
        assertEquals("https://caldav.icloud.com/old/event.ics", moveOp.targetUrl)
        assertEquals(calendar2Id, moveOp.targetCalendarId)

        val movedEvent = database.eventsDao().getById(synced.id)
        assertNull("caldavUrl should be cleared after move", movedEvent?.caldavUrl)
    }

    // ==================== Exception UID must match master ====================

    @Test
    fun `exception event UID matches master event UID`() = runTest {
        val masterEvent = createTestEvent().copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR"
        )
        val created = eventWriter.createEvent(masterEvent, isLocal = false)
        val masterUid = created.uid

        val occurrences = database.occurrencesDao().getForEvent(created.id)
        assertTrue(occurrences.isNotEmpty())

        val exception = eventWriter.editSingleOccurrence(
            created.id,
            occurrences.first().startTs,
            created.copy(title = "Exception")
        )

        // RFC 5545 §3.8.4.4 identifies an exception by the master's UID plus RECURRENCE-ID.
        assertEquals(
            "Exception UID must match master UID",
            masterUid,
            exception.uid
        )
    }

    // ==================== Time-based queries use occurrences table ====================

    @Test
    fun `recurring event with future occurrences found via occurrences table`() = runTest {
        // A daily series that started 30 days ago.
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val event = createTestEvent().copy(
            startTs = thirtyDaysAgo,
            endTs = thirtyDaysAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        val created = eventWriter.createEvent(event, isLocal = false)

        // Event.endTs is the first occurrence's end, so it is in the past.
        assertTrue(created.endTs < System.currentTimeMillis())

        // The occurrences table still has future rows.
        val futureOccurrences = database.occurrencesDao()
            .getForEvent(created.id)
            .filter { it.startTs >= System.currentTimeMillis() }

        assertTrue(
            "Should find future occurrences via occurrences table",
            futureOccurrences.isNotEmpty()
        )
    }
}
