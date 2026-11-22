package org.onekash.kashcal.sync.integration.multiserver

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Checks live, on every configured server, that an events calendar whose path ends in `/Tasks/`
 * is listed by discovery (#421: Radicale users create calendars at `/<user>/Tasks/`).
 *
 * The test creates the calendar with MKCALENDAR at `<home>/Tasks/` with a VEVENT component set
 * and a unique display name, lists the home with the production [CalDavClient.listCalendars],
 * and finds the calendar by that display name. A server that files the new calendar under its
 * own id instead of the requested path is skipped, since its listing then holds no `/tasks/`
 * href to judge. A server that refuses the MKCALENDAR (iCloud and Zoho answer 403) is skipped.
 *
 * The calendar is deleted afterwards at the URL the listing gave it, or at the requested path
 * when the listing doesn't hold it; a failed delete is printed. Nextcloud moves a deleted
 * calendar to its trash bin, where it keeps the path and makes the next MKCALENDAR fail with
 * 405, so the delete sends `X-NC-CalDAV-No-Trashbin: 1`, which other servers ignore. On a cloud
 * server a collection already at `<home>/Tasks/` isn't this run's, so it is left alone and the
 * server is skipped; on a local Docker server it is deleted first.
 *
 * The calendar briefly exists on shared test accounts, so another class that snapshots a
 * server's calendar list in a parallel fork can see it appear or vanish. Run this class on its
 * own (`--tests '*MultiServerTasksPathCalendarTest*'`), not in a `*MultiServer*` sweep.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MultiServerTasksPathCalendarTest(
    private val config: CalDavServerConfig,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun servers(): List<Array<Any>> =
            CalDavServerConfig.allServers().map { arrayOf<Any>(it) }

        private val XML = "application/xml; charset=utf-8".toMediaType()

        private val PROPFIND_DEPTH0 = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent()

        private fun mkcalendarBody(displayName: String) = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:set><d:prop>
                    <d:displayname>$displayName</d:displayname>
                    <c:supported-calendar-component-set>
                        <c:comp name="VEVENT"/>
                    </c:supported-calendar-component-set>
                </d:prop></d:set>
            </c:mkcalendar>
        """.trimIndent()
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

    /**
     * Returns whether a collection exists at [url]. A 207 alone doesn't say so: Xandikos answers
     * a missing path with a response-level 404 and Zoho with a 200 propstat holding an empty
     * prop, so only a reply that reports the `collection` resourcetype counts.
     */
    private fun exists(raw: OkHttpClient, url: String): Boolean =
        raw.newCall(
            Request.Builder().url(url)
                .method("PROPFIND", PROPFIND_DEPTH0.toRequestBody(XML))
                .header("Depth", "0")
                .build(),
        ).execute().use { response ->
            response.code == 207 &&
                CollectionResourceTypeProof.parseCollections(response.body?.string().orEmpty())
                    .any { "collection" in it.resourceTypes }
        }

    private fun delete(raw: OkHttpClient, url: String): Int =
        raw.newCall(
            Request.Builder().url(url).delete()
                .header("X-NC-CalDAV-No-Trashbin", "1")
                .build(),
        ).execute().use { it.code }

    @Test
    fun `an events calendar at a Tasks path is listed`() = runBlocking {
        assumeTrue("${config.name}: no credentials in local.properties", client != null)
        val c = client!!
        val cr = creds!!
        assumeTrue(
            "${config.name}: server unreachable at ${cr.davEndpoint}",
            CalDavTestServerLoader.isServerReachable(cr.davEndpoint),
        )

        val root = if (config.usesWellKnownDiscovery) {
            c.discoverWellKnown(cr.serverUrl).getOrNull() ?: cr.serverUrl
        } else {
            cr.davEndpoint
        }
        val principal = c.discoverPrincipal(root).getOrNull()
        assumeTrue("${config.name}: no principal", principal != null)
        val home = (c.discoverCalendarHome(principal!!) as? CalDavResult.Success)?.data?.firstOrNull()
        assumeTrue("${config.name}: no calendar-home-set", home != null)

        val raw = CollectionResourceTypeProof.rawClient(cr.username, cr.password)
        val target = home!!.trimEnd('/') + "/Tasks/"

        // A collection left by an earlier run would make MKCALENDAR fail and the server skip.
        val local = CalDavTestServerLoader.isLocalServer(cr.davEndpoint)
        if (local && exists(raw, target)) delete(raw, target)
        val occupied = exists(raw, target)
        if (local) {
            assertTrue("${config.name}: <home>/Tasks/ is still occupied after a delete", !occupied)
        } else {
            assumeTrue("${config.name}: <home>/Tasks/ already holds a collection", !occupied)
        }

        val displayName = "KashCal tasks path ${UUID.randomUUID()}"
        val created = raw.newCall(
            Request.Builder().url(target)
                .method("MKCALENDAR", mkcalendarBody(displayName).toRequestBody(XML))
                .build(),
        ).execute().use { it.code }
        assumeTrue("${config.name}: MKCALENDAR at <home>/Tasks/ refused ($created)", created in 200..299)

        var listedUrl: String? = null
        try {
            val listed = c.listCalendars(home)
            assertTrue("${config.name}: listCalendars failed: $listed", listed is CalDavResult.Success)
            val calendars = (listed as CalDavResult.Success).data
            val probe = calendars.find { it.displayName == displayName }
            if (probe == null) {
                // Not found by name: either discovery dropped it (the bug), or the server filed it
                // elsewhere and renamed it. The raw listing tells the two apart.
                val body = CollectionResourceTypeProof.fetchRawPropfind(raw, home, PROPFIND_DEPTH0)
                assertNotNull("${config.name}: raw listing of the calendar home failed", body)
                val rows = CollectionResourceTypeProof.parseCollections(body!!)
                val onServer = rows.any { it.href.trimEnd('/').endsWith("/Tasks", ignoreCase = true) }
                assumeTrue("${config.name}: server did not keep the calendar at <home>/Tasks/", onServer)
            }
            assertNotNull(
                "${config.name}: the calendar at <home>/Tasks/ is missing from listCalendars",
                probe,
            )
            listedUrl = probe!!.url
            assumeTrue(
                "${config.name}: server relocated the collection to its own id",
                probe.href.trimEnd('/').endsWith("/tasks", ignoreCase = true),
            )
        } finally {
            val code = delete(raw, listedUrl ?: target)
            if (code !in 200..299) println("${config.name}: cleanup DELETE returned $code")
        }
    }
}
