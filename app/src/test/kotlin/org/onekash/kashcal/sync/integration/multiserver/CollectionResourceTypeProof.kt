package org.onekash.kashcal.sync.integration.multiserver

import okhttp3.Credentials as OkHttpCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.onekash.kashcal.sync.carddav.DefaultCardDavQuirks
import org.onekash.kashcal.sync.client.DigestAuthenticator
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.quirks.DefaultQuirks
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/**
 * Provides the shared helpers for the collection-discovery safety matrix.
 *
 * It answers, on every reachable server: do the scheduling and notification collections a
 * server exposes beside real calendars and address books ever carry the `<calendar>` or
 * `<addressbook>` resourcetype? If they never do, the parser's resourcetype gate already
 * excludes them and the reserved-word name filter in the quirks is redundant. That is what
 * makes it safe to keep the filter whole-segment, not a substring match that can drop a
 * user's real collection.
 *
 * The matrix is built from the raw home-set PROPFIND response, not the parser's filtered
 * output, so it sees the inbox, outbox and notification collections the parser drops and can
 * confirm each carries a non-calendar, non-addressbook resourcetype.
 *
 * PII: a home-set response can carry the account's principal path and, on some servers, an
 * email-shaped href or displayname. Everything written to a fixture or printed as a matrix row
 * goes through [redactPii] first.
 */
object CollectionResourceTypeProof {

    private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

    /**
     * Resourcetype local names servers use for scheduling and notification collections. Used
     * only for the matrix's fold column ([CollectionRow.foldsSchedulingResourceType]), never
     * to exclude. The production name filter never inspects resourcetype or display name; it
     * matches reserved words against whole path segments.
     */
    private val SCHEDULING_RESOURCETYPES = setOf("schedule-inbox", "schedule-outbox", "notification", "notifications")

    /** Reserved path-segment words the production name filter skips, matched as whole segments. */
    val RESERVED_SEGMENTS = setOf("inbox", "outbox", "notification", "notifications")

    // Production quirks instances. The proof calls these, never a copy of the skip logic, so
    // a regression in the shipped predicate (e.g. substring matching or a display-name skip)
    // is caught. The name filter ignores the base URL; any value works.
    private val genericCalDavQuirks = DefaultQuirks(serverBaseUrl = "https://example.test/")
    private val iCloudQuirks = ICloudQuirks()
    private val genericCardDavQuirks = DefaultCardDavQuirks(serverBaseUrl = "https://example.test/")

    /**
     * Returns whether the production CalDAV name filter skips [row]. [isICloud] selects
     * [ICloudQuirks], which has no tasks path-segment skip because its `/tasks/` is a real
     * calendar; every other server uses [DefaultQuirks]. Delegates to the shipped
     * `shouldSkipCalendar`, so the proof can't drift from production.
     */
    fun calDavNameFilterSkips(row: CollectionRow, isICloud: Boolean): Boolean =
        if (isICloud) iCloudQuirks.shouldSkipCalendar(row.href, row.displayName)
        else genericCalDavQuirks.shouldSkipCalendar(row.href, row.displayName)

    /** Returns whether the production CardDAV name filter ([DefaultCardDavQuirks]) skips [row]. */
    fun cardDavNameFilterSkips(row: CollectionRow): Boolean =
        genericCardDavQuirks.shouldSkipAddressBook(row.href, row.displayName)

    /** One collection from a Depth:1 home-set PROPFIND. */
    data class CollectionRow(
        val href: String,
        val displayName: String?,
        /** Lower-cased local names inside `<resourcetype>`, e.g. "collection", "schedule-inbox". */
        val resourceTypes: Set<String>,
        /** Upper-cased `<supported-calendar-component-set>` names; empty when absent. */
        val supportedComponents: Set<String>,
    ) {
        /** The parser's gate: a calendar collection carries the `<calendar>` resourcetype. */
        val isCalendar: Boolean get() = "calendar" in resourceTypes
        /** The parser's gate: an address book carries the CardDAV `<addressbook>` resourcetype. */
        val isAddressBook: Boolean get() = "addressbook" in resourceTypes

        /**
         * True when the app would show this collection: it passes the parser's `<calendar>`
         * resourcetype gate and the quirks' VEVENT component gate, where an empty component set
         * passes. A VTODO-only collection, such as iCloud's `tasks` calendar, carries
         * `<calendar>` but is dropped. The name filter is redundant against this baseline: it
         * may only skip collections the app already wouldn't show.
         */
        val appSurfacesAsCalendar: Boolean
            get() = isCalendar && (supportedComponents.isEmpty() || "VEVENT" in supportedComponents)

        /** CardDAV has no component gate: a book shows exactly when it carries `<addressbook>`. */
        val appSurfacesAsAddressBook: Boolean get() = isAddressBook

        /**
         * True when a scheduling or notification resourcetype sits beside a calendar or
         * addressbook resourcetype, as on SOGo's primary calendar. Informational: it shows why
         * the gate must be a positive "has `<calendar>`" test, never a negative "lacks
         * scheduling" test.
         */
        val foldsSchedulingResourceType: Boolean
            get() = (isCalendar || isAddressBook) && resourceTypes.any { it in SCHEDULING_RESOURCETYPES }
    }

