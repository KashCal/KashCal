package org.onekash.kashcal.sync.parser

import android.util.Log
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
            var currentComponents = mutableSetOf<String>()

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
                                currentComponents = mutableSetOf()
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
                            "write", "write-content" -> if (inPrivilegeSet) hasWritePrivilege = true
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
                                    val href = currentHref!!
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
                                currentIcalData!!.contains("BEGIN:VCALENDAR")) {
                                events.add(
                                    CalDavQuirks.ParsedEventData(
                                        href = currentHref!!,
                                        etag = currentEtag,
                                        icalData = currentIcalData!!
                                    )
                                )
                            } else if (currentHref != null && currentIcalData == null &&
                                currentHref!!.endsWith(".ics")) {
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
     * Extract changed items (href + etag pairs) from sync-collection response.
     * Filters out deleted items (404 status) and non-.ics files.
     */
    fun extractChangedItems(xml: String): List<Pair<String, String?>> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val items = mutableListOf<Pair<String, String?>>()

            var inResponse = false
            var currentHref: String? = null
            var currentEtag: String? = null
            var isDeleted = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentEtag = null
                                isDeleted = false
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "getetag" -> {
                                val rawEtag = readText(parser)
                                currentEtag = EtagUtils.normalizeEtag(rawEtag)
                            }
                            "status" -> {
                                val statusText = readText(parser)
                                if (statusText?.contains("404") == true) {
                                    isDeleted = true
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (!isDeleted && currentHref != null && currentHref!!.endsWith(".ics")) {
                                items.add(Pair(currentHref!!, currentEtag))
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse changed items: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract deleted hrefs from sync-collection response.
     * Returns hrefs that have 404 status.
     */
    fun extractDeletedHrefs(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return try {
            val parser = createParser(xml)
            val deleted = mutableListOf<String>()

            var inResponse = false
            var currentHref: String? = null
            var isDeleted = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                isDeleted = false
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "status" -> {
                                val statusText = readText(parser)
                                if (statusText?.contains("404") == true) {
                                    isDeleted = true
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (isDeleted && currentHref != null) {
                                deleted.add(currentHref!!)
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
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
     */
    data class SyncCollectionData(
        val syncToken: String?,
        val changedItems: List<Pair<String, String?>>,
        val deletedHrefs: List<String>
    )

    fun extractSyncCollectionData(xml: String): SyncCollectionData {
        if (xml.isBlank()) return SyncCollectionData(null, emptyList(), emptyList())
        return try {
            val parser = createParser(xml)
            val changedItems = mutableListOf<Pair<String, String?>>()
            val deletedHrefs = mutableListOf<String>()
            var syncToken: String? = null

            var inResponse = false
            var currentHref: String? = null
            var currentEtag: String? = null
            var isDeleted = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentEtag = null
                                isDeleted = false
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                currentHref = readText(parser)
                            }
                            "getetag" -> {
                                val rawEtag = readText(parser)
                                currentEtag = EtagUtils.normalizeEtag(rawEtag)
                            }
                            "status" -> {
                                val statusText = readText(parser)
                                if (statusText?.contains("404") == true) {
                                    isDeleted = true
                                }
                            }
                            "sync-token" -> {
                                syncToken = readText(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (currentHref != null) {
                                if (isDeleted) {
                                    deletedHrefs.add(currentHref!!)
                                } else if (currentHref!!.endsWith(".ics")) {
                                    changedItems.add(Pair(currentHref!!, currentEtag))
                                }
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            SyncCollectionData(syncToken, changedItems, deletedHrefs)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse sync collection data: ${e.message}")
            SyncCollectionData(null, emptyList(), emptyList())
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
