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
import java.net.URI
import java.util.Properties

/**
 * Diagnostic test for Docker CalDAV servers (Radicale, Baikal, SOGo).
 * Tests each step of the pull flow individually, matching the app's
 * discovery logic: well-known → direct principal → path probing fallback.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*DockerServerDiagnosticTest*"
 */
class DockerServerDiagnosticTest {

    private val factory = OkHttpCalDavClientFactory()
    private val props = Properties()

    // Same paths as CalDavAccountDiscoveryService.KNOWN_CALDAV_PATHS
    private val knownCaldavPaths = listOf(
        "/dav/",              // Davis, generic sabre/dav
        "/remote.php/dav/",   // Nextcloud
        "/dav.php/",          // Baikal
        "/caldav",            // Zoho
        "/caldav/",           // Open-Xchange
        "/dav/cal/",          // Stalwart
        "/caldav.php/",
        "/cal.php/",
        "/SOGo/dav/"          // SOGo
    )

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

    private fun extractBaseHost(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }

    private suspend fun probeCaldavPaths(
        client: CalDavClient,
        baseUrl: String,
        triedUrl: String
    ): Pair<String, String>? {
        val baseHost = extractBaseHost(baseUrl)
        val triedNormalized = triedUrl.trimEnd('/')

        for (path in knownCaldavPaths) {
            val probeUrl = "$baseHost$path"
            if (probeUrl.trimEnd('/') == triedNormalized) continue

            println("  Probing: $probeUrl")
            val result = client.discoverPrincipal(probeUrl)
            if (result.isSuccess()) {
                val principalUrl = (result as CalDavResult.Success).data
                println("  Found CalDAV at $probeUrl (principal: $principalUrl)")
                return Pair(probeUrl, principalUrl)
            }
            if (result is CalDavResult.Error && result.isAuthError()) {
                println("  Auth error, stopping probe")
                return null
            }
        }
        return null
    }

