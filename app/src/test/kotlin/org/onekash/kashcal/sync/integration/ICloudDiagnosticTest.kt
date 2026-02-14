package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import java.io.File
import java.util.Properties

/**
 * Diagnostic test to isolate iCloud 400 error.
 * Tests each CalDAV operation individually against real iCloud.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*ICloudDiagnosticTest*"
 */
class ICloudDiagnosticTest {

    private val factory = OkHttpCalDavClientFactory()
    private val quirks = ICloudQuirks()
    private lateinit var client: CalDavClient
    private val props = Properties()

    @Before
    fun setup() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )
        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                file.inputStream().use { props.load(it) }
                break
            }
        }
        val username = props.getProperty("ICLOUD_USERNAME")
        val password = props.getProperty("ICLOUD_PASSWORD")
            ?: props.getProperty("ICLOUD_APP_PASSWORD")
        assumeTrue("iCloud credentials not configured", username != null && password != null)
        client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = "https://caldav.icloud.com"),
            quirks
        )
    }

    @Test
    fun `step-by-step iCloud pull diagnosis`() = runBlocking {
        val serverUrl = "https://caldav.icloud.com"

        // Step 1: Discovery
        println("=== Step 1: discoverPrincipal ===")
        val principalResult = client.discoverPrincipal(serverUrl)
        println("Principal: success=${principalResult.isSuccess()}, value=${principalResult.getOrNull()}")
        if (principalResult.isError()) {
            val err = principalResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
            return@runBlocking
        }

        println("\n=== Step 2: discoverCalendarHome ===")
        val homeResult = client.discoverCalendarHome(principalResult.getOrNull()!!)
        println("Home: success=${homeResult.isSuccess()}, value=${homeResult.getOrNull()}")
        if (homeResult.isError()) {
            val err = homeResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
            return@runBlocking
        }

        println("\n=== Step 3: listCalendars ===")
        val calendarsResult = client.listCalendars(homeResult.getOrNull()!!.first())
        println("Calendars: success=${calendarsResult.isSuccess()}")
        if (calendarsResult.isError()) {
            val err = calendarsResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
            return@runBlocking
        }
        val calendars = calendarsResult.getOrNull()!!
        println("Found ${calendars.size} calendars:")
        calendars.forEach { println("  - ${it.displayName} at ${it.url}") }

        // Use first VEVENT calendar
        val cal = calendars.first()
        val calUrl = cal.url
        println("\nUsing calendar: ${cal.displayName} at $calUrl")

        // Step 4: getCtag
        println("\n=== Step 4: getCtag ===")
        val ctagResult = client.getCtag(calUrl)
        println("Ctag: success=${ctagResult.isSuccess()}, value=${ctagResult.getOrNull()}")
        if (ctagResult.isError()) {
            val err = ctagResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
        }

        // Step 5: fetchEtagsInRange
        println("\n=== Step 5: fetchEtagsInRange ===")
        val now = System.currentTimeMillis()
        val pastWindow = 365L * 24 * 60 * 60 * 1000
        val startMs = now - pastWindow
        val endMs = 4102444800000L  // Jan 1, 2100 UTC (same as PullStrategy.FUTURE_END_MS)
        val startDate = quirks.formatDateForQuery(startMs)
        val endDate = quirks.formatDateForQuery(endMs)
        println("Time range: $startDate to $endDate")

        val etagResult = client.fetchEtagsInRange(calUrl, startMs, endMs)
        println("fetchEtagsInRange: success=${etagResult.isSuccess()}")
        if (etagResult.isSuccess()) {
            val etags = etagResult.getOrNull()!!
            println("Found ${etags.size} etags")
            etags.take(3).forEach { println("  - ${it.first} etag=${it.second?.take(20)}...") }
        } else {
            val err = etagResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}, retryable=${err.isRetryable}")
        }

        // Step 6: fetchEventsByHref (single)
        if (etagResult.isSuccess() && etagResult.getOrNull()!!.isNotEmpty()) {
            println("\n=== Step 6: fetchEventsByHref (1 href) ===")
            val hrefs = etagResult.getOrNull()!!
            val singleResult = client.fetchEventsByHref(calUrl, listOf(hrefs[0].first))
            println("Single-href: success=${singleResult.isSuccess()}, events=${singleResult.getOrNull()?.size}")
            if (singleResult.isError()) {
                val err = singleResult as CalDavResult.Error
                println("ERROR: code=${err.code}, message=${err.message}")
            }

            // Step 7: fetchEventsByHref (batch of 50)
            println("\n=== Step 7: fetchEventsByHref (50 hrefs) ===")
            val batchHrefs = hrefs.take(50).map { it.first }
            val batchResult = client.fetchEventsByHref(calUrl, batchHrefs)
            println("50-href batch: success=${batchResult.isSuccess()}, events=${batchResult.getOrNull()?.size}")
            if (batchResult.isError()) {
                val err = batchResult as CalDavResult.Error
                println("ERROR: code=${err.code}, message=${err.message}")
            }
        }

        println("\n=== Diagnosis Complete ===")
    }
}