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
 * On-demand attendee backfill from `Event.rawIcal`.
 *
 * Closes the gap left by the pull path's etag-unchanged-skip:
 * `PullStrategy` skips upsert when local etag matches server etag, so
 * events whose etag hasn't changed since the attendees table was added
 * have empty `attendees` rows. When the chip UI's
 * [EventReader.getAttendeesForEvent] Flow returns empty AND the event
 * has a `rawIcal` body containing ATTENDEE lines, this helper parses +
 * persists, and the Flow re-emits with the persisted set.
 *
 * **Idempotency contract.** [AttendeesDao.replaceForEvent] is delete-
 * then-insert in a `@Transaction`, so any number of concurrent calls
 * converge to the same final state at the row-set level. Best-effort
 * short-circuit on populated tables avoids unnecessary parse work, but
 * is NOT a hard invariant — a TOCTOU race may parse twice; the second
 * write replaces with an identical set. No Mutex required.
 *
 * **Master-VEVENT preference.** When `rawIcal` contains both the master
 * event and an exception (RECURRENCE-ID), only the master's attendees
 * are persisted. Exceptions get their own row in `events` and their
 * own attendee set via the normal pull path; this helper is for the
 * master event's table row (ID passed by the caller).
 */
@Singleton
class AttendeeBackfill @Inject constructor(
    private val attendeesDao: AttendeesDao,
    private val eventsDao: EventsDao
) {

    private val parser = ICalParser()

    /**
     * Parse [Event.rawIcal] and persist its master VEVENT's attendees
     * when [AttendeesDao.getForEvent] is empty. Returns the count of
     * attendees written (0 if no-op).
     *
     * Never throws — parse failures, malformed rawIcal, and missing
     * VEVENTs all return 0 with a single PII-redacted log entry
     * (UID is masked: first 4 + last 4 chars only).
     */
    suspend fun backfillIfEmpty(eventId: Long): Int {
        val event = eventsDao.getById(eventId) ?: return 0
        val rawIcal = event.rawIcal?.takeIf { it.isNotEmpty() } ?: return 0

        // Cheap pre-flight: skip parse if no ATTENDEE lines on the wire.
        if (!rawIcal.contains("ATTENDEE")) return 0

        // Short-circuit when the table is already populated AND every
        // persisted address is usable. Self-heal trigger: if any row was
        // stored with a principal-href / bare mailto: / blank address
        // (e.g. left over from a build that pre-dated the parser EMAIL=
        // fallback), re-parse from rawIcal. Concurrent callers may race
        // past this check; the @Transaction delete-then-insert in
        // replaceForEvent makes the final state correct even when both
        // hit the parse path.
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

        // Master VEVENT is the one with no RECURRENCE-ID — exception variants
        // have their own Event row + own attendee set, written via the normal
        // pull path. If no events at all (header-only ICS), no-op.
        val master = cal.events.firstOrNull { it.recurrenceId == null }
            ?: return 0

        val attendees = translateAttendees(master, eventId)
        if (attendees.isEmpty()) return 0

        // Skip the delete-then-insert when the re-parse produced the same
        // set as what's already persisted. Pathological case: an ATTENDEE
        // with a principal-href primary and no EMAIL= parameter parses to
        // the same un-usable address every time — without this guard, every
        // sheet open re-runs the transactional rewrite for no observable
        // change. Compare on (address, partstat) since those drive UI.
        val existingKey = existing.map { it.address to it.partstat }.toSet()
        val newKey = attendees.map { it.address to it.partstat }.toSet()
        if (existingKey == newKey) return 0

        attendeesDao.replaceForEvent(eventId, attendees)
        return attendees.size
    }

    /**
     * Translate master VEVENT's icaldav-core attendees into Room rows.
     * Uses [ICalEventMapper.toAttendeeRows] directly to skip the
     * event-mapping work (DTSTART, alarms, EXDATE, color, …) we'd
     * discard anyway. Attendees with no usable address are filtered:
     * - empty/whitespace `address`
     * - bare `mailto:` with no email (common when ATTENDEE has no value)
     * - ical4j's `net.fortunal.ical4j.invalid:` defensive marker, which
     *   the parser emits when an ATTENDEE value can't be parsed as URI.
     *   The trailing 'l' (`fortunal`, not `fortuna`) is an upstream ical4j
     *   typo — verified in the 4.2.2 jar's constants pool — and our match
     *   string MUST keep it. "Fixing" the spelling here breaks the
     *   predicate against what ical4j actually emits.
     */
    private fun translateAttendees(master: ICalEvent, eventId: Long): List<Attendee> =
        ICalEventMapper.toAttendeeRows(master, eventId)
            .filter { isUsableAddress(it.address) }

    private fun isUsableAddress(address: String): Boolean {
        if (address.isBlank()) return false
        val withoutMailto = address.removePrefix("mailto:")
        if (withoutMailto.isBlank()) return false
        if (withoutMailto.startsWith("net.fortunal.ical4j.invalid:")) return false
        // Reject principal-href forms ("/646691839/principal/") persisted by
        // builds that didn't have the EMAIL= parameter fallback yet. A real
        // CAL-ADDRESS is either a mailto: (handled above) or a non-`/`-prefixed
        // URI (urn:uuid:, etc.).
        if (withoutMailto.startsWith("/")) return false
        return true
    }

    companion object {
        private const val TAG = "AttendeeBackfill"
    }
}
