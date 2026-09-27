package org.onekash.kashcal.sync.provider.icloud

import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import org.onekash.kashcal.sync.quirks.CalDavQuirks
import org.onekash.kashcal.sync.quirks.matchesReservedCollection
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/**
 * Holds iCloud's CalDAV quirks. iCloud:
 * - uses non-prefixed XML namespaces (xmlns="DAV:" instead of d:)
 * - wraps calendar-data in CDATA blocks
 * - redirects to numbered partition hosts (p*-caldav.icloud.com), so built URLs are
 *   normalized to the canonical host ([ICloudUrlNormalizer])
 * - requires app-specific passwords for third-party apps
 *
 * Parsing goes through the namespace-aware [CalDavXmlParser].
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
            isListable(parsed.href, parsed.displayName, parsed.supportedComponents)
        }
    }

    override fun classifyCalendarProbe(responseBody: String, requestedPath: String): Boolean? {
        val probed = xmlParser.extractProbedCollection(responseBody, requestedPath) ?: return null
        return probed.isCalendar &&
            isListable(requestedPath, probed.displayName, probed.supportedComponents)
    }

    /**
     * The single include rule for calendar listings and calendar probes, so a
     * calendar the listing leaves out is also reported as not listable by a probe.
     */
    private fun isListable(href: String, displayName: String, supportedComponents: Set<String>): Boolean =
        !shouldSkipCalendar(href, displayName) &&
            // Skip calendars without VEVENT (VTODO-only, VJOURNAL-only). An empty set means the
            // server advertised none, so it can't be shown to be tasks-only and is kept. iCloud
            // always advertises the set (its Reminders list carries VTODO), so this hides
            // iCloud's tasks list. Name matching is deliberately not used, so a real calendar
            // named "Reminders" is never dropped.
            (supportedComponents.isEmpty() || "VEVENT" in supportedComponents)

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
        // Canonical form (p180-caldav.icloud.com → caldav.icloud.com)
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
        // Canonical form (p180-caldav.icloud.com → caldav.icloud.com)
        return ICloudUrlNormalizer.normalize(url) ?: url
    }

    override fun getAdditionalHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "KashCal/2.0 (Android)"
        )
    }

    override fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean {
        // 410 Gone or a valid-sync-token DAV error body means an expired sync-token. A bare
        // 403 is "permission denied", not sync-token expiry (Issue #51).
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

    /**
     * iCloud serves one account from the canonical host and its numbered
     * partition hosts (p*-caldav.icloud.com), and redirects between them, so any
     * pair of those hosts is the same server. Anything else is not.
     */
    override fun isSameServerRedirect(requestedHost: String, finalHost: String): Boolean =
        requestedHost.equals(finalHost, ignoreCase = true) ||
            (ICloudUrlNormalizer.isCalDavHost(requestedHost) && ICloudUrlNormalizer.isCalDavHost(finalHost))

    override fun shouldSkipCalendar(href: String, displayName: String?): Boolean {
        // Reserved words match only as whole path segments ([matchesReservedCollection]).
        // iCloud has no tasks path-segment skip: its `/calendars/tasks/` ("Reminders") is a
        // real <calendar> that is VTODO-only, so the VEVENT component gate in [isListable],
        // not a display-name match, keeps it hidden.
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
