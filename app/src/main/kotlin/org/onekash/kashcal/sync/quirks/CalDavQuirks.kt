package org.onekash.kashcal.sync.quirks

import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe

/**
 * Holds the per-provider differences in CalDAV XML responses, authentication and supported
 * features, so the sync layer stays provider-neutral.
 */
interface CalDavQuirks {

    /** Provider identifier ("icloud", "caldav"). */
    val providerId: String

    /** Human-readable provider name. */
    val displayName: String

    /** Base CalDAV URL for this provider. */
    val baseUrl: String

    /** Whether this provider requires app-specific passwords. */
    val requiresAppSpecificPassword: Boolean

    /** Extracts the principal URL from a PROPFIND response; providers differ in XML namespaces. */
    fun extractPrincipalUrl(responseBody: String): String?

    /**
     * Extracts every calendar home URL from a principal PROPFIND response. RFC 4791 §6.2.1
     * allows several hrefs in calendar-home-set.
     */
    fun extractCalendarHomeUrls(responseBody: String): List<String>

    /** Returns the first of [extractCalendarHomeUrls], or null if there is none. */
    fun extractCalendarHomeUrl(responseBody: String): String? = extractCalendarHomeUrls(responseBody).firstOrNull()

    /**
     * Extracts the `calendar-user-address-set` entries (RFC 6638 §2.4.1) from a principal
     * PROPFIND response: the user's CAL-ADDRESS forms (mailto, urn:uuid, principal-relative
     * path, full HTTP principal URI). Entries marked `preferred="1"` (iCloud) come first.
     * Returns an empty list on any extraction failure; discovery treats missing or empty as
     * non-fatal.
     */
    fun extractCalendarUserAddresses(responseBody: String): List<String>

    /**
     * Extracts the scheduling Outbox URL (RFC 6638 §2.1.1 CALDAV:schedule-outbox-URL) from a
     * principal PROPFIND response. Returns null when the property is empty or absent, which
     * per the RFC means the user isn't enabled to send scheduling messages. Discovery treats
     * null as non-fatal.
     */
    fun extractScheduleOutboxUrl(responseBody: String): String?

    /** Extracts the listable calendars from a calendar-home PROPFIND response. */
    fun extractCalendars(responseBody: String, baseHost: String): List<ParsedCalendar>

    /**
     * Extracts iCal data from a REPORT response; some providers wrap it in CDATA, others
     * escape it.
     */
    fun extractICalData(responseBody: String): List<ParsedEventData>

    /** Extracts the sync-token (RFC 6578) from a response. */
    fun extractSyncToken(responseBody: String): String?

    /** Extracts the ctag (collection tag) used for change detection. */
    fun extractCtag(responseBody: String): String?

    /**
     * Extracts one calendar's ctag, displayName, color and isReadOnly from the extended
     * getCtag PROPFIND response, which PullStrategy issues on every pull. Returns null when
     * the ctag is absent.
     */
    fun extractCalendarMetadata(responseBody: String): CalendarMetadataProbe?

    /**
     * Reads a Depth-0 probe of one calendar URL and says whether it is still a calendar this
     * provider's listing would include.
     *
     * true: still a listable calendar. false: the server describes the resource at
     * [requestedPath] and it isn't one (plain collection, tasks-only, or a reserved collection
     * the listing skips), or says nothing is there. null: the reply can't be read as an
     * answer about that path, so nothing may be concluded from it. The skip rules judge
     * [requestedPath], the form the calendar was stored under, not the reply's spelling of
     * the href.
     */
    fun classifyCalendarProbe(responseBody: String, requestedPath: String): Boolean?

    /**
     * Returns whether a redirect from [requestedHost] to [finalHost] stays on this provider's
     * own server, so the final answer still speaks for the calendar. Default: the same host,
     * ignoring case.
     */
    fun isSameServerRedirect(requestedHost: String, finalHost: String): Boolean =
        requestedHost.equals(finalHost, ignoreCase = true)

