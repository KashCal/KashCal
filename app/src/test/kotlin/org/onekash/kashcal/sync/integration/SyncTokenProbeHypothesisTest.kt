package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import java.io.File

/**
 * Validate that getSyncToken() reliably distinguishes servers that need
 * PROPFIND Depth:1 from those that work fine with calendar-query.
 *
 * Option C hypothesis: Use getSyncToken() as a probe before pullFull().
 * - If probe returns a token → server supports sync-token → calendar-query works
 * - If probe returns null/error → server lacks sync-token → use PROPFIND Depth:1
 *
 * Expected results:
 * - Purelymail: getSyncToken() returns null → PROPFIND needed ✓
 * - iCloud, Zoho, Nextcloud: getSyncToken() returns token → calendar-query safe ✓
 *
 * Run: ./gradlew testDebugUnitTest --tests "*SyncTokenProbeHypothesisTest*" -Pintegration
 */
class SyncTokenProbeHypothesisTest {

    private val factory = OkHttpCalDavClientFactory()

    data class ServerConfig(
        val name: String,
        val serverKey: String,
        val usernameKey: String,
        val passwordKey: String,
        val expectSyncToken: Boolean? // true = should have token, false = should NOT, null = unknown
    )

    private val servers = listOf(
        ServerConfig("iCloud", "ICLOUD_SERVER", "ICLOUD_USERNAME", "ICLOUD_PASSWORD", expectSyncToken = true),
        ServerConfig("Zoho", "ZOHO_SERVER", "ZOHO_USERNAME", "ZOHO_PASSWORD", expectSyncToken = true),
        ServerConfig("Purelymail", "PURELYMAIL_SERVER", "PURELYMAIL_USERNAME", "PURELYMAIL_PASSWORD", expectSyncToken = false),
        ServerConfig("Nextcloud Remote", "NEXTCLOUD_REMOTE_SERVER", "NEXTCLOUD_REMOTE_USERNAME_1", "NEXTCLOUD_REMOTE_PASSWORD_1", expectSyncToken = true),
        // Local docker servers (may not be running)
        ServerConfig("Nextcloud Local", "NEXTCLOUD_SERVER", "NEXTCLOUD_USERNAME", "NEXTCLOUD_PASSWORD", expectSyncToken = true),
        ServerConfig("Radicale", "RADICALE_SERVER", "RADICALE_USERNAME", "RADICALE_PASSWORD", expectSyncToken = null),
        ServerConfig("Baikal", "BAIKAL_SERVER", "BAIKAL_USERNAME", "BAIKAL_PASSWORD", expectSyncToken = null),
        ServerConfig("Stalwart", "STALWART_SERVER", "STALWART_USERNAME", "STALWART_PASSWORD", expectSyncToken = null),
        ServerConfig("SoGo", "SOGO_SERVER", "SOGO_USERNAME", "SOGO_PASSWORD", expectSyncToken = null)
    )

