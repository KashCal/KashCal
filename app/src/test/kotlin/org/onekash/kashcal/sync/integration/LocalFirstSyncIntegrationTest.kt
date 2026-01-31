package org.onekash.kashcal.sync.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.*
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.strategy.PullResult
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for local-first sync behavior.
 *
 * These tests verify the complete flow of sync operations when
 * events have pending local changes. They use a real Room database
 * with a mocked CalDAV client.
 *
 * Key scenarios tested:
 * 1. Events with PENDING_CREATE are not overwritten by server data
 * 2. Events with PENDING_UPDATE are not overwritten by server data
 * 3. Events with PENDING_DELETE are not deleted during pull sync
 * 4. Exception events with pending changes are preserved
 * 5. Occurrence links are preserved during regeneration
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LocalFirstSyncIntegrationTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var client: CalDavClient
    private lateinit var dataStore: KashCalDataStore
    private lateinit var syncSessionStore: SyncSessionStore
    private lateinit var testCalendar: Calendar
    private var testAccountId: Long = 0

    private val quirks = ICloudQuirks()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        client = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        syncSessionStore = mockk(relaxed = true)

        every { dataStore.defaultReminderMinutes } returns flowOf(15)
        every { dataStore.defaultAllDayReminder } returns flowOf(1440)

        pullStrategy = PullStrategy(
            database = database,
            calendarsDao = database.calendarsDao(),
            eventsDao = database.eventsDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = quirks,
            dataStore = dataStore,
            syncSessionStore = syncSessionStore
        )

        // Create test account and calendar
        runTest {
            testAccountId = database.accountsDao().insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
            )
            val calendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = testAccountId,
                    caldavUrl = "https://caldav.icloud.com/12345/calendars/home/",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
            testCalendar = database.calendarsDao().getById(calendarId)!!
        }
    }

    @After
    fun tearDown() {
        database.close()
        unmockkAll()
    }

    // ========== Full Sync Flow Tests ==========

    @Test
    fun `full sync preserves local event with PENDING_CREATE when server has different version`() = runTest {
        // Given - local event with pending create
        val localEvent = insertEvent(
            uid = "local-new-event@test.com",
            title = "Local New Event",
            caldavUrl = "${testCalendar.caldavUrl}local-new-event.ics",
            syncStatus = SyncStatus.PENDING_CREATE
        )
        occurrenceGenerator.regenerateOccurrences(localEvent)

        // Server has a different version of the same event
        val serverIcal = createIcal("local-new-event@test.com", "Server Version - Different Title")
        mockFullSyncResponse(
            ctag = "new-ctag",
            events = listOf(
                CalDavEvent(
                    href = "local-new-event.ics",
                    url = "${testCalendar.caldavUrl}local-new-event.ics",
                    etag = "server-etag",
                    icalData = serverIcal
                )
            )
        )

        // When - pull from server
        val result = pullStrategy.pull(testCalendar, client = client)

        // Then - local event should be preserved (not overwritten)
        assertTrue(result is PullResult.Success)
        val savedEvent = database.eventsDao().getById(localEvent.id)
        assertNotNull(savedEvent)
        assertEquals("Local New Event", savedEvent!!.title) // Original title preserved
        assertEquals(SyncStatus.PENDING_CREATE, savedEvent.syncStatus)
    }

    @Test
    fun `full sync preserves local event with PENDING_UPDATE when server has older version`() = runTest {
        // Given - local event that was modified offline
        val localEvent = insertEvent(
            uid = "modified-event@test.com",
            title = "Updated Title (Local)",
            caldavUrl = "${testCalendar.caldavUrl}modified-event.ics",
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        occurrenceGenerator.regenerateOccurrences(localEvent)

        // Server has older version
        val serverIcal = createIcal("modified-event@test.com", "Old Title (Server)")
        mockFullSyncResponse(
            ctag = "new-ctag",
            events = listOf(
                CalDavEvent(
                    href = "modified-event.ics",
                    url = "${testCalendar.caldavUrl}modified-event.ics",
                    etag = "old-etag",
                    icalData = serverIcal
                )
            )
        )

        // When
        val result = pullStrategy.pull(testCalendar, client = client)

        // Then - local changes preserved
        assertTrue(result is PullResult.Success)
        val savedEvent = database.eventsDao().getById(localEvent.id)
        assertNotNull(savedEvent)
        assertEquals("Updated Title (Local)", savedEvent!!.title)
        assertEquals(SyncStatus.PENDING_UPDATE, savedEvent.syncStatus)
    }

    @Test
    fun `full sync does not delete local event with PENDING_DELETE even if server says deleted`() = runTest {
        // Given - local event marked for deletion (waiting for push sync)
        val localEvent = insertEvent(
            uid = "to-delete@test.com",
            title = "Event To Delete",
            caldavUrl = "${testCalendar.caldavUrl}to-delete.ics",
            syncStatus = SyncStatus.PENDING_DELETE
        )

        // Server returns empty list (event was deleted by someone else or doesn't have it)
        mockFullSyncResponse(ctag = "new-ctag", events = emptyList())

        // When
        val result = pullStrategy.pull(testCalendar, client = client)

        // Then - local event is NOT deleted (our delete needs to push first)
        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        val savedEvent = database.eventsDao().getById(localEvent.id)
        assertNotNull("Event should still exist with PENDING_DELETE", savedEvent)
        assertEquals(SyncStatus.PENDING_DELETE, savedEvent!!.syncStatus)
    }

    // ========== Incremental Sync Flow Tests ==========

    @Test
    fun `incremental sync skips deletion of event with pending local changes`() = runTest {
        // Given - calendar with sync token
        val calendarWithToken = testCalendar.copy(
            ctag = "old-ctag",
            syncToken = "sync-token-123"
        )
        database.calendarsDao().update(calendarWithToken)

        // Local event with pending changes
        val localEvent = insertEvent(
            uid = "pending-event@test.com",
            title = "Has Pending Changes",
            caldavUrl = "${testCalendar.caldavUrl}pending-event.ics",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        // Server says this event was deleted
        coEvery { client.getCtag(any()) } returns CalDavResult.success("new-ctag")
        coEvery { client.syncCollection(any(), "sync-token-123") } returns CalDavResult.success(
            SyncReport(
                syncToken = "sync-token-456",
                changed = emptyList(),
                deleted = listOf("/calendars/home/pending-event.ics")
            )
        )

        // When
        val result = pullStrategy.pull(calendarWithToken, client = client)

        // Then - event should NOT be deleted
        assertTrue(result is PullResult.Success)
        assertEquals(0, (result as PullResult.Success).eventsDeleted)
        assertNotNull(database.eventsDao().getById(localEvent.id))
    }

    // ========== Recurring Event with Exception Tests ==========

    @Test
    fun `sync preserves exception event with pending update during master event update`() = runTest {
        // Given - master recurring event (synced)
        val masterEvent = insertEvent(
            uid = "recurring@test.com",
            title = "Weekly Meeting",
            caldavUrl = "${testCalendar.caldavUrl}recurring.ics",
            syncStatus = SyncStatus.SYNCED,
            rrule = "FREQ=WEEKLY;BYDAY=MO"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        // Get the second occurrence and create a local exception
        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        assertTrue("Should have at least 2 occurrences", occurrences.size >= 2)
        val secondOccurrence = occurrences[1]

        // Local exception event with pending changes
        val exceptionEvent = insertEvent(
            uid = "recurring@test.com",
            title = "Modified Meeting (Local Edit)",
            startTs = secondOccurrence.startTs + 3600000, // Moved 1 hour later
            caldavUrl = null,
            syncStatus = SyncStatus.PENDING_UPDATE,
            originalEventId = masterEvent.id,
            originalInstanceTime = secondOccurrence.startTs
        )

        // Link the exception to occurrence
        occurrenceGenerator.linkException(masterEvent.id, secondOccurrence.startTs, exceptionEvent.id)

        // Server has updated master with different exception
        val serverIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:recurring@test.com
            DTSTAMP:20240101T120000Z
            DTSTART:${formatTimestamp(masterEvent.startTs)}
            DTEND:${formatTimestamp(masterEvent.endTs)}
            SUMMARY:Weekly Meeting (Server Updated)
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring@test.com
            DTSTAMP:20240115T120000Z
            RECURRENCE-ID:${formatTimestamp(secondOccurrence.startTs)}
            DTSTART:${formatTimestamp(secondOccurrence.startTs + 7200000)}
            DTEND:${formatTimestamp(secondOccurrence.startTs + 10800000)}
            SUMMARY:Server Modified Exception
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        mockFullSyncResponse(
            ctag = "new-ctag",
            events = listOf(
                CalDavEvent(
                    href = "recurring.ics",
                    url = "${testCalendar.caldavUrl}recurring.ics",
                    etag = "new-etag",
                    icalData = serverIcal
                )
            )
        )

        // When
        val result = pullStrategy.pull(testCalendar, client = client)

        // Then - local exception with pending changes should be preserved
        assertTrue(result is PullResult.Success)
        val savedException = database.eventsDao().getById(exceptionEvent.id)
        assertNotNull("Local exception should still exist", savedException)
        assertEquals("Modified Meeting (Local Edit)", savedException!!.title) // Local title preserved
        assertEquals(SyncStatus.PENDING_UPDATE, savedException.syncStatus)
    }

    // ========== Occurrence Link Preservation Tests ==========

    @Test
    fun `occurrence regeneration preserves exception links through full sync cycle`() = runTest {
        // Given - recurring event with linked exception
        val masterEvent = insertEvent(
            uid = "linked-recurring@test.com",
            title = "Recurring Event",
            caldavUrl = "${testCalendar.caldavUrl}linked-recurring.ics",
            syncStatus = SyncStatus.SYNCED,
            rrule = "FREQ=DAILY;COUNT=5"
        )
        occurrenceGenerator.regenerateOccurrences(masterEvent)

        val occurrences = database.occurrencesDao().getForEvent(masterEvent.id)
        val targetOccurrence = occurrences[2] // 3rd occurrence

        // Create exception and link
        val exceptionEvent = insertEvent(
            uid = "linked-recurring@test.com",
            title = "Modified Occurrence",
            startTs = targetOccurrence.startTs + 3600000,
            syncStatus = SyncStatus.SYNCED,
            originalEventId = masterEvent.id,
            originalInstanceTime = targetOccurrence.startTs
        )
        occurrenceGenerator.linkException(masterEvent.id, targetOccurrence.startTs, exceptionEvent.id)
        occurrenceGenerator.cancelOccurrence(masterEvent.id, targetOccurrence.startTs)

        // Verify link exists before sync
        val beforeSync = database.occurrencesDao().getForEvent(masterEvent.id)
        val linkedBefore = beforeSync.find { it.startTs == targetOccurrence.startTs }
        assertEquals(exceptionEvent.id, linkedBefore?.exceptionEventId)
        assertTrue(linkedBefore?.isCancelled == true)

        // Server sends the same event (no changes, but triggers regeneration)
        val serverIcal = createRecurringIcal(
            uid = "linked-recurring@test.com",
            title = "Recurring Event",
            rrule = "FREQ=DAILY;COUNT=5",
            startTs = masterEvent.startTs
        )
        mockFullSyncResponse(
            ctag = "new-ctag",
            events = listOf(
                CalDavEvent(
                    href = "linked-recurring.ics",
                    url = "${testCalendar.caldavUrl}linked-recurring.ics",
                    etag = "new-etag",
                    icalData = serverIcal
                )
            )
        )

        // When
        pullStrategy.pull(testCalendar, client = client)

        // Then - exception links should still be preserved after regeneration
        // v15.0.6: Find by exceptionEventId since regeneration now updates occurrence times to exception's times
        val afterSync = database.occurrencesDao().getForEvent(masterEvent.id)
        val linkedAfter = afterSync.find { it.exceptionEventId == exceptionEvent.id }
        assertNotNull("Occurrence should exist after sync", linkedAfter)
        assertEquals("Exception link should be preserved", exceptionEvent.id, linkedAfter?.exceptionEventId)
        assertTrue("Cancelled status should be preserved", linkedAfter?.isCancelled == true)
        // Verify times were updated to exception event's times
        assertEquals("Times should match exception event", exceptionEvent.startTs, linkedAfter?.startTs)
    }

    // ========== Mixed Scenario Tests ==========

    @Test
    fun `sync correctly handles mix of synced and pending events`() = runTest {
        // Given - mix of events with different sync statuses
        val syncedEvent = insertEvent(
            uid = "synced@test.com",
            title = "Synced Event",
            caldavUrl = "${testCalendar.caldavUrl}synced.ics",
            syncStatus = SyncStatus.SYNCED
        )

        val pendingCreateEvent = insertEvent(
            uid = "pending-create@test.com",
            title = "Local Only Event",
            caldavUrl = "${testCalendar.caldavUrl}pending-create.ics",
            syncStatus = SyncStatus.PENDING_CREATE
        )

        val pendingUpdateEvent = insertEvent(
            uid = "pending-update@test.com",
            title = "Modified Locally",
            caldavUrl = "${testCalendar.caldavUrl}pending-update.ics",
            syncStatus = SyncStatus.PENDING_UPDATE
        )

        // Server has updates for synced event, and different versions for others
        mockFullSyncResponse(
            ctag = "new-ctag",
            events = listOf(
                CalDavEvent(
                    href = "synced.ics",
                    url = "${testCalendar.caldavUrl}synced.ics",
                    etag = "new-etag",
                    icalData = createIcal("synced@test.com", "Synced Event Updated")
                ),
                CalDavEvent(
                    href = "pending-create.ics",
                    url = "${testCalendar.caldavUrl}pending-create.ics",
                    etag = "server-etag",
                    icalData = createIcal("pending-create@test.com", "Server Version")
                ),
                CalDavEvent(
                    href = "pending-update.ics",
                    url = "${testCalendar.caldavUrl}pending-update.ics",
                    etag = "server-etag",
                    icalData = createIcal("pending-update@test.com", "Server Version")
                )
            )
        )

        // When
        val result = pullStrategy.pull(testCalendar, client = client)

        // Then
        assertTrue(result is PullResult.Success)

        // Synced event should be updated
        val updatedSynced = database.eventsDao().getById(syncedEvent.id)
        assertEquals("Synced Event Updated", updatedSynced?.title)

        // Pending events should NOT be overwritten
        val preservedCreate = database.eventsDao().getById(pendingCreateEvent.id)
        assertEquals("Local Only Event", preservedCreate?.title)
        assertEquals(SyncStatus.PENDING_CREATE, preservedCreate?.syncStatus)

        val preservedUpdate = database.eventsDao().getById(pendingUpdateEvent.id)
        assertEquals("Modified Locally", preservedUpdate?.title)
        assertEquals(SyncStatus.PENDING_UPDATE, preservedUpdate?.syncStatus)
    }

    // ========== Helper Methods ==========

    private suspend fun insertEvent(
        uid: String,
        title: String,
        caldavUrl: String? = null,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        rrule: String? = null,
        originalEventId: Long? = null,
        originalInstanceTime: Long? = null,
        startTs: Long = System.currentTimeMillis()
    ): Event {
        val event = Event(
            uid = uid,
            calendarId = testCalendar.id,
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            caldavUrl = caldavUrl,
            syncStatus = syncStatus,
            rrule = rrule,
            originalEventId = originalEventId,
            originalInstanceTime = originalInstanceTime
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }

    private fun mockFullSyncResponse(ctag: String, events: List<CalDavEvent>) {
        coEvery { client.getCtag(any()) } returns CalDavResult.success(ctag)
        coEvery { client.fetchEventsInRange(any(), any(), any()) } returns CalDavResult.success(events)
        coEvery { client.getSyncToken(any()) } returns CalDavResult.success("new-sync-token")
    }

    private fun createIcal(uid: String, summary: String): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20240101T120000Z
            DTSTART:20240101T100000Z
            DTEND:20240101T110000Z
            SUMMARY:$summary
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    private fun createRecurringIcal(uid: String, title: String, rrule: String, startTs: Long): String {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:20240101T120000Z
            DTSTART:${formatTimestamp(startTs)}
            DTEND:${formatTimestamp(startTs + 3600000)}
            SUMMARY:$title
            RRULE:$rrule
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    private fun formatTimestamp(ts: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        return String.format(
            "%04d%02d%02dT%02d%02d%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }
}
