package org.onekash.kashcal.sync.client

import org.onekash.kashcal.sync.client.model.*

/**
 * CalDAV client interface for server communication.
 *
 * Abstracts HTTP operations for CalDAV protocol:
 * - Discovery (PROPFIND for principal, calendar-home, calendars)
 * - Fetching (REPORT for calendar-query, calendar-multiget, sync-collection)
 * - Mutations (PUT for create/update, DELETE for remove)
 */
interface CalDavClient {

    // ========== Discovery ==========

    /**
     * Discover the CalDAV endpoint via RFC 6764 well-known URL.
     * Makes a request to /.well-known/caldav and follows redirects.
     *
     * @param serverUrl Base server URL (e.g., "https://nextcloud.example.com")
     * @return The final URL after following redirects, or original URL if well-known not supported
     */
    suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String>

    /**
     * Discover the user's principal URL from the server root.
     * Uses PROPFIND with current-user-principal property.
     *
     * @param serverUrl Base CalDAV server URL (e.g., "https://caldav.icloud.com")
     * @return Principal URL path or full URL
     */
    suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String>

    /**
     * Discover calendar home URLs from principal.
     * Uses PROPFIND with calendar-home-set property.
     * RFC 4791 Section 6.2.1 allows multiple home sets.
     *
     * @param principalUrl Full principal URL
     * @return List of calendar home URLs (typically 1, but can be multiple on SOGo/Cyrus)
     */
    suspend fun discoverCalendarHome(principalUrl: String): CalDavResult<List<String>>

    /**
     * List all calendars from calendar home.
     * Uses PROPFIND with Depth: 1 to enumerate collections.
     *
     * @param calendarHomeUrl Full calendar home URL
     * @return List of discovered calendars
     */
    suspend fun listCalendars(calendarHomeUrl: String): CalDavResult<List<CalDavCalendar>>

    // ========== Change Detection ==========

    /**
     * Get the current ctag (collection tag) for a calendar.
     * Used to detect if any changes have occurred since last sync.
     *
     * @param calendarUrl Full calendar URL
     * @return Current ctag value
     */
    suspend fun getCtag(calendarUrl: String): CalDavResult<String>

    /**
     * Get the current sync-token for incremental sync.
     *
     * @param calendarUrl Full calendar URL
     * @return Current sync-token
     */
    suspend fun getSyncToken(calendarUrl: String): CalDavResult<String?>

    // ========== Fetching ==========

    /**
     * Perform incremental sync using sync-collection REPORT (RFC 6578).
     * Returns only changed/deleted items since last sync.
     *
     * @param calendarUrl Full calendar URL
     * @param syncToken Previous sync token (null for initial sync)
     * @return Sync report with changes and new token
     */
    suspend fun syncCollection(
        calendarUrl: String,
        syncToken: String?
    ): CalDavResult<SyncReport>

    /**
     * Fetch events within a time range using calendar-query REPORT.
     * Used for initial sync or when sync-token is invalid.
     *
     * @param calendarUrl Full calendar URL
     * @param startMillis Start of time range (epoch millis)
     * @param endMillis End of time range (epoch millis)
     * @return List of events with iCal data
     */
    suspend fun fetchEventsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<CalDavEvent>>

    /**
     * Fetch only etags (not iCal data) for events in time range.
     * Used for etag-based fallback sync when sync-token expires (403/410).
     * Much lighter than fetchEventsInRange() - returns only href+etag pairs.
     *
     * @param calendarUrl Full calendar URL
     * @param startMillis Start of time range (epoch millis)
     * @param endMillis End of time range (epoch millis)
     * @return List of (href, etag) pairs for events in range
     */
    suspend fun fetchEtagsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<Pair<String, String?>>>

    /**
     * Fetch specific events by href using calendar-multiget REPORT.
     * Used to retrieve full iCal data for changed items from sync-collection.
     *
     * @param calendarUrl Full calendar URL
     * @param hrefs List of event hrefs to fetch
     * @return List of events with iCal data
     */
    suspend fun fetchEventsByHref(
        calendarUrl: String,
        hrefs: List<String>
    ): CalDavResult<List<CalDavEvent>>

    /**
     * Fetch a single event by its URL.
     *
     * @param eventUrl Full event URL
     * @return Event with iCal data and etag
     */
    suspend fun fetchEvent(eventUrl: String): CalDavResult<CalDavEvent>

    /**
     * Fetch only the ETag for an event URL using PROPFIND.
     *
     * This is a lightweight operation used as a fallback when PUT response
     * doesn't include an ETag header (e.g., Nextcloud). Per RFC 4791 Section 5.3.4,
     * servers SHOULD return ETag in PUT response but MAY not, in which case
     * clients should fetch it via PROPFIND.
     *
     * @param eventUrl Full event URL
     * @return ETag value (without quotes) or null if not found
     */
    suspend fun fetchEtag(eventUrl: String): CalDavResult<String?>

    // ========== Mutations ==========

    /**
     * Create a new event on the server.
     * Uses PUT with If-None-Match: * to ensure it doesn't exist.
     *
     * @param calendarUrl Full calendar URL
     * @param uid Event UID (will be used as filename)
     * @param icalData Complete iCal VCALENDAR data
     * @return Created event URL and etag
     */
    suspend fun createEvent(
        calendarUrl: String,
        uid: String,
        icalData: String
    ): CalDavResult<Pair<String, String>> // (url, etag)

    /**
     * Update an existing event on the server.
     * Uses PUT with If-Match: etag for optimistic locking.
     *
     * @param eventUrl Full event URL
     * @param icalData Complete iCal VCALENDAR data
     * @param etag Current etag for conflict detection
     * @return New etag after update
     */
    suspend fun updateEvent(
        eventUrl: String,
        icalData: String,
        etag: String
    ): CalDavResult<String> // new etag

    /**
     * Delete an event from the server.
     * Uses DELETE with If-Match: etag for optimistic locking.
     *
     * @param eventUrl Full event URL
     * @param etag Current etag for conflict detection
     * @return Success or error
     */
    suspend fun deleteEvent(
        eventUrl: String,
        etag: String
    ): CalDavResult<Unit>

    /**
     * Move an event to a different calendar using WebDAV MOVE (RFC 4918).
     *
     * This is an atomic operation that relocates the event resource to a new
     * calendar collection. Preferred over DELETE+CREATE for same-account moves
     * because it's atomic and avoids UID conflicts.
     *
     * Only works for same-server moves. For cross-server moves, use DELETE+CREATE.
     *
     * @param sourceUrl Current full event URL
     * @param destinationCalendarUrl Target calendar URL (collection, not event URL)
     * @param uid Event UID for constructing destination filename
     * @return Pair of (new event URL, new etag) on success
     */
    suspend fun moveEvent(
        sourceUrl: String,
        destinationCalendarUrl: String,
        uid: String
    ): CalDavResult<Pair<String, String>>

    // ========== Configuration ==========

    /**
     * Check if the server is reachable and credentials are valid.
     *
     * @param serverUrl Base CalDAV server URL
     * @return Success if reachable and authenticated
     */
    suspend fun checkConnection(serverUrl: String): CalDavResult<Unit>
}
