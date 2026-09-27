package org.onekash.kashcal.sync.parser

import android.util.Log
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.util.EtagUtils
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parses WebDAV and CalDAV multistatus responses with a streaming [XmlPullParser].
 *
 * Unlike regex extraction, the pull parser is namespace-aware, decodes XML entities and CDATA,
 * rejects malformed XML and reads several fields in one pass. Every `extract*` function
 * returns null or empty on malformed XML instead of throwing.
 */
class CalDavXmlParser {

    companion object {
        private const val TAG = "CalDavXmlParser"

        /**
         * Parses the code from an HTTP status line (`HTTP/<ver> <code> <reason>`), tolerating
         * extra whitespace. Null unless the second token is a code in 100..599.
         */
        private fun parseHttpStatusCode(statusText: String): Int? {
            val tokens = statusText.trim().split(Regex("""\s+"""))
            if (tokens.size < 2) return null
            return tokens[1].toIntOrNull()?.takeIf { it in 100..599 }
        }

        /**
         * Lists the WebDAV privilege local names that grant writing calendar-object content.
         *
         * DAV:all contains DAV:write, which contains DAV:write-content (RFC 3744 §3.11,
         * §3.12), and a server may advertise any level, so all three count. DAV:write-properties,
         * DAV:bind and DAV:unbind are deliberately excluded: they don't grant content writes,
         * so a calendar offering only those stays read-only.
         *
         * The source of truth for both parsers: `CardDavXmlParser` reads this set, so the
         * calendar and contact read paths can't drift.
         */
        internal val WRITE_PRIVILEGE_ELEMENTS = setOf("all", "write", "write-content")

        /**
         * Decodes the 5 standard XML entities in parsed display names and descriptions.
         *
         * XmlPullParser.next() should decode them, but Android's KXmlParser may not in all
         * cases (e.g., CDATA sections, certain runtime versions). Text without `&` is returned
         * unchanged; text the parser already decoded is decoded again, so a name containing a
         * literal `&lt;` becomes `<`.
         *
         * `&amp;` must be decoded last, or "&amp;lt;" would become "<" instead of "&lt;".
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

    /** Returns the href inside `<current-user-principal>`, or null. */
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
     * Returns the principal's scheduling Outbox URL, the one href inside
     * `<schedule-outbox-URL>` (RFC 6638 §2.1.1).
     *
     * Null when the property is empty or absent, which per the RFC means the calendar user
     * can't send scheduling messages. Only an href inside the property is read, so the
     * response's own href (the principal URL) is never taken for the outbox.
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
     * Returns every href inside `<calendar-home-set>`, which may hold several
     * (RFC 4791 §6.2.1).
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

    /** Returns the first of [extractCalendarHomeUrls], or null. */
    fun extractCalendarHomeUrl(xml: String): String? = extractCalendarHomeUrls(xml).firstOrNull()

    /**
     * Returns the hrefs inside `<calendar-user-address-set>` (RFC 6638 §2.4.1), or empty.
     *
     * Hrefs with `preferred="1"` (observed on iCloud) come first, so consumers that take the
     * first address as the account's primary get the preferred one. Any other `preferred`
     * value counts as not preferred. Within each group the wire order is kept.
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

    /** Returns the first `<sync-token>` text, or null. */
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
     * Reads one calendar's metadata from a Depth:0 PROPFIND reply, or null when there is no
     * ctag.
     *
     * A null field means the server omitted the property, and the refresh keeps the local
     * value. `isReadOnly` is null without a privilege set, unlike [extractCalendars], which
     * then assumes read-only: discovery errs safe, a refresh keeps what it has.
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

    /** Returns the first `<getctag>` text, or null. */
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
     * Returns the responses whose resourcetype holds `<calendar>` under a successful propstat.
     *
     * A calendar without a privilege set that grants writes, or with a `<read-only>` element,
     * is read-only.
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
            var statusOk = true  // Nothing reads this; inclusion uses resourceTypeStatusOk.
            var hasWritePrivilege = false
            var isReadOnly = false
            // A response may split properties over several propstats (RFC 4918), as Stalwart
            // and Radicale do; only the status of the one holding resourcetype decides.
            var currentPropstatHasResourceType = false
            var currentPropstatStatus: String? = null
            var resourceTypeStatusOk = true
            // RFC 4791 supported-calendar-component-set
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
                                statusOk = true
                                hasWritePrivilege = false
                                isReadOnly = false
                                resourceTypeStatusOk = true
                                currentPropstatHasResourceType = false
                                currentPropstatStatus = null
                                currentComponents.clear()
                            }
                            "propstat" -> {
                                inPropstat = true
                                currentPropstatHasResourceType = false
                                currentPropstatStatus = null
                            }
                            "resourcetype" -> {
                                inResourceType = true
                                currentPropstatHasResourceType = true
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
                                currentPropstatStatus = statusText
                                if (statusText != null && !statusText.contains("200") && !statusText.contains("201")) {
                                    statusOk = false
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "response" -> {
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
                                if (currentPropstatHasResourceType) {
                                    val status = currentPropstatStatus
                                    resourceTypeStatusOk = status == null ||  // No status means OK.
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
     * Reads a Depth-0 PROPFIND reply about one collection.
     *
     * Only the `<response>` whose href names [requestedPath] counts (compared as
     * decoded paths, ignoring a trailing slash). Returns null, meaning "could not
     * tell", when the body is not parseable XML, when no response or more than one
     * response names that path, or when no `<resourcetype>` was read from a
     * successful propstat. A null must never be read as "not a calendar": callers
     * use a non-null result as evidence that a calendar has gone.
     *
     * Two server answers for a missing resource come back as
     * [CalDavQuirks.ProbedCollection.absent]: a response-level `<status>` of
     * 404/410 (RFC 4918 §14.24, used instead of propstat), and a response
     * whose successful propstats hold no properties at all. The second is how some
     * servers answer for a URL that does not exist; a real resource always has a
     * resourcetype (RFC 4918 §15.9), and a reply that returns some properties but
     * not that one stays unreadable.
     */
    fun extractProbedCollection(xml: String, requestedPath: String): CalDavQuirks.ProbedCollection? {
        if (xml.isBlank()) return null
        val wantedPath = comparablePath(requestedPath)
        return try {
            val parser = createParser(xml)
            val matches = mutableListOf<CalDavQuirks.ProbedCollection?>()

            var inResponse = false
            var inPropstat = false
            var inResourceType = false
            var inSupportedComponentSet = false
            var currentHref: String? = null
            var currentDisplayName: String? = null
            var isCalendar = false
            var resourceTypeSeenOk = false
            var propstatHasResourceType = false
            var propstatSaysCalendar = false
            var propstatStatus: String? = null
            var inProp = false
            var propstatPropCount = 0
            var okPropstatCount = 0
            var okPropCount = 0
            var failedPropstatCount = 0
            var responseStatus: String? = null
            val currentComponents = mutableSetOf<String>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        // Count before any readText() below moves the parser past this tag.
                        // Any element inside <prop> is a returned property (or part of one).
                        if (inProp) propstatPropCount++
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentDisplayName = null
                                isCalendar = false
                                resourceTypeSeenOk = false
                                okPropstatCount = 0
                                okPropCount = 0
                                failedPropstatCount = 0
                                responseStatus = null
                                currentComponents.clear()
                            }
                            "propstat" -> {
                                inPropstat = true
                                propstatHasResourceType = false
                                propstatSaysCalendar = false
                                propstatStatus = null
                                propstatPropCount = 0
                            }
                            "prop" -> if (inPropstat) inProp = true
                            "resourcetype" -> if (inPropstat) {
                                inResourceType = true
                                propstatHasResourceType = true
                            }
                            "calendar" -> if (inResourceType) propstatSaysCalendar = true
                            "supported-calendar-component-set" -> inSupportedComponentSet = true
                            "comp" -> if (inSupportedComponentSet) {
                                parser.getAttributeValue(null, "name")?.uppercase()?.let {
                                    currentComponents.add(it)
                                }
                            }
                            "href" -> if (inResponse && !inPropstat && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "displayname" -> if (inPropstat) {
                                currentDisplayName = readText(parser)?.takeIf { it.isNotBlank() }
                                    ?.let { decodeXmlEntities(it) }
                            }
                            "status" -> when {
                                inPropstat -> propstatStatus = readText(parser)
                                inResponse -> responseStatus = readText(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "resourcetype" -> inResourceType = false
                            "supported-calendar-component-set" -> inSupportedComponentSet = false
                            "prop" -> inProp = false
                            "propstat" -> {
                                // No status means OK (RFC 4918 default).
                                val ok = propstatStatus?.let { (parseHttpStatusCode(it) ?: 0) in 200..299 } ?: true
                                if (ok) {
                                    okPropstatCount++
                                    okPropCount += propstatPropCount
                                    // Only a resourcetype under a successful status is evidence.
                                    if (propstatHasResourceType) {
                                        resourceTypeSeenOk = true
                                        if (propstatSaysCalendar) isCalendar = true
                                    }
                                } else {
                                    failedPropstatCount++
                                }
                                inPropstat = false
                            }
                            "response" -> {
                                val href = currentHref
                                if (href != null && comparablePath(href) == wantedPath) {
                                    val statusSaysGone = responseStatus
                                        ?.let { parseHttpStatusCode(it) } in CalDavResult.RESOURCE_GONE_CODES
                                    val emptyAnswer = okPropstatCount > 0 && okPropCount == 0 &&
                                        failedPropstatCount == 0
                                    val absent = statusSaysGone || (!resourceTypeSeenOk && emptyAnswer)
                                    // Neither a resourcetype nor a clear "nothing here": no answer.
                                    matches.add(
                                        if (resourceTypeSeenOk || absent) {
                                            CalDavQuirks.ProbedCollection(
                                                // Same default name the listing parser uses.
                                                displayName = currentDisplayName ?: "Unnamed",
                                                isCalendar = isCalendar && !absent,
                                                supportedComponents = currentComponents.toSet(),
                                                absent = absent
                                            )
                                        } else {
                                            null
                                        }
                                    )
                                }
                                inResponse = false
                            }
                        }
                    }
                }
                parser.next()
            }

            matches.singleOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse collection probe: ${e.message}")
            null
        }
    }

    /**
     * Reduces an href (absolute URL or path) to a decoded path without a trailing slash, so a
     * server's encoded or slash-less spelling matches the request.
     */
    private fun comparablePath(href: String): String {
        // Decode first: some servers percent-encode the whole absolute URL
        // ("http%3A//host%3A8999/path/"), not just the path.
        val decoded = percentDecode(href)
        val schemeEnd = decoded.indexOf("://")
        val path = if (schemeEnd > 0 && decoded.substring(0, schemeEnd).all { it.isLetter() }) {
            val afterAuthority = decoded.indexOf('/', schemeEnd + 3)
            if (afterAuthority < 0) "/" else decoded.substring(afterAuthority)
        } else {
            decoded
        }
        return path.trimEnd('/')
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Decodes `%XX` escapes as UTF-8, keeping anything that isn't a valid escape. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '%' && i + 2 < value.length &&
                value[i + 1].isHexDigit() && value[i + 2].isHexDigit()
            ) {
                value.substring(i + 1, i + 3).toInt(16)
            } else {
                null
            }
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Returns href, etag and calendar-data for each response of a calendar-multiget or
     * calendar-query that carries a VCALENDAR.
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
                                // An etag without calendar-data: a member the server didn't
                                // return data for. The etag rules out the collection's own
                                // row, so warn whatever the href looks like (servers may use
                                // extensionless UIDs).
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
     * Returns the href and etag of each changed member in a sync-collection or PROPFIND
     * Depth:1 reply.
     *
     * Each response is classified in this order:
     *   - The collection's own row ([ResponseState.isCollection]) is skipped.
     *   - A deleted member ([ResponseState.isDeleted]) is skipped. A propstat-404 next to a
     *     successful propstat is only a missing property (RFC 4918 §13), such as `<getetag/>`
     *     404 on the collection's own row.
     *   - A response with an etag is a changed member.
     *   - Anything else is logged and dropped.
     *
     * The href's file extension is never used: some servers store events at extensionless
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
     * Returns the hrefs a sync-collection or PROPFIND Depth:1 reply reports as deleted; the
     * two reporting styles are on [ResponseState.isDeleted].
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
     * Reads a sync-collection reply in one pass: changed items, deleted hrefs, the sync-token
     * and whether the server truncated it.
     *
     * Classifies each response as [extractChangedItems] and [extractDeletedHrefs] do, and
     * never emits a 507 truncation marker as a member.
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
                                // listing (RFC 6578 §3.6), whichever href carried it. The
                                // other responses on the page are still classified below.
                                if (state.isTruncationMarker()) truncated = true
                                if (state.currentHref != null) {
                                    when {
                                        state.isCollection() -> { /* the collection's own row */ }
                                        // The truncation signal, never emitted as a member.
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
     * Holds what one `<response>` said, for [extractChangedItems], [extractDeletedHrefs] and
     * [extractSyncCollectionData]: its href and etag, the statuses seen at response and
     * propstat level, and whether its resourcetype holds `<collection/>`.
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
            // RFC 6578 §3.6: a truncated sync-collection reports 507 as the response-level
            // status of the collection's own <response>, not in a propstat.
            if (code == 507) sawStatus507 = true
            if (propstatDepth == 0) {
                if (is404) responseLevel404 = true
            } else {
                if (is404) sawPropstat404 = true
                if (is2xx) sawSuccessfulPropstat = true
            }
        }

        /** Returns whether this `<response>` carried a 507 (RFC 6578 §3.6 truncation). */
        fun isTruncationMarker(): Boolean = sawStatus507

        /**
         * Returns whether this `<response>` is the collection's own row, not a member.
         *
         * The main signal is a trailing `/` on the href: "Wherever a server produces a URL
         * referring to a collection, the server SHOULD include the trailing slash" (RFC 4918
         * §5.2). Verified across 7 server families; every probed collection row has it.
         *
         * The fallback, a resourcetype holding `<collection/>`, covers servers that drop the
         * slash. The fetchAllEtags, fetchEtagsInRange and syncCollection requests don't ask
         * for resourcetype, so it fires only when a server volunteers it (RFC 4918 §9.1
         * allows extra properties). Never add resourcetype to those requests: iCloud answers
         * with a separate propstat-404 per member and the response grows past the read
         * timeout.
         */
        fun isCollection(): Boolean {
            val href = currentHref ?: return false
            return href.endsWith("/") || resourcetypeContainsCollection
        }

        /**
         * Returns whether the server reports the resource as deleted, by either:
         *   - a 404 directly inside `<response>`, the shape RFC 6578 §3.2 requires for a
         *     removed member, or
         *   - a propstat-404 with no successful propstat beside it, a convention some servers
         *     use (`<propstat><prop/><status>404</status></propstat>`), not an RFC rule.
         *
         * A propstat-404 next to a 2xx propstat, such as `<displayname/>` 404 beside
         * `<getetag>` 200, only reports a missing property and is not a deletion.
         */
        fun isDeleted(): Boolean =
            currentHref != null &&
                (responseLevel404 || (sawPropstat404 && !sawSuccessfulPropstat))
    }

    /** Calls [onResponse] at each `</response>` that had an href, with its [ResponseState]. */
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

    /** Advances past the current start tag and returns its trimmed text, or null if none. */
    private fun readText(parser: XmlPullParser): String? {
        parser.next()
        return if (parser.eventType == XmlPullParser.TEXT) {
            parser.text.trim()
        } else {
            null
        }
    }

    /**
     * Returns the current element's trimmed text, which may be a CDATA section (iCloud sends
     * calendar-data as CDATA).
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
