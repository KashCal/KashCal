package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.isCalendarGone
import org.onekash.kashcal.sync.provider.icloud.ICloudUrlNormalizer
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.util.UUID

/**
 * Proves live, across every configured server, the probe answers that decide whether a
 * calendar missing from a home-set listing is removed locally.
 *
 * Calendar refresh deletes a calendar the listing left out only when a direct probe of that
 * calendar's own URL says it is gone ([CalDavResult.RESOURCE_GONE_CODES]: 403, 404, 410) or is
 * no longer a calendar ([isCalendarGone]). That is only safe if real servers (a) answer "still a
 * calendar" for a live calendar, and (b) answer with one of those codes, or a reply the probe
 * reads as "nothing is there", for a calendar that doesn't exist. If a server said anything else
 * for (b), its deleted calendars would silently stay on the phone, so this test pins it with
 * the same rule refresh uses.
 *
 * Cloud accounts are only read, unless named in `KASHCAL_CLOUD_CALENDAR_WRITES`. On local
 * servers, and on those opted-in cloud accounts, the test also creates a calendar, deletes it,
 * and probes the deleted URL.
 *
 * Skips (never fails) servers without credentials, unreachable ones, and ones without CalDAV.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest -Pintegration \
 *       --tests '*MultiServerCalendarRemovalProbeTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerCalendarRemovalProbeTest(
    private val config: CalDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

    }

    private var client: CalDavClient? = null
    private var creds: ServerCredentials? = null

    @Before
    fun setup() {
        CalDavTestServerLoader.createClient(config)?.let {
            client = it.first
            creds = it.second
        }
    }

    private fun assumeReady() {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        assumeTrue(
            "${config.name}: server unreachable at ${creds!!.davEndpoint}",
            CalDavTestServerLoader.isServerReachable(creds!!.davEndpoint),
        )
    }

    private suspend fun calendarHome(): String {
        val c = client!!
        val cr = creds!!
        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull()
        assumeTrue("${config.name}: no principal (CalDAV likely unsupported)", principal != null)
        val home = (c.discoverCalendarHome(principal!!) as? CalDavResult.Success)?.data?.firstOrNull()
        assumeTrue("${config.name}: no calendar-home-set", home != null)
        return if (home!!.endsWith("/")) home else "$home/"
    }

    private fun describe(result: CalDavResult<Boolean>): String = when (result) {
        is CalDavResult.Success -> "Success(${result.data})"
        is CalDavResult.Error -> "Error(${result.code}: ${CollectionResourceTypeProof.redactPii(result.message)})"
    }

    private fun cloudCalendarWrites(): Set<String> =
        System.getenv("KASHCAL_CLOUD_CALENDAR_WRITES").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun isLocalServer(): Boolean {
        val host = runCatching { URI(creds!!.davEndpoint).host }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") || host.startsWith("192.168.")
    }

    @Test
    fun `every listed calendar probes as still a calendar`() = runBlocking {
        assumeReady()
        val home = calendarHome()
        val calendars = (client!!.listCalendars(home) as? CalDavResult.Success)?.data.orEmpty()
        assumeTrue("${config.name}: no calendars listed", calendars.isNotEmpty())

        // Every calendar a refresh would keep must probe as a calendar, or a short listing
        // could remove it. Read-only probes only: nothing on the account changes.
        for ((i, calendar) in calendars.withIndex()) {
            val result = client!!.probeCalendarCollection(calendar.url)
            println("${config.name}: listed calendar $i -> ${describe(result)}")
            assertEquals("${config.name}: listed calendar $i must probe true", CalDavResult.Success(true), result)

            if (config.name.equals("iCloud", ignoreCase = true)) {
                val canonical = ICloudUrlNormalizer.normalize(calendar.url)!!
                if (i == 0) printRedirectHost(canonical)
                val canonicalResult = client!!.probeCalendarCollection(canonical)
                println("${config.name}: listed calendar $i (canonical host) -> ${describe(canonicalResult)}")
                assertEquals(
                    "${config.name}: listed calendar $i at its stored canonical-host URL must also probe true",
                    CalDavResult.Success(true),
                    canonicalResult,
                )
            }
        }
    }

    @Test
    fun `never-existing calendar URL probes as gone`() = runBlocking {
        assumeReady()
        val missing = calendarHome() + "kashcal-missing-${UUID.randomUUID()}/"

        val result = client!!.probeCalendarCollection(missing)
        println("${config.name}: missing calendar -> ${describe(result)}")
        assertTrue(
            "${config.name}: a missing calendar must answer 403/404/410 or not-a-calendar, got ${describe(result)}",
            isCalendarGone(result),
        )
    }

    @Test
    fun `deleted calendar URL probes as gone`() = runBlocking {
        assumeReady()
        // Cloud accounts are real user accounts: a throwaway calendar is created there only for
        // servers named in KASHCAL_CLOUD_CALENDAR_WRITES (e.g. "Zoho,Mailbox"), and deleted again.
        assumeTrue(
            "${config.name}: cloud account stays read-only (set KASHCAL_CLOUD_CALENDAR_WRITES to opt in)",
            isLocalServer() || config.name in cloudCalendarWrites(),
        )
        val home = calendarHome()
        // What the account holds before this test touches it; checked again at the end.
        val before = (client!!.listCalendars(home) as? CalDavResult.Success)?.data.orEmpty()
        val probeId = UUID.randomUUID().toString()
        val requestedUrl = home + "kashcal-removal-probe-$probeId/"
        val displayName = "KashCal removal probe $probeId"
        val http = CollectionResourceTypeProof.rawClient(creds!!.username, creds!!.password)

        val mkcalendar = """
            <?xml version="1.0" encoding="UTF-8"?>
            <c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:set>
                    <d:prop>
                        <d:displayname>$displayName</d:displayname>
                        <c:supported-calendar-component-set>
                            <c:comp name="VEVENT"/>
                        </c:supported-calendar-component-set>
                    </d:prop>
                </d:set>
            </c:mkcalendar>
        """.trimIndent()
        val created = http.newCall(
            Request.Builder().url(requestedUrl)
                .method("MKCALENDAR", mkcalendar.toRequestBody("application/xml".toMediaType()))
                .build()
        ).execute().use { it.code }
        assumeTrue("${config.name}: MKCALENDAR not supported ($created)", created in 200..299)

        // The app only ever knows a calendar by the URL a listing gives it. Some servers
        // (Open-Xchange/Mailbox) file a new calendar under their own id rather than the URL
        // it was created at, so look it up by its unique name the way a refresh would.
        val calendarUrl = client!!.listCalendars(home).getOrNull().orEmpty()
            .firstOrNull { it.displayName == displayName }?.url ?: requestedUrl
        println("${config.name}: created calendar listed at the requested URL: ${calendarUrl == requestedUrl}")

        try {
            val beforeDelete = client!!.probeCalendarCollection(calendarUrl)
            println("${config.name}: created calendar -> ${describe(beforeDelete)}")
            assertEquals("${config.name}: created calendar must probe true", CalDavResult.Success(true), beforeDelete)
        } finally {
            // Delete at the host the server itself reported: iCloud answers 204 to a calendar
            // DELETE on the canonical host (the form the app stores) without deleting anything.
            val deleteUrl = serverReportedUrl(calendarUrl)
            val deleted = http.newCall(Request.Builder().url(deleteUrl).delete().build()).execute().use { it.code }
            println("${config.name}: DELETE -> $deleted")
            assertTrue("${config.name}: DELETE of the probe calendar failed ($deleted)", deleted in 200..299)
        }

        var afterDelete = client!!.probeCalendarCollection(calendarUrl)
        for (i in 0 until 6) {
            if (isCalendarGone(afterDelete)) break
            Thread.sleep(5_000)
            afterDelete = client!!.probeCalendarCollection(calendarUrl)
        }
        println("${config.name}: deleted calendar -> ${describe(afterDelete)}")

        // The account's own calendars are exactly as they were: all still listed, same names.
        val after = (client!!.listCalendars(home) as? CalDavResult.Success)?.data.orEmpty()
        val afterByUrl = after.associateBy { it.url }
        for ((i, cal) in before.withIndex()) {
            val now = afterByUrl[cal.url]
            assertTrue("${config.name}: existing calendar $i is no longer listed", now != null)
            assertEquals("${config.name}: existing calendar $i changed its name", cal.displayName, now!!.displayName)
            println("${config.name}: existing calendar $i unchanged, ctag same=${cal.ctag == now.ctag}")
        }
        assertTrue("${config.name}: the throwaway calendar is still listed", after.none { it.displayName == displayName })
        assertTrue(
            "${config.name}: a deleted calendar must answer 403/404/410 or not-a-calendar, got ${describe(afterDelete)}",
            isCalendarGone(afterDelete),
        )
    }

    /**
     * Returns [url] on the host the server reports for the calendar home. For iCloud that is the
     * account's partition (pNN-caldav.icloud.com), which the app normalizes away; for other
     * servers, or when the lookup fails, [url] comes back unchanged.
     */
    private fun serverReportedUrl(url: String): String {
        if (!config.name.equals("iCloud", ignoreCase = true)) return url
        val http = CollectionResourceTypeProof.rawClient(creds!!.username, creds!!.password)
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"""
        val principal = runBlocking { client!!.discoverPrincipal(creds!!.davEndpoint).getOrNull() } ?: return url
        val reported = runCatching {
            http.newCall(
                Request.Builder().url(principal)
                    .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
                    .header("Depth", "0")
                    .build()
            ).execute().use { r ->
                Regex("""calendar-home-set[^>]*>\s*<[^>]*href[^>]*>\s*(https?://[^<\s]+)""").find(r.body?.string().orEmpty())?.groupValues?.get(1)
            }
        }.getOrNull() ?: return url
        val host = URI(reported).let { "${it.scheme}://${it.host}" }
        return url.replaceFirst(Regex("""^https?://[^/]+"""), host)
    }

    /** Prints where a canonical-host iCloud request ends up, to check the same-server rule. */
    private fun printRedirectHost(url: String) {
        val http = CollectionResourceTypeProof.rawClient(creds!!.username, creds!!.password)
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        runCatching {
            http.newCall(
                Request.Builder().url(url)
                    .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
                    .header("Depth", "0")
                    .build()
            ).execute().use { response ->
                val redirected = generateSequence(response.priorResponse) { it.priorResponse }.any { it.isRedirect }
                println("${config.name}: canonical probe redirected=$redirected finalHost=${response.request.url.host}")
            }
        }
    }
}
