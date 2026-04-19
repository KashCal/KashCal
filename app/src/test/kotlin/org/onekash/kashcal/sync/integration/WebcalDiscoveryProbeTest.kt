package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.onekash.kashcal.sync.integration.multiserver.CalDavServerConfig
import org.onekash.kashcal.sync.integration.multiserver.CalDavTestServerLoader
import java.util.concurrent.TimeUnit

/**
 * Live probe test for CS:subscribed (webcal) discovery feasibility.
 *
 * Tests two things per server:
 * 1. NEGATIVE: Does adding <cs:source/> to the PROPFIND body break existing calendar listing?
 * 2. POSITIVE: Does the server expose any CS:subscribed collections?
 *
 * This test does NOT modify any production code. It sends raw PROPFIND requests
 * with the proposed property and verifies the response is still parseable.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*WebcalDiscoveryProbeTest*"
 */
@RunWith(Parameterized::class)
class WebcalDiscoveryProbeTest(
    private val config: CalDavServerConfig
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun servers(): List<CalDavServerConfig> = CalDavServerConfig.allServers()

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        private val CURRENT_PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                        xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <ic:calendar-color/>
                    <cs:getctag/>
                    <d:current-user-privilege-set/>
                    <c:supported-calendar-component-set/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        private val MODIFIED_PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                        xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <ic:calendar-color/>
                    <cs:getctag/>
                    <d:current-user-privilege-set/>
                    <c:supported-calendar-component-set/>
                    <cs:source/>
                </d:prop>
            </d:propfind>
        """.trimIndent()
    }

    @Test
    fun `adding cs-source to PROPFIND does not break calendar listing`() = runBlocking {
        val (client, creds) = CalDavTestServerLoader.createClient(config) ?: run {
            assumeTrue("No credentials for ${config.name}", false)
            return@runBlocking
        }
        assumeTrue(
            "${config.name} server not reachable",
            CalDavTestServerLoader.isServerReachable(creds.serverUrl)
        )

        // Step 1: Discover calendar home using CalDavClient
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            val wellKnown = client.discoverWellKnown(creds.davEndpoint)
            if (wellKnown.isSuccess()) wellKnown.getOrNull()!! else creds.davEndpoint
        } else {
            creds.davEndpoint
        }

        val principal = client.discoverPrincipal(caldavUrl).getOrNull()
        assumeTrue("Could not discover principal on ${config.name}", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("Could not discover calendar home on ${config.name}", home != null)

        // Step 2: Get baseline calendar list via CalDavClient (current production code)
        val baselineResult = client.listCalendars(home!!)
        assert(baselineResult.isSuccess()) {
            "${config.name}: baseline listCalendars failed: $baselineResult"
        }
        val baselineCalendars = baselineResult.getOrNull()!!

        println("\n=== ${config.name}: Baseline (${baselineCalendars.size} calendars) ===")
        baselineCalendars.forEach { cal ->
            println("  - ${cal.displayName} | ${cal.url}")
        }

        // Step 3: Send raw PROPFIND with <cs:source/> added using OkHttp directly
        val okhttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .authenticator { _, response ->
                if (response.request.header("Authorization") != null) return@authenticator null
                response.request.newBuilder()
                    .header("Authorization", OkHttpCredentials.basic(creds.username, creds.password))
                    .build()
            }
            .build()

        // Send CURRENT body via raw OkHttp (to validate our raw approach matches CalDavClient)
        val currentRawResponse = sendPropfind(okhttp, home, CURRENT_PROPFIND_BODY, creds)
        assert(currentRawResponse != null) {
            "${config.name}: Raw PROPFIND with CURRENT body failed — test setup issue"
        }

        // Send MODIFIED body via raw OkHttp
        val modifiedRawResponse = sendPropfind(okhttp, home, MODIFIED_PROPFIND_BODY, creds)
        assert(modifiedRawResponse != null) {
            "${config.name}: PROPFIND with <cs:source/> FAILED — this would BREAK discovery!"
        }

        // Step 4: Parse both raw responses with the same quirks
        val quirks = config.quirksFactory(creds.serverUrl)
        val baseHost = extractBaseHost(home)

        val currentParsed = quirks.extractCalendars(currentRawResponse!!, baseHost)
        val modifiedParsed = quirks.extractCalendars(modifiedRawResponse!!, baseHost)

        println("\n=== ${config.name}: Comparison ===")
        println("  Current PROPFIND:  ${currentParsed.size} calendars")
        println("  Modified PROPFIND: ${modifiedParsed.size} calendars")

        // Step 5: Calendar count must match
        assert(modifiedParsed.size == currentParsed.size) {
            "${config.name}: Calendar count CHANGED! Current: ${currentParsed.size}, Modified: ${modifiedParsed.size}. " +
                "Adding <cs:source/> altered the result — THIS IS A REGRESSION."
        }

        // Step 6: Same calendar hrefs in same order
        val currentHrefs = currentParsed.map { it.href }.sorted()
        val modifiedHrefs = modifiedParsed.map { it.href }.sorted()
        assert(currentHrefs == modifiedHrefs) {
            "${config.name}: Calendar hrefs CHANGED!\n  Current:  $currentHrefs\n  Modified: $modifiedHrefs"
        }

        // Step 7: Same display names
        val currentNames = currentParsed.map { it.displayName }.sorted()
        val modifiedNames = modifiedParsed.map { it.displayName }.sorted()
        assert(currentNames == modifiedNames) {
            "${config.name}: Calendar names CHANGED!\n  Current:  $currentNames\n  Modified: $modifiedNames"
        }

        // Step 8: Check for CS:subscribed/CS:source presence (informational)
        val hasSubscribed = modifiedRawResponse.contains("subscribed")
        val sourceMatches = Regex("<[^>]*:source[^>]*>.*?<[^>]*href[^>]*>(.+?)<", RegexOption.DOT_MATCHES_ALL)
            .findAll(modifiedRawResponse)
            .map { it.groupValues[1] }
            .toList()

        println("\n  Raw XML contains 'subscribed': $hasSubscribed")
        println("  CS:source URLs found: ${sourceMatches.size}")
        sourceMatches.forEach { url -> println("    → $url") }

        println("\n  RESULT: ${config.name} — SAFE (${currentParsed.size} calendars, no regression)")

        okhttp.dispatcher.executorService.shutdown()
        okhttp.connectionPool.evictAll()
    }

    private fun sendPropfind(
        okhttp: OkHttpClient,
        url: String,
        body: String,
        creds: org.onekash.kashcal.sync.integration.multiserver.ServerCredentials
    ): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "1")
                .header("Authorization", OkHttpCredentials.basic(creds.username, creds.password))
                .build()

            val response = okhttp.newCall(request).execute()
            if (response.code in 200..299 || response.code == 207) {
                response.body?.string()
            } else {
                println("  PROPFIND returned HTTP ${response.code}: ${response.body?.string()?.take(500)}")
                null
            }
        } catch (e: Exception) {
            println("  PROPFIND exception: ${e.message}")
            null
        }
    }

    private fun extractBaseHost(url: String): String {
        val parsed = java.net.URL(url)
        val port = if (parsed.port != -1 && parsed.port != parsed.defaultPort) ":${parsed.port}" else ""
        return "${parsed.protocol}://${parsed.host}$port"
    }
}
