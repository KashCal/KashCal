package org.onekash.kashcal.sync.discovery

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult

/**
 * A calendar a listing left out is removed only when a direct probe of its URL
 * says it is gone. Every other answer, including ones the app cannot read, keeps it.
 */
class UnlistedCalendarCheckTest {

    private lateinit var client: CalDavClient

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        client = mockk()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun calendar(id: Long) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = "https://server.test/calendars/user/cal-$id/",
        displayName = "Calendar $id",
        color = 0
    )

    // ==================== isCalendarGone ====================

    @Test
    fun `404 means gone`() = assertTrue(isCalendarGone(CalDavResult.notFoundError("x")))

    @Test
    fun `410 means gone`() = assertTrue(isCalendarGone(CalDavResult.error(410, "x")))

    @Test
    fun `not a calendar any more means gone`() = assertTrue(isCalendarGone(CalDavResult.success(false)))

    @Test
    fun `still a calendar is kept`() = assertFalse(isCalendarGone(CalDavResult.success(true)))

    @Test
    fun `403 means gone`() = assertTrue(isCalendarGone(CalDavResult.error(403, "x")))

    @Test
    fun `401 is kept`() = assertFalse(isCalendarGone(CalDavResult.authError("x")))

    @Test
    fun `network error is kept`() = assertFalse(isCalendarGone(CalDavResult.networkError("x")))

    @Test
    fun `timeout is kept`() = assertFalse(isCalendarGone(CalDavResult.timeoutError("x")))

    @Test
    fun `unreadable reply is kept`() = assertFalse(isCalendarGone(CalDavResult.error(500, "x")))

    @Test
    fun `server error is kept`() = assertFalse(isCalendarGone(CalDavResult.error(503, "x")))

    @Test
    fun `any other code is kept`() {
        listOf(400, 405, 409, 412, 501).forEach { code ->
            assertFalse("code $code", isCalendarGone(CalDavResult.error(code, "x")))
        }
    }

    // ==================== confirmUnlistedCalendars ====================

    @Test
    fun `nothing unlisted means no probes`() = runTest {
        val verdict = confirmUnlistedCalendars(client, emptyList(), "test")

        assertTrue(verdict.gone.isEmpty())
        assertTrue(verdict.kept.isEmpty())
        coVerify(exactly = 0) { client.probeCalendarCollection(any()) }
    }

    @Test
    fun `each unlisted calendar is probed at its stored URL and sorted by the answer`() = runTest {
        val gone = calendar(1)
        val live = calendar(2)
        val unreadable = calendar(3)
        coEvery { client.probeCalendarCollection(gone.caldavUrl) } returns CalDavResult.notFoundError("x")
        coEvery { client.probeCalendarCollection(live.caldavUrl) } returns CalDavResult.success(true)
        coEvery { client.probeCalendarCollection(unreadable.caldavUrl) } returns CalDavResult.error(500, "x")

        val verdict = confirmUnlistedCalendars(client, listOf(gone, live, unreadable), "test")

        assertEquals(listOf(gone), verdict.gone)
        assertEquals(listOf(live, unreadable), verdict.kept)
    }

    @Test
    fun `after a timeout the remaining calendars are kept without probing`() = runTest {
        val first = calendar(1)
        val second = calendar(2)
        val third = calendar(3)
        coEvery { client.probeCalendarCollection(first.caldavUrl) } returns CalDavResult.timeoutError("x")

        val verdict = confirmUnlistedCalendars(client, listOf(first, second, third), "test")

        assertTrue(verdict.gone.isEmpty())
        assertEquals(listOf(first, second, third), verdict.kept)
        coVerify(exactly = 1) { client.probeCalendarCollection(any()) }
    }

    @Test
    fun `after a network error the remaining calendars are kept without probing`() = runTest {
        val first = calendar(1)
        val second = calendar(2)
        coEvery { client.probeCalendarCollection(first.caldavUrl) } returns CalDavResult.networkError("x")

        val verdict = confirmUnlistedCalendars(client, listOf(first, second), "test")

        assertEquals(listOf(first, second), verdict.kept)
        coVerify(exactly = 1) { client.probeCalendarCollection(any()) }
    }

    @Test
    fun `refused connection is kept`() =
        assertFalse(isCalendarGone(CalDavResult.error(CalDavResult.CODE_TRANSPORT_REFUSED, "x", isRetryable = true)))

    @Test
    fun `after a refused connection the remaining calendars are kept without probing`() = runTest {
        // The client refused to send the probe ([CalDavResult.CODE_TRANSPORT_REFUSED], for
        // example a redirect to plain http); the check treats that as a network that can't
        // be used and keeps the rest unprobed.
        val first = calendar(1)
        val second = calendar(2)
        coEvery { client.probeCalendarCollection(first.caldavUrl) } returns
            CalDavResult.error(CalDavResult.CODE_TRANSPORT_REFUSED, "refused", isRetryable = true)

        val verdict = confirmUnlistedCalendars(client, listOf(first, second), "test")

        assertEquals(listOf(first, second), verdict.kept)
        coVerify(exactly = 1) { client.probeCalendarCollection(any()) }
    }

    @Test
    fun `a reply that is not a WebDAV answer is kept`() =
        assertFalse(isCalendarGone(CalDavResult.error(CalDavResult.CODE_NOT_MULTISTATUS, "x", isRetryable = true)))

    @Test
    fun `after a hotspot page answers a probe the remaining calendars are kept without probing`() = runTest {
        val first = calendar(1)
        val second = calendar(2)
        coEvery { client.probeCalendarCollection(first.caldavUrl) } returns
            CalDavResult.error(CalDavResult.CODE_NOT_MULTISTATUS, "login page", isRetryable = true)

        val verdict = confirmUnlistedCalendars(client, listOf(first, second), "test")

        assertEquals(listOf(first, second), verdict.kept)
        coVerify(exactly = 1) { client.probeCalendarCollection(any()) }
    }

    @Test
    fun `a probe that throws keeps that calendar and the rest are still probed`() = runTest {
        val first = calendar(1)
        val second = calendar(2)
        coEvery { client.probeCalendarCollection(first.caldavUrl) } throws IllegalStateException("boom")
        coEvery { client.probeCalendarCollection(second.caldavUrl) } returns CalDavResult.notFoundError("x")

        val verdict = confirmUnlistedCalendars(client, listOf(first, second), "test")

        assertEquals(listOf(second), verdict.gone)
        assertEquals(listOf(first), verdict.kept)
    }
}