    /**
     * Builds an OkHttp client with the production auth shape (preemptive Basic, then Digest on a
     * challenge), so Digest-only servers (Baikal-digest, Cyrus) answer the raw PROPFIND. It
     * follows redirects with OkHttp's own handling and issues no app write.
     */
    fun rawClient(username: String, password: String): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .authenticator(DigestAuthenticator(username, password, allowCleartext = true))
            .addNetworkInterceptor { chain ->
                val b = chain.request().newBuilder()
                if (chain.request().header("Authorization") == null) {
                    b.header("Authorization", OkHttpCredentials.basic(username, password, Charsets.UTF_8))
                }
                chain.proceed(b.build())
            }
            .build()

    /**
     * Sends the PROPFIND [body] with Depth 1 to [homeUrl] and returns the raw response XML, or
     * null on a transport failure or a non-2xx reply.
     */
    fun fetchRawPropfind(client: OkHttpClient, homeUrl: String, body: String): String? = try {
        val request = Request.Builder()
            .url(homeUrl)
            .method("PROPFIND", body.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .header("Content-Type", "application/xml")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful || resp.code == 207) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Parses a multistatus body into one [CollectionRow] per `<response>` with an href,
     * capturing every `<resourcetype>` child's local name and any advertised calendar
     * components. Matches by local name, so any server's namespace prefixes read the same.
     */
    fun parseCollections(xml: String): List<CollectionRow> {
        if (xml.isBlank()) return emptyList()
        val rows = mutableListOf<CollectionRow>()
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }.newPullParser()
        parser.setInput(StringReader(xml))

        var href: String? = null
        var displayName: String? = null
        var types = mutableSetOf<String>()
        var components = mutableSetOf<String>()
        var inResourceType = false
        var inComponentSet = false
        var depthOfResponse = -1
        var depth = 0

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (val ln = local(parser.name)) {
                        "response" -> {
                            depthOfResponse = depth
                            href = null; displayName = null
                            types = mutableSetOf(); components = mutableSetOf()
                        }
                        "href" -> if (href == null) {
                            // The first href under a response is the collection's own URL.
                            // nextText() consumes the matching END_TAG, which is never
                            // delivered, so the depth counter is decremented here.
                            href = parser.nextText().trim()
                            depth--
                        }
                        "displayname" -> if (displayName == null) {
                            displayName = parser.nextText().trim().ifEmpty { null }
                            depth--
                        }
                        "resourcetype" -> inResourceType = true
                        "supported-calendar-component-set" -> inComponentSet = true
                        "comp" -> if (inComponentSet) {
                            parser.getAttributeValue(null, "name")?.let { components.add(it.uppercase()) }
                        }
                        else -> if (inResourceType) types.add(ln.lowercase())
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (local(parser.name)) {
                        "resourcetype" -> inResourceType = false
                        "supported-calendar-component-set" -> inComponentSet = false
                        "response" -> if (depth == depthOfResponse) {
                            href?.let { rows.add(CollectionRow(it, displayName, types.toSet(), components.toSet())) }
                            depthOfResponse = -1
                        }
                    }
                    depth--
                }
            }
            ev = parser.next()
        }
        return rows
    }

    /** Local name of a possibly-prefixed element ("cal:calendar" -> "calendar"). */
    private fun local(name: String): String = name.substringAfterLast(':')

    /** Last non-empty path segment of an href, lower-cased. */
    fun lastSegment(href: String): String =
        href.trimEnd('/').substringAfterLast('/').lowercase()

    /**
     * The only display names kept verbatim: the reserved scheduling and task words the proof
     * reads. Any other display name may be a real-account label, such as a person's name or a
     * shared calendar's title, so it is masked. The repo is public and the live capture runs
     * against real cloud accounts, so the redactor must mask by default: a "looks like a
     * handle?" heuristic once let a real account holder's name through.
     */
    private val PROOF_DISPLAY_NAMES =
        setOf("inbox", "outbox", "notification", "notifications", "tasks", "reminders")

    /**
     * Masks every account-identifying token so a captured body is safe to commit to a public
     * repo, keeping the structure the proof reads: each collection's resourcetype, its own path
     * segment and the reserved-word display names. Masks, in order:
     *   1. email addresses, except those at the reserved `@example.test`;
     *   2. `sync-token` values;
     *   3. account-identifying path segments, six or more digits (iCloud DSID) or 24 or more
     *      hex characters (Zoho zuid), leaving neighbouring collection segments intact;
     *   4. every display name except an exact entry of [PROOF_DISPLAY_NAMES]. Tag attributes
     *      (`<displayname xmlns="DAV:">`) and CDATA content are handled; an empty plain or
     *      self-closing `<displayname/>` is left as is.
     */
    fun redactPii(text: String): String {
        // Placeholders have no angle brackets, so a redacted fixture stays well-formed XML that
        // `CollectionResourceTypeProofFixtureTest` can re-parse.
        var s = Regex("""[\w.+-]+@[\w.-]+""").replace(text) { m ->
            if (m.value.endsWith("@example.test")) m.value else "redacted@example.test"
        }
        s = Regex("""(<[\w:]*sync-token>)(.*?)(</[\w:]*sync-token>)""", RegexOption.DOT_MATCHES_ALL)
            .replace(s) { "${it.groupValues[1]}REDACTED_TOKEN${it.groupValues[3]}" }
        // Account-identifying path segments (bounded by '/'): pure digits >= 6, or hex >= 24.
        s = Regex("""(?<=/)(\d{6,}|[0-9a-fA-F]{24,})(?=/)""").replace(s) { "REDACTED_ACCOUNT" }
        // Display names, by allowlist. Only the paired-tag form carries content; the
        // self-closing `<displayname/>` is empty and must be left untouched. `[^>]*` allows tag
        // attributes; `[^<]*` excludes '<' so the match never spans into a sibling element.
        s = Regex("""(<[\w:]*displayname\b[^>]*>)([^<]*)(</[\w:]*displayname>)""").replace(s) { m ->
            val inner = m.groupValues[2].trim()
            if (inner.isEmpty() || inner.lowercase() in PROOF_DISPLAY_NAMES) m.value
            else "${m.groupValues[1]}REDACTED_DISPLAYNAME${m.groupValues[3]}"
        }
        // Display names with CDATA content (Cyrus): masked unless the CDATA holds an exact
        // reserved word.
        s = Regex(
            """(<[\w:]*displayname\b[^>]*>)<!\[CDATA\[(.*?)]]>(</[\w:]*displayname>)""",
            RegexOption.DOT_MATCHES_ALL,
        ).replace(s) { m ->
            val inner = m.groupValues[2].trim()
            if (inner.lowercase() in PROOF_DISPLAY_NAMES) m.value
            else "${m.groupValues[1]}REDACTED_DISPLAYNAME${m.groupValues[3]}"
        }
        return s
    }

    /** Formats one redacted matrix row for console output. */
    fun matrixRow(server: String, r: CollectionRow, protocol: String): String {
        val isCalDav = protocol == "caldav"
        val surfaced = if (isCalDav) r.appSurfacesAsCalendar else r.appSurfacesAsAddressBook
        val nameSkips =
            if (isCalDav) calDavNameFilterSkips(r, isICloud = server.equals("icloud", ignoreCase = true))
            else cardDavNameFilterSkips(r)
        val comps = if (r.supportedComponents.isEmpty()) "-" else r.supportedComponents.sorted().joinToString("+")
        return "%-10s | %-45s | %-38s | surfaced=%-5s | comps=%-11s | nameSkips=%-5s | fold=%-5s"
            .format(
                server,
                redactPii(shortHref(r.href)),
                r.resourceTypes.sorted().joinToString(",").ifEmpty { "(none)" },
                surfaced, comps, nameSkips, r.foldsSchedulingResourceType,
            )
    }

    /** Trims an href to its last two segments for the matrix output. */
    private fun shortHref(href: String): String {
        val segs = href.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return if (segs.size <= 2) href else ".../" + segs.takeLast(2).joinToString("/")
    }

    /**
     * Writes a redacted fixture to app/src/test/resources/<protocol>/resourcetype_proof/. Uses
     * the first base directory whose target folder exists or can be created, so it works from
     * the module or the repo root. A write failure is printed, not thrown.
     */
    fun writeFixture(protocol: String, serverName: String, rawXml: String) {
        val redacted = redactPii(rawXml)
        val rel = "src/test/resources/$protocol/resourcetype_proof/${serverName.lowercase()}.xml"
        val candidates = listOf(
            File(rel),               // test working dir is usually the module root (app/)
            File("app/$rel"),        // repo root
            File("/onekash/KashCal/app/$rel"),
        )
        val target = candidates.firstOrNull { it.parentFile?.let { p -> p.exists() || p.mkdirs() } == true }
            ?: candidates.last().also { it.parentFile?.mkdirs() }
        try {
            target.writeText(redacted)
            println("    wrote fixture: ${target.path}")
        } catch (e: Exception) {
            println("    could not write fixture (${e.message})")
        }
    }
}
