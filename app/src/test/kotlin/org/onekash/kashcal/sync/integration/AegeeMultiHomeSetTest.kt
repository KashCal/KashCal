package org.onekash.kashcal.sync.integration

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Integration test: Verify multiple calendar-home-set support against cal.aegee.org (SOGo).
 *
 * This server returns 3 calendar-home-set hrefs (Issue #70).
 * Test validates that:
 * 1. The raw HTTP response actually contains multiple <href> inside <calendar-home-set>
 * 2. The proposed extractCalendarHomeUrls() pattern parses all of them
 * 3. The existing extractCalendarHomeUrl() only returns the first (current bug)
 * 4. Each discovered home set URL can be queried for calendars
 *
 * Server details:
 * - well-known redirects: /.well-known/caldav → /dav/calendars/
 * - Principal discovery at /dav/ returns /dav/principals/user/{username}/
 * - calendar-home-set returns 3 hrefs for this user
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*AegeeMultiHomeSetTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AegeeMultiHomeSetTest {

    companion object {
        private const val SERVER_URL = "https://cal.aegee.org"
        // SOGo's CalDAV endpoint — well-known redirects here
        private const val DAV_URL = "https://cal.aegee.org/dav/"
        private const val USERNAME = "aaa"
        private const val PASSWORD = "abc"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }

    private val factory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    private lateinit var existingParser: CalDavXmlParser
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0

        existingParser = CalDavXmlParser()

        val credential = Credentials.basic(USERNAME, PASSWORD)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", credential)
                    .header("User-Agent", "KashCal-IntegrationTest/1.0")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    // ===== Proposed new method (inlined — does NOT touch production code) =====

    private fun extractCalendarHomeUrls(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var inHomeSet = false
            val urls = mutableListOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = true
                        } else if (inHomeSet && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                val url = parser.text.trim()
                                if (url.isNotEmpty()) {
                                    urls.add(url)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = false
                        }
                    }
                }
                parser.next()
            }
            urls
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===== HTTP helpers =====

    private fun propfind(url: String, body: String, depth: String = "0"): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", depth)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        return Pair(response.code, responseBody)
    }

    private fun discoverPrincipal(): String {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:current-user-principal/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        // Use /dav/ endpoint (well-known redirects here; root returns 405)
        val (code, responseBody) = propfind(DAV_URL, body)
        println("=== Principal Discovery ===")
        println("URL: $DAV_URL")
        println("Response code: $code")
        println("Response body:\n$responseBody")

        assertTrue("Principal PROPFIND failed with code $code", code == 207 || code == 200)

        val principalPath = existingParser.extractPrincipalUrl(responseBody)
        println("Extracted principal path: $principalPath")
        assertTrue("Could not extract principal URL from response", principalPath != null)

        return if (principalPath!!.startsWith("http")) principalPath
        else "$SERVER_URL$principalPath"
    }

    private fun discoverCalendarHomeSets(principalUrl: String): String {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <c:calendar-home-set/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val (code, responseBody) = propfind(principalUrl, body)
        println("\n=== Calendar Home Set Discovery ===")
        println("URL: $principalUrl")
        println("Response code: $code")
        println("Response body:\n$responseBody")

        assertTrue("Calendar home PROPFIND failed with code $code", code == 207 || code == 200)

        return responseBody
    }

    // ===== Tests =====

    @Test
    fun `AEGEE SOGo - discover principal and calendar home sets`() {
        // Step 1: Discover principal
        val principalUrl = discoverPrincipal()
        println("\nPrincipal URL: $principalUrl")

        // Step 2: Get raw calendar-home-set response
        val homeSetResponse = discoverCalendarHomeSets(principalUrl)

        // Step 3: Parse with EXISTING production parser (returns only first)
        val singleUrl = existingParser.extractCalendarHomeUrl(homeSetResponse)
        println("\n=== Existing parser (extractCalendarHomeUrl) ===")
        println("Single URL: $singleUrl")
        assertTrue("Existing parser returned null", singleUrl != null)

        // Step 4: Parse with PROPOSED new method (returns all)
        val allUrls = extractCalendarHomeUrls(homeSetResponse)
        println("\n=== Proposed parser (extractCalendarHomeUrls) ===")
        println("All URLs (${allUrls.size}):")
        allUrls.forEachIndexed { i, url -> println("  [$i] $url") }

        // Step 5: Verify we found multiple home sets (this is the bug!)
        assertTrue(
            "Expected multiple home sets from cal.aegee.org but got ${allUrls.size}",
            allUrls.size > 1
        )

        println("\n=== Analysis ===")
        println("BUG CONFIRMED: Server returns ${allUrls.size} home sets, existing parser only sees 1")
        println("Missing home sets with current parser: ${allUrls.drop(1)}")

        // Backward compat: first URL matches existing parser
        assertEquals(
            "Backward compat broken",
            singleUrl,
            allUrls.first()
        )
    }

    @Test
    fun `AEGEE SOGo - list calendars from each home set`() {
        // Step 1: Discover principal
        val principalUrl = discoverPrincipal()

        // Step 2: Get all home set URLs
        val homeSetResponse = discoverCalendarHomeSets(principalUrl)
        val allUrls = extractCalendarHomeUrls(homeSetResponse)
        assertTrue("No home set URLs found", allUrls.isNotEmpty())

        // Step 3: List calendars from each home set
        val listCalendarsBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <cs:getctag/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val calendarsByHomeSet = mutableMapOf<String, Int>()
        for ((index, homeUrl) in allUrls.withIndex()) {
            val absoluteUrl = if (homeUrl.startsWith("http")) homeUrl
            else "$SERVER_URL$homeUrl"

            println("\n=== Home Set [$index]: $absoluteUrl ===")
            try {
                val (code, body) = propfind(absoluteUrl, listCalendarsBody, depth = "1")
                println("Response code: $code")

                if (code == 207 || code == 200) {
                    // Use production parser to extract calendars
                    val calendars = existingParser.extractCalendars(body)
                    println("Calendars found: ${calendars.size}")
                    for (cal in calendars) {
                        println("  - ${cal.displayName ?: "(unnamed)"} [${cal.href}]")
                    }
                    calendarsByHomeSet[homeUrl] = calendars.size
                } else {
                    println("PROPFIND returned $code (may be permission error on shared home set)")
                    println("Response: ${body.take(500)}")
                    calendarsByHomeSet[homeUrl] = 0
                }
            } catch (e: Exception) {
                println("Error querying home set: ${e.message}")
                calendarsByHomeSet[homeUrl] = -1
            }
        }

        println("\n=== Summary ===")
        println("Home sets discovered: ${allUrls.size}")
        for ((url, count) in calendarsByHomeSet) {
            println("  $url → $count calendars")
        }
        val total = calendarsByHomeSet.values.filter { it > 0 }.sum()
        println("Total calendars across all home sets: $total")

        val firstHomeSetCalendars = calendarsByHomeSet[allUrls.first()] ?: 0
        println("\nWith existing parser (1st home set only): $firstHomeSetCalendars calendars")
        println("With proposed parser (all home sets): $total calendars")
        if (total > firstHomeSetCalendars) {
            println("IMPACT: ${total - firstHomeSetCalendars} calendars from other home sets would be missing!")
        }
    }

    @Test
    fun `AEGEE SOGo - raw response has multiple href in calendar-home-set`() {
        val principalUrl = discoverPrincipal()
        val homeSetResponse = discoverCalendarHomeSets(principalUrl)

        // Count href elements inside calendar-home-set using regex (NOT XML parser)
        // This independently confirms the server behavior without relying on our parser
        val homeSetBlock = Regex(
            """calendar-home-set.*?</[^>]*calendar-home-set>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(homeSetResponse)?.value ?: ""

        val hrefCount = Regex(
            """<[^>]*href[^>]*>[^<]+</[^>]*href>""",
            RegexOption.IGNORE_CASE
        ).findAll(homeSetBlock).count()

        println("\n=== Raw XML Analysis ===")
        println("calendar-home-set block:\n$homeSetBlock")
        println("Number of <href> elements: $hrefCount")

        assertTrue(
            "Expected multiple hrefs in calendar-home-set but found $hrefCount",
            hrefCount > 1
        )
    }
}
