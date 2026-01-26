package org.onekash.kashcal.sync.provider.icloud

import org.onekash.kashcal.sync.quirks.CalDavQuirks
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/**
 * iCloud-specific CalDAV quirks.
 *
 * iCloud CalDAV has several unique behaviors:
 * - Uses non-prefixed XML namespaces (xmlns="DAV:" instead of d:)
 * - Wraps calendar-data in CDATA blocks
 * - Redirects to regional servers (p*-caldav.icloud.com)
 * - Requires app-specific passwords for third-party apps
 */
class ICloudQuirks @Inject constructor() : CalDavQuirks {

    override val providerId = "icloud"
    override val displayName = "iCloud"
    override val baseUrl = "https://caldav.icloud.com"
    override val requiresAppSpecificPassword = true

    override fun extractPrincipalUrl(responseBody: String): String? {
        // iCloud uses non-prefixed namespaces, try multiple patterns
        val patterns = listOf(
            """<d:current-user-principal>\s*<d:href>([^<]+)</d:href>""",
            """<D:current-user-principal>\s*<D:href>([^<]+)</D:href>""",
            """current-user-principal.*?<.*?href[^>]*>([^<]+)</""",
            """<[^:]*:?current-user-principal[^>]*>\s*<[^:]*:?href[^>]*>([^<]+)</"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    override fun extractCalendarHomeUrl(responseBody: String): String? {
        val patterns = listOf(
            """calendar-home-set.*?<.*?href[^>]*>([^<]+)</""",
            """<c:calendar-home-set>\s*<d:href>([^<]+)</d:href>""",
            """<C:calendar-home-set>\s*<D:href>([^<]+)</D:href>""",
            """<[^:]*:?calendar-home-set[^>]*>\s*<[^:]*:?href[^>]*>([^<]+)</"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    override fun extractCalendars(responseBody: String, baseHost: String): List<CalDavQuirks.ParsedCalendar> {
        val calendars = mutableListOf<CalDavQuirks.ParsedCalendar>()

        // Match both <d:response> and <response xmlns="...">
        val responseRegex = Regex(
            """<(?:d:)?response[^>]*>(.*?)</(?:d:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]

            // Check if it's a calendar (has <calendar> inside <resourcetype>)
            // Must check resourcetype specifically to avoid matching <calendar-color> etc.
            val isCalendar = hasCalendarResourceType(responseXml)

            if (isCalendar) {
                val href = extractHref(responseXml) ?: continue
                val displayName = extractDisplayName(responseXml) ?: "Unnamed"

                // Skip inbox/outbox/tasks
                if (shouldSkipCalendar(href, displayName)) continue

                val color = extractCalendarColor(responseXml)
                val ctag = extractCtagFromResponse(responseXml)
                val isReadOnly = responseXml.contains("read-only", ignoreCase = true)

                calendars.add(
                    CalDavQuirks.ParsedCalendar(
                        href = href,
                        displayName = displayName,
                        color = color,
                        ctag = ctag,
                        isReadOnly = isReadOnly
                    )
                )
            }
        }

        return calendars
    }

    override fun extractICalData(responseBody: String): List<CalDavQuirks.ParsedEventData> {
        val events = mutableListOf<CalDavQuirks.ParsedEventData>()

        // Match response elements
        val responseRegex = Regex(
            """<(?:d:)?response[^>]*>(.*?)</(?:d:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]
            val href = extractHref(responseXml) ?: continue
            val etag = extractEtag(responseXml)
            val icalData = extractCalendarData(responseXml)

            if (icalData != null && icalData.contains("BEGIN:VCALENDAR")) {
                events.add(
                    CalDavQuirks.ParsedEventData(
                        href = href,
                        etag = etag,
                        icalData = icalData
                    )
                )
            }
        }

        return events
    }

    override fun extractSyncToken(responseBody: String): String? {
        val patterns = listOf(
            """<(?:d:)?sync-token[^>]*>([^<]+)</(?:d:)?sync-token>""",
            """sync-token[^>]*>([^<]+)</"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }

    override fun extractCtag(responseBody: String): String? {
        val patterns = listOf(
            """<(?:cs:)?getctag[^>]*>([^<]+)</(?:cs:)?getctag>""",
            """getctag[^>]*>([^<]+)</"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val match = regex.find(responseBody)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }

    override fun buildCalendarUrl(href: String, baseHost: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            "$baseHost$href"
        }
    }

    override fun buildEventUrl(href: String, calendarUrl: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            // Extract base host from calendarUrl (e.g., "https://p180-caldav.icloud.com:443")
            val baseHost = if (calendarUrl.contains("://")) {
                val afterProtocol = calendarUrl.substringAfter("://")
                val host = afterProtocol.substringBefore("/")
                calendarUrl.substringBefore("://") + "://" + host
            } else {
                calendarUrl.substringBefore("/")
            }
            "$baseHost$href"
        }
    }

    override fun getAdditionalHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "KashCal/2.0 (Android)"
        )
    }

    override fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean {
        // 403 or specific error message indicates invalid sync token
        return responseCode == 403 ||
            responseBody.contains("valid-sync-token", ignoreCase = true)
    }

    override fun extractDeletedHrefs(responseBody: String): List<String> {
        val deletedHrefs = mutableListOf<String>()

        // Match response elements
        val responseRegex = Regex(
            """<(?:d:)?response[^>]*>(.*?)</(?:d:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]

            // Check if this response indicates a deleted item (404 status)
            val isDeleted = responseXml.contains("HTTP/1.1 404", ignoreCase = true) ||
                responseXml.contains("404 Not Found", ignoreCase = true)

            if (isDeleted) {
                val href = extractHref(responseXml)
                if (href != null) {
                    deletedHrefs.add(href)
                }
            }
        }

        return deletedHrefs
    }

    override fun extractChangedItems(responseBody: String): List<Pair<String, String?>> {
        val items = mutableListOf<Pair<String, String?>>()

        // Match response elements
        val responseRegex = Regex(
            """<(?:d:)?response[^>]*>(.*?)</(?:d:)?response>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        for (match in responseRegex.findAll(responseBody)) {
            val responseXml = match.groupValues[1]

            // Skip 404 (deleted) items - those are handled by extractDeletedHrefs()
            if (responseXml.contains("HTTP/1.1 404", ignoreCase = true) ||
                responseXml.contains("404 Not Found", ignoreCase = true)) {
                continue
            }

            // Extract href
            val href = extractHref(responseXml) ?: continue

            // Skip non-.ics files (like the calendar collection itself)
            if (!href.endsWith(".ics")) continue

            // Extract etag
            val etag = extractEtag(responseXml)

            items.add(Pair(href, etag))
        }

        return items
    }

    override fun shouldSkipCalendar(href: String, displayName: String?): Boolean {
        val hrefLower = href.lowercase()
        val nameLower = displayName?.lowercase() ?: ""

        return hrefLower.contains("inbox") ||
            hrefLower.contains("outbox") ||
            hrefLower.contains("notification") ||
            nameLower.contains("tasks") ||
            nameLower.contains("reminders")
    }

    override fun formatDateForQuery(epochMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        return String.format(
            "%04d%02d%02dT000000Z",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    // Private helper methods

    private fun extractHref(xml: String): String? {
        val regex = Regex("""<(?:d:)?href[^>]*>([^<]+)</(?:d:)?href>""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun extractDisplayName(xml: String): String? {
        val regex = Regex("""<(?:d:)?displayname[^>]*>([^<]*)</(?:d:)?displayname>""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractCalendarColor(xml: String): String? {
        val regex = Regex("""<(?:ic:)?calendar-color[^>]*>([^<]+)</""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractCtagFromResponse(xml: String): String? {
        val regex = Regex("""<(?:cs:)?getctag[^>]*>([^<]+)</""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractEtag(xml: String): String? {
        val regex = Regex("""<(?:d:)?getetag[^>]*>"?([^"<]+)"?</""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.trim('"')
    }

    private fun extractCalendarData(xml: String): String? {
        // Try patterns in order of specificity
        val patterns = listOf(
            // CDATA format (iCloud)
            """<(?:c:|cal:)?calendar-data[^>]*><!\[CDATA\[(.*?)\]\]></(?:c:|cal:)?calendar-data>""",
            """<calendar-data[^>]*><!\[CDATA\[(.*?)\]\]></calendar-data>""",
            // Standard format without CDATA
            """<(?:c:|cal:)?calendar-data[^>]*>(.*?)</(?:c:|cal:)?calendar-data>""",
            """<calendar-data[^>]*>(.*?)</calendar-data>"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val match = regex.find(xml)
            if (match != null) {
                val data = match.groupValues[1]
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .trim()
                if (data.contains("BEGIN:VCALENDAR")) {
                    return data
                }
            }
        }
        return null
    }

    /**
     * Check if response has calendar resourcetype.
     *
     * Looks specifically for <calendar xmlns="...caldav..."/> inside <resourcetype>.
     * This avoids false positives from other tags like <calendar-color> which may
     * appear in 404 propstat sections for non-calendar collections.
     *
     * Real iCloud examples:
     * - Calendar: <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
     * - Root collection: <resourcetype><collection/></resourcetype> (no calendar)
     */
    private fun hasCalendarResourceType(xml: String): Boolean {
        // Extract resourcetype element first
        val resourceTypeRegex = Regex(
            """<(?:d:)?resourcetype[^>]*>(.*?)</(?:d:)?resourcetype>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val resourceTypeMatch = resourceTypeRegex.find(xml) ?: return false
        val resourceTypeContent = resourceTypeMatch.groupValues[1]

        // Check for <calendar> with caldav namespace inside resourcetype
        // Handles: <calendar xmlns="...caldav.../>, <c:calendar/>, <calendar/>
        return resourceTypeContent.contains("<calendar", ignoreCase = true) &&
            (resourceTypeContent.contains("caldav", ignoreCase = true) ||
             resourceTypeContent.contains("<c:calendar", ignoreCase = true))
    }
}
