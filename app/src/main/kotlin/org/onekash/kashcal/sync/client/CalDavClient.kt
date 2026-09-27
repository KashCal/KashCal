package org.onekash.kashcal.sync.client

import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.client.model.SyncReport

/**
 * Talks CalDAV to one server: discovery (PROPFIND, OPTIONS), fetching (calendar-query,
 * calendar-multiget and sync-collection REPORTs, PROPFIND), and writes (PUT, DELETE, MOVE,
 * outbox POST).
 *
 * Every call returns a [CalDavResult]; a request the transport guard refuses to send is an
 * Error with `CalDavResult.CODE_TRANSPORT_REFUSED`.
 */
interface CalDavClient {

    // ========== Discovery ==========

    /**
     * Discovers the CalDAV endpoint via the RFC 6764 well-known URL: a PROPFIND on the host's
     * `/.well-known/caldav`, following redirects.
     *
     * @param serverUrl base server URL, e.g. "https://nextcloud.example.com"
     * @return the redirect target, or [serverUrl] when there was no redirect or the request
     *   failed. It is an Error only when the transport guard refused to send the request.
     */
    suspend fun discoverWellKnown(serverUrl: String): CalDavResult<String>

    /**
     * Discovers the user's principal URL with a `current-user-principal` PROPFIND.
     *
     * @param serverUrl base CalDAV server URL, e.g. "https://caldav.icloud.com"
     * @return the absolute principal URL
     */
    suspend fun discoverPrincipal(serverUrl: String): CalDavResult<String>

    /**
     * Discovers the principal's calendar home URLs with a `calendar-home-set` PROPFIND.
     *
     * RFC 4791 §6.2.1 allows several home sets; SOGo and Cyrus can return more than one.
     *
     * @return the absolute home URLs; an Error when the reply lists none
     */
    suspend fun discoverCalendarHome(principalUrl: String): CalDavResult<List<String>>

    /**
     * Discovers the principal's `calendar-user-address-set` (RFC 6638 §2.4.1): every
     * CAL-ADDRESS the server recognizes as this user, in any mix of `mailto:`, `urn:uuid:`,
     * principal-relative paths and full principal URIs.
     *
     * Feeds `Account.calendarUserAddresses`; a failure there is non-fatal, see
     * [org.onekash.kashcal.sync.discovery.persistCalendarUserAddresses].
     *
     * @return the addresses with `preferred="1"` entries first; empty when the property is
     *   empty or absent
     */
    suspend fun discoverCalendarUserAddresses(principalUrl: String): CalDavResult<List<String>>

    /**
     * Discovers the principal's `CALDAV:schedule-outbox-URL` (RFC 6638 §2.1.1) with a
     * PROPFIND. Discovery only; [postToOutbox] sends.
     *
     * The outbox is where a client POSTs scheduling messages on servers that decline to
     * self-schedule. A failure is non-fatal to discovery
     * ([org.onekash.kashcal.sync.discovery.persistSchedulingDiscovery]).
     *
     * @return the advertised href, possibly server-relative, or null when the property is
     *   empty or absent (the principal has no outbox)
     */
    suspend fun discoverScheduleOutboxUrl(principalUrl: String): CalDavResult<String?>

    /**
     * Probes whether a calendar collection supports server-side auto-scheduling: the
     * `calendar-auto-schedule` token in the DAV header of an OPTIONS reply (RFC 6638 §2).
     *
     * Probe the collection URL, not the service root: some servers advertise the token only
     * on the collection. A failure leaves the capability unknown. The flag is advisory; the
     * authoritative delivery signal is read back at runtime, not derived from it.
     *
     * @return true when the collection advertises `calendar-auto-schedule`
     */
    suspend fun supportsAutoSchedule(calendarUrl: String): CalDavResult<Boolean>

    /** Lists the calendars in [calendarHomeUrl] with a Depth: 1 PROPFIND. */
    suspend fun listCalendars(calendarHomeUrl: String): CalDavResult<List<CalDavCalendar>>

