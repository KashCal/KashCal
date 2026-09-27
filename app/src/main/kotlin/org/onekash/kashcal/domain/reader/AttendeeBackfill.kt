package org.onekash.kashcal.domain.reader

import android.util.Log
import kotlinx.coroutines.flow.first
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.util.maskUid
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills an event's attendee rows on demand from `Event.rawIcal`.
 *
 * `PullStrategy` skips the upsert when the local etag matches the server's, so an event whose
 * etag hasn't changed since before attendees were stored has no attendee rows. The quick view
 * and the event form run this before subscribing to [EventReader.getAttendeesForEvent], so the
 * Flow emits the persisted set.
 *
 * Idempotent without a Mutex: [AttendeesDao.replaceForEvent] deletes and inserts in one
 * transaction, so concurrent calls converge on the same row set. The populated-table
 * short-circuit only saves parse work; a race may parse twice, and the second write replaces
 * with an identical set.
 *
 * Only the master VEVENT's attendees are persisted. Exceptions (RECURRENCE-ID) get their own
 * `events` row and attendee set from the pull; this helper writes the row whose ID the caller
 * passes.
 */
@Singleton
class AttendeeBackfill @Inject constructor(
    private val attendeesDao: AttendeesDao,
    private val eventsDao: EventsDao
) {

    private val parser = ICalParser()

    /**
     * Parses `rawIcal` and persists its master VEVENT's attendees when the event has no
     * attendee rows or any row's address is unusable. Returns the count written, 0 for a no-op.
     *
     * A parse that throws or fails returns 0 with one log line, the UID masked ([maskUid]). A
     * missing event or `rawIcal`, no ATTENDEE text, no master VEVENT or no usable address return
     * 0 without a log line. DAO exceptions propagate.
     */
    suspend fun backfillIfEmpty(eventId: Long): Int {
        val event = eventsDao.getById(eventId) ?: return 0
        val rawIcal = event.rawIcal?.takeIf { it.isNotEmpty() } ?: return 0

        // Skip the parse when the body has no ATTENDEE text.
        if (!rawIcal.contains("ATTENDEE")) return 0

        // Return when rows exist and every address is usable. A row with a principal-href, bare
        // mailto: or blank address (e.g. written by a build without the parser's EMAIL=
        // fallback) re-parses from rawIcal. Racing callers converge (class doc).
        val existing = attendeesDao.getForEvent(eventId).first()
        if (existing.isNotEmpty() && existing.all { isUsableAddress(it.address) }) return 0

        val parseResult = try {
            parser.parse(rawIcal)
        } catch (e: Exception) {
            Log.w(TAG, "rawIcal parse threw for event ${event.uid.maskUid()}: ${e.javaClass.simpleName}")
            return 0
        }

        val cal = (parseResult as? ParseResult.Success)?.value
            ?: run {
                Log.w(TAG, "rawIcal parse failed for event ${event.uid.maskUid()}")
                return 0
            }

        // The master is the VEVENT without RECURRENCE-ID. A header-only ICS is a no-op.
        val master = cal.events.firstOrNull { it.recurrenceId == null }
            ?: return 0

        val attendees = translateAttendees(master, eventId)
        if (attendees.isEmpty()) return 0

        // Skip the rewrite when the re-parse matches the persisted set. An ATTENDEE with a
        // principal-href value and no EMAIL= parameter parses to the same unusable address
        // every time, so without this every sheet open rewrites the set for no change.
        // Compared on (address, partstat), which drive the UI.
        val existingKey = existing.map { it.address to it.partstat }.toSet()
        val newKey = attendees.map { it.address to it.partstat }.toSet()
        if (existingKey == newKey) return 0

        attendeesDao.replaceForEvent(eventId, attendees)
        return attendees.size
    }

    /**
     * Maps the master VEVENT's attendees to Room rows, dropping those without a usable address
     * ([isUsableAddress]). Calls [ICalEventMapper.toAttendeeRows] to skip the event mapping
     * (DTSTART, alarms, EXDATE, color) it would discard.
     */
    private fun translateAttendees(master: ICalEvent, eventId: Long): List<Attendee> =
        ICalEventMapper.toAttendeeRows(master, eventId)
            .filter { isUsableAddress(it.address) }

    /**
     * Rejects a blank address, a bare `mailto:` (an ATTENDEE with no value), a principal href,
     * and ical4j's `net.fortunal.ical4j.invalid:` marker, which its relaxed parsing gives an
     * ATTENDEE value that isn't a URI. `fortunal` is an upstream typo (`Uris.INVALID_SCHEME` in
     * ical4j 4.3.0); the match must keep it, or it stops matching what ical4j emits.
     */
    private fun isUsableAddress(address: String): Boolean {
        if (address.isBlank()) return false
        val withoutMailto = address.removePrefix("mailto:")
        if (withoutMailto.isBlank()) return false
        if (withoutMailto.startsWith("net.fortunal.ical4j.invalid:")) return false
        // A principal href ("/646691839/principal/") is what builds without the EMAIL=
        // fallback stored. A real CAL-ADDRESS is a mailto: or a URI not starting with `/`
        // (e.g. urn:uuid:).
        if (withoutMailto.startsWith("/")) return false
        return true
    }

    companion object {
        private const val TAG = "AttendeeBackfill"
    }
}
