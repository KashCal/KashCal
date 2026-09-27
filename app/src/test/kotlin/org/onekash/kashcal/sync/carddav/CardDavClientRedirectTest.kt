package org.onekash.kashcal.sync.carddav

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.network.HostileDavServer
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.carddav.model.ContactDeleteResult
import org.onekash.kashcal.sync.carddav.model.ContactPrecondition
import org.onekash.kashcal.sync.carddav.model.ContactUploadResult
import org.onekash.kashcal.sync.client.model.CalDavResult
import java.util.concurrent.TimeUnit

/**
 * Tests the real contact client, as its factory builds it, on a redirecting or hostile server:
 * a contact write reaches its new location unchanged and says where it landed, nothing goes
 * over plain http for an https account, a web page is never read as an empty address book, a
 * listing truncated by number-of-matches is an error, and a photo fetch never follows a
 * redirect.
 */
class CardDavClientRedirectTest {

    private lateinit var server: MockWebServer

    private val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:c1\r\nFN:Jane\r\nN:;;;;\r\nEND:VCARD\r\n"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        server = HostileDavServer.httpServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    private fun clientFor(accountUrl: String): CardDavClient =
        OkHttpCardDavClientFactory().createClient(
            Credentials(username = "u", password = "p", serverUrl = accountUrl),
            DefaultCardDavQuirks(server.url("/").toString())
        )

    private fun httpAccount() = clientFor(server.url("/").toString())

    private fun MockWebServer.next(): RecordedRequest = takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun `a redirected contact update is sent again as a PUT and says where it landed`() = runTest {
        server.enqueue(HostileDavServer.redirect(301, "/ab/moved/c1.vcf"))
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"v2\""))

        val result = httpAccount().putContact(server.url("/ab/default/c1.vcf").toString(), vcard, ContactPrecondition.IfMatch("v1"))

        assertEquals(ContactUploadResult.Success("v2", finalUrl = server.url("/ab/moved/c1.vcf").toString()), result)
        server.next()
        val resent = server.next()
        assertEquals("PUT", resent.method)
        assertEquals("\"v1\"", resent.getHeader("If-Match"))
        assertEquals(vcard, resent.body.readUtf8())
    }

    @Test
    fun `a contact update that was not redirected reports no new URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"v2\""))

        val result = httpAccount().putContact(server.url("/ab/default/c1.vcf").toString(), vcard, ContactPrecondition.IfMatch("v1"))

        assertNull((result as ContactUploadResult.Success).finalUrl)
    }

    @Test
    fun `a redirected contact delete is sent again as a DELETE with its If-Match`() = runTest {
        server.enqueue(HostileDavServer.redirect(302, "/ab/moved/c1.vcf"))
        server.enqueue(MockResponse().setResponseCode(204))

        val result = httpAccount().deleteContact(server.url("/ab/default/c1.vcf").toString(), "v1")

        assertEquals(ContactDeleteResult.Deleted, result)
        server.next()
        val resent = server.next()
        assertEquals("DELETE", resent.method)
        assertEquals("\"v1\"", resent.getHeader("If-Match"))
    }

    @Test
    fun `an https account refuses a plain http contact URL before sending anything`() = runTest {
        val client = clientFor("https://dav.example.test/")

        val put = client.putContact(server.url("/ab/default/c1.vcf").toString(), vcard, ContactPrecondition.IfAbsent)
        val delete = client.deleteContact(server.url("/ab/default/c1.vcf").toString(), "v1")
        val listing = client.listAllContactHrefs(server.url("/ab/default/").toString())

        put as ContactUploadResult.Failed
        assertEquals(CalDavResult.CODE_TRANSPORT_REFUSED, put.code)
        assertTrue("the contact change waits instead of failing for good", put.isRetryable)
        assertEquals(CalDavResult.CODE_TRANSPORT_REFUSED, (delete as ContactDeleteResult.Failed).code)
        assertEquals(CalDavResult.CODE_TRANSPORT_REFUSED, (listing as CalDavResult.Error).code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a login page is never an empty address book`() = runTest {
        val client = httpAccount()
        val calls: List<Pair<String, suspend () -> CalDavResult<*>>> = listOf(
            "listAddressBooks" to { client.listAddressBooks(server.url("/ab/").toString()) },
            "listAllContactHrefs" to { client.listAllContactHrefs(server.url("/ab/default/").toString()) },
            "syncCollection" to { client.syncCollection(server.url("/ab/default/").toString(), "t1") },
            "fetchContactsByHref" to { client.fetchContactsByHref(server.url("/ab/default/").toString(), listOf("/ab/default/c1.vcf"), "3.0") },
        )
        for ((name, call) in calls) {
            server.enqueue(HostileDavServer.loginPage())
            val result = call()
            assertTrue("$name: $result", result is CalDavResult.Error)
            assertEquals(name, CalDavResult.CODE_NOT_MULTISTATUS, (result as CalDavResult.Error).code)
        }
    }

    @Test
    fun `a full contact listing limited by number-of-matches is an error`() = runTest {
        server.enqueue(
            HostileDavServer.multistatus(
                HostileDavServer.member("/ab/default/c1.vcf", "v1") +
                    """<d:response><d:href>/ab/default/</d:href><d:status>HTTP/1.1 200 OK</d:status>""" +
                    """<d:error><d:number-of-matches-within-limits/></d:error></d:response>"""
            )
        )

        val result = httpAccount().listAllContactHrefs(server.url("/ab/default/").toString())

        assertEquals(507, (result as CalDavResult.Error).code)
    }

    @Test
    fun `a contact photo still never follows a redirect`() = runTest {
        server.enqueue(HostileDavServer.redirect(302, "/photo/elsewhere.jpg"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody("REDIRECTED"))

        val result = httpAccount().fetchPhoto(server.url("/photo/1.jpg").toString())

        assertTrue("$result", result is CalDavResult.Error)
        assertEquals("the redirect target is never requested", 1, server.requestCount)
    }
}
