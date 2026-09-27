package org.onekash.kashcal.sync.scheduler

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.ics.IcsRefreshWorker

/**
 * Tests [IcsRefreshScheduleReconciler], which derives the ICS refresh job's period from the feeds
 * in the database. Feed mutations, backup restore and app start call it, so no mutation path
 * arms the job itself.
 *
 * The DAO double is deliberately not relaxed: a relaxed mock returns an empty list for
 * `getEnabled()`, the "cancel the job" branch, so a test that had stopped exercising the real
 * path would still pass.
 */
class IcsRefreshScheduleReconcilerTest {

    private lateinit var dao: IcsSubscriptionsDao
    private lateinit var scheduler: FakeIcsScheduler
    private lateinit var reconciler: IcsRefreshScheduleReconciler

    @Before
    fun setup() {
        dao = mockk()
        scheduler = FakeIcsScheduler()
        reconciler = IcsRefreshScheduleReconciler(dao, scheduler)
    }

    private fun feed(
        id: Long,
        intervalHours: Int,
        enabled: Boolean = true,
    ) = IcsSubscription(
        id = id,
        url = "https://example.test/$id.ics",
        name = "Feed $id",
        color = 0,
        calendarId = id,
        syncIntervalHours = intervalHours,
        enabled = enabled,
    )

    /** [IcsSubscriptionsDao.getEnabled] filters on `enabled` in SQL. */
    private fun givenFeeds(vararg feeds: IcsSubscription) {
        coEvery { dao.getEnabled() } returns feeds.filter { it.enabled }
    }

    @Test
    fun `no feeds at all cancels the job`() = runTest {
        givenFeeds()

        reconciler.reconcile()

        assertEquals(1, scheduler.cancelCalls)
        assertTrue(
            "Nothing should be armed when there are no feeds; got ${scheduler.ensureCalls}",
            scheduler.ensureCalls.isEmpty(),
        )
    }

    @Test
    fun `shortest interval wins`() = runTest {
        // One shared check pass serves every feed: the job wakes at the shortest interval and
        // each feed's own due-check filters the rest out.
        givenFeeds(feed(1, intervalHours = 24), feed(2, intervalHours = 6))

        reconciler.reconcile()

        assertEquals(listOf(6L), scheduler.ensureCalls)
        assertEquals(0, scheduler.cancelCalls)
    }

    @Test
    fun `disabled feeds are excluded from the minimum`() = runTest {
        // An hourly feed the user switched off must not keep the job waking hourly.
        givenFeeds(feed(1, intervalHours = 1, enabled = false), feed(2, intervalHours = 24))

        reconciler.reconcile()

        assertEquals(listOf(24L), scheduler.ensureCalls)
    }

    @Test
    fun `every feed disabled cancels the job`() = runTest {
        // Also the "user deleted the last feed" path: nothing enabled remains, so the job
        // stops instead of waking forever for no one.
        givenFeeds(feed(1, intervalHours = 1, enabled = false), feed(2, intervalHours = 24, enabled = false))

        reconciler.reconcile()

        assertEquals(1, scheduler.cancelCalls)
        assertTrue(scheduler.ensureCalls.isEmpty())
    }

    @Test
    fun `a single feed drives its own interval`() = runTest {
        givenFeeds(feed(1, intervalHours = 12))

        reconciler.reconcile()

        assertEquals(listOf(12L), scheduler.ensureCalls)
    }

    @Test
    fun `reconcile is idempotent`() = runTest {
        // Runs on every app start and every mutation, so repeat calls with unchanged data
        // must ask for the same thing.
        givenFeeds(feed(1, intervalHours = 6))

        reconciler.reconcile()
        reconciler.reconcile()

        assertEquals(listOf(6L, 6L), scheduler.ensureCalls)
        assertEquals(0, scheduler.cancelCalls)
    }

    @Test
    fun `a nonsensical stored interval is floored at the minimum`() = runTest {
        // Reachable without a UI bug: a row restored from a hand-edited or corrupt backup by
        // an older importer, which didn't coerce the interval, can hold 0 or less.
        givenFeeds(feed(1, intervalHours = 0), feed(2, intervalHours = 24))

        reconciler.reconcile()

        assertEquals(
            listOf(IcsRefreshWorker.MIN_REFRESH_INTERVAL_HOURS),
            scheduler.ensureCalls,
        )
    }

    @Test
    fun `a negative stored interval is floored at the minimum`() = runTest {
        givenFeeds(feed(1, intervalHours = -5))

        reconciler.reconcile()

        assertEquals(
            listOf(IcsRefreshWorker.MIN_REFRESH_INTERVAL_HOURS),
            scheduler.ensureCalls,
        )
    }

    @Test
    fun `a failing scheduler does not propagate out of reconcile`() = runTest {
        // Most callers are bare application-scope launches with no exception handler, so a
        // throw from here would kill the process on a recoverable failure, such as
        // WorkManager writing its own database on a full disk.
        givenFeeds(feed(1, intervalHours = 6))
        scheduler.failWith = IllegalStateException("WorkManager unavailable")

        reconciler.reconcile()

        assertEquals(listOf(6L), scheduler.ensureCalls)
    }

    @Test
    fun `a failing database read does not propagate out of reconcile`() = runTest {
        coEvery { dao.getEnabled() } throws IllegalStateException("disk I/O error")

        reconciler.reconcile()

        assertTrue(scheduler.ensureCalls.isEmpty())
        assertEquals(0, scheduler.cancelCalls)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        // Best-effort is right for a real failure, but cancellation isn't one. Restoring a
        // backup reconciles from a viewModelScope the user can cancel by leaving the screen,
        // and that path rethrows cancellation itself; swallowed here, the restore would be
        // reported as finished with the schedule half-applied.
        givenFeeds(feed(1, intervalHours = 6))
        scheduler.failWith = CancellationException("caller cancelled")

        val thrown = runCatching { reconciler.reconcile() }.exceptionOrNull()

        assertTrue(
            "reconcile should let cancellation through; got $thrown",
            thrown is CancellationException,
        )
    }

    @Test
    fun `concurrent reconciles are serialized`() = runTest {
        // Mutations and app start can overlap. Read-decide-apply has to be one step, or one
        // pass can read "one feed enabled" while the other reads "none left" and the
        // later-landing decision wins.
        givenFeeds(feed(1, intervalHours = 6))
        scheduler.duringEnsure = { yield(); yield() }

        val first = launch { reconciler.reconcile() }
        val second = launch { reconciler.reconcile() }
        first.join()
        second.join()

        assertEquals(
            "Interleaved enter/exit markers mean read-decide-apply is not atomic",
            listOf("ensure-enter:6", "ensure-exit:6", "ensure-enter:6", "ensure-exit:6"),
            scheduler.callLog,
        )
    }
}
