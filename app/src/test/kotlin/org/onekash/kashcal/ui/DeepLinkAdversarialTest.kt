package org.onekash.kashcal.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adversarial tests for deep link and intent handling.
 *
 * Tests edge cases:
 * - Reminder intent with deleted event
 * - Widget click with stale eventId
 * - Shortcut with invalid calendarId
 * - Rapid consecutive deep links
 * - Intent with negative/invalid timestamps
 * - Malformed URI schemes
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkAdversarialTest {

    private lateinit var database: KashCalDatabase
    private var testCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt()
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Deleted Event Tests ====================

    @Test
    fun `reminder intent with deleted event returns null`() = runTest {
        val now = System.currentTimeMillis()

        // Create and then delete event
        val event = Event(
            uid = "deleted@test.com",
            calendarId = testCalendarId,
            title = "Deleted Event",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        database.eventsDao().deleteById(eventId)

        // Simulate reminder intent arriving after deletion
        val deletedEvent = database.eventsDao().getById(eventId)
        assertNull("Deleted event should return null", deletedEvent)
    }

    @Test
    fun `widget click with stale eventId handled gracefully`() = runTest {
        // Event ID that never existed
        val staleEventId = 99999L

        val event = database.eventsDao().getById(staleEventId)
        assertNull("Non-existent event should return null", event)
    }

    @Test
    fun `deep link with soft-deleted event`() = runTest {
        val now = System.currentTimeMillis()

        // Create event marked for deletion (not yet synced)
        val event = Event(
            uid = "soft-deleted@test.com",
            calendarId = testCalendarId,
            title = "Soft Deleted",
            startTs = now,
            endTs = now + 3600000,
            dtstamp = now,
            syncStatus = SyncStatus.PENDING_DELETE
        )
        val eventId = database.eventsDao().insert(event)

        // Event exists but is pending deletion
        val softDeleted = database.eventsDao().getById(eventId)
        assertNotNull("Soft-deleted event still exists in DB", softDeleted)
        assertEquals(SyncStatus.PENDING_DELETE, softDeleted?.syncStatus)
    }

    // ==================== Invalid ID Tests ====================

    @Test
    fun `negative eventId handled gracefully`() = runTest {
        val negativeId = -1L
        val event = database.eventsDao().getById(negativeId)
        assertNull("Negative ID should return null", event)
    }

    @Test
    fun `zero eventId handled gracefully`() = runTest {
        val zeroId = 0L
        val event = database.eventsDao().getById(zeroId)
        assertNull("Zero ID should return null", event)
    }

    @Test
    fun `Long MAX_VALUE eventId handled gracefully`() = runTest {
        val maxId = Long.MAX_VALUE
        val event = database.eventsDao().getById(maxId)
        assertNull("MAX_VALUE ID should return null", event)
    }

    @Test
    fun `invalid calendarId returns empty event list`() = runTest {
        val invalidCalendarId = 99999L
        val now = System.currentTimeMillis()
        val events = database.eventsDao().getByCalendarIdInRange(invalidCalendarId, now, now + 86400000)
        assertTrue("Invalid calendar should return empty list", events.isEmpty())
    }

    // ==================== Timestamp Edge Cases ====================

    @Test
    fun `negative timestamp in deep link`() = runTest {
        val negativeTs = -1L

        // Query with negative timestamp should not crash
        val occurrences = database.occurrencesDao().getInRange(negativeTs, 0L)
        // Should return empty or handle gracefully
        assertNotNull("Should handle negative timestamp", occurrences)
    }

    @Test
    fun `zero timestamp in occurrence query`() = runTest {
        val zeroTs = 0L

        val occurrences = database.occurrencesDao().getInRange(zeroTs, 1000L)
        assertNotNull("Should handle zero timestamp", occurrences)
    }

    @Test
    fun `future timestamp beyond year 3000`() = runTest {
        // Year 3000 timestamp
        val farFuture = 32503680000000L

        val occurrences = database.occurrencesDao().getInRange(
            System.currentTimeMillis(),
            farFuture
        )
        assertNotNull("Should handle far future timestamp", occurrences)
    }

    // ==================== URI Parsing Tests ====================

    @Test
    fun `parse valid kashcal URI`() {
        val uri = Uri.parse("kashcal://event/123")

        assertEquals("kashcal", uri.scheme)
        assertEquals("event", uri.host)
        assertEquals("/123", uri.path)
    }

    @Test
    fun `parse URI with query parameters`() {
        val uri = Uri.parse("kashcal://event/123?occurrence=1704067200000")

        assertEquals("123", uri.pathSegments.firstOrNull())
        assertEquals("1704067200000", uri.getQueryParameter("occurrence"))
    }

    @Test
    fun `malformed URI with empty path`() {
        val uri = Uri.parse("kashcal://event/")

        assertTrue("Path segments should be empty or contain empty string",
            uri.pathSegments.isEmpty() || uri.pathSegments.first().isEmpty())
    }

    @Test
    fun `URI with non-numeric eventId`() {
        val uri = Uri.parse("kashcal://event/abc")

        val eventIdString = uri.pathSegments.firstOrNull()
        val eventId = eventIdString?.toLongOrNull()

        assertNull("Non-numeric eventId should parse to null", eventId)
    }

    // ==================== Intent Extra Tests ====================

    @Test
    fun `intent without required extras`() {
        val intent = Intent("org.onekash.kashcal.SHOW_EVENT")
        // No extras set

        val eventId = intent.getLongExtra("event_id", -1L)
        assertEquals("Missing extra should return default", -1L, eventId)
    }

    @Test
    fun `intent with wrong extra type`() {
        val intent = Intent("org.onekash.kashcal.SHOW_EVENT")
        intent.putExtra("event_id", "not_a_long") // String instead of Long

        // getLongExtra with wrong type returns default
        val eventId = intent.getLongExtra("event_id", -1L)
        assertEquals("Wrong type should return default", -1L, eventId)
    }

    @Test
    fun `intent with valid extras`() {
        val intent = Intent("org.onekash.kashcal.SHOW_EVENT")
        intent.putExtra("event_id", 123L)
        intent.putExtra("occurrence_ts", 1704067200000L)

        val eventId = intent.getLongExtra("event_id", -1L)
        val occurrenceTs = intent.getLongExtra("occurrence_ts", -1L)

        assertEquals(123L, eventId)
        assertEquals(1704067200000L, occurrenceTs)
    }

    // ==================== Concurrent Access Tests ====================

    @Test
    fun `rapid event lookups do not crash`() = runTest {
        val now = System.currentTimeMillis()

        // Create some events
        repeat(10) { i ->
            database.eventsDao().insert(
                Event(
                    uid = "rapid-$i@test.com",
                    calendarId = testCalendarId,
                    title = "Event $i",
                    startTs = now + i * 3600000,
                    endTs = now + i * 3600000 + 3600000,
                    dtstamp = now,
                    syncStatus = SyncStatus.SYNCED
                )
            )
        }

        // Rapid lookups (simulating fast widget/notification clicks)
        repeat(100) { i ->
            val eventId = (i % 15).toLong() + 1 // Mix of valid and invalid IDs
            database.eventsDao().getById(eventId)
        }

        // Should complete without crash
        assertTrue("Rapid lookups should complete", true)
    }
}
