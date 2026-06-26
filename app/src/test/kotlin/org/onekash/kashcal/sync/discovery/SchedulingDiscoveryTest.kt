package org.onekash.kashcal.sync.discovery

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * Unit tests for [persistSchedulingDiscovery] — RFC 6638 outbox + per-collection
 * auto-schedule capability discovery, persisted non-fatally.
 */
class SchedulingDiscoveryTest {

    private val client = mockk<CalDavClient>()
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val calendarRepository = mockk<CalendarRepository>(relaxed = true)

    private val principalUrl = "https://dav.example.com/principals/u/"
    private val accountId = 7L

    private fun calendar(id: Long, url: String) = Calendar(
        id = id,
        accountId = accountId,
        caldavUrl = url,
        displayName = "Cal $id",
        color = 0
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `persists discovered outbox url and per-collection capability`() = runTest {
        val calendars = listOf(
            calendar(1, "https://dav.example.com/cal/1/"),
            calendar(2, "https://dav.example.com/cal/2/")
        )
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns
            CalDavResult.success("https://dav.example.com/cal/outbox/")
        coEvery { client.supportsAutoSchedule("https://dav.example.com/cal/1/") } returns
            CalDavResult.success(true)
        coEvery { client.supportsAutoSchedule("https://dav.example.com/cal/2/") } returns
            CalDavResult.success(false)

        persistSchedulingDiscovery(
            client, principalUrl, accountId, calendars,
            accountRepository, calendarRepository, "TAG"
        )

        coVerify { accountRepository.updateScheduleOutboxUrl(accountId, "https://dav.example.com/cal/outbox/") }
        coVerify { calendarRepository.updateAutoScheduleSupported(1, true) }
        coVerify { calendarRepository.updateAutoScheduleSupported(2, false) }
    }

    @Test
    fun `outbox PROPFIND failure is non-fatal and persists null`() = runTest {
        val calendars = listOf(calendar(1, "https://dav.example.com/cal/1/"))
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns
            CalDavResult.error(404, "not found")
        coEvery { client.supportsAutoSchedule(any()) } returns CalDavResult.success(true)

        persistSchedulingDiscovery(
            client, principalUrl, accountId, calendars,
            accountRepository, calendarRepository, "TAG"
        )

        // Null persisted (not skipped), capability probe still runs.
        coVerify { accountRepository.updateScheduleOutboxUrl(accountId, null) }
        coVerify { calendarRepository.updateAutoScheduleSupported(1, true) }
    }

    @Test
    fun `capability probe failure persists unknown without aborting other calendars`() = runTest {
        val calendars = listOf(
            calendar(1, "https://dav.example.com/cal/1/"),
            calendar(2, "https://dav.example.com/cal/2/")
        )
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns CalDavResult.success(null)
        coEvery { client.supportsAutoSchedule("https://dav.example.com/cal/1/") } returns
            CalDavResult.networkError("timeout")
        coEvery { client.supportsAutoSchedule("https://dav.example.com/cal/2/") } returns
            CalDavResult.success(true)

        persistSchedulingDiscovery(
            client, principalUrl, accountId, calendars,
            accountRepository, calendarRepository, "TAG"
        )

        coVerify { accountRepository.updateScheduleOutboxUrl(accountId, null) }
        coVerify { calendarRepository.updateAutoScheduleSupported(1, null) }
        coVerify { calendarRepository.updateAutoScheduleSupported(2, true) }
    }

    @Test
    fun `repository write failure during discovery is swallowed and never aborts the sync`() = runTest {
        // A DAO write can throw (e.g. SQLiteException: disk full / DB locked).
        // The spec requires scheduling discovery to be non-fatal — a write
        // failure must not propagate out of the helper and sink account-add.
        val calendars = listOf(calendar(1, "https://dav.example.com/cal/1/"))
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns
            CalDavResult.success("https://dav.example.com/cal/outbox/")
        coEvery { client.supportsAutoSchedule(any()) } returns CalDavResult.success(true)
        coEvery { accountRepository.updateScheduleOutboxUrl(any(), any()) } throws
            RuntimeException("disk full")

        // Must NOT throw.
        persistSchedulingDiscovery(
            client, principalUrl, accountId, calendars,
            accountRepository, calendarRepository, "TAG"
        )
    }

    @Test
    fun `a capability write failure does not stop later calendars`() = runTest {
        val calendars = listOf(
            calendar(1, "https://dav.example.com/cal/1/"),
            calendar(2, "https://dav.example.com/cal/2/")
        )
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns CalDavResult.success(null)
        coEvery { client.supportsAutoSchedule(any()) } returns CalDavResult.success(true)
        coEvery { calendarRepository.updateAutoScheduleSupported(1, any()) } throws
            RuntimeException("transient DB lock")

        persistSchedulingDiscovery(
            client, principalUrl, accountId, calendars,
            accountRepository, calendarRepository, "TAG"
        )

        // Calendar 2 still gets written despite calendar 1's write throwing.
        coVerify { calendarRepository.updateAutoScheduleSupported(2, true) }
    }

    @Test
    fun `CancellationException from a repo write propagates (not swallowed)`() = runTest {
        // Structured concurrency: the non-fatal catch must NOT eat cancellation.
        val calendars = listOf(calendar(1, "https://dav.example.com/cal/1/"))
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns
            CalDavResult.success("https://dav.example.com/cal/outbox/")
        coEvery { accountRepository.updateScheduleOutboxUrl(any(), any()) } throws
            CancellationException("cancelled")

        var propagated = false
        try {
            persistSchedulingDiscovery(
                client, principalUrl, accountId, calendars,
                accountRepository, calendarRepository, "TAG"
            )
        } catch (e: CancellationException) {
            propagated = true
        }
        if (!propagated) fail("CancellationException must propagate, not be swallowed")
    }

    @Test
    fun `CancellationException in the capability loop propagates (not swallowed)`() = runTest {
        val calendars = listOf(calendar(1, "https://dav.example.com/cal/1/"))
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns CalDavResult.success(null)
        coEvery { client.supportsAutoSchedule(any()) } throws CancellationException("cancelled")

        var propagated = false
        try {
            persistSchedulingDiscovery(
                client, principalUrl, accountId, calendars,
                accountRepository, calendarRepository, "TAG"
            )
        } catch (e: CancellationException) {
            propagated = true
        }
        if (!propagated) fail("CancellationException must propagate, not be swallowed")
    }

    @Test
    fun `empty calendar list still probes the outbox`() = runTest {
        coEvery { client.discoverScheduleOutboxUrl(principalUrl) } returns
            CalDavResult.success("https://dav.example.com/cal/outbox/")

        persistSchedulingDiscovery(
            client, principalUrl, accountId, emptyList(),
            accountRepository, calendarRepository, "TAG"
        )

        coVerify { accountRepository.updateScheduleOutboxUrl(accountId, "https://dav.example.com/cal/outbox/") }
    }
}
