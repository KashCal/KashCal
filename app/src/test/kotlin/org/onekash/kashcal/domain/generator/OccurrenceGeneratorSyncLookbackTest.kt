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
 * Tests that [OccurrenceGenerator.regenerateOccurrences] limits the past window to the sync
 * lookback setting (`syncPastDays`) and keeps the 720-day (24 x 30 days) future window.
 *
 * With a 90-day lookback, a daily series from a year ago gets occurrences only from 90 days
 * back; "All events" (Int.MAX_VALUE) reaches back to the event start. The lookback doesn't
 * limit [OccurrenceGenerator.extendPastOccurrences].
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

        // Default: "All events" (no lookback limit).
        every { dataStore.syncPastDays } returns flowOf(Int.MAX_VALUE)

        occurrenceGenerator = OccurrenceGenerator(
            database,
            database.occurrencesDao(),
            database.eventsDao(),
            dataStore
        )

        // One local account and calendar for every test.
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

        // About 90 past + 720 future occurrences, not 365 past.
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastOccurrences = occurrences.filter { it.startTs < now }
        val futureOccurrences = occurrences.filter { it.startTs >= now }

        // Past bounded by about 90 days, with margin for test timing.
        assertTrue(
            "Expected ~90 past occurrences (±10) but got ${pastOccurrences.size}",
            pastOccurrences.size in 80..100
        )

        // Future about 720 (24 x 30 days).
        assertTrue(
            "Expected ~730 future occurrences (±30) but got ${futureOccurrences.size}",
            futureOccurrences.size in 700..760
        )

        // Oldest about 90 days ago, not 365.
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

        // With "All events", generation reaches back to the event start.
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastOccurrences = occurrences.filter { it.startTs < now }

        // About 365 past occurrences, all since the event start.
        assertTrue(
            "Expected ~365 past occurrences but got ${pastOccurrences.size}",
            pastOccurrences.size in 355..375
        )

        // Oldest about 365 days ago, at the event start.
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

        // Reaches back to the event start (about 5 years), not capped at 2 years.
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val daysOld = (now - oldest.startTs) / MS_PER_DAY

        // About 1825 days old (5 years), not about 720.
        assertTrue(
            "Oldest occurrence should be ~1825 days ago (event start) but was $daysOld days ago. " +
                "This fails if MAX_VALUE is still capped at 2 years (would be ~720).",
            daysOld in 1820..1830
        )

        // About 260 past weekly occurrences (5 years x 52 weeks).
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

        // Past bounded by about 180 days.
        assertTrue(
            "Expected ~180 past occurrences (±10) but got ${pastOccurrences.size}",
            pastOccurrences.size in 170..190
        )

        // Oldest about 180 days ago.
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

        // The event is newer than the lookback, so all of its about 30 past occurrences exist.
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastOccurrences = occurrences.filter { it.startTs < now }

        // About 30 past occurrences, all since the event start.
        assertTrue(
            "Expected ~30 past occurrences but got ${pastOccurrences.size}",
            pastOccurrences.size in 28..32
        )

        // The first occurrence is at the event start, within 2 days.
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val diffFromStart = kotlin.math.abs(oldest.startTs - thirtyDaysAgo)
        assertTrue(
            "First occurrence should match event start time",
            diffFromStart < 2 * MS_PER_DAY
        )
    }

    @Test
    fun `regenerateOccurrences keeps 2 year future window regardless of sync lookback`() = runTest {
        // Setup: Set sync lookback to 30 days
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

        // Future still about 720 days despite the 30-day lookback (only the future is asserted).
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val futureOccurrences = occurrences.filter { it.startTs >= now }

        // Future about 720 days (24 x 30).
        assertTrue(
            "Expected ~720 future occurrences but got ${futureOccurrences.size}",
            futureOccurrences.size in 700..740
        )

        // Newest about 720 days ahead, with margin.
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
        // syncPastDays limits the regeneration window, not extension. Extension runs on month
        // navigation and expands locally, so it costs no network.
        every { dataStore.syncPastDays } returns flowOf(90)

        // Create daily recurring event starting 2 years ago
        val twoYearsAgo = System.currentTimeMillis() - (730 * MS_PER_DAY)
        val event = createAndInsertEvent(
            startTs = twoYearsAgo,
            endTs = twoYearsAgo + 3600000,
            rrule = "FREQ=DAILY"
        )

        // Regenerate with the 90-day lookback: about 90 past and 720 future.
        occurrenceGenerator.regenerateOccurrences(event)

        val occurrencesBefore = database.occurrencesDao().getForEvent(event.id)
        val now = System.currentTimeMillis()
        val pastBefore = occurrencesBefore.filter { it.startTs < now }

        // Act: extend to 1 year ago, beyond the 90-day window.
        val oneYearAgoMs = now - (365 * MS_PER_DAY)
        val extended = occurrenceGenerator.extendPastOccurrences(event, oneYearAgoMs)

        // Assert: it extends; syncPastDays doesn't limit extension.
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

        // Generate only the last 30 days first, so there is room to extend.
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30 * MS_PER_DAY)
        occurrenceGenerator.generateOccurrences(event, thirtyDaysAgo, now + (730 * MS_PER_DAY))

        val pastBefore = database.occurrencesDao().getForEvent(event.id)
            .filter { it.startTs < now }

        // Act: extend to 120 days ago, within the 180-day lookback.
        val extendTarget = now - (120 * MS_PER_DAY)
        val extended = occurrenceGenerator.extendPastOccurrences(event, extendTarget)

        // Assert: it extends.
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

        // With Int.MAX_VALUE, regeneration alone reaches the event start (extension isn't
        // called here).
        occurrenceGenerator.regenerateOccurrences(event)

        val now = System.currentTimeMillis()
        val occurrences = database.occurrencesDao().getForEvent(event.id)
        val oldest = occurrences.minByOrNull { it.startTs }!!
        val daysOld = (now - oldest.startTs) / MS_PER_DAY

        // Oldest about 1095 days old, at the event start.
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