    /**
     * Asks the server about one calendar URL directly (PROPFIND, Depth: 0).
     *
     * Used before removing a calendar that a listing left out, because a listing can come
     * back short for reasons that say nothing about the calendar (a login page on a hotspot,
     * a garbled reply, one failing home set).
     *
     * @return Success(true) when it is still a calendar the listing would show; Success(false)
     *   when the server describes that URL as something else; otherwise the HTTP status as an
     *   Error (404 or 410 for a missing resource). An unreadable reply, or an answer that came
     *   through a redirect to another server or another path, is Error(500) and proves nothing.
     */
    suspend fun probeCalendarCollection(calendarUrl: String): CalDavResult<Boolean>

    // ========== Change Detection ==========

    /**
     * Reads per-calendar metadata with one PROPFIND: the ctag, plus display name, color and
     * read-only state when the server provides them.
     *
     * An Error when the ctag is missing; the pull then proceeds without the ctag check.
     */
    suspend fun getCtag(calendarUrl: String): CalDavResult<CalendarMetadataProbe>

    /** Reads the calendar's current sync-token, or null when the server reports none. */
    suspend fun getSyncToken(calendarUrl: String): CalDavResult<String?>

    // ========== Fetching ==========

    /**
     * Runs a sync-collection REPORT (RFC 6578): the items changed and deleted since
     * [syncToken], and a new token.
     *
     * A 403 or 410 means the token is invalid or expired. A truncated reply (RFC 6578 §3.6)
     * is a Success with `SyncReport.truncated` set; continue from its token.
     *
     * @param syncToken the previous token, or null for an initial sync
     */
    suspend fun syncCollection(
        calendarUrl: String,
        syncToken: String?
    ): CalDavResult<SyncReport>

    /**
     * Fetches events with iCal data in a time range via a calendar-query REPORT.
     *
     * No production code calls it today: the pull lists etags ([fetchEtagsInRange],
     * [fetchAllEtags]) and multigets the changed ones ([fetchEventsByHref]).
     *
     * @param startMillis start of the range, epoch millis
     * @param endMillis end of the range, epoch millis
     */
    suspend fun fetchEventsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<CalDavEvent>>

    /**
     * Lists the etag of every resource in a calendar with a Depth: 1 PROPFIND (RFC 4918), with
     * no time-range filter.
     *
     * The full pull uses it on servers without a sync-token (e.g. Purelymail), whose
     * calendar-query index may be stale. PROPFIND reads the collection directly, so it is
     * always current.
     *
     * @return (href, etag) pairs for every event in the calendar
     */
    suspend fun fetchAllEtags(calendarUrl: String): CalDavResult<List<Pair<String, String?>>>

    /**
     * Lists (href, etag) pairs, without iCal data, for events in a time range via a
     * calendar-query REPORT.
     *
     * Used by the etag-comparison fallback when a sync-token expires (403/410), and by the
     * full pull when forced, on servers with sync-tokens, or when [fetchAllEtags] fails.
     *
     * @param startMillis start of the range, epoch millis
     * @param endMillis end of the range, epoch millis
     */
    suspend fun fetchEtagsInRange(
        calendarUrl: String,
        startMillis: Long,
        endMillis: Long
    ): CalDavResult<List<Pair<String, String?>>>

    /**
     * Fetches events with iCal data by href via a calendar-multiget REPORT; the pull uses it
     * for the hrefs a delta or an etag listing found new or changed.
     */
    suspend fun fetchEventsByHref(
        calendarUrl: String,
        hrefs: List<String>
    ): CalDavResult<List<CalDavEvent>>

    /** Fetches one event, with its iCal data and etag, by GET on [eventUrl]. */
    suspend fun fetchEvent(eventUrl: String): CalDavResult<CalDavEvent>

    /**
     * Fetches only the ETag of [eventUrl] with a PROPFIND.
     *
     * Used when a PUT reply has no ETag header (e.g. Nextcloud; RFC 4791 §5.3.4 says the
     * server SHOULD return one but may not) and to refresh a stale etag before retrying a
     * conditional write.
     *
     * @return the unquoted ETag, or null when the reply carries no getetag. A 404 is an Error.
     */
    suspend fun fetchEtag(eventUrl: String): CalDavResult<String?>

    // ========== Mutations ==========

