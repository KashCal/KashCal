package org.onekash.kashcal.data.db.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IcsSubscription.isDueForSync].
 *
 * The due-check allows a tenth of the interval as slack. The periodic refresh
 * job wakes at the shortest interval across all enabled feeds, so a feed whose
 * interval is longer than that job period is only ever checked at job-period
 * multiples. Because `lastSync` is stamped part-way through a run, the check
 * that lands at exactly one interval measures slightly less than the interval,
 * finds the feed not due, and makes it wait a whole extra job period. The slack
 * absorbs that.
 */
class IcsSubscriptionDueForSyncTest {

    private fun subscription(
        intervalHours: Int,
        lastSyncAgoMs: Long,
        enabled: Boolean = true,
    ) = IcsSubscription(
        url = "https://example.test/feed.ics",
        name = "Feed",
        color = 0,
        calendarId = 1L,
        lastSync = System.currentTimeMillis() - lastSyncAgoMs,
        syncIntervalHours = intervalHours,
        enabled = enabled,
    )

    private val oneMinute = 60_000L
    private val oneHour = 60 * oneMinute

    @Test
    fun `hourly feed checked five minutes early is due`() {
        // A check landing just short of the full hour must not defer the feed to
        // the next job period, which would make an hourly feed ~2-hourly.
        assertTrue(subscription(intervalHours = 1, lastSyncAgoMs = 55 * oneMinute).isDueForSync())
    }

    @Test
    fun `hourly feed checked halfway through the interval is not due`() {
        assertFalse(subscription(intervalHours = 1, lastSyncAgoMs = 30 * oneMinute).isDueForSync())
    }

    @Test
    fun `daily feed is due an hour early`() {
        assertTrue(subscription(intervalHours = 24, lastSyncAgoMs = 23 * oneHour).isDueForSync())
    }

    @Test
    fun `daily feed is not due after twenty hours`() {
        assertFalse(subscription(intervalHours = 24, lastSyncAgoMs = 20 * oneHour).isDueForSync())
    }

    @Test
    fun `slack is proportional so it never spans a whole shorter interval`() {
        // Guards the shape of the tolerance, not just one value: a tenth of the
        // interval, so a feed is never due before 90% of its interval has passed.
        assertFalse(subscription(intervalHours = 24, lastSyncAgoMs = 21 * oneHour).isDueForSync())
        assertTrue(subscription(intervalHours = 24, lastSyncAgoMs = 22 * oneHour).isDueForSync())
    }

    @Test
    fun `disabled feed is never due regardless of how long since last sync`() {
        assertFalse(
            subscription(intervalHours = 1, lastSyncAgoMs = 100 * oneHour, enabled = false)
                .isDueForSync(),
        )
    }

    @Test
    fun `a zero interval on disk is floored instead of making the feed always due`() {
        // Without the floor a 0 makes this unconditionally true, so the feed is
        // re-fetched on every wake of the shared job. A row restored from a
        // hand-edited or corrupt backup written before the importer coerced the
        // value is still on disk, so the reader has to guard it.
        assertFalse(subscription(intervalHours = 0, lastSyncAgoMs = 30 * oneMinute).isDueForSync())
        assertTrue(subscription(intervalHours = 0, lastSyncAgoMs = 55 * oneMinute).isDueForSync())
    }

    @Test
    fun `a negative interval on disk is floored too`() {
        assertFalse(subscription(intervalHours = -5, lastSyncAgoMs = 30 * oneMinute).isDueForSync())
    }

    @Test
    fun `never synced feed is due`() {
        // lastSync defaults to 0 (epoch) for a feed added but not yet fetched.
        val neverSynced = IcsSubscription(
            url = "https://example.test/new.ics",
            name = "New",
            color = 0,
            calendarId = 2L,
        )
        assertTrue(neverSynced.isDueForSync())
    }
}
