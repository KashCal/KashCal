package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for OccurrenceGenerator respecting sync lookback setting.
 *
 * When user sets sync lookback to e.g. 90 days, recurring events should only
 * generate occurrences within that past window (plus 2 years into future).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OccurrenceGeneratorSyncLookbackTest {

    private lateinit var database: KashCalDatabase
    private lateinit var dataStore: KashCalDataStore
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    companion object {
        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStore = mockk()

        // Default: All events (no lookback limit)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        occurrenceGenerator = OccurrenceGenerator(
            database,
            database.occurrencesDao(),
            database.eventsDao(),
            dataStore
        )

        // Create test account and calendar
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

    // ========== Sync Lookback Tests ==========

    @Test
    fun `regenerateOccurrences respects 90 day sync lookback for past window`() = runTest {
        // Setup: Set sync lookback to 90 days
        every { dataStore.syncPastDays } returns flowOf(90)

        // Create daily recurring event starting 1 year ago
        val oneYearAgo = System.currentTimeMillis() - (365 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = oneYearAgo,
            endTs = oneYearAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Act
        val count = occurrenceGenerator.regenerateOccurrences(event)

        // Assert: Should have ~90 past occurrences + ~730 future = ~820 total
        // NOT 365 past + 730 future = ~1095 total
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastOccurrences = occurrences.filter { it.startTs < now }
        val futureOccurrences = occurrences.filter { it.startTs >= now }

        // Past should be bounded by ~90 days (with some margin for test timing)
        assertTrue(
            "Expected ~90 past occurrences (±10) but got ${pastOccurrences.size}",
            pastOccurrences.size in 80..100
        )

        // Future should still be ~730 (2 years)
        assertTrue(
            "Expected ~730 future occurrences (±30) but got ${futureOccurrences.size}",
            futureOccurrences.size in 700..760
        )

        // Oldest occurrence should be ~90 days ago, not 365 days ago
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val daysOld = (now - oldest.startTs) / MS_PER_DAY
        assertTrue(
            "Oldest occurrence should be ~90 days ago but was $daysOld days ago",
            daysOld in 85..95
        )
    }

    @Test
    fun `regenerateOccurrences with All lookback generates back to event start`() = runTest {
        // Setup: Set sync lookback to All (Int.MAX_VALUE)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // Create daily recurring event starting 1 year ago
        val oneYearAgo = System.currentTimeMillis() - (365 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = oneYearAgo,
            endTs = oneYearAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Act
        val count = occurrenceGenerator.regenerateOccurrences(event)

        // Assert: With "All events", should generate back to event start
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastOccurrences = occurrences.filter { it.startTs < now }

        // Past should have ~365 occurrences (all since event start)
        assertTrue(
            "Expected ~365 past occurrences but got ${pastOccurrences.size}",
            pastOccurrences.size in 355..375
        )

        // Oldest should be ~365 days ago (at event start)
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val daysOld = (now - oldest.startTs) / MS_PER_DAY
        assertTrue(
            "Oldest occurrence should be ~365 days ago but was $daysOld days ago",
            daysOld in 360..370
        )
    }

    @Test
    fun `regenerateOccurrences with All lookback generates back to event start for 5 year old event`() = runTest {
        // Setup: Set sync lookback to All (Int.MAX_VALUE)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // Create weekly recurring event starting 5 years ago
        val fiveYearsAgo = System.currentTimeMillis() - (1825 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = fiveYearsAgo,
            endTs = fiveYearsAgo + 3600000,
            rrule = "FREQ=WEEKLY"
        )

        // Act
        occurrenceGenerator.regenerateOccurrences(event)

        // Assert: Should generate back to event start (~5 years), NOT capped at 2 years
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val daysOld = (now - oldest.startTs) / MS_PER_DAY

        // Should be ~1825 days old (5 years), NOT ~720 (2 years)
        assertTrue(
            "Oldest occurrence should be ~1825 days ago (event start) but was $daysOld days ago. " +
                "This fails if MAX_VALUE is still capped at 2 years (would be ~720).",
            daysOld in 1820..1830
        )

        // Should have ~260 past weekly occurrences (5 years * 52 weeks)
        val pastOccurrences = occurrences.filter { it.startTs < now }
        assertTrue(
            "Expected ~260 past weekly occurrences but got ${pastOccurrences.size}",
            pastOccurrences.size in 255..265
        )
    }

    @Test
    fun `regenerateOccurrences respects 180 day sync lookback`() = runTest {
        // Setup: Set sync lookback to 180 days (6 months)
        every { dataStore.syncPastDays } returns flowOf(180)

        // Create daily recurring event starting 2 years ago
        val twoYearsAgo = System.currentTimeMillis() - (730 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = twoYearsAgo,
            endTs = twoYearsAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Act
        occurrenceGenerator.regenerateOccurrences(event)

        // Assert
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastOccurrences = occurrences.filter { it.startTs < now }

        // Past should be bounded by ~180 days
        assertTrue(
            "Expected ~180 past occurrences (±10) but got ${pastOccurrences.size}",
            pastOccurrences.size in 170..190
        )

        // Oldest occurrence should be ~180 days ago
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val daysOld = (now - oldest.startTs) / MS_PER_DAY
        assertTrue(
            "Oldest occurrence should be ~180 days ago but was $daysOld days ago",
            daysOld in 175..185
        )
    }

    @Test
    fun `regenerateOccurrences with short lookback still includes event start if within window`() = runTest {
        // Setup: Set sync lookback to 90 days
        every { dataStore.syncPastDays } returns flowOf(90)

        // Create daily recurring event starting 30 days ago (within 90 day window)
        val thirtyDaysAgo = System.currentTimeMillis() - (30 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = thirtyDaysAgo,
            endTs = thirtyDaysAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Act
        occurrenceGenerator.regenerateOccurrences(event)

        // Assert: Should include all ~30 past occurrences (since event is newer than lookback)
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastOccurrences = occurrences.filter { it.startTs < now }

        // Should have ~30 past occurrences (all since event started)
        assertTrue(
            "Expected ~30 past occurrences but got ${pastOccurrences.size}",
            pastOccurrences.size in 28..32
        )

        // First occurrence should be at event start time
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val diffFromStart = kotlin.math.abs(oldest.startTs - thirtyDaysAgo)
        assertTrue(
            "First occurrence should match event start time",
            diffFromStart < 2 * MS_PER_DAY
        )
    }

    @Test
    fun `regenerateOccurrences keeps 2 year future window regardless of sync lookback`() = runTest {
        // Setup: Set sync lookback to just 30 days
        every { dataStore.syncPastDays } returns flowOf(30)

        // Create daily recurring event starting 60 days ago
        val sixtyDaysAgo = System.currentTimeMillis() - (60 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = sixtyDaysAgo,
            endTs = sixtyDaysAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Act
        occurrenceGenerator.regenerateOccurrences(event)

        // Assert: Past should be ~30 days, but future should still be ~730 days
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val futureOccurrences = occurrences.filter { it.startTs >= now }

        // Future should still be 2 years (~720 days = 24*30)
        assertTrue(
            "Expected ~720 future occurrences but got ${futureOccurrences.size}",
            futureOccurrences.size in 700..740
        )

        // Newest occurrence should be ~2 years in future (24*30=720 days, with margin)
        val newest = occurrences.maxByOrNull { it.startTs }!!
        val daysInFuture = (newest.startTs - now) / MS_PER_DAY
        assertTrue(
            "Newest occurrence should be ~720 days in future but was $daysInFuture days",
            daysInFuture in 715..725
        )
    }

    // ========== extendPastOccurrences Sync Lookback Tests ==========

    @Test
    fun `extendPastOccurrences extends beyond initial window regardless of syncPastDays`() = runTest {
        // syncPastDays limits initial generation window, not on-demand extension.
        // On-demand extension is triggered by explicit user navigation — no bandwidth cost.
        every { dataStore.syncPastDays } returns flowOf(90)

        // Create daily recurring event starting 2 years ago
        val twoYearsAgo = System.currentTimeMillis() - (730 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = twoYearsAgo,
            endTs = twoYearsAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // First regenerate with 90-day lookback (creates ~90 past + ~730 future)
        occurrenceGenerator.regenerateOccurrences(event)

        val occurrencesBefore = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastBefore = occurrencesBefore.filter { it.startTs < now }

        // Act: Extend past occurrences to 1 year ago (beyond initial 90-day window)
        val oneYearAgoMs = now - (365 * MS_PER_DAY)
        val extended = occurrenceGenerator.extendPastOccurrences(event, oneYearAgoMs)

        // Assert: Should extend — syncPastDays does not limit on-demand extension
        val occurrencesAfter = database.occurrencesDao().getForEvent(event.id)
        val pastAfter = occurrencesAfter.filter { it.startTs < now }

        assertTrue(
            "extendPastOccurrences should add occurrences beyond initial window: before=${pastBefore.size}, after=${pastAfter.size}",
            pastAfter.size > pastBefore.size
        )
        assertTrue("Should return positive count when extending", extended > 0)
    }

    @Test
    fun `extendPastOccurrences extends within lookback window`() = runTest {
        // Setup: Set sync lookback to 180 days
        every { dataStore.syncPastDays } returns flowOf(180)

        // Create daily recurring event starting 1 year ago
        val oneYearAgo = System.currentTimeMillis() - (365 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = oneYearAgo,
            endTs = oneYearAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // First regenerate with limited window (only 30 days back for setup)
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30 * MS_PER_DAY)
        occurrenceGenerator.generateOccurrences(event, thirtyDaysAgo, now + (730 * MS_PER_DAY))

        val pastBefore = database.occurrencesDao().getForEvent(event.id)
            .filter { it.startTs < now }

        // Act: Extend to 120 days ago (within 180-day lookback)
        val extendTarget = now - (120 * MS_PER_DAY)
        val extended = occurrenceGenerator.extendPastOccurrences(event, extendTarget)

        // Assert: Should extend within lookback window
        val pastAfter = database.occurrencesDao().getForEvent(event.id)
            .filter { it.startTs < now }

        assertTrue(
            "Should have added occurrences: before=${pastBefore.size}, after=${pastAfter.size}",
            pastAfter.size > pastBefore.size
        )
        assertTrue("Should return positive count when extending", extended > 0)
    }

    @Test
    fun `extendPastOccurrences with All events lookback allows extension to event start`() = runTest {
        // Setup: Set sync lookback to All (Int.MAX_VALUE)
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        // Create daily recurring event starting 3 years ago
        val threeYearsAgo = System.currentTimeMillis() - (1095 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = threeYearsAgo,
            endTs = threeYearsAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // First regenerate — with unbounded MAX_VALUE, already goes back to event start
        occurrenceGenerator.regenerateOccurrences(event)

        val now = System.currentTimeMillis()
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val daysOld = (now - oldest.startTs) / MS_PER_DAY

        // With "All events", regenerate should already reach event start (~1095 days)
        assertTrue(
            "With 'All events', oldest occurrence should be ~1095 days old, was $daysOld",
            daysOld in 1090..1100
        )
    }

    // ========== Helper Functions ==========

    private suspend fun createAndInsertEvent(
        startTs: Long,
        endTs: Long,
        rrule: String? = null,
        title: String = "Test Event"
    ): Event {
        val event = Event(
            uid = "test-uid-${System.nanoTime()}@test.com",
            calendarId = testCalendarId,
            title = title,
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            rrule = rrule,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)
        return event.copy(id = eventId)
    }
}
