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
 * Shared proof machinery for the collection-discovery safety matrix.
 *
 * The question this answers empirically, across every reachable server:
 * *do the scheduling / notification collections a server exposes alongside real
 * calendars and address books ever carry the `<calendar>` / `<addressbook>`
 * resourcetype?* If they never do, then the resourcetype gate the parser already
 * applies is sufficient to exclude them, and the reserved-word NAME filter in the
 * quirks is pure redundancy — which is what makes it safe to keep that filter
 * strict (whole-segment) rather than a broad substring match that can false-drop
 * a user's real collection.
 *
 * The matrix is built from the RAW home-set PROPFIND response (not the parser's
 * already-filtered output), because the whole point is to see the inbox/outbox/
 * notification collections the parser drops and confirm each carries a distinct,
 * non-calendar/non-addressbook resourcetype.
 *
 * PII: PROPFIND home-set responses can carry the account's own principal path and
 * (on some servers) an email-shaped href or displayname. Everything written to a
 * fixture or printed to a matrix row is passed through [redactPii] first.
 */
object CollectionResourceTypeProof {

    private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

    /**
     * The resourcetype local-names servers use for scheduling/notification
     * collections. Used ONLY to characterize each collection in the matrix (the
     * SOGo-fold column) — NOT as an exclusion input. The production name filter
     * never inspects resourcetype; it matches reserved WORDS against the whole
     * path segments (the display name is not a discriminator).
     */
    private val SCHEDULING_RESOURCETYPES = setOf("schedule-inbox", "schedule-outbox", "notification", "notifications")

    /** Reserved path-segment words the production name filter skips (whole-segment match). */
    val RESERVED_SEGMENTS = setOf("inbox", "outbox", "notification", "notifications")

    // Real production quirks instances. The proof calls THESE — never a reimplemented
    // copy of the skip logic — so a regression in the shipped predicate (e.g. a revert
    // to substring matching, or a re-added display-name skip) is caught.
    // The base URL is irrelevant to the name filter; any value works.
    private val genericCalDavQuirks = DefaultQuirks(serverBaseUrl = "https://example.test/")
    private val iCloudQuirks = ICloudQuirks()
    private val genericCardDavQuirks = DefaultCardDavQuirks(serverBaseUrl = "https://example.test/")

    /**
     * Whether the PRODUCTION CalDAV name filter skips this collection. `iCloud`
     * selects `ICloudQuirks` (no tasks path-segment skip; its `/tasks/` is real);
     * every other server uses the generic `DefaultQuirks`. Delegates to the shipped
     * `shouldSkipCalendar` so the proof can never drift from production.
     */
    fun calDavNameFilterSkips(row: CollectionRow, isICloud: Boolean): Boolean =
        if (isICloud) iCloudQuirks.shouldSkipCalendar(row.href, row.displayName)
        else genericCalDavQuirks.shouldSkipCalendar(row.href, row.displayName)

    /** Whether the PRODUCTION CardDAV name filter skips this collection. */
    fun cardDavNameFilterSkips(row: CollectionRow): Boolean =
        genericCardDavQuirks.shouldSkipAddressBook(row.href, row.displayName)

    /** One collection from a Depth:1 home-set PROPFIND. */
    data class CollectionRow(
        val href: String,
        val displayName: String?,
        /** Lower-cased local names inside `<resourcetype>` (e.g. "collection", "calendar", "schedule-inbox"). */
        val resourceTypes: Set<String>,
        /** `<supported-calendar-component-set>` component names (VEVENT, VTODO, …); empty when absent. */
        val supportedComponents: Set<String>,
    ) {
        /** The parser's real gate: a calendar collection carries the CalDAV `<calendar>` resourcetype. */
        val isCalendar: Boolean get() = "calendar" in resourceTypes
        /** The parser's real gate: an address book carries the CardDAV `<addressbook>` resourcetype. */
        val isAddressBook: Boolean get() = "addressbook" in resourceTypes

        /**
         * Whether the app would actually SURFACE this collection to the user, applying
         * both real gates the parser uses: the positive `<calendar>` resourcetype AND
         * the VEVENT component gate (a VTODO-only collection — e.g. iCloud's real
         * `tasks` calendar, which carries `<calendar>` but advertises only VTODO — is
         * dropped even though it is a calendar). This is the honest baseline the name
         * filter is redundant against: it may only skip collections the app already
         * would not show.
         */
        val appSurfacesAsCalendar: Boolean
            get() = isCalendar && (supportedComponents.isEmpty() || "VEVENT" in supportedComponents)

        /** CardDAV has no component gate: an address book surfaces iff it carries `<addressbook>`. */
        val appSurfacesAsAddressBook: Boolean get() = isAddressBook

        /**
         * Does this collection carry a scheduling/notification resourcetype folded
         * alongside a real calendar/addressbook resourcetype? (SOGo does this on its
         * primary calendar.) Purely informational: proves why the gate MUST be a
         * positive `has <calendar>` test, never a negative `lacks scheduling` test.
         */
        val foldsSchedulingResourceType: Boolean
            get() = (isCalendar || isAddressBook) && resourceTypes.any { it in SCHEDULING_RESOURCETYPES }
    }

