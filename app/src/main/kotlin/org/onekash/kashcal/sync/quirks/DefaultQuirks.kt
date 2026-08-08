package org.onekash.kashcal.sync.quirks

import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
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
 * - Fastmail
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
            // tell it's tasks-only and keep it. A VTODO-only list therefore surfaces only on a
            // server that omits the component set; name matching is deliberately NOT used to hide
            // it, because a real events calendar the user happened to name "Tasks" must never drop.
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

    override fun extractSyncCollectionData(responseBody: String): CalDavQuirks.SyncCollectionData {
        return xmlParser.extractSyncCollectionData(responseBody)
    }

    override fun shouldSkipCalendar(href: String, displayName: String?): Boolean {
        // Skip the scheduling (inbox/outbox) and notification collections a server
        // may expose alongside real calendars, plus a generic server's task list.
        // Reserved words match only as whole PATH SEGMENTS, never as substrings, so a
        // user's real calendar "my-inbox-friends" or "outbox-archive" — or any account
        // whose username embeds one of these words — survives discovery. The task list
        // is matched only as the FINAL segment in its trailing-slash form (`.../tasks/`):
        // a server whose account segment is literally "tasks" keeps its calendars, and a
        // real events calendar the user named "Tasks" is not dropped on its name alone.
        return matchesReservedCollection(
            href = href,
            terminalSegments = setOf("tasks"),
        )
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
 * Path segments identifying the scheduling / notification collections a CalDAV
 * server exposes alongside real calendars (RFC 6638 §2.1). Matched as whole path
 * segments, never as substrings. Note "tasks" is deliberately NOT here: iCloud
 * exposes a real VTODO calendar at `/calendars/tasks/` (carrying the `<calendar>`
 * resourcetype), so a tasks-segment skip is a generic-server-only concern applied
 * in [DefaultQuirks] via its `terminalSegments`, not a universal reserved word.
 */
internal val RESERVED_CALENDAR_SEGMENTS =
    setOf("inbox", "outbox", "notification", "notifications")

/**
 * Shared collection-skip predicate for the CalDAV/CardDAV quirks. A collection is
 * skipped only when its href carries a reserved word as a whole PATH SEGMENT (never
 * as a substring). The display name is deliberately NOT a discriminator: this
 * predicate runs only on collections that already passed the positive
 * `<calendar>`/`<addressbook>` resourcetype gate, so a name match could only
 * false-drop a real collection the user named "Tasks"/"Reminders"/"Inbox". A genuine
 * VTODO-only task list is instead excluded downstream by the VEVENT component gate.
 *
 * @param terminalSegments extra segment words matched ONLY as the final path segment
 *   in its trailing-slash form (e.g. `.../tasks/`), so a real calendar at `.../tasks`
 *   without the slash — or any account whose *username* segment is "tasks" — is kept.
 *   Always union with [RESERVED_CALENDAR_SEGMENTS].
 */
internal fun matchesReservedCollection(
    href: String,
    terminalSegments: Set<String> = emptySet(),
): Boolean {
    val lower = href.lowercase()
    val segments = lower.split('/').filter { it.isNotEmpty() }

    if (segments.any { it in RESERVED_CALENDAR_SEGMENTS }) return true
    // Terminal-segment words require the trailing-slash form. Only the last path
    // component qualifies, and it must be followed by `/` in the original href.
    return terminalSegments.any { lower.endsWith("/$it/") }
}
