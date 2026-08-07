package org.onekash.kashcal.sync.parser

import android.util.Log
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.util.EtagUtils
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * XmlPullParser-based CalDAV XML parser.
 *
 * Uses Android's recommended streaming XML parser for parsing WebDAV/CalDAV responses.
 * Reference: https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
 *
 * Benefits over regex:
 * - Proper namespace handling (DAV:, caldav, etc.)
 * - Automatic XML entity decoding (&amp; -> &, &quot; -> ")
 * - Single-pass extraction (more efficient for multiple fields)
 * - Validates XML structure
 * - Handles CDATA sections properly
 */
class CalDavXmlParser {

    companion object {
        private const val TAG = "CalDavXmlParser"

        /**
         * WebDAV privilege element local-names that confer the right to write
         * calendar-object content. DAV:all aggregates DAV:write aggregates
         * DAV:write-content (RFC 3744 §3.11/§3.12), and a server may advertise
         * any of these aggregation levels, so all three count as writable.
         * DAV:write-properties / DAV:bind / DAV:unbind are deliberately excluded:
         * they don't grant content writes, so a calendar offering only those
         * stays read-only.
         */
        private val WRITE_PRIVILEGE_ELEMENTS = setOf("all", "write", "write-content")

        /**
         * Decode the 5 standard XML entities.
         *
         * XmlPullParser.next() should decode these automatically, but Android's
         * KXmlParser may not in all cases (e.g., CDATA sections, certain runtime
         * versions). This is a defensive no-op when entities are already decoded.
         *
         * IMPORTANT: &amp; must be decoded LAST to avoid double-decoding.
         * e.g., "&amp;lt;" should become "&lt;", not "<".
         */
        internal fun decodeXmlEntities(text: String): String {
            if (!text.contains('&')) return text
            return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }

    private val factory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }

    /**
     * Extract principal URL from PROPFIND response.
     * Looks for: <current-user-principal><href>...</href></current-user-principal>
     */
    fun extractPrincipalUrl(xml: String): String? {
        if (xml.isBlank()) return null
        return try {
            val parser = createParser(xml)
            var inPrincipal = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "current-user-principal") {
                            inPrincipal = true
                        } else if (inPrincipal && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                return parser.text.trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "current-user-principal") {
                            inPrincipal = false
                        }
                    }
                }
                parser.next()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse principal URL: ${e.message}")
            null
        }
    }

    /**
     * Extract the scheduling Outbox URL from a PROPFIND response.
     *
     * RFC 6638 §2.1.1 (CALDAV:schedule-outbox-URL): the property wraps a
     * single DAV:href identifying the principal's scheduling Outbox. Looks for:
     * `<schedule-outbox-URL><href>...</href></schedule-outbox-URL>`.
     *
     * Returns null when the property is empty or absent — per the RFC, that
     * means the calendar user is not enabled for sending scheduling messages.
     * The href read is scoped to inside the property element so the response's
     * own self-href (the principal URL) is never mistaken for the outbox.
     */
    fun extractScheduleOutboxUrl(xml: String): String? {
        if (xml.isBlank()) return null
        return try {
            val parser = createParser(xml)
            var inOutbox = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "schedule-outbox-URL") {
                            inOutbox = true
                        } else if (inOutbox && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                val href = parser.text.trim()
                                if (href.isNotEmpty()) return href
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "schedule-outbox-URL") {
                            inOutbox = false
                        }
                    }
                }
                parser.next()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse schedule-outbox-URL: ${e.message}")
            null
        }
    }

    /**
     * Extract ALL calendar home URLs from PROPFIND response.
     * RFC 4791 Section 6.2.1 allows multiple <href> values inside <calendar-home-set>.
     * Looks for: <calendar-home-set><href>...</href><href>...</href></calendar-home-set>
     */
    fun extractCalendarHomeUrls(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            var inHomeSet = false
            val urls = mutableListOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = true
                        } else if (inHomeSet && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                val url = parser.text.trim()
                                if (url.isNotEmpty()) {
                                    urls.add(url)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = false
                        }
                    }
                }
                parser.next()
            }
            urls
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse calendar home URLs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract first calendar home URL from PROPFIND response.
     * Delegates to [extractCalendarHomeUrls] for backward compatibility.
     */
    fun extractCalendarHomeUrl(xml: String): String? = extractCalendarHomeUrls(xml).firstOrNull()

    /**
     * Extract `calendar-user-address-set` entries from PROPFIND response
     * (RFC 6638 §2.4.1). Looks for:
     * `<calendar-user-address-set><href>...</href><href>...</href></calendar-user-address-set>`.
     *
     * Honors the `preferred="1"` attribute on individual `<href>` elements
     * (observed on iCloud) by hoisting preferred entries to the front of
     * the returned list. Otherwise wire order is preserved. The first
     * preferred entry is the primary address used by `addresses[0]`-by-
     * convention consumers (e.g., T3 organizer-emit). If multiple
     * entries are preferred, all preferred ones come first; relative
     * order among preferred and among non-preferred matches wire order.
     *
     * `preferred="0"` and any non-`"1"` value are treated as not preferred.
     *
     * Empty body, missing element, malformed XML — all return empty list.
     */
    fun extractCalendarUserAddresses(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            var inAddressSet = false
            val preferred = mutableListOf<String>()
            val rest = mutableListOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "calendar-user-address-set") {
                            inAddressSet = true
                        } else if (inAddressSet && parser.name == "href") {
                            val isPreferred = parser.getAttributeValue(null, "preferred") == "1"
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                val href = parser.text.trim()
                                if (href.isNotEmpty()) {
                                    if (isPreferred) preferred.add(href) else rest.add(href)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "calendar-user-address-set") {
                            inAddressSet = false
                        }
                    }
                }
                parser.next()
            }
            preferred + rest
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse calendar-user-address-set: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract sync token from multistatus response.
     * Looks for: <sync-token>...</sync-token>
     */
    fun extractSyncToken(xml: String): String? {
        if (xml.isBlank()) return null
        return try {
            val parser = createParser(xml)

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "sync-token") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                return parser.text.trim()
                            }
                        }
                    }
                }
                parser.next()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse sync token: ${e.message}")
            null
        }
    }

    /**
     * Extract per-calendar metadata from a Depth:0 PROPFIND response.
     *
     * Returns null when ctag is absent. Nullable fields preserve local state
     * for servers that omit the property. `isReadOnly` returns null when no
     * privilege-set element appears — which is the deliberate divergence from
     * [extractCalendars]'s "assume read-only" fallback (discovery-time safety
     * vs. refresh-time preservation).
     */
    fun extractCalendarMetadata(xml: String): CalendarMetadataProbe? {
        if (xml.isBlank()) return null
        return try {
            val parser = createParser(xml)

            var ctag: String? = null
            var displayName: String? = null
            var color: String? = null
            var inPrivilegeSet = false
            var sawPrivilegeSet = false
            var hasWritePrivilege = false
            var hasReadOnlyElement = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "getctag" -> {
                                ctag = readText(parser)?.takeIf { it.isNotBlank() }
                            }
                            "displayname" -> {
                                displayName = readText(parser)?.takeIf { it.isNotBlank() }
                                    ?.let { decodeXmlEntities(it) }
                            }
                            "calendar-color" -> {
                                color = readText(parser)?.takeIf { it.isNotBlank() }
                            }
                            "current-user-privilege-set" -> {
                                inPrivilegeSet = true
                                sawPrivilegeSet = true
                            }
                            in WRITE_PRIVILEGE_ELEMENTS -> {
                                if (inPrivilegeSet) hasWritePrivilege = true
                            }
                            "read-only" -> hasReadOnlyElement = true
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "current-user-privilege-set") {
                            inPrivilegeSet = false
                        }
                    }
                }
                parser.next()
            }

            if (ctag == null) return null

            val isReadOnly: Boolean? = if (sawPrivilegeSet) {
                hasReadOnlyElement || !hasWritePrivilege
            } else {
                null
            }

            CalendarMetadataProbe(
                ctag = ctag,
                displayName = displayName,
                color = color,
                isReadOnly = isReadOnly
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse calendar metadata: ${e.message}")
            null
        }
    }

    /**
     * Extract ctag (calendar tag) from response.
     * Looks for: <getctag>...</getctag>
     */
    fun extractCtag(xml: String): String? {
        if (xml.isBlank()) return null
        return try {
            val parser = createParser(xml)

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "getctag") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                return parser.text.trim()
                            }
                        }
                    }
                }
                parser.next()
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ctag: ${e.message}")
            null
        }
    }

    /**
     * Extract calendar list from PROPFIND response.
     * Returns calendars that have <calendar> resourcetype.
     */
    fun extractCalendars(xml: String): List<CalDavQuirks.ParsedCalendar> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val calendars = mutableListOf<CalDavQuirks.ParsedCalendar>()

            var inResponse = false
            var inPropstat = false
            var inResourceType = false
            var inPrivilegeSet = false
            var currentHref: String? = null
            var currentDisplayName: String? = null
            var currentColor: String? = null
            var currentCtag: String? = null
            var isCalendar = false
            var statusOk = true  // Default to OK - only set false if we see a non-200 status
            var hasWritePrivilege = false
            var isReadOnly = false
            // Per-propstat tracking for RFC 4918 multi-propstat support (Stalwart, Radicale)
            var currentPropstatHasResourceType = false
            var currentPropstatStatus: String? = null
            var resourceTypeStatusOk = true  // Status for propstat containing resourcetype
            // RFC 4791 supported-calendar-component-set tracking
            var inSupportedComponentSet = false
            val currentComponents = mutableSetOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentDisplayName = null
                                currentColor = null
                                currentCtag = null
                                isCalendar = false
                                statusOk = true  // Default to OK - only set false if we see a non-200 status
                                hasWritePrivilege = false
                                isReadOnly = false
                                // Reset per-propstat tracking for this response
                                resourceTypeStatusOk = true
                                currentPropstatHasResourceType = false
                                currentPropstatStatus = null
                                // Reset component tracking for this response
                                currentComponents.clear()
                            }
                            "propstat" -> {
                                inPropstat = true
                                // Reset per-propstat tracking
                                currentPropstatHasResourceType = false
                                currentPropstatStatus = null
                            }
                            "resourcetype" -> {
                                inResourceType = true
                                currentPropstatHasResourceType = true  // Mark this propstat contains resourcetype
                            }
                            "current-user-privilege-set" -> inPrivilegeSet = true
                            "calendar" -> if (inResourceType) isCalendar = true
                            in WRITE_PRIVILEGE_ELEMENTS -> if (inPrivilegeSet) hasWritePrivilege = true
                            "read-only" -> isReadOnly = true
                            "supported-calendar-component-set" -> inSupportedComponentSet = true
                            "comp" -> {
                                if (inSupportedComponentSet) {
                                    parser.getAttributeValue(null, "name")?.uppercase()?.let {
                                        currentComponents.add(it)
                                    }
                                }
                            }
                            "href" -> if (inResponse && !inPropstat && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "displayname" -> {
                                currentDisplayName = readText(parser)?.takeIf { it.isNotBlank() }
                                    ?.let { decodeXmlEntities(it) }
                            }
                            "calendar-color" -> {
                                currentColor = readText(parser)?.takeIf { it.isNotBlank() }
                            }
                            "getctag" -> {
                                currentCtag = readText(parser)?.takeIf { it.isNotBlank() }
                            }
                            "status" -> {
                                val statusText = readText(parser)
                                currentPropstatStatus = statusText  // Store for per-propstat check
                                // Keep existing global check for backward compat
                                if (statusText != null && !statusText.contains("200") && !statusText.contains("201")) {
                                    statusOk = false
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                // Use resourceTypeStatusOk for calendar inclusion (RFC 4918 multi-propstat support)
                                if (isCalendar && currentHref != null && resourceTypeStatusOk) {
                                    val href = currentHref
                                    val name = currentDisplayName ?: "Unnamed"
                                    calendars.add(
                                        CalDavQuirks.ParsedCalendar(
                                            href = href,
                                            displayName = name,
                                            color = currentColor,
                                            ctag = currentCtag,
                                            isReadOnly = isReadOnly || !hasWritePrivilege,
                                            supportedComponents = currentComponents.toSet()
                                        )
                                    )
                                }
                                inResponse = false
                            }
                            "propstat" -> {
                                // Only update resourceTypeStatusOk if this propstat contained resourcetype
                                if (currentPropstatHasResourceType) {
                                    val status = currentPropstatStatus
                                    resourceTypeStatusOk = status == null ||  // No status = OK (RFC 4918 default)
                                        status.contains("200") ||
                                        status.contains("201")
                                }
                                inPropstat = false
                            }
                            "resourcetype" -> inResourceType = false
                            "current-user-privilege-set" -> inPrivilegeSet = false
                            "supported-calendar-component-set" -> inSupportedComponentSet = false
                        }
                    }
                }
                parser.next()
            }

            calendars
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse calendars: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract iCal data from calendar-multiget or calendar-query response.
     * Returns list of events with href, etag, and iCal data.
     */
    fun extractICalData(xml: String): List<CalDavQuirks.ParsedEventData> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val events = mutableListOf<CalDavQuirks.ParsedEventData>()

            var inResponse = false
            var currentHref: String? = null
            var currentEtag: String? = null
            var currentIcalData: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentEtag = null
                                currentIcalData = null
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "getetag" -> {
                                val rawEtag = readText(parser)
                                currentEtag = EtagUtils.normalizeEtag(rawEtag)
                            }
                            "calendar-data" -> {
                                currentIcalData = readTextOrCdata(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (currentHref != null && currentIcalData != null &&
                                currentIcalData.contains("BEGIN:VCALENDAR")) {
                                events.add(
                                    CalDavQuirks.ParsedEventData(
                                        href = currentHref,
                                        etag = currentEtag,
                                        icalData = currentIcalData
                                    )
                                )
                            } else if (currentHref != null && currentIcalData == null &&
                                currentEtag != null) {
                                // Response carried an etag but no calendar-data — this is a
                                // member resource the server failed to materialize. The etag
                                // proves it isn't the collection self-row, so warn regardless
                                // of href filename (servers may use extensionless UIDs).
                                Log.w(TAG, "Response for ${currentHref} has no calendar-data " +
                                    "— server may not support calendar-data in calendar-query")
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            events
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse iCal data: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract changed items (href + etag pairs) from sync-collection or PROPFIND
     * Depth:1 response.
     *
     * Discriminator (RFC 4918 §5.2 + §13, RFC 6578 §3.2):
     *   - Response-level 404 (status directly inside `<response>`, no `<propstat>`) →
     *     deletion. RFC 6578 §3.2 mandates this shape for removed members.
     *   - propstat-404 in a response with NO successful propstat → deletion (pragmatic
     *     convention used by some servers: `<propstat><prop/><status>404</status></propstat>`).
     *   - propstat-404 in a response that also has a successful propstat → just a missing
     *     property (e.g., `<getetag/>` 404 on a collection self-row). RFC 4918 §13:
     *     propstat-level status applies only to those properties.
     *   - href ends with `/` → collection self-row (RFC 4918 §5.2 SHOULD), skipped.
     *   - resourcetype contains `<collection/>` → collection self-row, skipped (defensive
     *     fallback for non-conforming servers that omit the trailing slash; the
     *     fetchAllEtags / fetchEtagsInRange / syncCollection wire bodies do not request
     *     resourcetype, so this fires only when a server volunteers the element
     *     unprompted).
     *   - Otherwise, etag present → changed item.
     *   - No etag, not a collection, not deleted → diagnostic skip.
     *
     * Filename extension is NOT used: some servers store events at extensionless
     * UID hrefs.
     */
    fun extractChangedItems(xml: String): List<Pair<String, String?>> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val items = mutableListOf<Pair<String, String?>>()

            forEachResponse(parser) { state ->
                when {
                    state.isCollection() -> Unit
                    state.isDeleted() -> Unit
                    state.currentEtag != null ->
                        items.add(Pair(state.currentHref!!, state.currentEtag))
                    else -> Log.w(
                        TAG,
                        "Dropping ${state.currentHref}: no etag, not a collection, " +
                            "not deleted (server may have returned a propstat error " +
                            "such as 403 Forbidden, or omitted getetag)"
                    )
                }
            }

            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse changed items: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract deleted hrefs from sync-collection or PROPFIND Depth:1 response.
     *
     * Returns hrefs that the server reports as deleted. Two reporting styles supported:
     *   - Response-level 404 with no `<propstat>` (RFC 6578 §3.2 sync-collection
     *     mandate): status directly inside `<response>`.
     *   - propstat-404 with no successful sibling propstat (pragmatic convention used
     *     by some servers): `<propstat><prop/><status>404</status></propstat>` with no
     *     sibling 2xx propstat indicates the resource itself is gone.
     *
     * propstat-404 alongside a successful propstat (e.g., `<displayname/>` 404 next
     * to `<getetag>` 200) is a missing-property report, NOT deletion.
     */
    fun extractDeletedHrefs(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val deleted = mutableListOf<String>()

            forEachResponse(parser) { state ->
                if (!state.isCollection() && state.isDeleted()) {
                    deleted.add(state.currentHref!!)
                }
            }

            deleted
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse deleted hrefs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Single-pass extraction of all sync-collection data.
     * More efficient than 3 separate calls for changed items, deleted hrefs, and sync token.
     *
     * Uses the same discriminator as [extractChangedItems] / [extractDeletedHrefs].
     */
    fun extractSyncCollectionData(xml: String): CalDavQuirks.SyncCollectionData {
        if (xml.isBlank()) return CalDavQuirks.SyncCollectionData(null, emptyList(), emptyList())
        return try {
            val parser = createParser(xml)
            val changedItems = mutableListOf<Pair<String, String?>>()
            val deletedHrefs = mutableListOf<String>()
            var syncToken: String? = null
            var truncated = false

            var inResponse = false
            val state = ResponseState()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                state.reset()
                            }
                            "propstat" -> state.enterPropstat()
                            "resourcetype" -> state.insideResourcetype = true
                            "collection" -> if (state.insideResourcetype) {
                                state.resourcetypeContainsCollection = true
                            }
                            "href" -> if (inResponse && state.currentHref == null) {
                                state.currentHref = readText(parser)
                            }
                            "getetag" -> {
                                val rawEtag = readText(parser)
                                state.currentEtag = EtagUtils.normalizeEtag(rawEtag)
                            }
                            "status" -> {
                                val statusText = readText(parser)
                                state.observeStatus(statusText)
                            }
                            "sync-token" -> {
                                syncToken = readText(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "propstat" -> state.exitPropstat()
                            "resourcetype" -> state.insideResourcetype = false
                            "response" -> {
                                // A 507 on any <response> means the server truncated the
                                // listing (RFC 6578 §3.6); note it regardless of which
                                // href carried it, then fall through so a genuine member
                                // href on the same page is still classified below.
                                if (state.isTruncationMarker()) truncated = true
                                if (state.currentHref != null) {
                                    when {
                                        state.isCollection() -> { /* skip collection self-row */ }
                                        // A 507-marked response is the truncation signal, not a
                                        // member change/delete — never emit it as a resource.
                                        state.isTruncationMarker() -> { /* truncation marker, not a member */ }
                                        state.isDeleted() -> deletedHrefs.add(state.currentHref!!)
                                        state.currentEtag != null ->
                                            changedItems.add(Pair(state.currentHref!!, state.currentEtag))
                                        else -> Log.w(
                                            TAG,
                                            "Dropping ${state.currentHref}: no etag, not a " +
                                                "collection, not deleted (server may have " +
                                                "returned a propstat error such as 403 " +
                                                "Forbidden, or omitted getetag)"
                                        )
                                    }
                                }
                                inResponse = false
                            }
                        }
                    }
                }
                parser.next()
            }

            CalDavQuirks.SyncCollectionData(syncToken, changedItems, deletedHrefs, truncated)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse sync collection data: ${e.message}")
            CalDavQuirks.SyncCollectionData(null, emptyList(), emptyList())
        }
    }

    /**
     * Per-response state shared by [extractChangedItems], [extractDeletedHrefs],
     * and [extractSyncCollectionData].
     *
     * Tracks the four pieces of information needed to classify a `<response>`:
     *   - href and etag (for changed-item output);
     *   - propstat depth + status observations (for deletion semantics — RFC 6578 §3.2
     *     for response-level 404, plus the propstat-404 convention used by some servers);
     *   - resourcetype-collection marker (defensive fallback for collection self-row
     *     when href omits the trailing slash; the primary signal is href.endsWith("/"),
     *     RFC 4918 §5.2).
     */
    private class ResponseState {
        var currentHref: String? = null
        var currentEtag: String? = null
        var insideResourcetype: Boolean = false
        var resourcetypeContainsCollection: Boolean = false

        private var propstatDepth: Int = 0
        private var responseLevel404: Boolean = false
        private var sawSuccessfulPropstat: Boolean = false
        private var sawPropstat404: Boolean = false
        private var sawStatus507: Boolean = false

        fun reset() {
            currentHref = null
            currentEtag = null
            insideResourcetype = false
            resourcetypeContainsCollection = false
            propstatDepth = 0
            responseLevel404 = false
            sawSuccessfulPropstat = false
            sawPropstat404 = false
            sawStatus507 = false
        }

        fun enterPropstat() { propstatDepth++ }
        fun exitPropstat() { propstatDepth-- }

        fun observeStatus(statusText: String?) {
            if (statusText == null) return
            val code = parseHttpStatusCode(statusText) ?: return
            val is404 = code == 404
            val is2xx = code in 200..299
            // RFC 6578 §3.6: a truncated sync-collection reports 507 on the
            // collection's own <response> (response-level status), not in a propstat.
            if (code == 507) sawStatus507 = true
            if (propstatDepth == 0) {
                if (is404) responseLevel404 = true
            } else {
                if (is404) sawPropstat404 = true
                if (is2xx) sawSuccessfulPropstat = true
            }
        }

        /** True when this `<response>` carried a 507 status (RFC 6578 §3.6 truncation). */
        fun isTruncationMarker(): Boolean = sawStatus507

        /**
         * Parse the 3-digit code from an HTTP status line (`HTTP/<ver> <code> <reason>`),
         * tolerating extra whitespace. Returns null if no 3-digit code is found.
         */
        private fun parseHttpStatusCode(statusText: String): Int? {
            val tokens = statusText.trim().split(Regex("""\s+"""))
            if (tokens.size < 2) return null
            return tokens[1].toIntOrNull()?.takeIf { it in 100..599 }
        }

        /**
         * True when this `<response>` describes the collection itself rather than a
         * member resource.
         *
         * Primary signal: href ends with `/` (RFC 4918 §5.2 SHOULD — *"Wherever a
         * server produces a URL referring to a collection, the server SHOULD include
         * the trailing slash."*). Verified across 7 server families; every probed
         * collection self-row honors this.
         *
         * Defensive fallback: resourcetype contains `<collection/>`. The wire bodies
         * for fetchAllEtags / fetchEtagsInRange / syncCollection do NOT request
         * resourcetype, so this fallback only fires when a server volunteers the
         * element unprompted (RFC 4918 §9.1 permits servers to return more properties
         * than requested). It is kept for resilience against non-conforming servers
         * that drop the trailing slash; do not re-add wire-level resourcetype "for
         * safety" — iCloud emits a separate propstat-404 per member resource for an
         * empty resourcetype query and the response bloats well past the read timeout.
         */
        fun isCollection(): Boolean {
            val href = currentHref ?: return false
            return href.endsWith("/") || resourcetypeContainsCollection
        }

        /**
         * True when the server is reporting the resource as deleted.
         *
         * Either:
         *   - response-level 404 with no `<propstat>` (RFC 6578 §3.2 mandates this
         *     shape for removed members), or
         *   - propstat-404 with no successful sibling propstat (pragmatic convention
         *     used by some servers; not RFC-mandated).
         *
         * propstat-404 alongside a 2xx propstat is a missing-property report — e.g.,
         * `<displayname/>` 404 alongside `<getetag>` 200. That is NOT a deletion.
         */
        fun isDeleted(): Boolean =
            currentHref != null &&
                (responseLevel404 || (sawPropstat404 && !sawSuccessfulPropstat))
    }

    /**
     * Streaming iterator over `<response>` elements. Invokes [onResponse] at each
     * `</response>` with the populated [ResponseState], allowing callers to project
     * different views (changed items, deleted hrefs, etc.) from the same parse.
     */
    private inline fun forEachResponse(
        parser: XmlPullParser,
        onResponse: (ResponseState) -> Unit
    ) {
        var inResponse = false
        val state = ResponseState()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "response" -> {
                            inResponse = true
                            state.reset()
                        }
                        "propstat" -> state.enterPropstat()
                        "resourcetype" -> state.insideResourcetype = true
                        "collection" -> if (state.insideResourcetype) {
                            state.resourcetypeContainsCollection = true
                        }
                        "href" -> if (inResponse && state.currentHref == null) {
                            state.currentHref = readText(parser)
                        }
                        "getetag" -> {
                            val rawEtag = readText(parser)
                            state.currentEtag = EtagUtils.normalizeEtag(rawEtag)
                        }
                        "status" -> {
                            val statusText = readText(parser)
                            state.observeStatus(statusText)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "propstat" -> state.exitPropstat()
                        "resourcetype" -> state.insideResourcetype = false
                        "response" -> {
                            if (state.currentHref != null) {
                                onResponse(state)
                            }
                            inResponse = false
                        }
                    }
                }
            }
            parser.next()
        }
    }

    private fun createParser(xml: String): XmlPullParser {
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return parser
    }

    /**
     * Read text content from current element.
     * Advances parser to next token.
     */
    private fun readText(parser: XmlPullParser): String? {
        parser.next()
        return if (parser.eventType == XmlPullParser.TEXT) {
            parser.text.trim()
        } else {
            null
        }
    }

    /**
     * Read text or CDATA content from current element.
     * Handles both regular text and CDATA sections (used by iCloud for calendar-data).
     */
    private fun readTextOrCdata(parser: XmlPullParser): String? {
        parser.next()
        return when (parser.eventType) {
            XmlPullParser.TEXT -> parser.text.trim()
            XmlPullParser.CDSECT -> parser.text.trim()
            else -> null
        }
    }
}
