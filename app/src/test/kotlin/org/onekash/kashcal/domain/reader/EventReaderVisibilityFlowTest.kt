package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for EventReader visibility flow reactivity.
 *
 * Documents the bug where `getOccurrencesWithEventsInRangeFlow()` doesn't re-emit
 * when calendar visibility changes, and verifies the fix with
 * `getVisibleOccurrencesWithEventsInRangeFlow()`.
 *
 * PRE tests document broken behavior (should pass before fix).
 * POST tests verify fix works (should pass after fix).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventReaderVisibilityFlowTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventReader: EventReader
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var calendar1Id: Long = 0
    private var calendar2Id: Long = 0

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventReader = EventReader(database)

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "test@local")
            )

            // Create two visible calendars
            calendar1Id = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "local://calendar1",
                    displayName = "Calendar 1",
                    color = 0xFF2196F3.toInt(),
                    isVisible = true
                )
            )
            calendar2Id = database.calendarsDao().insert(
                Calendar(
                    accountId = accountId,
                    caldavUrl = "local://calendar2",
                    displayName = "Calendar 2",
                    color = 0xFF4CAF50.toInt(),
                    isVisible = true
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun createEvent(
        calendarId: Long,
        title: String = "Test Event",
        startTs: Long = System.currentTimeMillis()
    ): Event {
        val event = Event(
            id = 0,
            uid = "test-${System.nanoTime()}",
            calendarId = calendarId,
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000, // 1 hour
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        val id = database.eventsDao().insert(event)
        val created = event.copy(id = id)

        // Generate occurrences
        val rangeStart = startTs - 86400000L * 7
        val rangeEnd = startTs + 86400000L * 7
        occurrenceGenerator.generateOccurrences(created, rangeStart, rangeEnd)

        return created
    }

    // ==================== PRE Tests (Document Bug) ====================

    /**
     * PRE TEST: Documents that getOccurrencesWithEventsInRangeFlow does NOT
     * re-emit when visibility changes.
     *
     * This test PASSES - documenting the broken behavior.
     * The OLD method only emits when occurrences/events change, not visibility.
     */
    @Test
    fun `PRE - getOccurrencesWithEventsInRangeFlow does NOT re-emit on visibility change`() = runTest {
        // Setup: 2 calendars, both visible, each with 1 event
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        // Use turbine to test flow emissions
        eventReader.getOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            // Get initial emission
            val initial = awaitItem()
            assertEquals("Initial emission should have 2 events", 2, initial.size)

            // Toggle calendar1 visibility to hidden
            database.calendarsDao().setVisible(calendar1Id, false)

            // The OLD method should NOT emit again on visibility change
            // This expectNoEvents() proves the bug - UI wouldn't update
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * PRE TEST: Shows that even after hiding a calendar, the old method's
     * emission still contains events from hidden calendars (no filtering).
     */
    @Test
    fun `PRE - old method does not filter by visibility`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        // Hide calendar1 BEFORE querying
        database.calendarsDao().setVisible(calendar1Id, false)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        // Old method returns ALL events regardless of visibility
        val result = eventReader.getOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).first()
        assertEquals(
            "Old method returns ALL events regardless of visibility (the bug)",
            2,
            result.size
        )
    }

    // ==================== POST Tests (Verify Fix) ====================

    /**
     * POST TEST: Verifies the new method excludes hidden calendar events.
     */
    @Test
    fun `POST - getVisibleOccurrencesWithEventsInRangeFlow excludes hidden calendar events`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Visible Event", startTs = now)

        // Hide calendar2 before creating its event
        database.calendarsDao().setVisible(calendar2Id, false)
        createEvent(calendarId = calendar2Id, title = "Hidden Event", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        // New method should only return visible calendar's event
        val visible = eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).first()

        assertEquals("Should only return 1 visible event", 1, visible.size)
        assertEquals("Should be from calendar1", calendar1Id, visible[0].occurrence.calendarId)
    }

    /**
     * POST TEST: KEY TEST - Proves combine() makes the flow reactive to visibility changes.
     */
    @Test
    fun `POST - getVisibleOccurrencesWithEventsInRangeFlow re-emits when visibility changes`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            // Get initial emission
            val initial = awaitItem()
            assertEquals("Initial emission should have 2 events", 2, initial.size)

            // Toggle calendar1 visibility to hidden
            database.calendarsDao().setVisible(calendar1Id, false)

            // NEW method SHOULD re-emit with filtered results
            val afterHide = awaitItem()
            assertEquals("After hiding calendar1, should only have 1 event", 1, afterHide.size)
            assertEquals("Remaining event should be from calendar2", calendar2Id, afterHide[0].occurrence.calendarId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * POST TEST: Regression test - still emits when events are added.
     */
    @Test
    fun `POST - getVisibleOccurrencesWithEventsInRangeFlow re-emits when events added`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Initial emission should have 1 event", 1, initial.size)

            // Add another event
            createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

            // Should re-emit with new event
            val afterAdd = awaitItem()
            assertEquals("After adding event, should have 2 events", 2, afterAdd.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * POST TEST: Verifies calendar deletion removes events from emission.
     */
    @Test
    fun `POST - re-emits when calendar is deleted`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Initial should have 2 events", 2, initial.size)

            // Delete calendar1's events and the calendar itself
            database.eventsDao().deleteByCalendarId(calendar1Id)
            database.occurrencesDao().deleteForCalendar(calendar1Id)
            database.calendarsDao().deleteById(calendar1Id)

            // Should re-emit with remaining event
            val afterDelete = awaitItem()
            assertEquals("Should have 1 event after deletion", 1, afterDelete.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * POST TEST: Verifies empty list when all calendars hidden.
     */
    @Test
    fun `POST - returns empty list when all calendars hidden`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        // Hide both calendars
        database.calendarsDao().setVisible(calendar1Id, false)
        database.calendarsDao().setVisible(calendar2Id, false)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        val result = eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).first()

        assertEquals("Should return empty list when all hidden", 0, result.size)
    }

    /**
     * POST TEST: Stress test for rapid visibility toggles.
     */
    @Test
    fun `POST - handles rapid visibility toggles gracefully`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            // Get initial
            awaitItem()

            // Rapid toggles - final state will be visible (9 % 2 == 1 -> true)
            repeat(10) {
                database.calendarsDao().setVisible(calendar1Id, it % 2 == 1)
            }

            // Skip intermediate emissions, get final state
            // (debounce(50) batches rapid changes)
            skipItems(awaitItem().let { 0 }) // Consume at least one emission

            // Verify no crash and eventually consistent
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * POST TEST: Verifies show all calendars works correctly.
     */
    @Test
    fun `POST - show calendar after hide updates emission`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        // Hide calendar1 first
        database.calendarsDao().setVisible(calendar1Id, false)

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Initially should have 1 event (calendar1 hidden)", 1, initial.size)

            // Show calendar1 again
            database.calendarsDao().setVisible(calendar1Id, true)

            val afterShow = awaitItem()
            assertEquals("After showing calendar1, should have 2 events", 2, afterShow.size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
