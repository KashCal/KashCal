package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Diagnostic test against live Purelymail CalDAV to investigate the
 * "event vanishes after save" bug reported in KashCal/KashCal#102.
 *
 * Purelymail only supports basic CalDAV (RFC 4791) — no sync-collection (RFC 6578),
 * possibly no ctag. This test probes exactly what's supported and reproduces the
 * create-then-fetch timing issue.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*PurelymailLiveDebugTest*" -Pintegration
 */
class PurelymailLiveDebugTest {

    private lateinit var client: CalDavClient
    private var serverUrl: String? = null
    private var username: String? = null
    private var password: String? = null
    private val factory = OkHttpCalDavClientFactory()
    private val xmlParser = CalDavXmlParser()

    @Before
    fun setup() {
        loadCredentials()
        assumeTrue("Purelymail credentials not available", serverUrl != null && username != null && password != null)

        if (!serverUrl!!.startsWith("http")) {
            serverUrl = "https://$serverUrl"
        }

        val quirks = DefaultQuirks(serverUrl!!)
        client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = serverUrl!!),
            quirks
        )
    }

    // ========== Capability Probing ==========

    @Test
    fun `probe Purelymail CalDAV capabilities`() = runBlocking {
        println("\n===== PURELYMAIL CAPABILITY PROBE =====")

        // Step 1: Well-known
        println("\n--- Step 1: Well-known discovery ---")
        val wellKnownResult = client.discoverWellKnown(serverUrl!!)
        println("Well-known: success=${wellKnownResult.isSuccess()}, value=${wellKnownResult.getOrNull()}")
        if (wellKnownResult.isError()) {
            val err = wellKnownResult as CalDavResult.Error
            println("Well-known ERROR (code=${err.code}): ${err.message}")
        }

        // Step 2: Principal
        println("\n--- Step 2: Current-user-principal ---")
        val principalResult = client.discoverPrincipal(serverUrl!!)
        println("Principal: success=${principalResult.isSuccess()}, value=${principalResult.getOrNull()}")
        if (principalResult.isError()) {
            val err = principalResult as CalDavResult.Error
            println("Principal ERROR (code=${err.code}): ${err.message}")
            return@runBlocking
        }
        val principalUrl = principalResult.getOrNull()!!

        // Step 3: Calendar home set
        println("\n--- Step 3: Calendar-home-set ---")
        val homeResult = client.discoverCalendarHome(principalUrl)
        println("Home: success=${homeResult.isSuccess()}, value=${homeResult.getOrNull()}")
        if (homeResult.isError()) {
            val err = homeResult as CalDavResult.Error
            println("Home ERROR (code=${err.code}): ${err.message}")
            return@runBlocking
        }
        val homeUrls = homeResult.getOrNull()!!
        println("Home set URLs: $homeUrls")
        val homeUrl = homeUrls.first()

        // Step 4: List calendars
        println("\n--- Step 4: List calendars ---")
        val calendarsResult = client.listCalendars(homeUrl)
        println("Calendars: success=${calendarsResult.isSuccess()}")
        if (calendarsResult.isError()) {
            val err = calendarsResult as CalDavResult.Error
            println("Calendars ERROR (code=${err.code}): ${err.message}")
            return@runBlocking
        }
        val calendars = calendarsResult.getOrNull()!!
        println("Found ${calendars.size} calendars:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName} | url=${cal.url} | ctag=${cal.ctag} | color=${cal.color}")
        }

        if (calendars.isEmpty()) {
            println("No calendars found!")
            return@runBlocking
        }

        val calendarUrl = calendars[0].url
        println("\nUsing calendar: ${calendars[0].displayName} at $calendarUrl")

        // Step 5: ctag
        println("\n--- Step 5: getctag (CalendarServer extension) ---")
        val ctagResult = client.getCtag(calendarUrl)
        println("ctag: success=${ctagResult.isSuccess()}, value=${ctagResult.getOrNull()}")
        if (ctagResult.isError()) {
            val err = ctagResult as CalDavResult.Error
            println("ctag ERROR (code=${err.code}): ${err.message}")
            println("→ ctag NOT SUPPORTED (expected for Purelymail)")
        }

        // Step 6: sync-token via PROPFIND
        println("\n--- Step 6: sync-token (RFC 6578) ---")
        val syncTokenResult = client.getSyncToken(calendarUrl)
        println("sync-token: success=${syncTokenResult.isSuccess()}, value=${syncTokenResult.getOrNull()}")
        if (syncTokenResult.isError()) {
            val err = syncTokenResult as CalDavResult.Error
            println("sync-token ERROR (code=${err.code}): ${err.message}")
            println("→ sync-token NOT SUPPORTED (expected for Purelymail)")
        }

        // Step 7: sync-collection REPORT (if token available)
        val syncToken = syncTokenResult.getOrNull()
        if (syncToken != null) {
            println("\n--- Step 7: sync-collection REPORT ---")
            val syncResult = client.syncCollection(calendarUrl, syncToken)
            println("sync-collection: success=${syncResult.isSuccess()}")
            if (syncResult.isError()) {
                val err = syncResult as CalDavResult.Error
                println("sync-collection ERROR (code=${err.code}): ${err.message}")
            } else {
                val report = syncResult.getOrNull()!!
                println("  changed=${report.changed.size}, deleted=${report.deleted.size}, truncated=${report.truncated}")
            }
        } else {
            println("\n--- Step 7: sync-collection REPORT → SKIPPED (no sync token) ---")
        }

        // Step 8: fetchEtagsInRange (calendar-query without calendar-data)
        println("\n--- Step 8: fetchEtagsInRange (calendar-query) ---")
        val now = System.currentTimeMillis()
        val pastWindow = now - (365L * 24 * 60 * 60 * 1000) // 1 year
        val futureEnd = 4102444800000L // Jan 1, 2100
        val etagResult = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
        println("fetchEtagsInRange: success=${etagResult.isSuccess()}")
        if (etagResult.isError()) {
            val err = etagResult as CalDavResult.Error
            println("fetchEtagsInRange ERROR (code=${err.code}): ${err.message}")
        } else {
            val etags = etagResult.getOrNull()!!
            println("Got ${etags.size} etag pairs:")
            etags.take(5).forEach { (href, etag) ->
                println("  href=$href | etag=$etag")
            }
        }

        // Step 9: fetchEventsInRange (calendar-query WITH calendar-data)
        println("\n--- Step 9: fetchEventsInRange (calendar-query + calendar-data) ---")
        val eventsResult = client.fetchEventsInRange(calendarUrl, pastWindow, futureEnd)
        println("fetchEventsInRange: success=${eventsResult.isSuccess()}")
        if (eventsResult.isError()) {
            val err = eventsResult as CalDavResult.Error
            println("fetchEventsInRange ERROR (code=${err.code}): ${err.message}")
        } else {
            val events = eventsResult.getOrNull()!!
            println("Got ${events.size} events:")
            events.take(3).forEach { ev ->
                println("  href=${ev.href} | etag=${ev.etag} | data=${ev.icalData.take(100)}...")
            }
        }

        println("\n===== CAPABILITY SUMMARY =====")
        println("ctag:              ${if (ctagResult.isSuccess()) "SUPPORTED (${ctagResult.getOrNull()})" else "NOT SUPPORTED"}")
        println("sync-token:        ${if (syncTokenResult.isSuccess()) "SUPPORTED" else "NOT SUPPORTED"}")
        println("calendar-query:    ${if (etagResult.isSuccess()) "SUPPORTED" else "NOT SUPPORTED"}")
        println("calendar-multiget: (test below)")
        println("==============================")
    }

    // ========== The Core Bug: Create → Fetch Timing ==========

    @Test
    fun `reproduce vanishing event - create then immediately fetch etags`() = runBlocking {
        println("\n===== REPRODUCE: CREATE → FETCH ETAGS TIMING =====")

        // Discovery
        val principalUrl = client.discoverPrincipal(serverUrl!!).getOrNull()!!
        val homeUrl = client.discoverCalendarHome(principalUrl).getOrNull()!!.first()
        val calendars = client.listCalendars(homeUrl).getOrNull()!!
        assumeTrue("No calendars found", calendars.isNotEmpty())
        val calendarUrl = calendars[0].url
        println("Calendar: ${calendars[0].displayName} at $calendarUrl")

        val testUid = "kashcal-vanish-test-${System.currentTimeMillis()}"
        val icalData = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//Vanish Test//EN
CALSCALE:GREGORIAN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:20260329T120000Z
DTSTART:20260401T100000Z
DTEND:20260401T110000Z
SUMMARY:KashCal Vanish Test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        // --- Step 1: CREATE event ---
        println("\n--- Step 1: CREATE event (PUT) ---")
        val createResult = client.createEvent(calendarUrl, testUid, icalData)
        println("Create: success=${createResult.isSuccess()}")
        if (createResult.isError()) {
            val err = createResult as CalDavResult.Error
            println("Create ERROR (code=${err.code}): ${err.message}")
            return@runBlocking
        }
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        println("Created at: $eventUrl")
        println("Create etag: $createEtag")

        try {
            // --- Step 2: IMMEDIATELY fetch etags (0ms delay) ---
            println("\n--- Step 2: fetchEtagsInRange (0ms after create) ---")
            val now = System.currentTimeMillis()
            val pastWindow = now - (365L * 24 * 60 * 60 * 1000)
            val futureEnd = 4102444800000L
            val etagResult0 = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
            if (etagResult0.isSuccess()) {
                val etags0 = etagResult0.getOrNull()!!
                val quirks = DefaultQuirks(serverUrl!!)
                val foundImmediate = etags0.any { (href, _) ->
                    quirks.buildEventUrl(href, calendarUrl) == eventUrl
                }
                println("Etags returned: ${etags0.size}")
                println("Event found immediately: $foundImmediate")
                if (!foundImmediate) {
                    println("!!! EVENT NOT FOUND IN ETAG RESPONSE — THIS IS THE BUG")
                    // Show what URLs ARE in the response
                    etags0.forEach { (href, etag) ->
                        val url = quirks.buildEventUrl(href, calendarUrl)
                        println("  server: $url | etag=$etag")
                    }
                    println("  expected: $eventUrl")
                }
            } else {
                val err = etagResult0 as CalDavResult.Error
                println("Etag ERROR (code=${err.code}): ${err.message}")
            }

            // --- Step 3: Wait 1s, fetch again ---
            println("\n--- Step 3: fetchEtagsInRange (1000ms after create) ---")
            delay(1000)
            val etagResult1 = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
            if (etagResult1.isSuccess()) {
                val etags1 = etagResult1.getOrNull()!!
                val quirks = DefaultQuirks(serverUrl!!)
                val found1s = etags1.any { (href, _) ->
                    quirks.buildEventUrl(href, calendarUrl) == eventUrl
                }
                println("Etags returned: ${etags1.size}")
                println("Event found after 1s: $found1s")
            }

            // --- Step 4: Wait 3s, fetch again ---
            println("\n--- Step 4: fetchEtagsInRange (4000ms after create) ---")
            delay(3000)
            val etagResult4 = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
            if (etagResult4.isSuccess()) {
                val etags4 = etagResult4.getOrNull()!!
                val quirks = DefaultQuirks(serverUrl!!)
                val found4s = etags4.any { (href, _) ->
                    quirks.buildEventUrl(href, calendarUrl) == eventUrl
                }
                println("Etags returned: ${etags4.size}")
                println("Event found after 4s: $found4s")
            }

            // --- Step 5: Wait 10s, fetch again ---
            println("\n--- Step 5: fetchEtagsInRange (14000ms after create) ---")
            delay(10000)
            val etagResult14 = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
            if (etagResult14.isSuccess()) {
                val etags14 = etagResult14.getOrNull()!!
                val quirks = DefaultQuirks(serverUrl!!)
                val found14s = etags14.any { (href, _) ->
                    quirks.buildEventUrl(href, calendarUrl) == eventUrl
                }
                println("Etags returned: ${etags14.size}")
                println("Event found after 14s: $found14s")
            }

            // --- Step 6: Check sync-collection if available ---
            println("\n--- Step 6: sync-collection (if supported) ---")
            val syncTokenResult = client.getSyncToken(calendarUrl)
            if (syncTokenResult.isSuccess()) {
                val token = syncTokenResult.getOrNull()
                if (token != null) {
                    println("Got sync token: ${token.take(20)}...")
                    val syncResult = client.syncCollection(calendarUrl, token)
                    println("sync-collection: success=${syncResult.isSuccess()}")
                    if (syncResult.isSuccess()) {
                        val report = syncResult.getOrNull()!!
                        println("  changed=${report.changed.size}, deleted=${report.deleted.size}")
                        report.changed.forEach { item ->
                            println("  changed: ${item.href} | ${item.etag}")
                        }
                    } else {
                        val err = syncResult as CalDavResult.Error
                        println("sync-collection ERROR (code=${err.code}): ${err.message}")
                    }
                } else {
                    println("sync-token is null (not supported)")
                }
            } else {
                println("No sync token available")
            }

            // --- Step 7: Check ctag change after create ---
            println("\n--- Step 7: ctag after create ---")
            val ctagResult = client.getCtag(calendarUrl)
            println("ctag: success=${ctagResult.isSuccess()}, value=${ctagResult.getOrNull()}")

        } finally {
            // --- Cleanup: DELETE the test event ---
            println("\n--- Cleanup: DELETE test event ---")
            val deleteResult = client.deleteEvent(eventUrl, createEtag)
            println("Delete: success=${deleteResult.isSuccess()}")
            if (deleteResult.isError()) {
                val err = deleteResult as CalDavResult.Error
                println("Delete ERROR (code=${err.code}): ${err.message}")
                // Try with wildcard etag
                val deleteResult2 = client.deleteEvent(eventUrl, "*")
                println("Delete (wildcard etag): success=${deleteResult2.isSuccess()}")
            }
        }
    }

    // ========== URL Comparison Test ==========

    @Test
    fun `check URL format consistency between create and fetch`() = runBlocking {
        println("\n===== URL FORMAT CONSISTENCY TEST =====")

        val principalUrl = client.discoverPrincipal(serverUrl!!).getOrNull()!!
        val homeUrl = client.discoverCalendarHome(principalUrl).getOrNull()!!.first()
        val calendars = client.listCalendars(homeUrl).getOrNull()!!
        assumeTrue("No calendars found", calendars.isNotEmpty())
        val calendarUrl = calendars[0].url
        println("Calendar URL: $calendarUrl")

        val testUid = "kashcal-url-test-${System.currentTimeMillis()}"
        val icalData = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//URL Test//EN
CALSCALE:GREGORIAN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:20260329T120000Z
DTSTART:20260401T100000Z
DTEND:20260401T110000Z
SUMMARY:KashCal URL Test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        val createResult = client.createEvent(calendarUrl, testUid, icalData)
        assumeTrue("Create failed", createResult.isSuccess())
        val (eventUrl, etag) = createResult.getOrNull()!!
        println("Stored caldavUrl: $eventUrl")

        try {
            // Wait a bit for indexing
            delay(2000)

            val now = System.currentTimeMillis()
            val pastWindow = now - (365L * 24 * 60 * 60 * 1000)
            val futureEnd = 4102444800000L
            val etagResult = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
            assumeTrue("Etag fetch failed", etagResult.isSuccess())
            val etags = etagResult.getOrNull()!!

            val quirks = DefaultQuirks(serverUrl!!)
            println("\nURL comparison:")
            println("  Stored caldavUrl: $eventUrl")
            println("  Server returned hrefs:")
            etags.forEach { (href, serverEtag) ->
                val constructedUrl = quirks.buildEventUrl(href, calendarUrl)
                val matches = constructedUrl == eventUrl
                println("    href=$href → url=$constructedUrl | match=$matches | etag=$serverEtag")
            }

            val exactMatch = etags.any { (href, _) ->
                quirks.buildEventUrl(href, calendarUrl) == eventUrl
            }
            println("\nExact URL match found: $exactMatch")
            if (!exactMatch) {
                println("!!! URL MISMATCH - This could cause the deletion bug")
                println("!!! The stored caldavUrl from createEvent doesn't match any URL")
                println("!!! constructed from fetchEtagsInRange hrefs via buildEventUrl")
            }
        } finally {
            println("\n--- Cleanup ---")
            client.deleteEvent(eventUrl, etag ?: "*")
        }
    }

    // ========== PROPFIND Depth:1 Hypothesis Test ==========

    @Test
    fun `PROPFIND Depth 1 finds newly created event where calendar-query does not`() = runBlocking {
        println("\n===== PROPFIND DEPTH:1 vs CALENDAR-QUERY TEST =====")

        // Discovery
        val principalUrl = client.discoverPrincipal(serverUrl!!).getOrNull()!!
        val homeUrl = client.discoverCalendarHome(principalUrl).getOrNull()!!.first()
        val calendars = client.listCalendars(homeUrl).getOrNull()!!
        assumeTrue("No calendars found", calendars.isNotEmpty())
        val calendarUrl = calendars[0].url
        println("Calendar: ${calendars[0].displayName} at $calendarUrl")

        val testUid = "kashcal-propfind-test-${System.currentTimeMillis()}"
        val icalData = """
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//KashCal//PROPFIND Test//EN
CALSCALE:GREGORIAN
BEGIN:VEVENT
UID:$testUid
DTSTAMP:20260329T120000Z
DTSTART:20260401T100000Z
DTEND:20260401T110000Z
SUMMARY:KashCal PROPFIND Test
END:VEVENT
END:VCALENDAR
        """.trimIndent()

        // --- Step 1: CREATE event ---
        println("\n--- Step 1: CREATE event (PUT) ---")
        val createResult = client.createEvent(calendarUrl, testUid, icalData)
        assumeTrue("Create failed: ${if (createResult.isError()) (createResult as CalDavResult.Error).message else ""}", createResult.isSuccess())
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        println("Created at: $eventUrl")
        println("Create etag: $createEtag")

        try {
            // --- Step 2: calendar-query REPORT (same as fetchEtagsInRange) ---
            println("\n--- Step 2: calendar-query REPORT (0ms after create) ---")
            val now = System.currentTimeMillis()
            val pastWindow = now - (365L * 24 * 60 * 60 * 1000)
            val futureEnd = 4102444800000L
            val etagResult = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
            if (etagResult.isSuccess()) {
                val etags = etagResult.getOrNull()!!
                val quirks = DefaultQuirks(serverUrl!!)
                val foundViaQuery = etags.any { (href, _) ->
                    quirks.buildEventUrl(href, calendarUrl) == eventUrl
                }
                println("calendar-query returned: ${etags.size} events")
                println("Event found via calendar-query: $foundViaQuery")
                if (!foundViaQuery) {
                    println("  (confirms: calendar-query index is stale)")
                }
            } else {
                println("calendar-query failed: ${(etagResult as CalDavResult.Error).message}")
            }

            // --- Step 3: PROPFIND Depth:1 (raw HTTP) ---
            println("\n--- Step 3: PROPFIND Depth:1 (0ms after create) ---")
            val propfindBody = """
<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
    <d:prop>
        <d:getetag/>
        <d:resourcetype/>
        <d:getcontenttype/>
    </d:prop>
</d:propfind>
            """.trimIndent()

            // Use OkHttp directly with Basic auth
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("Authorization", okhttp3.Credentials.basic(username!!, password!!, Charsets.UTF_8))
                        .build()
                    chain.proceed(req)
                }
                .build()

            val propfindRequest = okhttp3.Request.Builder()
                .url(calendarUrl)
                .method("PROPFIND", propfindBody.toByteArray().toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .build()

            val propfindResponse = okHttpClient.newCall(propfindRequest).execute()
            val propfindXml = propfindResponse.body?.string() ?: ""
            println("PROPFIND response code: ${propfindResponse.code}")
            println("PROPFIND response length: ${propfindXml.length} chars")

            // Parse hrefs from PROPFIND response
            val hrefPattern = Regex("""<(?:d:|D:)?href[^>]*>([^<]+)</(?:d:|D:)?href>""", RegexOption.IGNORE_CASE)
            val propfindHrefs = hrefPattern.findAll(propfindXml).map { it.groupValues[1].trim() }.toList()
            println("PROPFIND returned ${propfindHrefs.size} hrefs:")
            propfindHrefs.forEach { href ->
                println("  $href")
            }

            // Check if our event is in the PROPFIND response
            val eventFilename = "$testUid.ics"
            val foundViaPropfind = propfindHrefs.any { href ->
                href.contains(testUid) || href.contains(eventFilename)
            }
            // Also check by full URL - server may have renamed the file
            val foundByCreateUrl = propfindHrefs.any { href ->
                val fullUrl = if (href.startsWith("http")) href
                              else "${calendarUrl.trimEnd('/')}/${href.trimStart('/')}"
                fullUrl == eventUrl
            }

            println("\nEvent found via PROPFIND (by UID): $foundViaPropfind")
            println("Event found via PROPFIND (by URL): $foundByCreateUrl")

            // Also extract etags from PROPFIND to see the full picture
            val etagPattern = Regex("""<(?:d:|D:)?getetag[^>]*>"?([^"<]+)"?</(?:d:|D:)?getetag>""", RegexOption.IGNORE_CASE)
            val propfindEtags = etagPattern.findAll(propfindXml).map { it.groupValues[1].trim() }.toList()
            println("PROPFIND etags: $propfindEtags")

            println("\n===== CONCLUSION =====")
            val calQueryResult = if (etagResult.isSuccess()) {
                val quirks = DefaultQuirks(serverUrl!!)
                etagResult.getOrNull()!!.any { (href, _) -> quirks.buildEventUrl(href, calendarUrl) == eventUrl }
            } else false
            println("calendar-query found event: $calQueryResult")
            println("PROPFIND Depth:1 found event: ${foundViaPropfind || foundByCreateUrl}")
            if (!calQueryResult && (foundViaPropfind || foundByCreateUrl)) {
                println(">>> HYPOTHESIS CONFIRMED: PROPFIND Depth:1 works where calendar-query doesn't!")
                println(">>> FIX: Use PROPFIND Depth:1 for etag listing on servers without sync-token")
            } else if (!calQueryResult && !foundViaPropfind && !foundByCreateUrl) {
                println(">>> Neither method found the event - server may need more time")
            } else if (calQueryResult) {
                println(">>> Both methods work - calendar-query index may have caught up")
            }

            // Print raw XML snippet for debugging
            println("\n--- Raw PROPFIND XML (first 3000 chars) ---")
            println(propfindXml.take(3000))

        } finally {
            // --- Cleanup ---
            println("\n--- Cleanup: DELETE test event ---")
            val deleteResult = client.deleteEvent(eventUrl, createEtag)
            println("Delete: success=${deleteResult.isSuccess()}")
            if (deleteResult.isError()) {
                val deleteResult2 = client.deleteEvent(eventUrl, "*")
                println("Delete (wildcard): success=${deleteResult2.isSuccess()}")
            }
        }
    }

    // ========== Helper ==========

    private fun loadCredentials() {
        // Try multiple locations for local.properties
        val candidates = listOf(
            File("local.properties"),
            File("../local.properties"),
            File(System.getProperty("user.dir"), "local.properties")
        )
        val props = candidates.firstOrNull { it.exists() }
        if (props == null) {
            println("local.properties not found in: ${candidates.map { it.absolutePath }}")
            return
        }
        println("Loading credentials from: ${props.absolutePath}")
        val properties = java.util.Properties().apply { load(props.inputStream()) }
        serverUrl = properties.getProperty("PURELYMAIL_SERVER")
        username = properties.getProperty("PURELYMAIL_USERNAME")
        password = properties.getProperty("PURELYMAIL_PASSWORD")
        println("Loaded: server=$serverUrl, user=$username, pass=${password?.take(3)}***")
    }
}
