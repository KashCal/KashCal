package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.onekash.kashcal.sync.client.OkHttpCalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File

/**
 * Test to validate iCloud eventual consistency hypothesis.
 *
 * Hypothesis: sync-collection returns hrefs before calendar-data server
 * has the actual data, causing fetchEventsByHref to return fewer events.
 *
 * Run with: ./gradlew testDebugUnitTest --tests "*ICloudEventualConsistencyTest*"
 */
@Ignore("Integration test - requires iCloud credentials in local.properties")
class ICloudEventualConsistencyTest {

    private lateinit var client: OkHttpCalDavClient
    private var username: String? = null
    private var password: String? = null
    private val serverUrl = "https://caldav.icloud.com"

    @Before
    fun setup() {
        val quirks = ICloudQuirks()
        client = OkHttpCalDavClient(quirks)
        loadCredentials()
        if (username != null && password != null) {
            client.setCredentials(username!!, password!!)
        }
    }

    private fun loadCredentials() {
        listOf("local.properties", "../local.properties", "/onekash/KashCal/local.properties")
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?.readLines()
            ?.forEach { line ->
                val parts = line.split("=").map { it.trim() }
                if (parts.size == 2) {
                    when {
                        parts[0].contains("username", ignoreCase = true) -> username = parts[1]
                        parts[0].contains("password", ignoreCase = true) &&
                            !parts[0].contains("keystore", ignoreCase = true) -> password = parts[1]
                    }
                }
            }
    }

    @Test
    fun `HYPOTHESIS - eventual consistency causes missing events`(): Unit = runBlocking {
        assumeTrue("Credentials required", username != null && password != null)

        println("=== EVENTUAL CONSISTENCY HYPOTHESIS TEST ===\n")

        // Step 1: Discover calendar
        val principal = client.discoverPrincipal(serverUrl).getOrNull()!!
        val home = client.discoverCalendarHome(principal).getOrNull()!!
        val calendars = client.listCalendars(home).getOrNull()!!
        val calendar = calendars.first { !it.url.contains("inbox") && !it.url.contains("outbox") }
        println("Calendar: ${calendar.displayName}")

        // Step 2: Get current sync token
        val syncToken = client.getSyncToken(calendar.url).getOrNull()
        assumeTrue("Need sync token", syncToken != null)
        println("Sync token: ${syncToken!!.take(50)}...")

        // Step 3: Create event via PUT
        val testUid = "eventual-consistency-test-${System.currentTimeMillis()}"
        val eventUrl = "${calendar.url.trimEnd('/')}/$testUid.ics"
        val createResult = client.createEvent(calendar.url, testUid, createTestIcal(testUid))
        assumeTrue("Event created", createResult.isSuccess())
        println("Created event: $testUid")

        // Step 4: IMMEDIATELY call sync-collection
        println("\nIMMEDIATELY calling sync-collection...")
        val syncResult = client.syncCollection(calendar.url, syncToken)
        assumeTrue("sync-collection succeeded", syncResult.isSuccess())

        val syncReport = (syncResult as CalDavResult.Success).data
        val changedHrefs = syncReport.changed.map { it.href }
        println("sync-collection returned ${changedHrefs.size} hrefs")
        changedHrefs.forEach { println("  - $it") }

        // Step 5: IMMEDIATELY call fetchEventsByHref
        println("\nIMMEDIATELY calling fetchEventsByHref...")
        val fetchResult = client.fetchEventsByHref(calendar.url, changedHrefs)
        assumeTrue("fetchEventsByHref succeeded", fetchResult.isSuccess())

        val events = (fetchResult as CalDavResult.Success).data
        println("fetchEventsByHref returned ${events.size} events")
        events.forEach { println("  - ${it.href}") }

        // Step 6: Compare!
        println("\n=== RESULT ===")
        println("Requested hrefs: ${changedHrefs.size}")
        println("Received events: ${events.size}")

        if (events.size < changedHrefs.size) {
            val receivedHrefs = events.map { it.href }.toSet()
            val missingHrefs = changedHrefs.filter { it !in receivedHrefs }
            println("\n*** HYPOTHESIS CONFIRMED! ***")
            println("Missing hrefs: $missingHrefs")
            println("\niCloud eventual consistency caused ${missingHrefs.size} events to be lost!")
        } else {
            println("\nHypothesis NOT confirmed in this run.")
            println("(May need multiple runs - eventual consistency is timing-dependent)")
        }

        // Cleanup
        client.deleteEvent(eventUrl, "")
    }

    private fun createTestIcal(uid: String): String {
        val now = System.currentTimeMillis()
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//KashCal//Test//EN
            BEGIN:VEVENT
            UID:$uid
            DTSTAMP:${formatUtc(now)}
            DTSTART:${formatUtc(now + 3600000)}
            DTEND:${formatUtc(now + 7200000)}
            SUMMARY:Eventual Consistency Test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    private fun formatUtc(millis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis
        return String.format("%04d%02d%02dT%02d%02d%02dZ",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND))
    }
}