    private fun diagnoseServer(
        serverName: String,
        serverUrl: String,
        username: String,
        password: String
    ) = runBlocking {
        val quirks = DefaultQuirks(serverUrl)
        val client = factory.createClient(
            Credentials(username = username, password = password, serverUrl = serverUrl),
            quirks
        )

        // Step 0: Well-known discovery (same as app)
        println("=== $serverName: Step 0 - discoverWellKnown ===")
        val wellKnownResult = client.discoverWellKnown(serverUrl)
        val discoveryUrl = if (wellKnownResult.isSuccess()) {
            println("Well-known: ${wellKnownResult.getOrNull()}")
            wellKnownResult.getOrNull()!!
        } else {
            println("Well-known not supported, using server URL: $serverUrl")
            serverUrl
        }

        // Step 1: Discover principal (with path probing fallback like the app)
        println("=== $serverName: Step 1 - discoverPrincipal ===")
        var principalResult = client.discoverPrincipal(discoveryUrl)
        var principalUrl: String? = null

        if (principalResult.isSuccess()) {
            principalUrl = principalResult.getOrNull()
            println("Principal: success=true, value=$principalUrl")
        } else {
            val err = principalResult as CalDavResult.Error
            println("Principal failed (code=${err.code}), trying path probing fallback...")
            val probeResult = probeCaldavPaths(client, serverUrl, discoveryUrl)
            if (probeResult != null) {
                principalUrl = probeResult.second
                println("Principal via probing: $principalUrl")
            } else {
                println("ERROR: All probes failed for $serverName")
                return@runBlocking
            }
        }

        println("=== $serverName: Step 2 - discoverCalendarHome ===")
        val homeResult = client.discoverCalendarHome(principalUrl!!)
        println("Home: success=${homeResult.isSuccess()}, value=${homeResult.getOrNull()}")
        if (homeResult.isError()) {
            val err = homeResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
            return@runBlocking
        }

        println("=== $serverName: Step 3 - listCalendars ===")
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
        assumeTrue("$serverName: no calendars", calendars.isNotEmpty())

        val cal = calendars.first()
        val calUrl = cal.url

        println("=== $serverName: Step 4 - getCtag ===")
        val ctagResult = client.getCtag(calUrl)
        println("Ctag: success=${ctagResult.isSuccess()}, value=${ctagResult.getOrNull()}")
        if (ctagResult.isError()) {
            val err = ctagResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
        }

        println("=== $serverName: Step 5 - fetchEtagsInRange ===")
        val now = System.currentTimeMillis()
        val startMs = now - (365L * 24 * 60 * 60 * 1000)
        val endMs = 4102444800000L  // Jan 1, 2100 (same as PullStrategy)
        val etagResult = client.fetchEtagsInRange(calUrl, startMs, endMs)
        println("fetchEtagsInRange: success=${etagResult.isSuccess()}")
        if (etagResult.isSuccess()) {
            val etags = etagResult.getOrNull()!!
            println("Found ${etags.size} etags")
            if (etags.isNotEmpty()) {
                etags.take(3).forEach { println("  - ${it.first}") }

                println("=== $serverName: Step 6 - fetchEventsByHref (1 href) ===")
                val singleResult = client.fetchEventsByHref(calUrl, listOf(etags[0].first))
                println("Single-href: success=${singleResult.isSuccess()}, events=${singleResult.getOrNull()?.size}")
                if (singleResult.isError()) {
                    val err = singleResult as CalDavResult.Error
                    println("ERROR: code=${err.code}, message=${err.message}")
                }

                if (etags.size > 1) {
                    println("=== $serverName: Step 7 - fetchEventsByHref (${etags.size} hrefs) ===")
                    val allResult = client.fetchEventsByHref(calUrl, etags.map { it.first })
                    println("All-href: success=${allResult.isSuccess()}, events=${allResult.getOrNull()?.size}")
                    if (allResult.isError()) {
                        val err = allResult as CalDavResult.Error
                        println("ERROR: code=${err.code}, message=${err.message}")
                    }
                }
            } else {
                println("No events on server (empty calendar)")
            }
        } else {
            val err = etagResult as CalDavResult.Error
            println("ERROR: code=${err.code}, message=${err.message}")
        }

        println("=== $serverName: DONE ===\n")
    }

    @Test
    fun `Radicale - full pull diagnosis`() {
        val server = props.getProperty("RADICALE_SERVER")
        val username = props.getProperty("RADICALE_USERNAME")
        val password = props.getProperty("RADICALE_PASSWORD")
        assumeTrue("Radicale credentials not configured",
            server != null && username != null && password != null)
        diagnoseServer("Radicale", server!!, username!!, password!!)
    }

    @Test
    fun `Baikal - full pull diagnosis`() {
        val server = props.getProperty("BAIKAL_SERVER")
        val username = props.getProperty("BAIKAL_USERNAME")
        val password = props.getProperty("BAIKAL_PASSWORD")
        assumeTrue("Baikal credentials not configured",
            server != null && username != null && password != null)
        diagnoseServer("Baikal", server!!, username!!, password!!)
    }

    @Test
    fun `Baikal Digest - full pull diagnosis`() {
        val server = props.getProperty("BAIKAL_DIGEST_SERVER")
        val username = props.getProperty("BAIKAL_DIGEST_USERNAME")
        val password = props.getProperty("BAIKAL_DIGEST_PASSWORD")
        assumeTrue("Baikal Digest credentials not configured",
            server != null && username != null && password != null)
        diagnoseServer("Baikal Digest", server!!, username!!, password!!)
    }

    @Test
    fun `SOGo - full pull diagnosis`() {
        val server = props.getProperty("SOGO_SERVER")
        val username = props.getProperty("SOGO_USERNAME")
        val password = props.getProperty("SOGO_PASSWORD")
        assumeTrue("SOGo credentials not configured",
            server != null && username != null && password != null)
        diagnoseServer("SOGo", server!!, username!!, password!!)
    }
}
