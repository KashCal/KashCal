package org.onekash.kashcal.sync.client

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
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests [OkHttpCalDavClient.listWebcalFeeds]: the request it sends and each kind of reply. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class OkHttpCalDavClientWebcalFeedsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpCalDavClient
    private lateinit var homeUrl: String

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
        val base = server.url("/").toString()
        homeUrl = server.url("/remote.php/dav/calendars/admin/").toString()
        client = OkHttpCalDavClientFactory()
            .createClient(Credentials("user", "pass", base), DefaultQuirks(base)) as OkHttpCalDavClient
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun load(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes().decodeToString() }

    private fun multistatus(code: Int = 207, body: String) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(body)

    @Test
    fun `sends a Depth 1 PROPFIND naming resourcetype, displayname, color and source`() = runTest {
        server.enqueue(multistatus(body = load("caldav/webcal_feeds/nextcloud.xml")))

        client.listWebcalFeeds(homeUrl)

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/remote.php/dav/calendars/admin/", request.path)
        assertEquals("1", request.getHeader("Depth"))
        val body = request.body.readUtf8()
        listOf("resourcetype", "displayname", "calendar-color", "source", "http://calendarserver.org/ns/")
            .forEach { assertTrue("request body lacks $it", body.contains(it)) }
    }

    @Test
    fun `a 207 listing returns the parsed feeds`() = runTest {
        server.enqueue(multistatus(body = load("caldav/webcal_feeds/nextcloud.xml")))

        val result = client.listWebcalFeeds(homeUrl)

        val feeds = (result as CalDavResult.Success).data
        assertEquals(3, feeds.size)
        assertTrue(feeds.any { it.source == "webcal://holidays.example.test/media/caldata/autogen/GermanHolidays.ics" })
    }

    @Test
    fun `a 207 listing without feeds returns an empty list`() = runTest {
        server.enqueue(multistatus(body = load("caldav/resourcetype_proof/baikal.xml")))

        val result = client.listWebcalFeeds(homeUrl)

        assertEquals(emptyList<Any>(), (result as CalDavResult.Success).data)
    }

    @Test
    fun `401 is an error with that code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.listWebcalFeeds(homeUrl)

        assertEquals(401, (result as CalDavResult.Error).code)
    }

    @Test
    fun `403 is an error with that code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.listWebcalFeeds(homeUrl)

        assertEquals(403, (result as CalDavResult.Error).code)
    }

    @Test
    fun `404 is an error with that code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.listWebcalFeeds(homeUrl)

        assertEquals(404, (result as CalDavResult.Error).code)
    }

    @Test
    fun `500 is an error with that code after the client's retry`() = runTest {
        // 5xx is retried once (two attempts in all), so both get a 500.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.listWebcalFeeds(homeUrl)

        assertEquals(500, (result as CalDavResult.Error).code)
    }

    @Test
    fun `a 207 that is not a readable multistatus is the existing not-multistatus error`() = runTest {
        server.enqueue(multistatus(body = "<html><body>login</body></html>"))

        val result = client.listWebcalFeeds(homeUrl)

        assertEquals(CalDavResult.CODE_NOT_MULTISTATUS, (result as CalDavResult.Error).code)
    }
}
