package org.onekash.kashcal.sync.model

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What a pulled change did to an event, in the words the Recent changes sheet uses.
 *
 * Alarms, busy/free (TRANSP), SEQUENCE, unknown properties and server stamps belong to no
 * category: a phone calendar app can add its default alarm and TRANSP the first time it edits
 * an event another client made, and that isn't a change the user would call an update.
 */
enum class ChangedField {
    /** Start, end or the all-day flag. A timezone change at the same instants isn't one. */
    TIME,
    LOCATION,
    TITLE,
    /** The repeat rule, RDATE, or an occurrence no longer excluded. */
    RECURRENCE,
    /** Any other field the user sees, such as notes, status or organizer. */
    OTHER;

    companion object {

        /**
         * Returns the categories in which [new] differs from [old]. A newly excluded occurrence
         * is no category here; [cancelledInstances] lists it.
         */
        fun between(old: Event, new: Event): Set<ChangedField> = buildSet {
            if (old.startTs != new.startTs || old.endTs != new.endTs || old.isAllDay != new.isAllDay) {
                add(TIME)
            }
            if (old.location.orEmpty() != new.location.orEmpty() ||
                old.geoLat != new.geoLat || old.geoLon != new.geoLon
            ) {
                add(LOCATION)
            }
            if (old.title != new.title) add(TITLE)
            if (old.rrule != new.rrule || old.rdate != new.rdate || restoresInstances(old, new)) {
                add(RECURRENCE)
            }
            if (old.description.orEmpty() != new.description.orEmpty() ||
                old.status != new.status ||
                old.url.orEmpty() != new.url.orEmpty() ||
                old.color != new.color ||
                old.categories.orEmpty() != new.categories.orEmpty() ||
                old.classification != new.classification ||
                old.priority != new.priority ||
                old.organizerEmail.orEmpty() != new.organizerEmail.orEmpty() ||
                old.organizerName.orEmpty() != new.organizerName.orEmpty()
            ) {
                add(OTHER)
            }
        }

        /**
         * Returns the occurrences [new]'s EXDATE excludes that [old]'s didn't. When the series
         * moved (start, zone or all-day flag changed), a client rewrites the existing EXDATEs to
         * the new instances (RFC 5545 §3.8.5.1 makes them match one), so an EXDATE then counts as
         * new only if no old one is on the same local date or at the same offset from the series
         * start. Otherwise both sides are normalized against the same DTSTART and compare as
         * exact instants.
         */
        fun cancelledInstances(old: Event, new: Event): List<Long> {
            if (old.exdate == new.exdate) return emptyList()
            val known = ExdateMatcher(old, new)
            return exdates(new).filterNot { known.matchesOld(it) }
        }

        private fun restoresInstances(old: Event, new: Event): Boolean {
            if (old.exdate == new.exdate) return false
            val kept = ExdateMatcher(new, old)
            return exdates(old).any { !kept.matchesOld(it) }
        }

        /**
         * Matches an EXDATE of [to] against the EXDATEs of [from], per [cancelledInstances]. A
         * change of start, zone or all-day flag counts as a move: the client may rewrite the
         * EXDATEs then. Each side's dates are read in its own zone, or in UTC when all-day.
         */
        private class ExdateMatcher(from: Event, to: Event) {
            private val moved = from.startTs != to.startTs || from.timezone != to.timezone ||
                from.isAllDay != to.isAllDay
            private val toZone = zoneOf(to)
            private val instants = exdates(from).toHashSet()
            private val shifted = instants.mapTo(HashSet()) { it + (to.startTs - from.startTs) }
            private val dates = if (moved) {
                val fromZone = zoneOf(from)
                instants.mapTo(HashSet()) { DateTimeUtils.eventTsToLocalDate(it, from.isAllDay, fromZone) }
            } else {
                emptySet()
            }
            private val toAllDay = to.isAllDay

            fun matchesOld(ts: Long): Boolean =
                if (!moved) {
                    ts in instants
                } else {
                    ts in shifted || DateTimeUtils.eventTsToLocalDate(ts, toAllDay, toZone) in dates
                }

            private fun zoneOf(e: Event): ZoneId = TimezoneUtils.resolveZoneOrNull(e.timezone) ?: ZoneOffset.UTC
        }

        /** Reads the epoch-ms CSV the pull stores in [Event.exdate]. */
        private fun exdates(e: Event): List<Long> =
            e.exdate?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
    }
}
