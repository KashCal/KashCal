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
 * Multi-server test to verify the PROPFIND Depth:1 hypothesis:
 *
 * - Servers WITH sync-token will never use the PROPFIND path (iCloud, Nextcloud, etc.)
 * - Servers WITHOUT sync-token (Purelymail, basic CalDAV) need PROPFIND for reliable etag listing
 *
 * This test checks each server's sync-token and ctag support to confirm
 * the PROPFIND fallback only activates for servers that need it.
 *
 * Run: ./gradlew testDebugUnitTest --tests "*PropfindHypothesisMultiServerTest*" -Pintegration
 */
class PropfindHypothesisMultiServerTest {

    private val factory = OkHttpCalDavClientFactory()

    data class ServerConfig(
        val name: String,
        val serverKey: String,
        val usernameKey: String,
        val passwordKey: String
    )

    private val servers = listOf(
        ServerConfig("iCloud", "ICLOUD_SERVER", "ICLOUD_USERNAME", "ICLOUD_PASSWORD"),
        ServerConfig("Nextcloud Remote", "NEXTCLOUD_REMOTE_SERVER", "NEXTCLOUD_REMOTE_USERNAME_1", "NEXTCLOUD_REMOTE_PASSWORD_1"),
        ServerConfig("Zoho", "ZOHO_SERVER", "ZOHO_USERNAME", "ZOHO_PASSWORD"),
        ServerConfig("Purelymail", "PURELYMAIL_SERVER", "PURELYMAIL_USERNAME", "PURELYMAIL_PASSWORD"),
        ServerConfig("Nextcloud Local", "NEXTCLOUD_SERVER", "NEXTCLOUD_USERNAME", "NEXTCLOUD_PASSWORD"),
        ServerConfig("Radicale", "RADICALE_SERVER", "RADICALE_USERNAME", "RADICALE_PASSWORD"),
        ServerConfig("Baikal", "BAIKAL_SERVER", "BAIKAL_USERNAME", "BAIKAL_PASSWORD"),
        ServerConfig("Stalwart", "STALWART_SERVER", "STALWART_USERNAME", "STALWART_PASSWORD"),
        ServerConfig("SoGo", "SOGO_SERVER", "SOGO_USERNAME", "SOGO_PASSWORD")
    )

    @Test
    fun `check sync-token and ctag support across all configured servers`() = runBlocking {
        val properties = loadProperties()
        assumeTrue("local.properties not found", properties != null)

        println("\n===== MULTI-SERVER SYNC CAPABILITY CHECK =====")
        println("Purpose: Verify which servers return sync-token (PROPFIND path not needed)")
        println("         vs which need PROPFIND Depth:1 fallback\n")
        println(String.format("%-20s %-12s %-12s %-10s %s", "Server", "sync-token", "ctag", "Calendars", "Notes"))
        println("-".repeat(90))

        var testedCount = 0

        for (server in servers) {
            val serverUrl = properties!!.getProperty(server.serverKey)
            val username = properties.getProperty(server.usernameKey)
            val password = properties.getProperty(server.passwordKey)

            if (serverUrl == null || username == null || password == null) {
                println(String.format("%-20s %-12s %-12s %-10s %s", server.name, "-", "-", "-", "credentials missing"))
                continue
            }

            val effectiveUrl = if (serverUrl.startsWith("http")) serverUrl else "https://$serverUrl"

            try {
                val quirks = DefaultQuirks(effectiveUrl)
                val client = factory.createClient(
                    Credentials(username = username, password = password, serverUrl = effectiveUrl),
                    quirks
                )

                // Discover calendars
                val principalResult = client.discoverPrincipal(effectiveUrl)
                if (principalResult.isError()) {
                    println(String.format("%-20s %-12s %-12s %-10s %s", server.name, "?", "?", "?", "principal failed: ${(principalResult as CalDavResult.Error).message}"))
                    continue
                }
                val principalUrl = principalResult.getOrNull()!!

                val homeResult = client.discoverCalendarHome(principalUrl)
                if (homeResult.isError()) {
                    println(String.format("%-20s %-12s %-12s %-10s %s", server.name, "?", "?", "?", "home failed: ${(homeResult as CalDavResult.Error).message}"))
                    continue
                }
                val homeUrls = homeResult.getOrNull()!!
                val calendarsResult = client.listCalendars(homeUrls.first())
                if (calendarsResult.isError()) {
                    println(String.format("%-20s %-12s %-12s %-10s %s", server.name, "?", "?", "?", "list failed: ${(calendarsResult as CalDavResult.Error).message}"))
                    continue
                }
                val calendars = calendarsResult.getOrNull()!!
                if (calendars.isEmpty()) {
                    println(String.format("%-20s %-12s %-12s %-10s %s", server.name, "?", "?", "0", "no calendars"))
                    continue
                }

                val calendarUrl = calendars[0].url

                // Check sync-token
                val syncTokenResult = client.getSyncToken(calendarUrl)
                val hasSyncToken = syncTokenResult.isSuccess() && syncTokenResult.getOrNull() != null
                val syncTokenDisplay = if (hasSyncToken) "YES" else "no"

                // Check ctag
                val ctagResult = client.getCtag(calendarUrl)
                val hasCtag = ctagResult.isSuccess()
                val ctagDisplay = if (hasCtag) "YES" else "no"

                val needsPropfind = !hasSyncToken
                val notes = if (needsPropfind) "** NEEDS PROPFIND FALLBACK **" else "incremental sync OK"

                println(String.format("%-20s %-12s %-12s %-10d %s", server.name, syncTokenDisplay, ctagDisplay, calendars.size, notes))
                testedCount++
            } catch (e: Exception) {
                println(String.format("%-20s %-12s %-12s %-10s %s", server.name, "ERR", "ERR", "?", e.message?.take(50) ?: e.javaClass.simpleName))
            }
        }

        println("-".repeat(90))
        println("\nTested $testedCount servers")
        println("Servers WITHOUT sync-token need PROPFIND Depth:1 for reliable etag listing")
        println("Servers WITH sync-token use sync-collection (PROPFIND path never reached)")
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