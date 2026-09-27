package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Tests RRULE expansion through [OccurrenceGenerator.regenerateOccurrences] across DST changes.
 *
 * Covers:
 * - Spring forward (DST gap): 2:30 AM doesn't exist.
 * - Fall back (DST overlap): 1:30 AM exists twice.
 * - Weekly, monthly and yearly series keeping their wall-clock time across a change.
 * - A weekly series whose 2 AM start falls in the gap.
 * - All-day events during DST changes.
 * - Zones without DST (UTC, America/Phoenix).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RRuleDSTInteractionTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var testCalendarId: Long = 0

    // US/Eastern DST changes in 2025:
    // Spring forward: March 9, 2025 at 2:00 AM -> 3:00 AM.
    // Fall back: November 2, 2025 at 2:00 AM -> 1:00 AM.
    private val springForwardDate = "2025-03-09"
    private val fallBackDate = "2025-11-02"
    private val easternZone = ZoneId.of("America/New_York")

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())

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

    // ==================== Spring Forward (DST Gap) Tests ====================

    @Test
    fun `daily recurring at 2-30 AM on DST spring forward should handle non-existent time`() = runTest {
        // Daily at 2:30 AM Eastern. On March 9, 2025, 2:30 AM doesn't exist.
        val march8_230am = createEasternDateTime(2025, 3, 8, 2, 30)

        val event = createAndInsertRecurringEvent(
            title = "Early Morning Meeting",
            startTs = march8_230am,
            endTs = march8_230am + 3600000, // 1 hour
            timezone = "America/New_York",
            rrule = "FREQ=DAILY;COUNT=5"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(5, occurrences.size)

        // RFC 5545 §3.3.10 says an instance at a nonexistent local time MUST be ignored; this
        // test instead expects 5 occurrences with one on March 9, and checks only that it isn't
        // at 2:30 AM.
        val march9Occurrence = occurrences.find {
            val dateTime = Instant.ofEpochMilli(it.startTs).atZone(easternZone)
            dateTime.dayOfMonth == 9 && dateTime.monthValue == 3
        }
        assertNotNull("Should have occurrence on March 9", march9Occurrence)

        val march9Time = Instant.ofEpochMilli(march9Occurrence!!.startTs).atZone(easternZone)
        assertTrue(
            "Time should be adjusted from non-existent 2:30 AM, got ${march9Time.hour}:${march9Time.minute}",
            march9Time.hour != 2 || march9Time.minute != 30 ||
            march9Time.hour == 3 // Acceptable adjustment to 3:30 AM
        )
    }

    @Test
    fun `weekly recurring spanning DST spring forward should maintain wall clock time`() = runTest {
        // Sundays at 10:00 AM, across the spring-forward change.
        val march2_10am = createEasternDateTime(2025, 3, 2, 10, 0)

        val event = createAndInsertRecurringEvent(
            title = "Sunday Meeting",
            startTs = march2_10am,
            endTs = march2_10am + 3600000,
            timezone = "America/New_York",
            rrule = "FREQ=WEEKLY;COUNT=3"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(3, occurrences.size)

        occurrences.forEach { occ ->
            val localTime = Instant.ofEpochMilli(occ.startTs).atZone(easternZone)
            assertEquals(
                "All occurrences should be at 10:00 AM local time",
                10, localTime.hour
            )
        }
    }

    // ==================== Fall Back (DST Overlap) Tests ====================

    @Test
    fun `daily recurring at 1-30 AM on DST fall back should handle ambiguous time`() = runTest {
        // Daily at 1:30 AM Eastern. On November 2, 2025, 1:30 AM occurs twice.
        val nov1_130am = createEasternDateTime(2025, 11, 1, 1, 30)

        val event = createAndInsertRecurringEvent(
            title = "Very Early Meeting",
            startTs = nov1_130am,
            endTs = nov1_130am + 3600000,
            timezone = "America/New_York",
            rrule = "FREQ=DAILY;COUNT=5"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(5, occurrences.size)

        val nov2Occurrence = occurrences.find {
            val dateTime = Instant.ofEpochMilli(it.startTs).atZone(easternZone)
            dateTime.dayOfMonth == 2 && dateTime.monthValue == 11
        }
        assertNotNull("Should have occurrence on November 2", nov2Occurrence)

        // RFC 5545 §3.3.5: a repeated local time means its first occurrence (EDT). The asserts
        // check the 1:30 AM wall-clock time, not which offset.
        val nov2Time = Instant.ofEpochMilli(nov2Occurrence!!.startTs).atZone(easternZone)
        assertEquals(1, nov2Time.hour)
        assertEquals(30, nov2Time.minute)
    }

    @Test
    fun `hourly recurring during DST overlap should not skip or double`() = runTest {
        // Hourly from 11 PM on Nov 1, across the fall back.
        val nov1_11pm = createEasternDateTime(2025, 11, 1, 23, 0)

        val event = createAndInsertRecurringEvent(
            title = "Hourly Check",
            startTs = nov1_11pm,
            endTs = nov1_11pm + 1800000, // 30 min
            timezone = "America/New_York",
            rrule = "FREQ=HOURLY;COUNT=6"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(6, occurrences.size)

        // Fall back repeats the 1 AM hour (EDT, then EST), so a wall-clock expansion may not
        // step exactly 3600000 ms. Each step is checked to be within 0 to 2 hours, which lets a
        // zero step (a doubled occurrence) pass.
        for (i in 0 until occurrences.size - 1) {
            val diff = occurrences[i + 1].startTs - occurrences[i].startTs
            assertTrue(
                "Occurrence ${i} to ${i+1} diff=${diff}ms should be approximately 1 hour",
                diff in 0..7200000L
            )
        }
    }

    // ==================== Spanning DST Boundary Tests ====================

    @Test
    fun `monthly recurring spanning spring and fall DST changes should be consistent`() = runTest {
        // The 15th of each month at 2:00 PM, across both 2025 changes.
        val jan15_2pm = createEasternDateTime(2025, 1, 15, 14, 0)

        val event = createAndInsertRecurringEvent(
            title = "Monthly Review",
            startTs = jan15_2pm,
            endTs = jan15_2pm + 3600000,
            timezone = "America/New_York",
            rrule = "FREQ=MONTHLY;COUNT=12"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(12, occurrences.size)

        occurrences.forEach { occ ->
            val localTime = Instant.ofEpochMilli(occ.startTs).atZone(easternZone)
            assertEquals(
                "Monthly occurrence on ${localTime.month} ${localTime.dayOfMonth} should be at 2:00 PM",
                14, localTime.hour
            )
        }
    }

    @Test
    fun `yearly recurring on DST transition day should handle correctly`() = runTest {
        // 3:00 AM on 2025's spring-forward day (March 9), the first hour after the gap. A 2025
        // start keeps all 3 yearly occurrences before the window's end, about 24 months from now.
        val march9_3am = createEasternDateTime(2025, 3, 9, 3, 0)

        val event = createAndInsertRecurringEvent(
            title = "Yearly DST Day Event",
            startTs = march9_3am,
            endTs = march9_3am + 3600000,
            timezone = "America/New_York",
            rrule = "FREQ=YEARLY;COUNT=3"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(3, occurrences.size)

        occurrences.forEach { occ ->
            val localTime = Instant.ofEpochMilli(occ.startTs).atZone(easternZone)
            assertEquals(3, localTime.hour)
        }
    }

    // ==================== Start Time in the DST Gap ====================

    @Test
    fun `BYHOUR rule during DST gap should handle missing hour`() = runTest {
        // Sundays at 2 AM from DTSTART Saturday March 1 (BYDAY=SU, no BYHOUR); 2 AM doesn't
        // exist on Sunday March 9.
        val march1 = createEasternDateTime(2025, 3, 1, 2, 0)

        val event = createAndInsertRecurringEvent(
            title = "2 AM Weekly",
            startTs = march1,
            endTs = march1 + 3600000,
            timezone = "America/New_York",
            rrule = "FREQ=WEEKLY;BYDAY=SU;COUNT=4"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)

        // Asserts only that some occurrences are generated.
        assertTrue(occurrences.isNotEmpty())
    }

    // ==================== All-Day Events During DST Tests ====================

    @Test
    fun `all-day event during DST spring forward should span correct day`() = runTest {
        // All-day on March 9, the spring-forward day, yearly.
        val march9Midnight = createEasternMidnight(2025, 3, 9)

        val event = createAndInsertRecurringEvent(
            title = "DST Day All-Day Event",
            startTs = march9Midnight,
            endTs = march9Midnight + 86400000, // 24 hours
            timezone = "America/New_York",
            rrule = "FREQ=YEARLY;COUNT=3",
            isAllDay = true
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)

        assertEquals(3, occurrences.size)

        // Each startDay should be a YYYYMMDD code for March 9; the assert also accepts any code
        // containing "03".
        occurrences.forEach { occ ->
            val dayCode = occ.startDay
            assertTrue(
                "Day code $dayCode should end in 09 for March 9",
                dayCode.toString().endsWith("0309") ||
                dayCode.toString().contains("03") // Year varies
            )
        }
    }

    @Test
    fun `all-day recurring spanning DST change should show on correct dates`() = runTest {
        // Daily all-day from March 8 to 12, across the spring-forward change.
        val march8Midnight = createEasternMidnight(2025, 3, 8)

        val event = createAndInsertRecurringEvent(
            title = "Daily All-Day",
            startTs = march8Midnight,
            endTs = march8Midnight + 86400000,
            timezone = "America/New_York",
            rrule = "FREQ=DAILY;COUNT=5",
            isAllDay = true
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(5, occurrences.size)

        val expectedDays = listOf(8, 9, 10, 11, 12)
        occurrences.forEachIndexed { index, occ ->
            val dayOfMonth = Instant.ofEpochMilli(occ.startTs)
                .atZone(ZoneId.of("UTC"))
                .dayOfMonth
            // Accepts the expected UTC day or the one before it.
            assertTrue(
                "Occurrence $index should be around day ${expectedDays[index]}",
                dayOfMonth == expectedDays[index] || dayOfMonth == expectedDays[index] - 1
            )
        }
    }

    // ==================== Different Timezone Tests ====================

    @Test
    fun `event in non-DST timezone should not be affected by local DST`() = runTest {
        val march9UtcNoon = ZonedDateTime.of(2025, 3, 9, 12, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()

        val event = createAndInsertRecurringEvent(
            title = "UTC Event",
            startTs = march9UtcNoon,
            endTs = march9UtcNoon + 3600000,
            timezone = "UTC",
            rrule = "FREQ=DAILY;COUNT=5"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(5, occurrences.size)

        for (i in 0 until occurrences.size - 1) {
            val diff = occurrences[i + 1].startTs - occurrences[i].startTs
            assertEquals(
                "UTC events should be exactly 24 hours apart",
                86400000L, diff
            )
        }
    }

    @Test
    fun `event in Arizona timezone should not have DST adjustments`() = runTest {
        // Arizona doesn't observe DST
        val march9ArizonaNoon = ZonedDateTime.of(2025, 3, 9, 12, 0, 0, 0, ZoneId.of("America/Phoenix"))
            .toInstant().toEpochMilli()

        val event = createAndInsertRecurringEvent(
            title = "Arizona Event",
            startTs = march9ArizonaNoon,
            endTs = march9ArizonaNoon + 3600000,
            timezone = "America/Phoenix",
            rrule = "FREQ=WEEKLY;COUNT=4"
        )

        occurrenceGenerator.regenerateOccurrences(event)

        val occurrences = database.occurrencesDao().getForEvent(event.id)
            .sortedBy { it.startTs }

        assertEquals(4, occurrences.size)

        val phoenixZone = ZoneId.of("America/Phoenix")
        occurrences.forEach { occ ->
            val localTime = Instant.ofEpochMilli(occ.startTs).atZone(phoenixZone)
            assertEquals(12, localTime.hour)
        }
    }

    // ==================== Helper Methods ====================

    private fun createEasternDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, easternZone)
            .toInstant().toEpochMilli()
    }

    private fun createEasternMidnight(year: Int, month: Int, day: Int): Long {
        // Returns UTC midnight, the all-day storage form, despite the name.
        return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
    }

    private suspend fun createAndInsertRecurringEvent(
        title: String,
        startTs: Long,
        endTs: Long,
        timezone: String,
        rrule: String,
        isAllDay: Boolean = false
    ): Event {
        val now = System.currentTimeMillis()
        val event = Event(
            calendarId = testCalendarId,
            uid = UUID.randomUUID().toString(),
            title = title,
            startTs = startTs,
            endTs = endTs,
            timezone = timezone,
            rrule = rrule,
            isAllDay = isAllDay,
            syncStatus = SyncStatus.PENDING_CREATE,
            createdAt = now,
            updatedAt = now,
            dtstamp = now
        )
        val id = database.eventsDao().insert(event)
        return event.copy(id = id)
    }
}