    /**
     * Creates an event with a PUT to `<calendar>/<uid>.ics` and `If-None-Match: *`, so an
     * existing resource there fails with 412 instead of being overwritten.
     *
     * @param uid the event UID; the resource name is `<uid>.ics`, percent-encoded as one
     *   path segment
     * @param icalData the complete VCALENDAR
     * @return the URL the server stored it at (the redirect target, if the PUT was
     *   redirected) and its etag, empty when neither the reply nor [fetchEtag] gave one
     */
    suspend fun createEvent(
        calendarUrl: String,
        uid: String,
        icalData: String
    ): CalDavResult<Pair<String, String>> // (url, etag)

    /**
     * Updates an event with a PUT and `If-Match: "<etag>"`; a changed server copy fails
     * with 412.
     *
     * @param icalData the complete VCALENDAR
     * @param etag the etag the client last saw
     * @return the new etag; when neither the reply nor [fetchEtag] gives one, [etag] itself
     */
    suspend fun updateEvent(
        eventUrl: String,
        icalData: String,
        etag: String
    ): CalDavResult<String> // new etag

    /**
     * Deletes an event with a DELETE conditioned on `If-Match: "<etag>"`. A 404 counts as
     * success: the event is already gone.
     *
     * @param etag the etag the client last saw, or null to delete whatever is there (no
     *   If-Match header). Never pass an empty etag: `If-Match: ""` matches nothing.
     */
    suspend fun deleteEvent(
        eventUrl: String,
        etag: String?
    ): CalDavResult<Unit>

    /**
     * Moves an event to another calendar with a WebDAV MOVE (RFC 4918), `Overwrite: F`.
     *
     * Preferred over DELETE+CREATE for same-account moves because it is atomic and avoids UID
     * conflicts. Works only within one server; a cross-server move needs DELETE+CREATE.
     *
     * @param destinationCalendarUrl the target collection URL, not an event URL
     * @param uid the event UID, used only for logging: the destination keeps the source's
     *   resource name
     * @return the new event URL and etag, the etag empty when none could be read
     */
    suspend fun moveEvent(
        sourceUrl: String,
        destinationCalendarUrl: String,
        uid: String
    ): CalDavResult<Pair<String, String>>

    /**
     * POSTs an iTIP scheduling message to a principal's scheduling Outbox (RFC 6638 §6).
     *
     * The client-side delivery fallback when a server declines to self-schedule (stamps
     * `SCHEDULE-AGENT=CLIENT`): the app builds a `METHOD:REQUEST` and POSTs it here so the
     * invitation reaches the attendee.
     *
     * Recipients go both ways for interop: as ATTENDEE properties in [icalData] (the RFC 6638
     * §6 normative form) and as one `Recipient` header each (the `caldav-sched` draft form that
     * Apple-lineage and Zoho servers expect). [originator] and [recipients] are bare
     * CAL-ADDRESSes; this method prepends `mailto:` on the wire.
     *
     * The server answers with a `CALDAV:schedule-response` carrying a per-recipient
     * `request-status`, which drives the caller's send-idempotency and class-aware retry. A
     * status other than 200/207 (e.g. Sabre 501 free/busy-only, Stalwart 400) is an Error;
     * the caller treats any failure as non-fatal to the push.
     *
     * @param outboxUrl the scheduling-outbox URL from account discovery; a server-relative
     *   href is resolved against the account's host
     * @param originator the organizer's bare CAL-ADDRESS: the account's own discovered
     *   address, never synthesized from the username
     * @param recipients bare CAL-ADDRESSes of the attendees to deliver to
     * @param icalData the complete `METHOD:REQUEST` VCALENDAR
     * @return the parsed per-recipient response, on HTTP 200 or 207
     */
    suspend fun postToOutbox(
        outboxUrl: String,
        originator: String,
        recipients: List<String>,
        icalData: String
    ): CalDavResult<org.onekash.kashcal.sync.client.model.OutboxResponse>

    // ========== Configuration ==========

    /**
     * Checks that [serverUrl] answers an OPTIONS request and advertises `calendar-access` in
     * its DAV header (RFC 4791). A 401 is an auth Error; a missing token is Error(501).
     */
    suspend fun checkConnection(serverUrl: String): CalDavResult<Unit>
}
