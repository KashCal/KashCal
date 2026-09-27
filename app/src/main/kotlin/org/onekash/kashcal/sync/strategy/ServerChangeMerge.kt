package org.onekash.kashcal.sync.strategy

import kotlinx.coroutines.CancellationException
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.parser.ICalParser
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.parser.icaldav.IcsPatcher
import org.onekash.kashcal.sync.parser.icaldav.MappedEntity
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Builds the body to upload after a 412: the server's current copy with only the changes the
 * user made in KashCal applied on top, so a change made elsewhere (a guest's answer the server
 * wrote into the organizer's copy, another device's edit) survives unless the user changed the
 * same field group.
 *
 * What the user changed is found by mapping the stored copy (the series row's raw_ical, the copy
 * its fields reflect) as pull maps a body, and comparing it with the local rows one field group at
 * a time, on normalized values: text with empty as null, status, transparency and class ignoring
 * case, times by instant and all-day flag plus the zone for a timed series (a missing zone and the
 * spellings of UTC as one), the rule parsed, reminders and categories as sets, EXDATE and RDATE as
 * sets of instants. A group the user changed takes the local value, even when the server changed
 * it too; every other group takes the server's. EXDATE and RDATE merge as sets: the server's,
 * plus what the user added, minus what the user removed. SEQUENCE never drops below the server's,
 * and a bump over the stored copy ends above it. ORGANIZER and everything KashCal doesn't model
 * stay as the server holds them on the series VEVENT, because the merged series is patched onto
 * the server body.
 *
 * Attendees merge by membership, because KashCal can only add or remove guests: an address the
 * user added is sent, one the user removed is dropped, and every other address the server holds
 * goes out with the server's parameters and answer. Only the account's own answer comes from
 * KashCal, when the user changed it. With membership and the own answer unchanged, or with no
 * local attendee rows (as on every upload, an empty table clears nothing), the series keeps the
 * server's attendees as parsed, parameters and answers included. The merged rows replace the
 * series' ATTENDEE block only on an organized event ([IcsPatcher.serialize]).
 *
 * Changed occurrences are keyed by instance time, normalized as pull stores
 * [Event.originalInstanceTime]:
 * - a local one the server holds is merged field by field onto the server's;
 * - a local one the server doesn't hold is sent as it is when the stored copy didn't hold it
 *   either, or when the user edited it (a field or its guest list); either way its instance
 *   leaves the server's EXDATE (the occurrence wins over the deletion);
 * - a local one the server removed and the user left alone is dropped;
 * - one the server added is kept; one the user removed is dropped.
 * They are rebuilt from their fields by [IcsPatcher.serializeWithExceptions], as every upload
 * rebuilds them, so only their unknown properties come from the server body.
 *
 * A stored copy whose UID isn't the series' counts as no stored copy.
 *
 * With no stored copy nothing can tell what the user changed, so every modelled field takes the
 * local value; attendees are the local ones plus the server's, with the server's rows for the
 * addresses both hold.
 */
internal object ServerChangeMerge {

    /**
     * The body to upload, the merged series row it was built from, and the merged
     * changed-occurrence rows the body carries that have a local row (same ids). Neither row is
     * stored: its fields hold the server's side too, and the series' raw_ical is the server's copy.
     */
    class Merged(val body: String, val series: Event, val occurrences: List<Event>)

    private val parser = ICalParser()

