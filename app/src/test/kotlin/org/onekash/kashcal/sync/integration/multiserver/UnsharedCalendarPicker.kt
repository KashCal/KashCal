package org.onekash.kashcal.sync.integration.multiserver

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.DigestAuthenticator
import org.w3c.dom.Element

/**
 * Picks a calendar that live tests may write to without other people seeing the test events,
 * for the multi-server tests that create and edit events on real accounts
 * (MultiServerUnchangedSeriesReportTest, MultiServerRecentChangesTest).
 */
internal class UnsharedCalendarPicker(
    private val config: CalDavServerConfig,
    private val client: CalDavClient,
    private val creds: ServerCredentials,
) {
    /** Returns the first writable VEVENT calendar [isConfirmedUnshared] accepts, or null. */
    suspend fun find(): String? {
        val endpoint = creds.davEndpoint
        val caldavUrl = if (config.usesWellKnownDiscovery) {
            client.discoverWellKnown(endpoint).getOrNull() ?: endpoint
        } else endpoint
        val principal = client.discoverPrincipal(caldavUrl).getOrNull() ?: return null
        val home = client.discoverCalendarHome(principal).getOrNull()?.firstOrNull() ?: return null
        return client.listCalendars(home).getOrNull()
            ?.filter { !it.url.contains("inbox") && !it.url.contains("outbox") && !it.isReadOnly }
            ?.filter { it.supportedComponents.isEmpty() || "VEVENT" in it.supportedComponents }
            ?.firstOrNull { isConfirmedUnshared(it.url) }?.url
    }

    /**
     * Returns true only when the calendar can be written without other people seeing it.
     *
     * A local test server (localhost) has no other users. On a real account, any of these counts
     * as shared: a published calendar (`cs:publish-url`), one shared in (`cs:shared`, or a
     * `share-access` of read or read-write), or a sharee in a CalendarServer, WebDAV or ownCloud
     * invite. `shared-owner` alone doesn't count: SabreDAV and OX mark every owner calendar that
     * way. Absence isn't proof, since a server can share by ACL and report nothing, so a real
     * account also needs an invite answered with no sharees (OX), or must be iCloud, which
     * answers `cs:invite` on a calendar only once it is shared (seen 2026-10-05: the shared
     * calendar lists its sharees, the others 404 it). Any failure counts as shared.
     */
    fun isConfirmedUnshared(calendarUrl: String): Boolean = try {
        val host = calendarUrl.toHttpUrl().host
        if (host == "localhost" || host == "127.0.0.1") true else realAccountUnshared(calendarUrl)
    } catch (_: Exception) {
        false
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .authenticator(DigestAuthenticator(creds.username, creds.password, allowCleartext = false))
            .build()
    }

    private fun realAccountUnshared(calendarUrl: String): Boolean {
        val body = """<d:propfind xmlns:d="DAV:" xmlns:cs="$CS" xmlns:oc="$OC"><d:prop>""" +
            "<d:resourcetype/><cs:invite/><d:invite/><d:share-access/><oc:invite/>" +
            "<cs:publish-url/></d:prop></d:propfind>"
        val request = Request.Builder().url(calendarUrl)
            .header("Authorization", okhttp3.Credentials.basic(creds.username, creds.password))
            .header("Depth", "0")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        return http.newCall(request).execute().use { resp ->
            if (resp.code != 207) return@use false
            // Parsed by namespace, not prefix: iCloud writes its sharees as a bare <user>.
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(org.xml.sax.InputSource(java.io.StringReader(resp.body?.string().orEmpty())))
            // Properties answered with 200, by "namespace localName".
            val answered = mutableMapOf<String, Element>()
            val propstats = doc.getElementsByTagNameNS("DAV:", "propstat")
            for (i in 0 until propstats.length) {
                val ps = propstats.item(i) as Element
                val status = ps.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent.orEmpty()
                if (!status.contains(" 200")) continue
                val prop = ps.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element ?: continue
                for (j in 0 until prop.childNodes.length) {
                    val e = prop.childNodes.item(j) as? Element ?: continue
                    answered["${e.namespaceURI} ${e.localName}"] = e
                }
            }
            fun has(e: Element?, ns: String, name: String) = e != null && e.getElementsByTagNameNS(ns, name).length > 0
            val resourcetype = answered["DAV: resourcetype"] ?: return@use false
            val invites = listOfNotNull(answered["$CS invite"], answered["DAV: invite"], answered["$OC invite"])
            val shareAccess = answered["DAV: share-access"]
            val shared = answered.containsKey("$CS publish-url") ||
                has(resourcetype, CS, "shared") ||
                has(shareAccess, "DAV:", "read") || has(shareAccess, "DAV:", "read-write") ||
                invites.any { has(it, CS, "user") || has(it, "DAV:", "sharee") || has(it, OC, "user") }
            val positivelyUnshared = invites.isNotEmpty() || config == CalDavServerConfig.ICLOUD
            !shared && positivelyUnshared
        }
    }

    private companion object {
        const val CS = "http://calendarserver.org/ns/"
        const val OC = "http://owncloud.org/ns"
    }
}
