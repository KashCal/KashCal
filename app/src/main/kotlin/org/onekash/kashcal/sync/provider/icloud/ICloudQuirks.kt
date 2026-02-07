package org.onekash.kashcal.sync.provider.icloud

import org.onekash.kashcal.sync.parser.CalDavXmlParser
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
 *
 * Uses XmlPullParser for robust XML parsing with proper namespace handling.
 */
class ICloudQuirks @Inject constructor() : CalDavQuirks {

    private val xmlParser = CalDavXmlParser()

    override val providerId = "icloud"
    override val displayName = "iCloud"
    override val baseUrl = "https://caldav.icloud.com"
    override val requiresAppSpecificPassword = true

    override fun extractPrincipalUrl(responseBody: String): String? {
        return xmlParser.extractPrincipalUrl(responseBody)
    }

    override fun extractCalendarHomeUrl(responseBody: String): String? {
        return xmlParser.extractCalendarHomeUrl(responseBody)
    }

    override fun extractCalendars(responseBody: String, baseHost: String): List<CalDavQuirks.ParsedCalendar> {
        val calendars = xmlParser.extractCalendars(responseBody)
        // Apply shouldSkipCalendar filter (inbox/outbox/tasks)
        return calendars.filter { !shouldSkipCalendar(it.href, it.displayName) }
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
        val url = if (href.startsWith("http")) {
            href
        } else {
            "$baseHost$href"
        }
        // Normalize to canonical form (p180-caldav.icloud.com → caldav.icloud.com)
        return ICloudUrlNormalizer.normalize(url) ?: url
    }

    override fun buildEventUrl(href: String, calendarUrl: String): String {
        val url = if (href.startsWith("http")) {
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
        // Normalize to canonical form (p180-caldav.icloud.com → caldav.icloud.com)
        return ICloudUrlNormalizer.normalize(url) ?: url
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
}
