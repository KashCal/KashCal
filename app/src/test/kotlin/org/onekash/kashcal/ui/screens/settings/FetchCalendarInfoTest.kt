package org.onekash.kashcal.ui.screens.settings

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.UiMessage

/**
 * Tests [fetchCalendarInfo], the feed preview fetch behind the add-subscription dialog's "Fetch
 * Calendar" button and the holiday catalog picker, and [normalizeSubscriptionUrl].
 *
 * Covers a parsed feed name, the HTTP, empty-body and not-a-calendar errors, and webcal://
 * handling. The URL field can be pre-filled from a webcal:// deep link (tapping "Add to
 * calendar" on a feed's web page), so the fetch must convert the scheme to https:// before
 * calling OkHttp, which rejects webcal:// and would surface a network error.
 */
class FetchCalendarInfoTest {

    private lateinit var server: MockWebServer

    private val validIcs = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//EN
        X-WR-CALNAME:Reluctant Motivation
        BEGIN:VEVENT
        UID:1@example.test
        DTSTART;VALUE=DATE:20260101
        SUMMARY:Not behind.
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // A test may already have shut the server down to free its port.
        runCatching { server.shutdown() }
        unmockkAll()
    }

    @Test
    fun `fetchCalendarInfo fetches and parses a valid feed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(validIcs))
        val url = server.url("/reluctant-motivation.ics").toString()

        val result = fetchCalendarInfo(url)

        assertTrue(
            "Expected Success, got $result",
            result is FetchCalendarState.Success,
        )
        assertEquals("Reluctant Motivation", (result as FetchCalendarState.Success).name)
    }

    @Test
    fun `fetchCalendarInfo maps an HTTP error to the http error string with the code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val url = server.url("/missing.ics").toString()

        val result = fetchCalendarInfo(url)

        val message = (result as? FetchCalendarState.Error)?.message
        assertEquals(
            UiMessage.ResId(R.string.ics_fetch_error_http, listOf(404)),
            message,
        )
    }

    @Test
    fun `fetchCalendarInfo maps an empty body to the empty error string`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val url = server.url("/empty.ics").toString()

        val result = fetchCalendarInfo(url)

        val message = (result as? FetchCalendarState.Error)?.message
        assertEquals(UiMessage.ResId(R.string.ics_fetch_error_empty), message)
    }

    @Test
    fun `fetchCalendarInfo maps a non-calendar body to the not-calendar error string`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not a calendar</html>"))
        val url = server.url("/page.html").toString()

        val result = fetchCalendarInfo(url)

        val message = (result as? FetchCalendarState.Error)?.message
        assertEquals(UiMessage.ResId(R.string.ics_fetch_error_not_calendar), message)
    }

    @Test
    fun `fetchCalendarInfo treats a webcal URL the same as its https form`() = runTest {
        // Point a webcal:// URL and its https:// form at a port nothing listens on, so both
        // fail with the same connection error. fetchCalendarInfo rewrites webcal:// to the
        // identical https:// request, so the two errors are equal.
        //
        // This doesn't depend on OkHttp's error wording: without the normalizeSubscriptionUrl
        // call, the webcal input hits OkHttp's synchronous scheme rejection
        // (IllegalArgumentException) while the https input gets a connection error, two
        // different messages. No live server: an https attempt against a plaintext port gives
        // nondeterministic TLS-handshake errors, while connection-refused is stable.
        val deadPort = server.port
        server.shutdown() // free the port so connections are refused, not served

        val authority = "${server.hostName}:$deadPort"
        val webcalResult = fetchCalendarInfo("webcal://$authority/reluctant-motivation.ics")
        val httpsResult = fetchCalendarInfo("https://$authority/reluctant-motivation.ics")

        assertTrue("webcal input should error, got $webcalResult",
            webcalResult is FetchCalendarState.Error)
        assertEquals(
            "A webcal URL must resolve to the same request as its https form",
            (httpsResult as FetchCalendarState.Error).message,
            (webcalResult as FetchCalendarState.Error).message,
        )
    }

    @Test
    fun `normalizeSubscriptionUrl rewrites only the leading webcal scheme`() {
        // The normalized form is what fetchCalendarInfo hands to OkHttp: the leading scheme
        // becomes https, and a webcal literal elsewhere in the URL, such as a query param, is
        // left untouched.
        assertEquals(
            "https://host/feed.ics",
            normalizeSubscriptionUrl("webcal://host/feed.ics"),
        )
        assertEquals(
            "https://host/f.ics?next=webcal://other",
            normalizeSubscriptionUrl("webcal://host/f.ics?next=webcal://other"),
        )
    }
}
