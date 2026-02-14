package org.onekash.kashcal.sync.integration

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.onekash.kashcal.sync.provider.caldav.CalDavAccountDiscoveryService
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.net.HttpURLConnection
import java.net.URL

/**
 * Integration test for Baikal well-known discovery flow.
 *
 * Tests the discoverCalendars() method of CalDavAccountDiscoveryService
 * against a real Baikal server to debug well-known discovery issues.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*BaikalWellKnownTest*"
 *
 * Prerequisites:
 * - Baikal server running at localhost:8081
 * - User testuser1 with password testpass1 created in Baikal
 *
 * Start Baikal:
 *   docker run -d --name baikal-caldav-test -p 8081:80 ckulka/baikal:nginx
 *   Then setup at http://localhost:8081/admin
 */
class BaikalWellKnownTest {

    private lateinit var discoveryService: CalDavAccountDiscoveryService
    private lateinit var clientFactory: OkHttpCalDavClientFactory
    private lateinit var client: CalDavClient

    // Mocked dependencies (we don't need DB operations for discovery-only test)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val calendarRepository: CalendarRepository = mockk(relaxed = true)

    // Test configuration
    private val serverUrl = "http://localhost:8081"
    private val username = "testuser1"
    private val password = "testpass1"

    @Before
    fun setup() {
        clientFactory = OkHttpCalDavClientFactory()

        // Create the discovery service with mocked repositories
        // We inject the real client factory
        discoveryService = CalDavAccountDiscoveryService(
            calDavClientFactory = clientFactory,
            accountRepository = accountRepository,
            calendarRepository = calendarRepository
        )

        // Also create a direct client for debugging individual steps
        val quirks = DefaultQuirks(serverUrl)
        val credentials = Credentials(
            username = username,
            password = password,
            serverUrl = serverUrl,
            trustInsecure = false
        )
        client = clientFactory.createClient(credentials, quirks)
    }