    /**
     * Returns the merged body, or null when the server body doesn't parse, holds no series
     * VEVENT, or can't be mapped or merged: a body another client wrote may be hostile, and a
     * failure here must fail this write, never throw into the push. A stored copy that doesn't
     * parse counts as no stored copy.
     */
    fun merge(
        baseline: String?,
        series: Event,
        seriesAttendees: List<Attendee>,
        occurrences: List<Pair<Event, List<Attendee>>>,
        server: String,
        account: Account?,
    ): Merged? = try {
        mergeOrNull(baseline, series, seriesAttendees, occurrences, server, account)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private class Resource(val series: MappedEntity?, val occurrences: Map<Long?, MappedEntity>)

    private fun map(body: String, calendarId: Long): Resource? {
        val parsed = parser.parseAllEvents(body).getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val seriesVevent: ICalEvent? = parsed.firstOrNull { !it.isModifiedInstance() }
        val series = seriesVevent?.let { ICalEventMapper.toEntity(it, body, calendarId, null, null) }
        val occurrences = buildMap {
            parsed.filter { it.isModifiedInstance() }.forEach {
                val mapped = ICalEventMapper.toEntity(it, body, calendarId, null, null, masterDtStart = seriesVevent?.dtStart)
                putIfAbsent(mapped.event.originalInstanceTime, mapped)
            }
        }
        return Resource(series, occurrences)
    }

    private fun mergeOrNull(
        baseline: String?,
        series: Event,
        seriesAttendees: List<Attendee>,
        occurrences: List<Pair<Event, List<Attendee>>>,
        server: String,
        account: Account?,
    ): Merged? {
        val theirs = map(server, series.calendarId) ?: return null
        val theirSeries = theirs.series ?: return null
        // A series split off with "this and future" starts as a copy of the old row, stored body
        // included; a stored copy of another UID says nothing about this series.
        val base = baseline?.let { map(it, series.calendarId) }?.takeIf {
            (it.series ?: it.occurrences.values.firstOrNull())?.event?.uid == series.uid
        }

        var mergedSeries = mergeFields(series, base?.series?.event, theirSeries.event).copy(rawIcal = server)
        val seriesRows = mergeAttendees(seriesAttendees, base?.series?.attendees, theirSeries.attendees, account)

        val sentLocal = mutableListOf<Event>()
        val out = mutableListOf<Pair<Event, List<Attendee>?>>()
        val keptOverDeletion = mutableSetOf<Long>()
        val localKeys = occurrences.map { it.first.originalInstanceTime }.toSet()
        for ((local, rows) in occurrences) {
            val key = local.originalInstanceTime
            val stored = base?.occurrences?.get(key)
            val current = theirs.occurrences[key]
            when {
                current != null -> {
                    val merged = mergeFields(local, stored?.event, current.event)
                    out += merged to (mergeAttendees(rows, stored?.attendees, current.attendees, account) ?: current.attendees)
                    sentLocal += merged
                }
                stored != null && changedGroups(local, stored.event).isEmpty() &&
                    !membershipChanged(rows, stored.attendees) -> Unit
                else -> {
                    out += local to rows.ifEmpty { null }
                    sentLocal += local
                    key?.let { keptOverDeletion += it }
                }
            }
        }
        for ((key, current) in theirs.occurrences) {
            val removedByUser = base?.occurrences?.containsKey(key) == true
            if (key !in localKeys && !removedByUser) {
                out += current.event.copy(originalEventId = series.id, uid = series.uid) to current.attendees
            }
        }

        // An occurrence KashCal sends that the server deleted wins over the deletion, so its
        // instance leaves the server's EXDATE.
        if (keptOverDeletion.isNotEmpty()) {
            mergedSeries = mergedSeries.copy(
                exdate = (instants(mergedSeries.exdate) - keptOverDeletion).sorted().joinToString(",").ifEmpty { null }
            )
        }

        val body = if (mergedSeries.rrule != null) {
            IcsPatcher.serializeWithExceptions(mergedSeries, seriesRows, out)
        } else {
            IcsPatcher.serialize(mergedSeries, seriesRows)
        }
        return Merged(body, mergedSeries, sentLocal)
    }

    // ---- fields ----

    private enum class Group { TITLE, DESCRIPTION, LOCATION, TIME, STATUS, TRANSP, CLASS, RRULE, EXDATE, RDATE,
        REMINDERS, PRIORITY, GEO, COLOR, URL, CATEGORIES, SEQUENCE }

    private fun text(value: String?) = value?.takeIf { it.isNotEmpty() }

    /** Returns the zone id, with a missing zone and the spellings of UTC as one. */
    private fun zone(id: String?) = id?.takeUnless { it in UTC_IDS } ?: "UTC"
    private val UTC_IDS = setOf("UTC", "Etc/UTC", "Z", "GMT", "Etc/GMT")
    private fun upper(value: String?) = text(value)?.uppercase()
    private fun instants(csv: String?) = csv?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
    private fun rule(value: String?) = text(value)?.let { runCatching { RRule.parse(it) }.getOrNull() ?: it }
    private fun reminders(value: List<String>?) =
        value.orEmpty().map { runCatching { DurationUtils.parse(it) }.getOrNull() ?: it }.toSet()

    /**
     * Returns the value [group] is compared on. [withZone] adds the zone to a timed event's
     * time: a series save keeps the stored zone unless the user picks another, while a
     * single-occurrence save writes the series' zone onto the occurrence, so there a zone
     * difference alone isn't an edit.
     */
    private fun key(group: Group, e: Event, withZone: Boolean): Any? = when (group) {
        Group.TITLE -> text(e.title)
        Group.DESCRIPTION -> text(e.description)
        Group.LOCATION -> text(e.location)
        Group.TIME -> if (e.isAllDay || !withZone) {
            listOf(e.startTs, e.endTs, e.isAllDay)
        } else {
            listOf(e.startTs, e.endTs, false, zone(e.timezone), zone(e.endTimezone ?: e.timezone))
        }
        Group.STATUS -> upper(e.status)
        Group.TRANSP -> upper(e.transp)
        Group.CLASS -> upper(e.classification)
        Group.RRULE -> rule(e.rrule)
        Group.EXDATE -> instants(e.exdate)
        Group.RDATE -> instants(e.rdate)
        Group.REMINDERS -> reminders(e.reminders)
        Group.PRIORITY -> e.priority
        Group.GEO -> e.geoLat to e.geoLon
        Group.COLOR -> e.color
        Group.URL -> text(e.url)
        Group.CATEGORIES -> e.categories.orEmpty().toSet()
        Group.SEQUENCE -> e.sequence
    }

    private fun changedGroups(local: Event, stored: Event?): Set<Group> {
        if (stored == null) return Group.entries.toSet()
        val withZone = local.originalEventId == null
        return Group.entries.filter { key(it, local, withZone) != key(it, stored, withZone) }.toSet()
    }

    private fun take(group: Group, target: Event, from: Event): Event = when (group) {
        Group.TITLE -> target.copy(title = from.title)
        Group.DESCRIPTION -> target.copy(description = from.description)
        Group.LOCATION -> target.copy(location = from.location)
        Group.TIME -> target.copy(
            startTs = from.startTs, endTs = from.endTs, isAllDay = from.isAllDay,
            timezone = from.timezone, endTimezone = from.endTimezone, duration = from.duration,
        )
        Group.STATUS -> target.copy(status = from.status)
        Group.TRANSP -> target.copy(transp = from.transp)
        Group.CLASS -> target.copy(classification = from.classification)
        Group.RRULE -> target.copy(rrule = from.rrule)
        // Merged as sets in [mergeFields] whenever there is a stored copy.
        Group.EXDATE, Group.RDATE -> target
        Group.REMINDERS -> target.copy(reminders = from.reminders, alarmCount = from.alarmCount)
        Group.PRIORITY -> target.copy(priority = from.priority)
        Group.GEO -> target.copy(geoLat = from.geoLat, geoLon = from.geoLon)
        Group.COLOR -> target.copy(color = from.color)
        Group.URL -> target.copy(url = from.url)
        Group.CATEGORIES -> target.copy(categories = from.categories)
        // Always set in [mergeFields].
        Group.SEQUENCE -> target
    }

    /** Returns [local] with every group the user didn't change taken from [current]. */
    private fun mergeFields(local: Event, stored: Event?, current: Event): Event {
        val changed = changedGroups(local, stored)
        var merged = local
        for (group in Group.entries) {
            if (group !in changed) merged = take(group, merged, current)
        }
        if (stored != null) {
            merged = merged.copy(
                exdate = mergeSet(local.exdate, stored.exdate, current.exdate),
                rdate = mergeSet(local.rdate, stored.rdate, current.rdate),
            )
        }
        val sequence = when {
            stored != null && local.sequence > stored.sequence -> maxOf(local.sequence, current.sequence + 1)
            stored != null -> current.sequence
            else -> maxOf(local.sequence, current.sequence)
        }
        return merged.copy(sequence = sequence)
    }

    private fun mergeSet(local: String?, stored: String?, current: String?): String? {
        val mine = instants(local)
        val before = instants(stored)
        return ((instants(current) + (mine - before)) - (before - mine)).sorted().joinToString(",").ifEmpty { null }
    }

    // ---- attendees ----

    /**
     * Returns whether the user added or removed a guest: [local] rows against [stored]. No local
     * rows counts as unchanged, as in [mergeAttendees].
     */
    private fun membershipChanged(local: List<Attendee>, stored: List<Attendee>): Boolean =
        local.isNotEmpty() && local.map { address(it.address) }.toSet() != stored.map { address(it.address) }.toSet()

    private fun address(value: String) = AddressNormalizer.canonical(value)

    /**
     * Returns the attendee rows to send, or null to keep the server's ATTENDEE lines as they are
     * (no local rows, or nothing the user can change differs from the stored copy).
     */
    private fun mergeAttendees(
        local: List<Attendee>,
        stored: List<Attendee>?,
        current: List<Attendee>,
        account: Account?,
    ): List<Attendee>? {
        if (local.isEmpty()) return null
        val localByAddress = local.associateBy { address(it.address) }
        val currentAddresses = current.map { address(it.address) }.toSet()
        if (stored == null) {
            return current + local.filter { address(it.address) !in currentAddresses }
        }
        val storedAddresses = stored.map { address(it.address) }.toSet()
        fun own(rows: List<Attendee>) = account?.let { a -> rows.firstOrNull { a.matchesAttendee(it.address) } }
        val ownLocal = own(local)
        val ownChanged = ownLocal != null && upper(ownLocal.partstat) != upper(own(stored)?.partstat)
        val removed = storedAddresses - localByAddress.keys
        val added = localByAddress.keys - storedAddresses
        if (removed.isEmpty() && added.isEmpty() && !ownChanged) return null

        val ownAnswer = ownLocal?.partstat?.takeIf { ownChanged }
        val kept = current.filter { address(it.address) !in removed }.map {
            if (ownAnswer != null && account?.matchesAttendee(it.address) == true) it.copy(partstat = ownAnswer) else it
        }
        val fresh = local.filter { address(it.address) in added && address(it.address) !in currentAddresses }
        val ownMissing = ownLocal?.takeIf {
            ownAnswer != null && (kept + fresh).none { account?.matchesAttendee(it.address) == true }
        }
        return kept + fresh + listOfNotNull(ownMissing)
    }
}
