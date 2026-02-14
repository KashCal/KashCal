package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File

/**
 * Diagnostic test against live Zoho CalDAV to trace the two-step fetch flow.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*ZohoLiveDebugTest*"
 */
class ZohoLiveDebugTest {

    private lateinit var client: CalDavClient
    private var serverUrl: String? = null
    private var username: String? = null
    private var password: String? = null
    private val factory = OkHttpCalDavClientFactory()
    private val xmlParser = CalDavXmlParser()

    @Before
    fun setup() {
        loadCredentials()
        assumeTrue("Zoho credentials not available", serverUrl != null && username != null && password != null)

        // Ensure scheme is present
        if (!serverUrl!!.startsWith("http")) {
            serverUrl = "https://$serverUrl"
        }

        val quirks = DefaultQuirks(serverUrl!!)
        client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = serverUrl!!),
            quirks
        )
    }

    @Test
    fun `trace full Zoho discovery and pull flow`() = runBlocking {
        println("\n=== Step 1: Discover principal ===")
        val principalResult = client.discoverPrincipal(serverUrl!!)
        println("Principal result: success=${principalResult.isSuccess()}, value=${principalResult.getOrNull()}")
        if (principalResult.isError()) {
            println("ERROR: ${(principalResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error).message}")
            return@runBlocking
        }
        val principalUrl = principalResult.getOrNull()!!

        println("\n=== Step 2: Discover calendar home ===")
        val homeResult = client.discoverCalendarHome(principalUrl)
        println("Home result: success=${homeResult.isSuccess()}, value=${homeResult.getOrNull()}")
        if (homeResult.isError()) {
            println("ERROR: ${(homeResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error).message}")
            return@runBlocking
        }
        val homeUrl = homeResult.getOrNull()!!.first()

        println("\n=== Step 3: List calendars ===")
        val calendarsResult = client.listCalendars(homeUrl)
        println("Calendars result: success=${calendarsResult.isSuccess()}")
        if (calendarsResult.isError()) {
            println("ERROR: ${(calendarsResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error).message}")
            return@runBlocking
        }
        val calendars = calendarsResult.getOrNull()!!
        println("Found ${calendars.size} calendars:")
        calendars.forEach { cal ->
            println("  - ${cal.displayName} | url=${cal.url} | ctag=${cal.ctag}")
        }

        if (calendars.isEmpty()) {
            println("No calendars found!")
            return@runBlocking
        }

        val calendarUrl = calendars[0].url
        println("\nUsing calendar: ${calendars[0].displayName} at $calendarUrl")

        println("\n=== Step 4: Get ctag ===")
        val ctagResult = client.getCtag(calendarUrl)
        println("Ctag result: success=${ctagResult.isSuccess()}, value=${ctagResult.getOrNull()}")
        if (ctagResult.isError()) {
            val err = ctagResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("Ctag ERROR (code=${err.code}): ${err.message}")
        }

        println("\n=== Step 5: fetchEtagsInRange (calendar-query, no calendar-data) ===")
        val now = System.currentTimeMillis()
        val pastWindow = now - (90L * 24 * 60 * 60 * 1000) // 90 days ago
        val futureEnd = Long.MAX_VALUE / 2
        val etagResult = client.fetchEtagsInRange(calendarUrl, pastWindow, futureEnd)
        println("Etag result: success=${etagResult.isSuccess()}")
        if (etagResult.isError()) {
            val err = etagResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("Etag ERROR (code=${err.code}): ${err.message}")
            return@runBlocking
        }
        val etags = etagResult.getOrNull()!!
        println("Got ${etags.size} etag pairs:")
        etags.take(10).forEach { (href, etag) ->
            println("  href=$href | etag=$etag")
        }

        if (etags.isEmpty()) {
            println("\n!!! ZERO ETAGS - Calendar-query returned no events.")
            return@runBlocking
        }

        println("\n=== Step 5b: Raw multiget request ===")
        val hrefs = etags.map { it.first }
        val rawXml = rawMultiget(calendarUrl, hrefs)
        println("Raw multiget response (first 3000 chars):")
        println(rawXml?.take(3000))

        if (rawXml != null) {
            println("\n=== Step 5c: Parse raw response with extractICalData ===")
            val parsed = xmlParser.extractICalData(rawXml)
            println("extractICalData returned ${parsed.size} events")
            parsed.take(3).forEach { p ->
                println("  href=${p.href} | etag=${p.etag} | data=${p.icalData.take(80)}...")
            }
        }

        println("\n=== Step 6: fetchEventsByHref (calendar-multiget) ===")
        val fetchResult = client.fetchEventsByHref(calendarUrl, hrefs)
        println("Multiget result: success=${fetchResult.isSuccess()}")
        if (fetchResult.isError()) {
            val err = fetchResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("Multiget ERROR (code=${err.code}): ${err.message}")
            return@runBlocking
        }
        val events = fetchResult.getOrNull()!!
        println("Got ${events.size} events with data:")
        events.take(5).forEach { ev ->
            println("  href=${ev.href} | etag=${ev.etag} | data=${ev.icalData.take(80)}...")
        }

        println("\n=== Step 7: fetchEventsInRange (single-step, calendar-query WITH calendar-data) ===")
        val singleStepResult = client.fetchEventsInRange(calendarUrl, pastWindow, futureEnd)
        println("Single-step result: success=${singleStepResult.isSuccess()}")
        if (singleStepResult.isSuccess()) {
            val singleStepEvents = singleStepResult.getOrNull()!!
            println("Got ${singleStepEvents.size} events via single-step")
            singleStepEvents.take(3).forEach { ev ->
                println("  href=${ev.href} | etag=${ev.etag} | data=${ev.icalData.take(80)}...")
            }
        }

        println("\n=== Step 8: Individual GET for first event ===")
        val firstHref = hrefs[0]
        val firstEventUrl = "https://calendar.zoho.com$firstHref"
        println("Fetching: $firstEventUrl")
        val individualResult = client.fetchEvent(firstEventUrl)
        println("Individual GET result: success=${individualResult.isSuccess()}")
        if (individualResult.isSuccess()) {
            val ev = individualResult.getOrNull()!!
            println("  href=${ev.href} | etag=${ev.etag} | data=${ev.icalData.take(200)}...")
        } else if (individualResult.isError()) {
            val err = individualResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("Individual GET ERROR (code=${err.code}): ${err.message}")
        }

        println("\n=== SUMMARY ===")
        println("Calendars: ${calendars.size}")
        println("Etags from calendar-query: ${etags.size}")
        println("Events from multiget: ${events.size}")
        println("Events from single-step: ${if (singleStepResult.isSuccess()) singleStepResult.getOrNull()!!.size else "ERROR"}")
        println("Two-step fetch ${if (events.isNotEmpty()) "WORKS" else "FAILED"}")
    }

    /**
     * Test whether Zoho's multiget failure is batch-size dependent.
     * KashCal chunks at 10 — does a batch of 10 work? A batch of 1?
     */
    @Test
    fun `multiget batch size experiment`() = runBlocking {
        println("\n=== Multiget Batch Size Experiment ===")

        // Discovery
        val principalUrl = client.discoverPrincipal(serverUrl!!).getOrNull()!!
        val homeUrl = client.discoverCalendarHome(principalUrl).getOrNull()!!.first()
        val calendars = client.listCalendars(homeUrl).getOrNull()!!
        assumeTrue("No calendars found", calendars.isNotEmpty())
        val calendarUrl = calendars[0].url
        println("Calendar: ${calendars[0].displayName} at $calendarUrl")

        // Get etags
        val now = System.currentTimeMillis()
        val pastWindow = now - (90L * 24 * 60 * 60 * 1000)
        val etags = client.fetchEtagsInRange(calendarUrl, pastWindow, Long.MAX_VALUE / 2).getOrNull()!!
        println("Total events on server: ${etags.size}")
        assumeTrue("Need at least 1 event", etags.isNotEmpty())

        val allHrefs = etags.map { it.first }

        // --- Test 1: Single-href multiget via client ---
        println("\n--- Test 1: fetchEventsByHref with 1 href ---")
        val single = client.fetchEventsByHref(calendarUrl, listOf(allHrefs[0]))
        println("Result: success=${single.isSuccess()}, events=${single.getOrNull()?.size ?: "N/A"}")
        if (single.isError()) {
            val err = single as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("ERROR: code=${err.code}, msg=${err.message}")
        }

        // --- Test 2: Batch of 10 via client ---
        val batch10 = allHrefs.take(10)
        println("\n--- Test 2: fetchEventsByHref with ${batch10.size} hrefs ---")
        val batch10Result = client.fetchEventsByHref(calendarUrl, batch10)
        println("Result: success=${batch10Result.isSuccess()}, events=${batch10Result.getOrNull()?.size ?: "N/A"}")
        if (batch10Result.isError()) {
            val err = batch10Result as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("ERROR: code=${err.code}, msg=${err.message}")
        }

        // --- Test 3: Raw multiget with 1 href (bypass client parsing) ---
        println("\n--- Test 3: Raw multiget REPORT with 1 href ---")
        val rawSingle = rawMultiget(calendarUrl, listOf(allHrefs[0]))
        println("Raw response (first 2000 chars):")
        println(rawSingle?.take(2000))

        // --- Test 4: Raw multiget with 10 hrefs ---
        println("\n--- Test 4: Raw multiget REPORT with ${batch10.size} hrefs ---")
        val rawBatch10 = rawMultiget(calendarUrl, batch10)
        val rawBody = rawBatch10 ?: ""
        val hasCalendarData = rawBody.contains("calendar-data") && rawBody.contains("VCALENDAR")
        println("Response length: ${rawBody.length} chars")
        println("Contains calendar-data with VCALENDAR: $hasCalendarData")
        println("Raw response (first 2000 chars):")
        println(rawBody.take(2000))

        // --- Test 5: Individual GET for first event ---
        println("\n--- Test 5: Individual GET (client.fetchEvent) ---")
        val quirks = DefaultQuirks(serverUrl!!)
        val eventUrl = quirks.buildEventUrl(allHrefs[0], calendarUrl)
        println("URL: $eventUrl")
        val getResult = client.fetchEvent(eventUrl)
        println("Result: success=${getResult.isSuccess()}")
        if (getResult.isSuccess()) {
            val ev = getResult.getOrNull()!!
            println("  etag=${ev.etag} | data=${ev.icalData.take(200)}...")
        } else if (getResult.isError()) {
            val err = getResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("ERROR: code=${err.code}, msg=${err.message}")
        }

        // --- Test 6: Individual GET for 10 events (sequential) ---
        println("\n--- Test 6: Individual GET x${batch10.size} (sequential) ---")
        var getSuccessCount = 0
        var getFailCount = 0
        for (href in batch10) {
            val url = quirks.buildEventUrl(href, calendarUrl)
            val result = client.fetchEvent(url)
            if (result.isSuccess()) {
                getSuccessCount++
            } else {
                getFailCount++
                val err = result as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("  FAIL: $href → code=${err.code}, msg=${err.message}")
            }
        }
        println("Individual GET results: $getSuccessCount success, $getFailCount failed out of ${batch10.size}")

        // --- Summary ---
        println("\n=== BATCH SIZE EXPERIMENT SUMMARY ===")
        println("fetchEventsByHref(1 href):  ${if (single.isSuccess()) "${single.getOrNull()!!.size} events" else "FAILED"}")
        println("fetchEventsByHref(10 hrefs): ${if (batch10Result.isSuccess()) "${batch10Result.getOrNull()!!.size} events" else "FAILED"}")
        println("Raw multiget (1 href):      ${if (rawSingle?.contains("VCALENDAR") == true) "HAS calendar-data" else "EMPTY/FAILED"}")
        println("Raw multiget (10 hrefs):    ${if (hasCalendarData) "HAS calendar-data" else "EMPTY/FAILED"}")
        println("Individual GET (1):         ${if (getResult.isSuccess()) "SUCCESS" else "FAILED"}")
        println("Individual GET (10):        $getSuccessCount/${batch10.size} success")
    }

    /**
     * Test push operations (CREATE → UPDATE → DELETE) against live Zoho.
     * Diagnoses etag handling: Zoho may not return ETag in PUT response header,
     * requiring PROPFIND fallback or multiget to retrieve the etag.
     */
    @Test
    fun `push cycle - create update delete`() = runBlocking {
        println("\n=== Push Cycle: CREATE → UPDATE → DELETE ===")

        // Discovery
        val principalUrl = client.discoverPrincipal(serverUrl!!).getOrNull()!!
        val homeUrl = client.discoverCalendarHome(principalUrl).getOrNull()!!.first()
        val calendars = client.listCalendars(homeUrl).getOrNull()!!
        assumeTrue("No calendars found", calendars.isNotEmpty())
        val calendarUrl = calendars[0].url
        println("Calendar: ${calendars[0].displayName} at $calendarUrl")

        val testUid = "kashcal-push-test-${System.currentTimeMillis()}@zoho.com"
        val icalCreate = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Push Test//EN
            BEGIN:VEVENT
            UID:$testUid
            DTSTAMP:20260213T120000Z
            DTSTART:20260215T100000Z
            DTEND:20260215T110000Z
            SUMMARY:KashCal Push Test - Create
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // --- Step 1: CREATE ---
        println("\n--- Step 1: CREATE (PUT If-None-Match: *) ---")
        val createResult = client.createEvent(calendarUrl, testUid, icalCreate)
        println("Result: success=${createResult.isSuccess()}")
        if (createResult.isError()) {
            val err = createResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
            println("CREATE ERROR: code=${err.code}, msg=${err.message}")
            return@runBlocking
        }
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        println("  url=$eventUrl")
        println("  etag='$createEtag' (empty=${createEtag.isEmpty()})")

        try {
            // --- Step 1b: Fetch etag via PROPFIND ---
            println("\n--- Step 1b: Fetch ETag via PROPFIND ---")
            val propfindEtag = client.fetchEtag(eventUrl)
            println("PROPFIND result: success=${propfindEtag.isSuccess()}")
            val fetchedEtag = if (propfindEtag.isSuccess()) {
                val etag = propfindEtag.getOrNull()
                println("  etag='$etag'")
                etag
            } else {
                val err = propfindEtag as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("  PROPFIND ERROR: code=${err.code}, msg=${err.message}")
                null
            }

            // --- Step 1c: Fetch etag via single-href multiget ---
            println("\n--- Step 1c: Fetch ETag via single-href multiget ---")
            val href = eventUrl.removePrefix("https://calendar.zoho.com")
            val multigetResult = client.fetchEventsByHref(calendarUrl, listOf(href))
            val multigetEtag = if (multigetResult.isSuccess() && multigetResult.getOrNull()!!.isNotEmpty()) {
                val etag = multigetResult.getOrNull()!![0].etag
                println("  etag='$etag'")
                etag
            } else {
                println("  multiget returned empty or error")
                null
            }

            // --- Step 1d: Raw PROPFIND to see full response ---
            println("\n--- Step 1d: Raw PROPFIND response ---")
            val rawPropfind = rawPropfind(eventUrl)
            println("  Raw response: ${rawPropfind?.take(1000)}")

            // Use the best available etag
            val bestEtag = createEtag.ifEmpty { fetchedEtag ?: multigetEtag ?: "" }
            val etagSource = when {
                createEtag.isNotEmpty() -> "PUT response"
                fetchedEtag != null -> "PROPFIND"
                multigetEtag != null -> "multiget"
                else -> "NONE"
            }
            println("\n  Best available etag: '$bestEtag' (source=$etagSource)")

            // --- Step 2: UPDATE with best etag ---
            val icalUpdate = icalCreate.replace(
                "SUMMARY:KashCal Push Test - Create",
                "SUMMARY:KashCal Push Test - Updated"
            ).replace(
                "DTEND:20260215T110000Z",
                "DTEND:20260215T120000Z"
            )
            println("\n--- Step 2: UPDATE (PUT If-Match: \"$bestEtag\") ---")
            val updateResult = client.updateEvent(eventUrl, icalUpdate, bestEtag)
            println("Result: success=${updateResult.isSuccess()}")

            var deleteEtag = bestEtag
            if (updateResult.isSuccess()) {
                deleteEtag = updateResult.getOrNull()!!
                println("  newEtag='$deleteEtag'")
            } else {
                val err = updateResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("UPDATE ERROR: code=${err.code}, msg=${err.message}")

                // --- Step 2b: Try raw PUT without If-Match ---
                println("\n--- Step 2b: Raw PUT without If-Match ---")
                val rawStatus = rawPut(eventUrl, icalUpdate)
                println("  Raw PUT status: $rawStatus")
                if (rawStatus in 200..299) {
                    // Fetch new etag after raw update
                    val newEtagResult = client.fetchEtag(eventUrl)
                    if (newEtagResult.isSuccess() && newEtagResult.getOrNull() != null) {
                        deleteEtag = newEtagResult.getOrNull()!!
                        println("  New etag after raw update: '$deleteEtag'")
                    }
                }
            }

            // --- Step 3: DELETE ---
            println("\n--- Step 3: DELETE (If-Match: \"$deleteEtag\") ---")
            val deleteResult = client.deleteEvent(eventUrl, deleteEtag)
            println("Result: success=${deleteResult.isSuccess()}")
            if (deleteResult.isError()) {
                val err = deleteResult as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("DELETE ERROR: code=${err.code}, msg=${err.message}")
                // Try raw DELETE without If-Match
                println("\n--- Step 3b: Raw DELETE without If-Match ---")
                val rawDeleteStatus = rawDelete(eventUrl)
                println("  Raw DELETE status: $rawDeleteStatus")
            }

            // --- Summary ---
            println("\n=== PUSH CYCLE SUMMARY ===")
            println("CREATE (PUT):            ${if (createResult.isSuccess()) "SUCCESS" else "FAILED"}")
            println("  ETag in PUT response:  ${createEtag.ifEmpty { "MISSING" }}")
            println("  ETag via PROPFIND:     ${fetchedEtag ?: "FAILED"}")
            println("  ETag via multiget:     ${multigetEtag ?: "FAILED"}")
            println("  Best etag source:      $etagSource")
            println("UPDATE (PUT If-Match):   ${if (updateResult.isSuccess()) "SUCCESS" else "FAILED (${(updateResult as? org.onekash.kashcal.sync.client.model.CalDavResult.Error)?.code})"}")
            println("DELETE:                  ${if (deleteResult.isSuccess()) "SUCCESS" else "FAILED"}")
        } catch (e: Exception) {
            println("\nException: ${e.message}")
            println("Attempting cleanup...")
            try { rawDelete(eventUrl) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Test whether the empty-etag update flow works after the 201 fix.
     *
     * Simulates real app flow:
     *   1. CREATE → etag="" (Zoho returns no ETag, PROPFIND 501)
     *   2. UPDATE with If-Match: "" → does Zoho accept?
     *   3. UPDATE again with If-Match: "" → still works?
     *   4. Verify data was actually updated on server via single-href multiget
     *   5. DELETE cleanup
     *
     * If this passes, Bug 2 (ETag fallback chain) is cosmetic, not functional.
     */
    @Test
    fun `empty etag update flow - is Bug 2 needed`() = runBlocking {
        println("\n=== Empty ETag Update Flow ===")

        // Discovery
        val principalUrl = client.discoverPrincipal(serverUrl!!).getOrNull()!!
        val homeUrl = client.discoverCalendarHome(principalUrl).getOrNull()!!.first()
        val calendars = client.listCalendars(homeUrl).getOrNull()!!
        assumeTrue("No calendars found", calendars.isNotEmpty())
        val calendarUrl = calendars[0].url
        println("Calendar: ${calendars[0].displayName} at $calendarUrl")

        val testUid = "kashcal-etag-test-${System.currentTimeMillis()}@zoho.com"
        val icalV1 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//ETag Test//EN
            BEGIN:VEVENT
            UID:$testUid
            DTSTAMP:20260213T120000Z
            DTSTART:20260215T100000Z
            DTEND:20260215T110000Z
            SUMMARY:ETag Test v1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // --- Step 1: CREATE ---
        println("\n--- Step 1: CREATE ---")
        val createResult = client.createEvent(calendarUrl, testUid, icalV1)
        assert(createResult.isSuccess()) { "CREATE failed" }
        val (eventUrl, createEtag) = createResult.getOrNull()!!
        println("  url=$eventUrl")
        println("  etag='$createEtag' (empty=${createEtag.isEmpty()})")

        try {
            // --- Step 2: UPDATE with empty etag (simulates real app flow) ---
            val emptyEtag = ""  // What KashCal stores when PROPFIND fails
            val icalV2 = icalV1.replace("SUMMARY:ETag Test v1", "SUMMARY:ETag Test v2")
            println("\n--- Step 2: UPDATE with If-Match: \"\" (empty etag) ---")
            val update1 = client.updateEvent(eventUrl, icalV2, emptyEtag)
            println("  Result: success=${update1.isSuccess()}")
            if (update1.isError()) {
                val err = update1 as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("  ERROR: code=${err.code}, msg=${err.message}")
            } else {
                println("  newEtag='${update1.getOrNull()}'")
            }

            // --- Step 3: UPDATE again with empty etag (rapid edits) ---
            val icalV3 = icalV1.replace("SUMMARY:ETag Test v1", "SUMMARY:ETag Test v3")
            println("\n--- Step 3: Second UPDATE with If-Match: \"\" ---")
            val update2 = client.updateEvent(eventUrl, icalV3, emptyEtag)
            println("  Result: success=${update2.isSuccess()}")
            if (update2.isError()) {
                val err = update2 as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                println("  ERROR: code=${err.code}, msg=${err.message}")
            } else {
                println("  newEtag='${update2.getOrNull()}'")
            }

            // --- Step 4: Verify data on server via single-href multiget ---
            println("\n--- Step 4: Verify server data via single-href multiget ---")
            val href = eventUrl.removePrefix("https://calendar.zoho.com")
            val verifyResult = client.fetchEventsByHref(calendarUrl, listOf(href))
            if (verifyResult.isSuccess() && verifyResult.getOrNull()!!.isNotEmpty()) {
                val serverEvent = verifyResult.getOrNull()!![0]
                val summaryMatch = Regex("SUMMARY:(.+)").find(serverEvent.icalData)
                println("  Server SUMMARY: ${summaryMatch?.groupValues?.get(1)}")
                println("  Server etag: ${serverEvent.etag}")
                val dataMatchesV3 = serverEvent.icalData.contains("ETag Test v3")
                println("  Data matches v3: $dataMatchesV3")
            } else {
                println("  Could not verify - multiget returned empty or error")
            }

            // --- Step 5: UPDATE with real etag (for comparison) ---
            println("\n--- Step 5: UPDATE with real etag from multiget ---")
            val realEtag = if (verifyResult.isSuccess() && verifyResult.getOrNull()!!.isNotEmpty()) {
                verifyResult.getOrNull()!![0].etag
            } else null
            if (realEtag != null) {
                val icalV4 = icalV1.replace("SUMMARY:ETag Test v1", "SUMMARY:ETag Test v4 (real etag)")
                val update3 = client.updateEvent(eventUrl, icalV4, realEtag)
                println("  Result: success=${update3.isSuccess()}")
                if (update3.isError()) {
                    val err = update3 as org.onekash.kashcal.sync.client.model.CalDavResult.Error
                    println("  ERROR: code=${err.code}, msg=${err.message}")
                } else {
                    println("  newEtag='${update3.getOrNull()}'")
                }
            } else {
                println("  Skipped - no real etag available")
            }

            // --- Summary ---
            println("\n=== EMPTY ETAG FLOW SUMMARY ===")
            println("CREATE:                      ${if (createResult.isSuccess()) "SUCCESS" else "FAILED"}")
            println("  etag from create:          '${createEtag.ifEmpty { "(empty)" }}'")
            println("UPDATE with empty etag (#1): ${if (update1.isSuccess()) "SUCCESS" else "FAILED"}")
            println("UPDATE with empty etag (#2): ${if (update2.isSuccess()) "SUCCESS" else "FAILED"}")
            println("UPDATE with real etag:       ${if (realEtag != null) { val r = client.updateEvent(eventUrl, icalV1, realEtag); if (r.isSuccess()) "N/A (tested in step 5)" else "N/A" } else "SKIPPED"}")
            println()
            println("CONCLUSION: ${if (update1.isSuccess() && update2.isSuccess()) "Bug 2 is NOT needed — empty etag works on Zoho" else "Bug 2 IS needed — empty etag fails"}")

        } finally {
            // Cleanup
            println("\n--- Cleanup: DELETE ---")
            val deleteResult = client.deleteEvent(eventUrl, "")
            if (deleteResult.isError()) {
                rawDelete(eventUrl)
            }
            println("  Cleaned up test event")
        }
    }

    private fun rawPropfind(eventUrl: String): String? {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:getetag/>
                </d:prop>
            </d:propfind>
        """.trimIndent()
        val httpClient = OkHttpClient.Builder()
            .authenticator { _, response ->
                val credential = OkCredentials.basic(username!!, password!!)
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        val request = Request.Builder()
            .url(eventUrl)
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            println("  PROPFIND HTTP status: ${response.code}")
            response.body?.string()
        }
    }

    private fun rawPut(eventUrl: String, icalData: String): Int {
        val httpClient = OkHttpClient.Builder()
            .authenticator { _, response ->
                val credential = OkCredentials.basic(username!!, password!!)
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        val request = Request.Builder()
            .url(eventUrl)
            .put(icalData.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .build()
        return httpClient.newCall(request).execute().use { it.code }
    }

    private fun rawDelete(eventUrl: String): Int {
        val httpClient = OkHttpClient.Builder()
            .authenticator { _, response ->
                val credential = OkCredentials.basic(username!!, password!!)
                response.request.newBuilder().header("Authorization", credential).build()
            }
            .build()
        val request = Request.Builder().url(eventUrl).delete().build()
        return httpClient.newCall(request).execute().use { it.code }
    }

    private fun rawMultiget(calendarUrl: String, hrefs: List<String>): String? {
        val hrefElements = hrefs.joinToString("\n") { "<d:href>$it</d:href>" }
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                    <c:calendar-data/>
                </d:prop>
                $hrefElements
            </c:calendar-multiget>
        """.trimIndent()

        val httpClient = OkHttpClient.Builder()
            .authenticator { _, response ->
                val credential = OkCredentials.basic(username!!, password!!)
                response.request.newBuilder()
                    .header("Authorization", credential)
                    .build()
            }
            .build()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            println("Raw HTTP status: ${response.code}")
            response.body?.string()
        }
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )

        for (path in possiblePaths) {
            val propsFile = File(path)
            if (propsFile.exists()) {
                propsFile.readLines().forEach { line ->
                    val parts = line.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            "ZOHO_SERVER" -> serverUrl = parts[1]
                            "ZOHO_USERNAME" -> username = parts[1]
                            "ZOHO_PASSWORD" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null && serverUrl != null) break
            }
        }
    }
}