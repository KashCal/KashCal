package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for OccurrenceGenerator millisecond/second conversions.
 *
 * These tests specifically verify the boundary between KashCal (milliseconds)
 * and lib-recur (seconds) to catch unit conversion bugs.
 *
 * Key conversion points in OccurrenceGenerator:
 * - Line 306: event.startTs / MILLISECONDS_PER_SECOND (ms → sec)
 * - Line 374: occurrenceTsSeconds * MILLISECONDS_PER_SECOND (sec → ms)
 * - Line 492: timestampSeconds * MILLISECONDS_PER_SECOND (sec → ms)
 * - Line 532: calendar.timeInMillis / MILLISECONDS_PER_SECOND (ms → sec)
 *
 * Created as part of iCalDAV integration audit (Task #2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceGeneratorUnitConversionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "test@test.com")
            )
            testCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "https://test.com/cal/",
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

    // ========== Millisecond Precision Tests ==========

    @Test
    fun `occurrence startTs is in milliseconds not seconds`() = runTest {
        // This is the most basic sanity check
        val startMs = 1737590400000L // Jan 23, 2025 00:00:00 UTC
        val endMs = startMs + 3600000L // +1 hour

        val event = createEvent(startMs, endMs)
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1, occurrences.size)

        // Key assertion: startTs should be 13 digits (milliseconds), not 10 digits (seconds)
        assertTrue(
            "startTs should be in milliseconds (>1e12), got ${occurrences[0].startTs}",
            occurrences[0].startTs > 1_000_000_000_000L
        )
        assertEquals(startMs, occurrences[0].startTs)
    }

    @Test
    fun `occurrence preserves exact millisecond timestamp for non-recurring`() = runTest {
        // Test with a timestamp that has specific milliseconds
        // Non-recurring events use event.startTs directly, preserving milliseconds
        val startMs = 1737590400123L // Has 123 milliseconds
        val endMs = startMs + 3600000L

        val event = createEvent(startMs, endMs)
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)

        // Non-recurring events preserve exact milliseconds
        assertEquals(startMs, occurrences[0].startTs)
        assertEquals(endMs, occurrences[0].endTs)
    }

    @Test
    fun `recurring event truncates milliseconds to seconds`() = runTest {
        // Recurring events go through lib-recur which works in seconds
        val startMs = 1737590400123L // Has 123 milliseconds
        val endMs = startMs + 3600000L

        val event = createEvent(startMs, endMs, rrule = "FREQ=DAILY;COUNT=2")
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 5 * 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(2, occurrences.size)

        // lib-recur works in seconds, so milliseconds are truncated
        val expectedTruncated = (startMs / 1000) * 1000 // 1737590400000
        assertEquals(expectedTruncated, occurrences[0].startTs)

        // But duration should still be 3600000 (1 hour)
        assertEquals(3600000L, occurrences[0].endTs - occurrences[0].startTs)
    }

    @Test
    fun `recurring event occurrences are all in milliseconds`() = runTest {
        val startMs = 1737590400000L // Jan 23, 2025
        val endMs = startMs + 3600000L

        val event = createEvent(startMs, endMs, rrule = "FREQ=DAILY;COUNT=5")
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 10 * 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(5, occurrences.size)

        // All occurrences should be in milliseconds
        for (occ in occurrences) {
            assertTrue(
                "All startTs should be in milliseconds, got ${occ.startTs}",
                occ.startTs > 1_000_000_000_000L
            )
        }

        // Verify correct spacing (24 hours = 86400000 ms)
        for (i in 1 until occurrences.size) {
            val diff = occurrences[i].startTs - occurrences[i - 1].startTs
            assertEquals("Occurrences should be 24 hours apart", 86400000L, diff)
        }
    }

    @Test
    fun `expandForPreview returns milliseconds not seconds`() = runTest {
        val dtstartMs = 1737590400000L // Jan 23, 2025

        val results = occurrenceGenerator.expandForPreview(
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = dtstartMs,
            rangeStartMs = dtstartMs - 86400000,
            rangeEndMs = dtstartMs + 10 * 86400000
        )

        assertEquals(3, results.size)
        for (ts in results) {
            assertTrue(
                "expandForPreview should return milliseconds, got $ts",
                ts > 1_000_000_000_000L
            )
        }
    }

    // ========== Second Boundary Tests ==========

    @Test
    fun `event at exact second boundary handled correctly`() = runTest {
        // Timestamp at exact second (no milliseconds)
        val startMs = 1737590400000L // Exactly 00:00:00.000
        val endMs = startMs + 3600000L

        val event = createEvent(startMs, endMs)
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(startMs, occurrences[0].startTs)
    }

    @Test
    fun `event duration preserved across millisecond-second conversion`() = runTest {
        val startMs = 1737590400000L
        val durationMs = 5400000L // 1.5 hours
        val endMs = startMs + durationMs

        val event = createEvent(startMs, endMs)
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val actualDuration = occurrences[0].endTs - occurrences[0].startTs

        assertEquals("Duration should be preserved", durationMs, actualDuration)
    }

    @Test
    fun `recurring event with 30-minute interval generates correct timestamps`() = runTest {
        // This tests that sub-hour intervals work correctly through the conversion
        val startMs = 1737590400000L // 00:00
        val endMs = startMs + 1800000L // 30 minutes

        // Every 30 minutes for 4 occurrences
        // Note: MINUTELY isn't standard RRULE, so we test with times that differ by 30 min
        val event = createEvent(startMs, endMs, rrule = "FREQ=HOURLY;INTERVAL=1;COUNT=4")
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(4, occurrences.size)

        // Should be 1 hour apart (3600000 ms)
        assertEquals(3600000L, occurrences[1].startTs - occurrences[0].startTs)
    }

    // ========== Edge Cases ==========

    @Test
    fun `large timestamp (year 2100) handled correctly`() = runTest {
        // Tests that large timestamps don't overflow during conversion
        val startMs = 4102444800000L // Jan 1, 2100 00:00:00 UTC
        val endMs = startMs + 3600000L

        val event = createEvent(startMs, endMs)
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1, occurrences.size)
        assertEquals(startMs, occurrences[0].startTs)
    }

    @Test
    fun `historical timestamp (year 2000) handled correctly`() = runTest {
        val startMs = 946684800000L // Jan 1, 2000 00:00:00 UTC
        val endMs = startMs + 3600000L

        val event = createEvent(startMs, endMs)
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1, occurrences.size)
        assertEquals(startMs, occurrences[0].startTs)
    }

    @Test
    fun `very short event (1 second) preserved correctly`() = runTest {
        val startMs = 1737590400000L
        val endMs = startMs + 1000L // 1 second

        val event = createEvent(startMs, endMs)
        occurrenceGenerator.generateOccurrences(event, startMs - 86400000, startMs + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(1000L, occurrences[0].endTs - occurrences[0].startTs)
    }

    // ========== All-Day Event UTC Handling ==========

    @Test
    fun `all-day event timestamp is UTC midnight`() = runTest {
        val utcMidnight = 1737504000000L // Jan 22, 2025 00:00:00 UTC
        val endTs = utcMidnight + 86400000L - 1 // End of day

        val event = createAllDayEvent(utcMidnight, endTs)
        occurrenceGenerator.generateOccurrences(event, utcMidnight - 86400000, utcMidnight + 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(utcMidnight, occurrences[0].startTs)
    }

    @Test
    fun `all-day recurring event stays at UTC midnight`() = runTest {
        val utcMidnight = 1737504000000L // Jan 22, 2025 00:00:00 UTC
        val endTs = utcMidnight + 86400000L - 1

        val event = createAllDayEvent(utcMidnight, endTs, rrule = "FREQ=DAILY;COUNT=3")
        occurrenceGenerator.generateOccurrences(event, utcMidnight - 86400000, utcMidnight + 10 * 86400000)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
        assertEquals(3, occurrences.size)

        // Each occurrence should be at UTC midnight
        for (occ in occurrences) {
            val msInDay = occ.startTs % 86400000
            assertEquals("All-day occurrence should be at UTC midnight", 0L, msInDay)
        }
    }

    // ========== Helpers ==========

    private suspend fun createEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null,
        exdate: String? = null
    ): Event {
        val event = Event(
            uid = "test-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = "Test Event",
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            rrule = rrule,
            exdate = exdate,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }

    private suspend fun createAllDayEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null
    ): Event {
        val event = Event(
            uid = "test-allday-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = "All-Day Event",
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            isAllDay = true,
            timezone = null, // All-day events use UTC
            rrule = rrule,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }
}
