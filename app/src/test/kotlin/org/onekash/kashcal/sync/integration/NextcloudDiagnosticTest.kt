package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File
import java.util.Properties

/**
 * Diagnostic test to isolate Nextcloud 500 error.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*NextcloudDiagnosticTest*"
 */
class NextcloudDiagnosticTest {

    private val factory = OkHttpCalDavClientFactory()
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
    }

    @Test
    fun `step-by-step Nextcloud Remote pull diagnosis`() = runBlocking {
        val server = props.getProperty("NEXTCLOUD_REMOTE_SERVER")
        val username = props.getProperty("NEXTCLOUD_REMOTE_USERNAME_1")
        val password = props.getProperty("NEXTCLOUD_REMOTE_PASSWORD_1")
        assumeTrue("Nextcloud Remote credentials not configured",
            server != null && username != null && password != null)

        val quirks = DefaultQuirks(server!!)
        val client = factory.createClient(
            Credentials(username = username!!, password = password!!, serverUrl = server),
            quirks
        )
        val davEndpoint = "$server/remote.php/dav/"

        // Step 1: Discovery
        println("=== Step 1: discoverPrincipal ===")
        val principalResult = client.discoverPrincipal(davEndpoint)
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
        val calendarsResult = client.listCalendars(homeResult.getOrNull()!!)
        println("Calendars: success=${calendarsResult.isSuccess()}")
        if (calendarsResult.isError()) {
            val err = calendarsResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
            return@runBlocking
        }
        val calendars = calendarsResult.getOrNull()!!
        println("Found ${calendars.size} calendars:")
        calendars.forEach { println("  - ${it.displayName} at ${it.url}") }

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
        val endMs = 4102444800000L
        val etagResult = client.fetchEtagsInRange(calUrl, startMs, endMs)
        println("fetchEtagsInRange: success=${etagResult.isSuccess()}")
        if (etagResult.isSuccess()) {
            val etags = etagResult.getOrNull()!!
            println("Found ${etags.size} etags")
            etags.take(3).forEach { println("  - ${it.first} etag=${it.second?.take(20)}...") }
        } else {
            val err = etagResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
        }

        // Step 6: fetchEventsByHref
        if (etagResult.isSuccess() && etagResult.getOrNull()!!.isNotEmpty()) {
            val hrefs = etagResult.getOrNull()!!

            println("\n=== Step 6: fetchEventsByHref (1 href) ===")
            val singleResult = client.fetchEventsByHref(calUrl, listOf(hrefs[0].first))
            println("Single-href: success=${singleResult.isSuccess()}, events=${singleResult.getOrNull()?.size}")
            if (singleResult.isError()) {
                val err = singleResult as CalDavResult.Error
                println("ERROR: code=${err.code}, message=${err.message}")
            }

            if (hrefs.size > 1) {
                println("\n=== Step 7: fetchEventsByHref (all ${hrefs.size} hrefs) ===")
                val allHrefs = hrefs.map { it.first }
                val batchResult = client.fetchEventsByHref(calUrl, allHrefs)
                println("All-href batch: success=${batchResult.isSuccess()}, events=${batchResult.getOrNull()?.size}")
                if (batchResult.isError()) {
                    val err = batchResult as CalDavResult.Error
                    println("ERROR: code=${err.code}, message=${err.message}")
                }
            }
        }

        println("\n=== Diagnosis Complete ===")
    }
}
