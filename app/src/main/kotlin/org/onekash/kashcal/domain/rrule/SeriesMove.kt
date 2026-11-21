package org.onekash.kashcal.domain.rrule

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.util.RruleUtils
import org.onekash.kashcal.util.zone.ZoneCatalog
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Moves a recurring series' start, zone or all-day flag together with what is recorded at its
 * occurrences: the repeat rule (via [RruleShift.shift]), each cancelled date (EXDATE) and each
 * changed occurrence's key (RECURRENCE-ID, RFC 5545 §3.8.4.4). Extra dates (RDATE) don't move.
 *
 * Each key is mapped on its own: it must be an occurrence of the old rule
 * ([RruleShift.isInstance]), it moves to its local date plus the day shift at the new clock time
 * in the new zone ([RruleShift.moved]), and the result must be an occurrence of the new rule. A
 * key that isn't an old occurrence (one on an extra date) is left as it is. Because the check is
 * per key, a series that started years ago maps last week's key, and a spring-forward gap
 * occurrence keeps its slot.
 */
class SeriesMove private constructor(
    private val inputs: Inputs,
    /** The rule the moved series is written with: the rewritten one, or the typed one. */
    val newRule: String,
    /** False when nothing moved: every key maps to itself. */
    val isMove: Boolean,
    private val prefixAligned: Boolean,
    private val zone: ZoneId,
    private val newZone: ZoneId,
    private val days: Long,
    private val newTime: LocalTime,
) {

    /**
     * What a move needs.
     *
     * @param rule the stored rule; [dtstartTs] its series start (where it is expanded from)
     * @param anchorTs the old occurrence that lands on [newStartTs]: the series start for All
     *   events, the opened occurrence for This and future
     * @param typedRule a rule the user edited in the same save, or null to rewrite [rule]
     */
    data class Inputs(
        val rule: String,
        val dtstartTs: Long,
        val anchorTs: Long,
        val zone: String?,
        val isAllDay: Boolean,
        val newStartTs: Long,
        val newZone: String?,
        val newIsAllDay: Boolean,
        val typedRule: String? = null,
    )

    /** Where one key lands. */
    sealed interface Key {
        data class Moved(val ts: Long) : Key

        /** Not an occurrence of the old rule (an extra date): left as it is. */
        data object NotAnOccurrence : Key

        /** Past the end of a bounded typed rule: left to that rule. */
        data object PastTypedEnd : Key

        /** No occurrence of the new rule to land on: the move can't be saved with this key. */
        data object Inexpressible : Key
    }

    /** Returns where the occurrence key [ts] lands. */
    fun mapKey(ts: Long): Key {
        if (!isMove) return Key.Moved(ts)
        if (!RruleShift.isInstance(inputs.rule, inputs.dtstartTs, inputs.zone, inputs.isAllDay, ts)) {
            return Key.NotAnOccurrence
        }
        if (!prefixAligned) return Key.Inexpressible
        val moved = RruleShift.moved(ts, zone, days, newTime, newZone, inputs.newIsAllDay)
        if (RruleShift.isInstance(newRule, inputs.newStartTs, inputs.newZone, inputs.newIsAllDay, moved)) {
            return Key.Moved(moved)
        }
        if (inputs.typedRule != null && isBounded(newRule)) {
            val last = RruleShift.firstInstances(newRule, inputs.newStartTs, inputs.newZone, inputs.newIsAllDay).lastOrNull()
            if (last != null && moved > last) return Key.PastTypedEnd
        }
        return Key.Inexpressible
    }

    /**
     * Returns an EXDATE list (comma-separated epoch ms) with each entry moved, or null when an
     * entry has no occurrence to land on. Entries that aren't epoch ms (legacy `YYYYMMDD` or
     * `YYYYMMDDTHHMMSS` tokens) and entries left as they are ([Key.NotAnOccurrence],
     * [Key.PastTypedEnd]) are kept.
     */
    fun mapExdates(csv: String?): String? {
        if (csv.isNullOrBlank() || !isMove) return csv
        val out = csv.split(',').map { raw ->
            val token = raw.trim()
            val ts = token.takeIf { EPOCH_MS.matches(it) }?.toLongOrNull() ?: return@map token
            when (val key = mapKey(ts)) {
                is Key.Moved -> key.ts.toString()
                Key.NotAnOccurrence, Key.PastTypedEnd -> token
                Key.Inexpressible -> return null
            }
        }
        return out.joinToString(",")
    }

    /** A series save after [planSave]: the master row to write and its moved occurrences. */
    class SavePlan(val event: Event, val exceptions: List<Event>)

    /** The new series of a This-and-future split after [planSplit]. */
    class SplitPlan(val rule: String, val exdate: String?)

    companion object {
        private val EPOCH_MS = Regex("""-?\d{10,}""")
        private val SUB_DAILY = setOf("HOURLY", "MINUTELY", "SECONDLY")

        /**
         * Returns the move for [inputs], or null when the rule can't express it: [RruleShift.shift]
         * refuses it, or the rule repeats more often than daily. A typed rule is taken as typed;
         * its keys map only when the old and typed series line up over their shared first
         * instances.
         */
        fun plan(inputs: Inputs): SeriesMove? = try {
            val isMove = inputs.anchorTs != inputs.newStartTs ||
                inputs.isAllDay != inputs.newIsAllDay ||
                !ZoneCatalog.sameZone(inputs.zone, inputs.newZone)
            val zone = RruleShift.zoneFor(inputs.zone, inputs.isAllDay)
            val newZone = RruleShift.zoneFor(inputs.newZone, inputs.newIsAllDay)
            val oldStart = Instant.ofEpochMilli(inputs.anchorTs).atZone(zone)
            val newStart = Instant.ofEpochMilli(inputs.newStartTs).atZone(newZone)
            val days = ChronoUnit.DAYS.between(oldStart.toLocalDate(), newStart.toLocalDate())
            val newTime = newStart.toLocalTime()
            when {
                !isMove -> SeriesMove(inputs, inputs.typedRule ?: inputs.rule, false, true, zone, newZone, days, newTime)
                frequency(inputs.typedRule ?: inputs.rule) in SUB_DAILY -> null
                inputs.typedRule == null -> RruleShift.shift(
                    inputs.rule, inputs.anchorTs, inputs.newStartTs, inputs.zone, inputs.isAllDay,
                    inputs.newZone, inputs.newIsAllDay,
                )?.let { SeriesMove(inputs, it, true, true, zone, newZone, days, newTime) }
                else -> {
                    val old = RruleShift.firstInstances(inputs.rule, inputs.anchorTs, inputs.zone, inputs.isAllDay)
                    val typed = RruleShift.firstInstances(inputs.typedRule, inputs.newStartTs, inputs.newZone, inputs.newIsAllDay)
                    val shared = minOf(old.size, typed.size)
                    val aligned = shared > 0 && (0 until shared).all { i ->
                        RruleShift.moved(old[i], zone, days, newTime, newZone, inputs.newIsAllDay) == typed[i]
                    }
                    SeriesMove(inputs, inputs.typedRule, true, aligned, zone, newZone, days, newTime)
                }
            }
        } catch (_: Exception) {
            null
        }

        /**
         * Plans saving [edited] over the recurring master [stored], or returns null when the rule
         * can't express the move: the rule (unless the user typed a new one), the cancelled
         * dates (unless the caller set them) and each changed occurrence's key move together. A
         * changed occurrence whose time the user never edited also moves its start and takes the
         * series' new zone and all-day flag; one with an edited time keeps it; one with no key
         * is left as it is. [anchorTs] is the occurrence that lands on [edited]'s start: the
         * series start, or the split occurrence for an in-place split. One computation for the
         * writer and for the scope checks that grey a scope out before the save.
         */
        fun planSave(stored: Event, edited: Event, anchorTs: Long, exceptions: List<Event>): SavePlan? {
            val storedRule = stored.rrule
            val editedRule = edited.rrule
            if (storedRule.isNullOrBlank() || editedRule.isNullOrBlank() || stored.originalEventId != null) {
                return SavePlan(edited, emptyList())
            }
            val typed = editedRule.takeUnless {
                sameRuleForMove(storedRule, stored.timezone, stored.isAllDay, it, edited.timezone, edited.isAllDay)
            }
            val move = plan(
                Inputs(
                    rule = storedRule, dtstartTs = stored.startTs, anchorTs = anchorTs,
                    zone = stored.timezone, isAllDay = stored.isAllDay,
                    newStartTs = edited.startTs, newZone = edited.timezone, newIsAllDay = edited.isAllDay,
                    typedRule = typed,
                )
            ) ?: return null
            if (!move.isMove) return SavePlan(edited, emptyList())
            val exdate = if (edited.exdate != stored.exdate) {
                edited.exdate
            } else {
                stored.exdate?.let { move.mapExdates(it) ?: return null }
            }
            val moved = exceptions.mapNotNull { exception ->
                val key = exception.originalInstanceTime ?: return@mapNotNull null
                when (val landed = move.mapKey(key)) {
                    is Key.Moved -> movedException(exception, key, landed.ts, stored, edited)
                    Key.NotAnOccurrence, Key.PastTypedEnd -> null
                    Key.Inexpressible -> return null
                }
            }
            return SavePlan(edited.copy(rrule = move.newRule, exdate = exdate), moved)
        }

        /**
         * Plans a split's new series, which moves from the split occurrence [splitTs] to
         * [edited]'s start: its rule ([newSeriesRule], the user's rule with the remaining count,
         * shifted unless typed) and its cancelled dates (those at or after the split, moved,
         * unless the caller set them). Null when the rule can't express the move.
         * [pastCount] is the number of the master's occurrences before the split.
         */
        fun planSplit(master: Event, splitTs: Long, edited: Event, pastCount: Int, newSeriesRule: String): SplitPlan? {
            val masterRule = master.rrule ?: return null
            val typed = edited.rrule?.takeUnless {
                sameRuleForMove(masterRule, master.timezone, master.isAllDay, it, edited.timezone, edited.isAllDay)
            }
            // The old series' remainder comes from its own rule: the form rewrites the user's UNTIL
            // into the new all-day form, which the old series can't be expanded with.
            val oldRemainder =
                RruleUtils.splitRruleAtTime(masterRule, masterRule, splitTs - 1L, pastCount, master.isAllDay).second ?: newSeriesRule
            val move = plan(
                Inputs(
                    rule = oldRemainder, dtstartTs = splitTs, anchorTs = splitTs,
                    zone = master.timezone, isAllDay = master.isAllDay,
                    newStartTs = edited.startTs, newZone = edited.timezone, newIsAllDay = edited.isAllDay,
                    typedRule = typed?.let { newSeriesRule },
                )
            ) ?: return null
            val exdate = if (edited.exdate != master.exdate) {
                edited.exdate
            } else {
                exdatesFrom(master.exdate, splitTs)?.let { move.mapExdates(it) ?: return null }
            }
            return SplitPlan(move.newRule, exdate)
        }

        /**
         * True when a This-and-future save of [edited] from the occurrence [splitTs] can be
         * written: the same decisions the writer's split makes (an edit in place at or before
         * the first occurrence or for a degenerate count split, else [planSplit]).
         */
        fun canSplit(master: Event, splitTs: Long, edited: Event, exceptions: List<Event>): Boolean {
            val rule = master.rrule ?: return true
            if (splitTs <= master.startTs) return planSave(master, edited, splitTs, exceptions) != null
            val pastCount = if (rule.contains("COUNT=")) {
                org.onekash.kashcal.domain.generator.IcalDavRRuleEngine.expandToTimestamps(
                    rule, master.startTs, master.startTs - 1_000L, splitTs - 1L, master.timezone, master.isAllDay, null, null,
                ).size
            } else {
                0
            }
            if (RruleUtils.isDegenerateCountSplit(rule, pastCount)) return planSave(master, edited, splitTs, exceptions) != null
            val newRule = RruleUtils.splitRruleAtTime(rule, edited.rrule, splitTs - 1L, pastCount, master.isAllDay).second
                ?: return true
            return planSplit(master, splitTs, edited, pastCount, newRule) != null
        }

        /** [exception] re-keyed from [oldKey] to [newKey]; an unedited time moves with it. */
        private fun movedException(exception: Event, oldKey: Long, newKey: Long, stored: Event, edited: Event): Event {
            val timeUnedited = kotlin.math.abs(exception.startTs - oldKey) < LINK_TOLERANCE_MS &&
                exception.isAllDay == stored.isAllDay
            if (!timeUnedited) return exception.copy(originalInstanceTime = newKey)
            val length = if (stored.isAllDay != edited.isAllDay) edited.endTs - edited.startTs else exception.endTs - exception.startTs
            return exception.copy(
                originalInstanceTime = newKey,
                startTs = newKey,
                endTs = newKey + length,
                isAllDay = edited.isAllDay,
                timezone = edited.timezone,
            )
        }

        /**
         * True when [b] (of a series in [bZone], all-day per [bAllDay]) is [a] for a move: the same
         * rule by meaning, or one whose UNTIL names the same last date in another form (the form
         * re-anchors UNTIL on an all-day switch), which the move rewrites. An UNTIL on another
         * date is the user's new end, a typed rule.
         */
        fun sameRuleForMove(a: String, aZone: String?, aAllDay: Boolean, b: String, bZone: String?, bAllDay: Boolean): Boolean {
            if (RruleUtils.rrulesEquivalent(a, b)) return true
            fun withoutUntil(rule: String) =
                rule.split(';').filterNot { it.startsWith("UNTIL=", ignoreCase = true) }.joinToString(";")
            val aEnd = untilDate(a, aZone, aAllDay) ?: return false
            val bEnd = untilDate(b, bZone, bAllDay) ?: return false
            return aEnd == bEnd && RruleUtils.rrulesEquivalent(withoutUntil(a), withoutUntil(b))
        }

        /** The last date [rule]'s UNTIL names: a DATE as is, a date-time in the series' zone. */
        private fun untilDate(rule: String, zone: String?, isAllDay: Boolean): java.time.LocalDate? {
            val value = rule.split(';').firstOrNull { it.startsWith("UNTIL=", ignoreCase = true) }
                ?.substringAfter('=')?.trim() ?: return null
            return try {
                when {
                    value.length == 8 -> java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                    value.endsWith("Z", ignoreCase = true) -> java.time.LocalDateTime
                        .parse(value.dropLast(1), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        .toInstant(java.time.ZoneOffset.UTC).atZone(RruleShift.zoneFor(zone, isAllDay)).toLocalDate()
                    else -> java.time.LocalDateTime
                        .parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toLocalDate()
                }
            } catch (_: Exception) {
                null
            }
        }

        /** [csv]'s cancelled dates at or after [fromMs]; tokens that aren't epoch ms are kept. */
        private fun exdatesFrom(csv: String?, fromMs: Long): String? =
            csv?.split(',')?.map { it.trim() }
                ?.filter { token -> !EPOCH_MS.matches(token) || token.toLong() >= fromMs }
                ?.joinToString(",")?.ifBlank { null }

        /** Linking tolerance between a changed occurrence's key and its series slot. */
        private const val LINK_TOLERANCE_MS = 60_000L

        private fun frequency(rule: String): String? =
            rule.removePrefix("RRULE:").split(';')
                .firstOrNull { it.startsWith("FREQ=", ignoreCase = true) }
                ?.substringAfter('=')?.uppercase()

        private fun isBounded(rule: String) = rule.contains("COUNT=") || rule.contains("UNTIL=")
    }
}
