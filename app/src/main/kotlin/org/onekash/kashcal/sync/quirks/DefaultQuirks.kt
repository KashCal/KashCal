package org.onekash.kashcal.sync.quirks

import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import java.util.Calendar
import java.util.TimeZone

/**
 * Handles generic RFC-conformant CalDAV servers (Nextcloud, Baikal, Radicale, Fastmail and
 * others) through [CalDavXmlParser].
 *
 * Unlike [org.onekash.kashcal.sync.provider.icloud.ICloudQuirks], it takes the server URL as
 * a constructor parameter (the account's `homeSetUrl`, or the entered URL during discovery)
 * and doesn't require app-specific passwords.
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

    override fun extractWebcalFeeds(responseBody: String): List<CalDavQuirks.ParsedWebcalFeed>? =
        xmlParser.extractWebcalFeeds(responseBody)

    override fun classifyCalendarProbe(responseBody: String, requestedPath: String): Boolean? {
        val probed = xmlParser.extractProbedCollection(responseBody, requestedPath) ?: return null
        return probed.isCalendar &&
            isListable(requestedPath, probed.displayName, probed.supportedComponents)
    }

    /**
     * Decides inclusion for both calendar listings and calendar probes, so a calendar the
     * listing leaves out is also reported as not listable by a probe.
     */
    private fun isListable(href: String, displayName: String, supportedComponents: Set<String>): Boolean =
        !shouldSkipCalendar(href, displayName) &&
            // Skip calendars that support only non-VEVENT components (VTODO-only, VJOURNAL-only).
            // An empty set means the server advertised no component set, so it's kept. A
            // VTODO-only list therefore surfaces on a server that omits the set. Neither its
            // name nor its path is used to hide it: the creating client can pick both, so an events
            // calendar can be named "Tasks" or sit at `.../Tasks/` (#421).
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
        return if (href.startsWith("http")) {
            href
        } else {
            // Strip the host's trailing slash.
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
            // scheme://host of calendarUrl.
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
        // 410 Gone or a DAV:valid-sync-token error body means the token expired. A bare 403
        // is "permission denied", not expiry (#51).
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
        // Skips the scheduling (inbox, outbox) and notification collections a server may
        // expose beside real calendars. Task lists are left to the component gate in
        // [isListable].
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

/**
 * Path segments of the scheduling and notification collections a CalDAV server exposes
 * beside real calendars (RFC 6638 §2.1). "tasks" isn't one of them: a task list is told apart
 * by its component set, not its path, because an events calendar can sit at `.../Tasks/`
 * (#421).
 */
internal val RESERVED_CALENDAR_SEGMENTS =
    setOf("inbox", "outbox", "notification", "notifications")

/**
 * Returns whether an href names a reserved collection; shared by the CalDAV and CardDAV
 * quirks. A reserved word matches only as a whole path segment, never as a substring, so a
 * user's calendar "my-inbox-friends" or "outbox-archive", or an account whose username
 * embeds one of these words, survives discovery.
 *
 * The display name is never a discriminator: this runs only on collections that passed the
 * `<calendar>` or `<addressbook>` resourcetype gate, so a name match could only drop a real
 * collection the user named "Tasks", "Reminders" or "Inbox". A CalDAV task list is left to
 * the component gate in [DefaultQuirks.isListable].
 */
internal fun matchesReservedCollection(href: String): Boolean =
    href.lowercase().split('/').any { it in RESERVED_CALENDAR_SEGMENTS }
