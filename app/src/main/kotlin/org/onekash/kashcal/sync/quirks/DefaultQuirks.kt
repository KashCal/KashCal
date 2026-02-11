package org.onekash.kashcal.sync.quirks

import org.onekash.kashcal.sync.parser.CalDavXmlParser
import java.util.Calendar
import java.util.TimeZone

/**
 * Default CalDAV quirks for generic CalDAV servers.
 *
 * Works with RFC-compliant servers like:
 * - Nextcloud
 * - Baikal
 * - Radicale
 * - FastMail
 * - Any standard CalDAV server
 *
 * Unlike ICloudQuirks, this implementation:
 * - Takes server URL as constructor parameter (from Account.homeSetUrl)
 * - Does NOT require app-specific passwords
 *
 * Uses XmlPullParser for robust XML parsing with proper namespace handling.
 */
class DefaultQuirks(
    private val serverBaseUrl: String
) : CalDavQuirks {

    private val xmlParser = CalDavXmlParser()

    override val providerId = "caldav"
    override val displayName = "CalDAV"
    override val baseUrl: String get() = serverBaseUrl
    override val requiresAppSpecificPassword = false

    override fun extractPrincipalUrl(responseBody: String): String? {
        return xmlParser.extractPrincipalUrl(responseBody)
    }

    override fun extractCalendarHomeUrl(responseBody: String): String? {
        return xmlParser.extractCalendarHomeUrl(responseBody)
    }

    override fun extractCalendars(responseBody: String, baseHost: String): List<CalDavQuirks.ParsedCalendar> {
        val calendars = xmlParser.extractCalendars(responseBody)
        return calendars.filter { parsed ->
            !shouldSkipCalendar(parsed.href, parsed.displayName) &&
            // Skip calendars that only support non-VEVENT components (VTODO-only, VJOURNAL-only)
            // Empty set = server didn't advertise components → keep (name-matching fallback handles it)
            (parsed.supportedComponents.isEmpty() || "VEVENT" in parsed.supportedComponents)
        }
    }

    override fun extractICalData(responseBody: String): List<CalDavQuirks.ParsedEventData> {
        return xmlParser.extractICalData(responseBody)
    }

    override fun extractSyncToken(responseBody: String): String? {
        return xmlParser.extractSyncToken(responseBody)
    }

    override fun extractCtag(responseBody: String): String? {
        return xmlParser.extractCtag(responseBody)
    }

    override fun buildCalendarUrl(href: String, baseHost: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            // Normalize base host (remove trailing slash)
            val normalizedHost = baseHost.trimEnd('/')
            // Ensure href starts with /
            val normalizedHref = if (href.startsWith("/")) href else "/$href"
            "$normalizedHost$normalizedHref"
        }
    }

    override fun buildEventUrl(href: String, calendarUrl: String): String {
        return if (href.startsWith("http")) {
            href
        } else {
            // Extract base host from calendarUrl
            val baseHost = if (calendarUrl.contains("://")) {
                val afterProtocol = calendarUrl.substringAfter("://")
                val host = afterProtocol.substringBefore("/")
                calendarUrl.substringBefore("://") + "://" + host
            } else {
                calendarUrl.substringBefore("/")
            }
            // Ensure href starts with /
            val normalizedHref = if (href.startsWith("/")) href else "/$href"
            "$baseHost$normalizedHref"
        }
    }

    override fun getAdditionalHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "KashCal/2.0 (Android)"
        )
    }

    override fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean {
        // 410 Gone or specific DAV error body indicates expired sync token.
        // A bare 403 is "permission denied", not sync-token expiry (Issue #51).
        return responseCode == 410 ||
            responseBody.contains("valid-sync-token", ignoreCase = true)
    }

    override fun extractDeletedHrefs(responseBody: String): List<String> {
        return xmlParser.extractDeletedHrefs(responseBody)
    }

    override fun extractChangedItems(responseBody: String): List<Pair<String, String?>> {
        return xmlParser.extractChangedItems(responseBody)
    }

    override fun shouldSkipCalendar(href: String, displayName: String?): Boolean {
        val hrefLower = href.lowercase()
        val nameLower = displayName?.lowercase() ?: ""

        return hrefLower.contains("inbox") ||
            hrefLower.contains("outbox") ||
            hrefLower.contains("notification") ||
            hrefLower.endsWith("/tasks/") ||
            nameLower == "tasks" ||
            nameLower == "reminders"
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
}