    private fun assumeServerAvailable() {
        try {
            val url = URL("$serverUrl/dav.php/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "OPTIONS"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val responseCode = connection.responseCode
            assumeTrue(
                "Baikal server not available (code: $responseCode)",
                responseCode in 200..299 || responseCode == 401
            )
        } catch (e: Exception) {
            assumeTrue("Baikal server not reachable: ${e.message}", false)
        }
    }

    // ========== WELL-KNOWN DISCOVERY TEST ==========

    @Test
    fun `test well-known discovery on Baikal`() = runBlocking {
        assumeServerAvailable()

        println("\n" + "=".repeat(60))
        println("BAIKAL WELL-KNOWN DISCOVERY TEST")
        println("=".repeat(60))
        println("Server URL: $serverUrl")
        println("Username: $username")
        println("=".repeat(60) + "\n")

        // Step 1: Test well-known directly with the client
        println("Step 1: Testing well-known endpoint directly...")
        val wellKnownResult = client.discoverWellKnown(serverUrl)
        println("Well-known result: $wellKnownResult")
        if (wellKnownResult.isSuccess()) {
            println("  - Discovered URL: ${wellKnownResult.getOrNull()}")
        } else if (wellKnownResult.isError()) {
            println("  - ERROR: ${wellKnownResult}")
        }

        println()

        // Step 2: Test principal discovery
        println("Step 2: Testing principal discovery...")
        // Baikal uses /dav.php/ as the DAV endpoint
        val davEndpoint = "$serverUrl/dav.php/"
        val principalResult = client.discoverPrincipal(davEndpoint)
        println("Principal result: $principalResult")
        if (principalResult.isSuccess()) {
            println("  - Principal URL: ${principalResult.getOrNull()}")
        } else if (principalResult.isError()) {
            println("  - ERROR: ${principalResult}")
        }

        println()

        // Step 3: Use the full discovery service
        println("Step 3: Testing full discoverCalendars() flow...")
        val discoveryResult = discoveryService.discoverCalendars(
            serverUrl = serverUrl,
            username = username,
            password = password,
            trustInsecure = false
        )

        println("Discovery result type: ${discoveryResult::class.simpleName}")

        when (discoveryResult) {
            is DiscoveryResult.CalendarsFound -> {
                println("SUCCESS - Calendars found!")
                println("  - Server URL: ${discoveryResult.serverUrl}")
                println("  - Principal URL: ${discoveryResult.principalUrl}")
                println("  - Calendar Home: ${discoveryResult.calendarHomeUrl}")
                println("  - Calendars discovered: ${discoveryResult.calendars.size}")
                discoveryResult.calendars.forEach { cal ->
                    println("    - ${cal.displayName}: ${cal.href}")
                }
            }
            is DiscoveryResult.AuthError -> {
                println("AUTH ERROR: ${discoveryResult.message}")
            }
            is DiscoveryResult.Error -> {
                println("ERROR: ${discoveryResult.message}")
            }
            is DiscoveryResult.Success -> {
                println("SUCCESS (account created): ${discoveryResult.account}")
            }
        }

        println("\n" + "=".repeat(60))
        println("WELL-KNOWN DISCOVERY TEST COMPLETE")
        println("=".repeat(60))
    }

    // ========== STEP-BY-STEP DEBUG TEST ==========

    @Test
    fun `debug step-by-step discovery`() = runBlocking {
        assumeServerAvailable()

        println("\n" + "=".repeat(60))
        println("STEP-BY-STEP DISCOVERY DEBUG")
        println("=".repeat(60) + "\n")

        // Test various URL formats that might be entered by users
        val testUrls = listOf(
            serverUrl,                          // http://localhost:8081
            "$serverUrl/",                      // http://localhost:8081/
            "$serverUrl/dav.php",              // http://localhost:8081/dav.php
            "$serverUrl/dav.php/",             // http://localhost:8081/dav.php/
        )

        for (testUrl in testUrls) {
            println("Testing URL: $testUrl")
            println("-".repeat(40))

            // Create fresh client for each URL
            val quirks = DefaultQuirks(testUrl)
            val credentials = Credentials(
                username = username,
                password = password,
                serverUrl = testUrl,
                trustInsecure = false
            )
            val testClient = clientFactory.createClient(credentials, quirks)

            // Try well-known
            val wellKnownResult = testClient.discoverWellKnown(testUrl)
            println("  well-known: ${if (wellKnownResult.isSuccess()) "OK - ${wellKnownResult.getOrNull()}" else "FAIL - $wellKnownResult"}")

            // Get the CalDAV URL to use for further discovery
            val caldavUrl = wellKnownResult.getOrNull() ?: testUrl

            // Try principal
            val principalResult = testClient.discoverPrincipal(caldavUrl)
            println("  principal: ${if (principalResult.isSuccess()) "OK - ${principalResult.getOrNull()}" else "FAIL - $principalResult"}")

            // If principal worked, try calendar home
            if (principalResult.isSuccess()) {
                val principalUrl = principalResult.getOrNull()!!
                val homeResult = testClient.discoverCalendarHome(principalUrl)
                println("  calendar-home: ${if (homeResult.isSuccess()) "OK - ${homeResult.getOrNull()}" else "FAIL - $homeResult"}")

                // If home worked, list calendars from first home set
                if (homeResult.isSuccess()) {
                    val homeUrl = homeResult.getOrNull()!!.first()
                    val calendarsResult = testClient.listCalendars(homeUrl)
                    if (calendarsResult.isSuccess()) {
                        val calendars = calendarsResult.getOrNull()!!
                        println("  calendars: OK - ${calendars.size} found")
                        calendars.forEach { cal ->
                            println("    - ${cal.displayName}: ${cal.url}")
                        }
                    } else {
                        println("  calendars: FAIL - $calendarsResult")
                    }
                }
            }

            println()
        }

        println("=".repeat(60))
        println("STEP-BY-STEP DEBUG COMPLETE")
        println("=".repeat(60))
    }

    // ========== RAW HTTP DEBUG TEST ==========

    @Test
    fun `debug raw well-known HTTP request`() = runBlocking {
        assumeServerAvailable()

        println("\n" + "=".repeat(60))
        println("RAW WELL-KNOWN HTTP DEBUG")
        println("=".repeat(60) + "\n")

        val wellKnownUrl = "$serverUrl/.well-known/caldav"
        println("Testing URL: $wellKnownUrl")

        try {
            val url = URL(wellKnownUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PROPFIND"
            connection.setRequestProperty("Depth", "0")
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")

            // Add basic auth
            val auth = java.util.Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray())
            connection.setRequestProperty("Authorization", "Basic $auth")

            // Send PROPFIND body
            connection.doOutput = true
            val propfindBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                    <d:prop>
                        <d:current-user-principal/>
                    </d:prop>
                </d:propfind>
            """.trimIndent()
            connection.outputStream.use { it.write(propfindBody.toByteArray()) }

            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false  // Don't auto-follow to see redirects

            val responseCode = connection.responseCode
            println("Response code: $responseCode")
            println("Response message: ${connection.responseMessage}")

            // Print headers
            println("Response headers:")
            connection.headerFields.forEach { (key, value) ->
                println("  $key: $value")
            }

            // Print body
            val responseBody = try {
                if (responseCode >= 400) {
                    connection.errorStream?.bufferedReader()?.readText() ?: "(no error body)"
                } else {
                    connection.inputStream.bufferedReader().readText()
                }
            } catch (e: Exception) {
                "(could not read body: ${e.message})"
            }
            println("Response body:")
            println(responseBody.take(2000))
            if (responseBody.length > 2000) {
                println("... (truncated)")
            }

        } catch (e: Exception) {
            println("Exception: ${e::class.simpleName} - ${e.message}")
            e.printStackTrace()
        }

        println("\n" + "=".repeat(60))
        println("RAW HTTP DEBUG COMPLETE")
        println("=".repeat(60))
    }
}
