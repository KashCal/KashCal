package org.onekash.kashcal.sync.quirks

import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe

/**
 * Abstraction for CalDAV provider-specific behaviors.
 *
 * Different CalDAV servers have quirks
 * in their XML responses, authentication flows, and supported features.
 * This interface allows the sync layer to handle these differences cleanly.
 */
interface CalDavQuirks {

    /** Provider identifier (e.g., "icloud", "fastmail") */
    val providerId: String

    /** Human-readable provider name */
    val displayName: String

    /** Base CalDAV URL for this provider */
    val baseUrl: String

    /** Whether this provider requires app-specific passwords */
    val requiresAppSpecificPassword: Boolean

    /**
     * Extract principal URL from PROPFIND response.
     * Different providers use different XML namespace formats.
     */
    fun extractPrincipalUrl(responseBody: String): String?

    /**
     * Extract all calendar home URLs from principal PROPFIND response.
     * RFC 4791 Section 6.2.1 allows multiple hrefs in calendar-home-set.
     */
    fun extractCalendarHomeUrls(responseBody: String): List<String>

    /**
     * Extract first calendar home URL from principal PROPFIND response.
     */
    fun extractCalendarHomeUrl(responseBody: String): String? = extractCalendarHomeUrls(responseBody).firstOrNull()

    /**
     * Extract `calendar-user-address-set` entries from principal
     * PROPFIND response (RFC 6638 §2.4.1). Returns the user's
     * CAL-ADDRESS forms (mailto, urn:uuid, principal-relative path,
     * full HTTP principal URI). iCloud's `preferred="1"` attribute
     * hoists matching entries to the front of the list. Returns empty
     * list on any extraction failure — the discovery flow treats
     * missing/empty as non-fatal.
     */
    fun extractCalendarUserAddresses(responseBody: String): List<String>

    /**
     * Extract the scheduling Outbox URL from a principal PROPFIND response
     * (RFC 6638 §2.1.1 CALDAV:schedule-outbox-URL). Returns null when the
     * property is empty or absent — per the RFC, the calendar user is then
     * not enabled for sending scheduling messages. The discovery flow treats
     * null as non-fatal.
     */
    fun extractScheduleOutboxUrl(responseBody: String): String?

    /**
     * Extract calendar list from calendar-home PROPFIND response.
     */
    fun extractCalendars(responseBody: String, baseHost: String): List<ParsedCalendar>

    /**
     * Extract iCal data from REPORT response.
     * Some providers wrap in CDATA, others use XML entities.
     */
    fun extractICalData(responseBody: String): List<ParsedEventData>

    /**
     * Extract sync-token from response for incremental sync.
     */
    fun extractSyncToken(responseBody: String): String?

    /**
     * Extract ctag (collection tag) for change detection.
     */
    fun extractCtag(responseBody: String): String?

    /**
     * Extract per-calendar metadata (ctag + displayName + color + isReadOnly)
     * from the extended getCtag PROPFIND response. Used by the per-pull
     * metadata refresh path in PullStrategy. Returns null when ctag is absent.
     */
    fun extractCalendarMetadata(responseBody: String): CalendarMetadataProbe?

    /**
     * Build the full URL for a calendar given its href.
     */
    fun buildCalendarUrl(href: String, baseHost: String): String

    /**
     * Build the full URL for an event given its href.
     */
    fun buildEventUrl(href: String, calendarUrl: String): String

    /**
     * Get additional headers required by this provider.
     */
    fun getAdditionalHeaders(): Map<String, String>

    /**
     * Check if a response indicates the sync-token is invalid/expired.
     */
    fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean

    /**
     * Extract deleted resource hrefs from sync-collection response.
     * In CalDAV, deleted items are indicated by 404 status in the response.
     * @param responseBody The XML response body from sync-collection
     * @return List of hrefs for deleted resources
     */
    fun extractDeletedHrefs(responseBody: String): List<String>

    /**
     * Extract changed item hrefs and etags from sync-collection response.
     * Unlike extractICalData(), this does NOT require calendar-data to be present.
     * Used for incremental sync (RFC 6578) where sync-collection returns hrefs/etags,
     * and we then fetch full event data via calendar-multiget.
     * @param responseBody The XML response body from sync-collection
     * @return List of (href, etag) pairs for changed/added resources with 200 OK status
     */
    fun extractChangedItems(responseBody: String): List<Pair<String, String?>>

    /**
     * Single-pass extraction of sync token, changed items, and deleted hrefs.
     * Default implementation calls the three individual methods (3 XML passes).
     * Override with CalDavXmlParser.extractSyncCollectionData() for single-pass efficiency.
     */
    fun extractSyncCollectionData(responseBody: String): SyncCollectionData {
        return SyncCollectionData(
            syncToken = extractSyncToken(responseBody),
            changedItems = extractChangedItems(responseBody),
            deletedHrefs = extractDeletedHrefs(responseBody)
        )
    }

    /**
     * Result of single-pass sync-collection response parsing.
     *
     * @property truncated True when the response reported RFC 6578 §3.6 truncation
     *   *inside* the multistatus body — a `<response>` for the collection whose
     *   `<status>` is `507 Insufficient Storage` — rather than as a top-level HTTP
     *   507. The client must re-issue the report on [syncToken] to fetch the rest.
     *   The 3-pass default cannot see this (it makes no status pass), so it leaves
     *   this false; only the single-pass [CalDavXmlParser.extractSyncCollectionData]
     *   sets it.
     */
    data class SyncCollectionData(
        val syncToken: String?,
        val changedItems: List<Pair<String, String?>>,
        val deletedHrefs: List<String>,
        val truncated: Boolean = false
    )

    /**
     * Check if a calendar href should be skipped (inbox, outbox, etc).
     */
    fun shouldSkipCalendar(href: String, displayName: String?): Boolean

    /**
     * Format date for time-range filter in REPORT query.
     * @param epochMillis timestamp in milliseconds
     * @return formatted date string (e.g., "20240101T000000Z")
     */
    fun formatDateForQuery(epochMillis: Long): String

    /**
     * Default sync range - how far back to sync.
     * @return milliseconds (default: 1 year)
     */
    fun getDefaultSyncRangeBack(): Long = 365L * 24 * 60 * 60 * 1000

    /**
     * Default sync range - how far forward to sync.
     * @return milliseconds (default: far future - Jan 1, 2100 UTC)
     */
    fun getDefaultSyncRangeForward(): Long = 4102444800000L  // Jan 1, 2100 UTC

    /**
     * Parsed calendar info from PROPFIND response.
     */
    data class ParsedCalendar(
        val href: String,
        val displayName: String,
        val color: String?,
        val ctag: String?,
        val isReadOnly: Boolean = false,
        val supportedComponents: Set<String> = emptySet()
    )

    /**
     * Parsed event data from REPORT response.
     */
    data class ParsedEventData(
        val href: String,
        val etag: String?,
        val icalData: String
    )
}
