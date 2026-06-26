package org.onekash.kashcal.sync.client

import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.SyncReport

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
     * Discover the user's `calendar-user-address-set` from the principal
     * (RFC 6638 §2.4.1). Returns the full set of CAL-ADDRESS forms the
     * server recognizes as this user — `mailto:`, `urn:uuid:`,
     * principal-relative paths, full HTTP principal URIs, in any
     * combination. Used by the identity-discovery flow to populate
     * `Account.calendarUserAddresses`.
     *
     * Failures (HTTP 4xx/5xx, network errors, timeouts) are surfaced as
     * [CalDavResult.Error]; callers in the discovery flow treat the error
     * as non-fatal and fall through to an empty address set.
     *
     * @param principalUrl Full principal URL
     * @return List of CAL-ADDRESS strings; preferred entries hoisted to
     *         the front of the list; empty list when the server returns
     *         an empty or absent property
     */
    suspend fun discoverCalendarUserAddresses(principalUrl: String): CalDavResult<List<String>>

    /**
     * Discover the principal's scheduling Outbox URL via PROPFIND
     * (RFC 6638 §2.1.1 CALDAV:schedule-outbox-URL).
     *
     * The outbox is where a client POSTs scheduling messages on servers that
     * decline to self-schedule. This is discovery only — it does not POST.
     *
     * Failures (HTTP 4xx/5xx, network, timeout) are surfaced as
     * [CalDavResult.Error]; discovery-flow callers treat the error as
     * non-fatal. A successful probe returns the href, or null when the
     * property is empty/absent (the principal is not outbox-enabled).
     *
     * @param principalUrl Full principal URL
     * @return The outbox URL, or null when not advertised
     */
    suspend fun discoverScheduleOutboxUrl(principalUrl: String): CalDavResult<String?>

    /**
     * Probe whether a calendar collection supports server-side
     * auto-scheduling (RFC 6638 §2: the "calendar-auto-schedule" token in the
     * DAV response header from an OPTIONS request on the collection).
     *
     * Must be probed against the collection URL, not the service root — some
     * servers advertise the token only on the collection.
     *
     * Failures are surfaced as [CalDavResult.Error]; discovery-flow callers
     * treat the error as non-fatal (capability stays unknown). This flag is
     * advisory only — the authoritative delivery signal is read back at
     * runtime, not derived from this capability.
     *
     * @param calendarUrl Full calendar collection URL
     * @return true when the collection advertises calendar-auto-schedule
     */
    suspend fun supportsAutoSchedule(calendarUrl: String): CalDavResult<Boolean>

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
     * Get per-calendar metadata via an extended PROPFIND: ctag plus
     * displayName, color, and isReadOnly when the server provides them.
     * Error when ctag is missing (callers fall back to the ctag-less path).
     */
    suspend fun getCtag(calendarUrl: String): CalDavResult<CalendarMetadataProbe>

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
     * Fetch etags for ALL events in a calendar using PROPFIND Depth:1 (RFC 4918).
     * Unlike fetchEtagsInRange(), this has no time-range filter — it returns every
     * .ics resource in the collection.
     *
     * Used by PullStrategy.pullFull() on servers without sync-token (e.g., Purelymail)
     * where calendar-query REPORT may have a stale index. PROPFIND reads the filesystem
     * directly — always accurate.
     *
     * @param calendarUrl Full calendar URL
     * @return List of (href, etag) pairs for all events in the calendar
     */
    suspend fun fetchAllEtags(calendarUrl: String): CalDavResult<List<Pair<String, String?>>>

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

    /**
     * POST an iTIP scheduling message to a principal's scheduling Outbox
     * (RFC 6638 §6). Used as the client-side delivery fallback when a server
     * declines to self-schedule (stamps `SCHEDULE-AGENT=CLIENT`) — the app
     * builds a `METHOD:REQUEST` and POSTs it here so the invitation reaches the
     * attendee.
     *
     * Sends the recipients both ways for interop: as ATTENDEE properties in the
     * [icalData] body (RFC 6638 §6 normative form) AND as one `Recipient` HTTP
     * header each (the `caldav-sched` draft form that Apple-lineage and Zoho
     * servers expect). [originator] and [recipients] are bare CAL-ADDRESSes
     * (no `mailto:` prefix) — this method prepends `mailto:` on the wire.
     *
     * The server answers with a `CALDAV:schedule-response` carrying a
     * per-recipient `request-status`; the parsed outcome drives the caller's
     * send-idempotency and class-aware retry. Non-2xx HTTP (e.g. Sabre 501
     * free/busy-only, Stalwart 400) is surfaced as [CalDavResult.Error]; the
     * caller treats any failure as non-fatal to the push.
     *
     * @param outboxUrl Full scheduling-outbox URL (from account discovery)
     * @param originator Bare CAL-ADDRESS of the organizer (the account's own
     *   discovered address; never synthesized from the username)
     * @param recipients Bare CAL-ADDRESSes of the attendees to deliver to
     * @param icalData Complete `METHOD:REQUEST` VCALENDAR body
     * @return Parsed per-recipient outbox response on HTTP 200/207
     */
    suspend fun postToOutbox(
        outboxUrl: String,
        originator: String,
        recipients: List<String>,
        icalData: String
    ): CalDavResult<org.onekash.kashcal.sync.client.model.OutboxResponse>

    // ========== Configuration ==========

    /**
     * Check if the server is reachable and credentials are valid.
     *
     * @param serverUrl Base CalDAV server URL
     * @return Success if reachable and authenticated
     */
    suspend fun checkConnection(serverUrl: String): CalDavResult<Unit>
}