    /** Builds a calendar's full URL from its href. */
    fun buildCalendarUrl(href: String, baseHost: String): String

    /** Builds an event's full URL from its href. */
    fun buildEventUrl(href: String, calendarUrl: String): String

    /** Returns the extra headers this provider needs on every request. */
    fun getAdditionalHeaders(): Map<String, String>

    /** Returns whether a response says the sync-token is invalid or expired. */
    fun isSyncTokenInvalid(responseCode: Int, responseBody: String): Boolean

    /** Extracts the hrefs a sync-collection response reports deleted (a 404 status). */
    fun extractDeletedHrefs(responseBody: String): List<String>

    /**
     * Extracts (href, etag) pairs from a sync-collection, PROPFIND Depth-1 or calendar-query
     * response, skipping collections, deleted members and members without an etag. Unlike
     * [extractICalData] it needs no calendar-data; the caller fetches event bodies afterwards
     * with calendar-multiget.
     */
    fun extractChangedItems(responseBody: String): List<Pair<String, String?>>

    /**
     * Extracts the sync-token, changed items and deleted hrefs of a sync-collection response.
     * The default makes three XML passes and can't detect truncation; implementations
     * override it with the single-pass [CalDavXmlParser.extractSyncCollectionData].
     */
    fun extractSyncCollectionData(responseBody: String): SyncCollectionData {
        return SyncCollectionData(
            syncToken = extractSyncToken(responseBody),
            changedItems = extractChangedItems(responseBody),
            deletedHrefs = extractDeletedHrefs(responseBody)
        )
    }

    /**
     * Holds the parsed parts of a sync-collection response.
     *
     * @property truncated true when the multistatus body reports RFC 6578 §3.6 truncation: a
     *   `<response>` for the collection with `<status>` `507 Insufficient Storage`, as opposed
     *   to a top-level HTTP 507. The client must re-issue the report on [syncToken] to fetch
     *   the rest. Only [CalDavXmlParser.extractSyncCollectionData] sets it; the three-pass
     *   default has no status pass and leaves it false.
     */
    data class SyncCollectionData(
        val syncToken: String?,
        val changedItems: List<Pair<String, String?>>,
        val deletedHrefs: List<String>,
        val truncated: Boolean = false
    )

    /**
     * Returns whether a calendar href is a reserved collection to skip (inbox, outbox,
     * notifications).
     */
    fun shouldSkipCalendar(href: String, displayName: String?): Boolean

    /** Formats [epochMillis] for a REPORT time-range filter, e.g. "20240101T000000Z". */
    fun formatDateForQuery(epochMillis: Long): String

    /** Returns how far back to sync, in milliseconds (default one year). Only tests call it. */
    fun getDefaultSyncRangeBack(): Long = 365L * 24 * 60 * 60 * 1000

    /**
     * Returns how far forward to sync, as epoch milliseconds (default 2100-01-01 UTC). Only
     * tests call it.
     */
    fun getDefaultSyncRangeForward(): Long = 4102444800000L  // Jan 1, 2100 UTC

    /** One calendar parsed from a calendar-home PROPFIND response. */
    data class ParsedCalendar(
        val href: String,
        val displayName: String,
        val color: String?,
        val ctag: String?,
        val isReadOnly: Boolean = false,
        val supportedComponents: Set<String> = emptySet()
    )

    /**
     * Describes one collection from a Depth-0 PROPFIND reply. [absent] is true when the server
     * answered that nothing exists at that href; otherwise [isCalendar] comes from a
     * resourcetype the server returned.
     */
    data class ProbedCollection(
        val displayName: String,
        val isCalendar: Boolean,
        val supportedComponents: Set<String>,
        val absent: Boolean = false
    )

    /** One event parsed from a REPORT response. */
    data class ParsedEventData(
        val href: String,
        val etag: String?,
        val icalData: String
    )
}