    /**
     * Build an OkHttp client matching the production auth surface (preemptive
     * Basic + Digest fallback), so digest-only servers (Baikal-digest, Cyrus)
     * answer the raw PROPFIND. Test-only transport; issues no app write.
     */
    fun rawClient(username: String, password: String): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .authenticator(DigestAuthenticator(username, password))
            .addNetworkInterceptor { chain ->
                val b = chain.request().newBuilder()
                if (chain.request().header("Authorization") == null) {
                    b.header("Authorization", OkHttpCredentials.basic(username, password, Charsets.UTF_8))
                }
                chain.proceed(b.build())
            }
            .build()

    /**
     * Issue the exact production home-set PROPFIND [body] (Depth:1) against
     * [homeUrl] and return the raw response XML, or null on transport failure /
     * non-2xx.
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
     * Parse a multistatus body into one [CollectionRow] per `<response>`, capturing
     * every `<resourcetype>` child local-name and any advertised calendar components.
     * Namespace-agnostic (matches by local name) so it reads every server's prefixing.
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
                            // First href under this response is the collection's own URL.
                            // nextText() consumes the matching END_TAG, so compensate the
                            // depth counter (the END_TAG event is never delivered to us).
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
     * The only display-name values the proof needs to read verbatim (the reserved
     * scheduling/task words the production name filter matches). Any OTHER display
     * name is a potential real-account label — a person's name, a shared-calendar
     * title — so it is masked. This is an AGPL/F-Droid public repo and the live
     * capture runs against REAL cloud accounts; the redactor must be allowlist-based
     * (mask by default) rather than heuristic (a "looks like a handle?" guess let a
     * real account holder's name through once).
     */
    private val PROOF_DISPLAY_NAMES =
        setOf("inbox", "outbox", "notification", "notifications", "tasks", "reminders")

    /**
     * Neutralize every account-identifying token so a captured body is safe to
     * commit to a public repo, while preserving the STRUCTURE the proof reads
     * (per-collection resourcetype, the collection's own path segment, and the
     * reserved-word display names the name filter matches). Masks, in order:
     *   1. email addresses (except reserved-TLD `@example.test`),
     *   2. `sync-token` values,
     *   3. account-identifying path SEGMENTS — a long all-digit run (iCloud DSID)
     *      or a long hex run (Zoho zuid) — leaving the neighbouring collection
     *      segments (inbox / personal / …) intact,
     *   4. EVERY display name except an exact reserved word (allowlist). Handles the
     *      three wire shapes: attributes on the tag (`<displayname xmlns="DAV:">`),
     *      CDATA-wrapped content, and self-closing empty `<displayname/>`.
     */
    fun redactPii(text: String): String {
        // Placeholders are deliberately bracket-free so a redacted fixture stays
        // well-formed XML and can be re-parsed by the offline replay test.
        var s = Regex("""[\w.+-]+@[\w.-]+""").replace(text) { m ->
            if (m.value.endsWith("@example.test")) m.value else "redacted@example.test"
        }
        s = Regex("""(<[\w:]*sync-token>)(.*?)(</[\w:]*sync-token>)""", RegexOption.DOT_MATCHES_ALL)
            .replace(s) { "${it.groupValues[1]}REDACTED_TOKEN${it.groupValues[3]}" }
        // Account-identifying path segments (bounded by '/'): pure digits >= 6, or hex >= 24.
        s = Regex("""(?<=/)(\d{6,}|[0-9a-fA-F]{24,})(?=/)""").replace(s) { "REDACTED_ACCOUNT" }
        // Display names: allowlist. Only the paired-tag form (`<displayname …>value</…>`)
        // carries content; the self-closing form (`<displayname/>`) is already empty
        // and must be left untouched. The `[^>]*` allows tag attributes; `[^<]*`
        // content excludes '<' so it never spans into a CDATA-less sibling element.
        s = Regex("""(<[\w:]*displayname\b[^>]*>)([^<]*)(</[\w:]*displayname>)""").replace(s) { m ->
            val inner = m.groupValues[2].trim()
            if (inner.isEmpty() || inner.lowercase() in PROOF_DISPLAY_NAMES) m.value
            else "${m.groupValues[1]}REDACTED_DISPLAYNAME${m.groupValues[3]}"
        }
        // Display names with CDATA content (Cyrus): mask unless the CDATA holds an
        // exact reserved word.
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

    /** Format one matrix row for console output. */
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

    /** Trim an href to its last two segments for readable matrix output. */
    private fun shortHref(href: String): String {
        val segs = href.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return if (segs.size <= 2) href else ".../" + segs.takeLast(2).joinToString("/")
    }

    /**
     * Write a redacted fixture under app/src/test/resources/<protocol>/resourcetype_proof/.
     * Tries a few base dirs so it works whether the test working dir is the module or repo root.
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
