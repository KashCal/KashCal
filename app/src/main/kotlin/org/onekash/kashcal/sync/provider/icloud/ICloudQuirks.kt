package org.onekash.kashcal.sync.provider.icloud

import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.matchesReservedCollection
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

    override fun extractCalendarHomeUrls(responseBody: String): List<String> {
        return xmlParser.extractCalendarHomeUrls(responseBody)
    }

    override fun extractCalendarUserAddresses(responseBody: String): List<String> {
        return xmlParser.extractCalendarUserAddresses(responseBody)
    }

    override fun extractScheduleOutboxUrl(responseBody: String): String? {
        return xmlParser.extractScheduleOutboxUrl(responseBody)
    }

    override fun extractCalendars(responseBody: String, baseHost: String): List<CalDavQuirks.ParsedCalendar> {
        val calendars = xmlParser.extractCalendars(responseBody)
        return calendars.filter { parsed ->
            !shouldSkipCalendar(parsed.href, parsed.displayName) &&
            // Skip calendars that only support non-VEVENT components (VTODO-only, VJOURNAL-only).
            // An empty set means the server didn't advertise a component set at all, so we can't
            // tell it's tasks-only and keep it. iCloud always advertises the set (its Reminders
            // list carries VTODO), so this branch keeps iCloud's tasks list off-screen; name
            // matching is deliberately NOT used to hide it, so a real calendar named "Reminders"
            // is never dropped.
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

    override fun extractCalendarMetadata(responseBody: String): CalendarMetadataProbe? {
        return xmlParser.extractCalendarMetadata(responseBody)
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

    override fun extractSyncCollectionData(responseBody: String): CalDavQuirks.SyncCollectionData {
        return xmlParser.extractSyncCollectionData(responseBody)
    }

    override fun shouldSkipCalendar(href: String, displayName: String?): Boolean {
        // Match a reserved word only as a whole PATH SEGMENT, never as a substring, so a
        // real calendar "my-inbox-friends" or an account whose username embeds one of
        // these words is not silently hidden. iCloud has NO tasks path-segment skip: its
        // `/calendars/tasks/` ("Reminders") is a real <calendar> that is VTODO-only, so
        // the VEVENT component gate — not a display-name match — is what keeps it hidden.
        return matchesReservedCollection(href = href)
    }

    override fun formatDateForQuery(epochMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMillis
        return String.format(
            java.util.Locale.ROOT,
            "%04d%02d%02dT000000Z",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