    @Test
    fun `getSyncToken probe correctly identifies servers needing PROPFIND`() = runBlocking {
        val properties = loadProperties()
        assumeTrue("local.properties not found", properties != null)

        println("\n===== SYNC-TOKEN PROBE HYPOTHESIS TEST (Option C) =====")
        println("Purpose: Validate getSyncToken() as a reliable probe for PROPFIND routing")
        println("Logic: token returned → calendar-query safe; null/error → use PROPFIND Depth:1\n")
        println(String.format("%-20s %-15s %-15s %-10s %s", "Server", "Probe Result", "Route To", "Expected", "Verdict"))
        println("-".repeat(95))

        var testedCount = 0
        var passCount = 0
        var failCount = 0

        for (server in servers) {
            val serverUrl = properties!!.getProperty(server.serverKey)
            val username = properties.getProperty(server.usernameKey)
            val password = properties.getProperty(server.passwordKey)

            if (serverUrl == null || username == null || password == null) {
                println(String.format("%-20s %-15s %-15s %-10s %s", server.name, "-", "-", "-", "credentials missing"))
                continue
            }

            val effectiveUrl = if (serverUrl.startsWith("http")) serverUrl else "https://$serverUrl"

            try {
                val quirks = DefaultQuirks(effectiveUrl)
                val client = factory.createClient(
                    Credentials(username = username, password = password, serverUrl = effectiveUrl),
                    quirks
                )

                // Step 1: Discover a calendar URL
                val principalResult = client.discoverPrincipal(effectiveUrl)
                if (principalResult.isError()) {
                    println(String.format("%-20s %-15s %-15s %-10s %s", server.name, "?", "?", "?",
                        "principal failed: ${(principalResult as CalDavResult.Error).message.take(50)}"))
                    continue
                }
                val principalUrl = principalResult.getOrNull()!!

                val homeResult = client.discoverCalendarHome(principalUrl)
                if (homeResult.isError()) {
                    println(String.format("%-20s %-15s %-15s %-10s %s", server.name, "?", "?", "?",
                        "home failed: ${(homeResult as CalDavResult.Error).message.take(50)}"))
                    continue
                }
                val calendarsResult = client.listCalendars(homeResult.getOrNull()!!.first())
                if (calendarsResult.isError() || calendarsResult.getOrNull()!!.isEmpty()) {
                    println(String.format("%-20s %-15s %-15s %-10s %s", server.name, "?", "?", "?", "no calendars"))
                    continue
                }

                val calendarUrl = calendarsResult.getOrNull()!![0].url

                // Step 2: Probe getSyncToken — this is the Option C logic
                val tokenResult = client.getSyncToken(calendarUrl)
                val serverHasSyncToken = tokenResult.isSuccess() && tokenResult.getOrNull() != null
                val token = if (serverHasSyncToken) tokenResult.getOrNull()!!.take(30) + "..." else null

                val probeDisplay = if (serverHasSyncToken) "HAS TOKEN" else "NO TOKEN"
                val routeDisplay = if (serverHasSyncToken) "calendar-query" else "PROPFIND"

                // Step 3: Verify against expectation
                val verdict = when (server.expectSyncToken) {
                    true -> if (serverHasSyncToken) "PASS ✓" else "FAIL ✗"
                    false -> if (!serverHasSyncToken) "PASS ✓" else "FAIL ✗"
                    null -> "OK (no expectation)"
                }
                val expectedDisplay = when (server.expectSyncToken) {
                    true -> "HAS TOKEN"
                    false -> "NO TOKEN"
                    null -> "?"
                }

                if (verdict.startsWith("PASS") || verdict.startsWith("OK")) passCount++ else failCount++

                println(String.format("%-20s %-15s %-15s %-10s %s", server.name, probeDisplay, routeDisplay, expectedDisplay, verdict))
                if (token != null) {
                    println(String.format("%-20s   token: %s", "", token))
                }
                testedCount++
            } catch (e: Exception) {
                println(String.format("%-20s %-15s %-15s %-10s %s", server.name, "ERR", "?", "?",
                    e.message?.take(50) ?: e.javaClass.simpleName))
            }
        }

        println("-".repeat(95))
        println("\nTested: $testedCount servers | Pass: $passCount | Fail: $failCount")
        println("\nConclusion: ${if (failCount == 0) "getSyncToken() probe is RELIABLE for PROPFIND routing" else "HYPOTHESIS FAILED — probe is NOT reliable"}")
        println("If reliable: Option C (probe before pullFull) correctly routes PROPFIND only to servers that need it")
    }

    private fun loadProperties(): java.util.Properties? {
        val candidates = listOf(
            File("local.properties"),
            File("../local.properties"),
            File(System.getProperty("user.dir"), "local.properties")
        )
        val props = candidates.firstOrNull { it.exists() } ?: return null
        return java.util.Properties().apply { load(props.inputStream()) }
    }
}
