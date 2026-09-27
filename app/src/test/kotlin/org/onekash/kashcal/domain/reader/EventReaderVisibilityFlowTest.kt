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
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Tests how calendar visibility reaches the occurrence Flows.
 *
 * The `PRE` tests pin that [EventReader.getOccurrencesWithEventsInRangeFlow] neither filters by
 * visibility nor re-emits when it changes, so a UI on it wouldn't follow a toggle. The `POST`
 * tests cover [EventReader.getVisibleOccurrencesWithEventsInRangeFlow], which combines with the
 * visible calendars: it drops hidden calendars' events, emits an empty list when every calendar
 * is hidden, re-emits on a hide, a show, an added event and a deleted calendar, and survives
 * rapid toggles.
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

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        eventReader = EventReader(database)

        runTest {
            val accountId = database.accountsDao().insert(
                Account(provider = AccountProvider.LOCAL, email = "test@local")
            )

            // Two visible calendars.
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

        // Occurrences for a week either side of the start.
        val rangeStart = startTs - 86400000L * 7
        val rangeEnd = startTs + 86400000L * 7
        occurrenceGenerator.generateOccurrences(created, rangeStart, rangeEnd)

        return created
    }

    // ==================== Unfiltered Flow ====================

    /**
     * Checks that [EventReader.getOccurrencesWithEventsInRangeFlow] doesn't re-emit on a
     * visibility change: it emits only when occurrences or events change.
     */
    @Test
    fun `PRE - getOccurrencesWithEventsInRangeFlow does NOT re-emit on visibility change`() = runTest {
        // Two visible calendars with one event each.
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Initial emission should have 2 events", 2, initial.size)

            database.calendarsDao().setVisible(calendar1Id, false)

            // No emission follows the hide, so a UI on this Flow wouldn't update.
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Checks that the unfiltered Flow still returns a hidden calendar's events. */
    @Test
    fun `PRE - old method does not filter by visibility`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        // Hide calendar1 before querying.
        database.calendarsDao().setVisible(calendar1Id, false)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        // Both events come back, visible or not.
        val result = eventReader.getOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).first()
        assertEquals(
            "Old method returns ALL events regardless of visibility (the bug)",
            2,
            result.size
        )
    }

    // ==================== Visible-calendar Flow ====================

    /** Checks that a hidden calendar's events are left out. */
    @Test
    fun `POST - getVisibleOccurrencesWithEventsInRangeFlow excludes hidden calendar events`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Visible Event", startTs = now)

        // Hide calendar2 before creating its event.
        database.calendarsDao().setVisible(calendar2Id, false)
        createEvent(calendarId = calendar2Id, title = "Hidden Event", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        // Only the visible calendar's event.
        val visible = eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).first()

        assertEquals("Should only return 1 visible event", 1, visible.size)
        assertEquals("Should be from calendar1", calendar1Id, visible[0].occurrence.calendarId)
    }

    /** Checks that hiding a calendar re-emits without its events, through `combine`. */
    @Test
    fun `POST - getVisibleOccurrencesWithEventsInRangeFlow re-emits when visibility changes`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Initial emission should have 2 events", 2, initial.size)

            database.calendarsDao().setVisible(calendar1Id, false)

            // Re-emits with calendar1's event filtered out.
            val afterHide = awaitItem()
            assertEquals("After hiding calendar1, should only have 1 event", 1, afterHide.size)
            assertEquals("Remaining event should be from calendar2", calendar2Id, afterHide[0].occurrence.calendarId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Checks that the Flow still re-emits when an event is added. */
    @Test
    fun `POST - getVisibleOccurrencesWithEventsInRangeFlow re-emits when events added`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Initial emission should have 1 event", 1, initial.size)

            // Insert and generateOccurrences are two writes, so Room may emit the state
            // between them.
            createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

            // Skip emissions until both events are present.
            var afterAdd = awaitItem()
            while (afterAdd.size < 2) {
                afterAdd = awaitItem()
            }
            assertEquals("After adding event, should have 2 events", 2, afterAdd.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Checks that deleting a calendar re-emits without its events. */
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

            // Delete calendar1's events, occurrences and the calendar.
            database.eventsDao().deleteByCalendarId(calendar1Id)
            database.occurrencesDao().deleteForCalendar(calendar1Id)
            database.calendarsDao().deleteById(calendar1Id)

            // Re-emits with calendar2's event.
            val afterDelete = awaitItem()
            assertEquals("Should have 1 event after deletion", 1, afterDelete.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Checks that the Flow emits an empty list when every calendar is hidden. */
    @Test
    fun `POST - returns empty list when all calendars hidden`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        database.calendarsDao().setVisible(calendar1Id, false)
        database.calendarsDao().setVisible(calendar2Id, false)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        val result = eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).first()

        assertEquals("Should return empty list when all hidden", 0, result.size)
    }

    /**
     * Toggles visibility ten times in a row. Passes when an emission arrives and nothing
     * throws; the final state isn't asserted.
     */
    @Test
    fun `POST - handles rapid visibility toggles gracefully`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            awaitItem()

            // The last toggle leaves it visible (9 % 2 == 1).
            repeat(10) {
                database.calendarsDao().setVisible(calendar1Id, it % 2 == 1)
            }

            // Waits for one emission; debounce(50) batches the toggles.
            skipItems(awaitItem().let { 0 })

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Checks that showing a hidden calendar re-emits with its events. */
    @Test
    fun `POST - show calendar after hide updates emission`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = calendar1Id, title = "Event 1", startTs = now)
        createEvent(calendarId = calendar2Id, title = "Event 2", startTs = now + 1800000)

        val rangeStart = now - 86400000
        val rangeEnd = now + 86400000

        // Hide calendar1 before subscribing.
        database.calendarsDao().setVisible(calendar1Id, false)

        eventReader.getVisibleOccurrencesWithEventsInRangeFlow(rangeStart, rangeEnd).test(timeout = 5.seconds) {
            val initial = awaitItem()
            assertEquals("Initially should have 1 event (calendar1 hidden)", 1, initial.size)

            database.calendarsDao().setVisible(calendar1Id, true)

            val afterShow = awaitItem()
            assertEquals("After showing calendar1, should have 2 events", 2, afterShow.size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
