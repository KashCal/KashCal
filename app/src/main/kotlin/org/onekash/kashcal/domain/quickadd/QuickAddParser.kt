package org.onekash.kashcal.domain.quickadd

import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.category.TagTokenizer
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.normalizer.SpannedText
import org.onekash.kashcal.domain.quickadd.rule.AbsoluteDateRule
import org.onekash.kashcal.domain.quickadd.rule.DateRangeRule
import org.onekash.kashcal.domain.quickadd.rule.DurationRule
import org.onekash.kashcal.domain.quickadd.rule.LocationRule
import org.onekash.kashcal.domain.quickadd.rule.ParseContext
import org.onekash.kashcal.domain.quickadd.rule.RecurrenceRule
import org.onekash.kashcal.domain.quickadd.rule.RelativeDateRule
import org.onekash.kashcal.domain.quickadd.rule.RelativeOffsetRule
import org.onekash.kashcal.domain.quickadd.rule.StructuredDateRule
import org.onekash.kashcal.domain.quickadd.rule.TimeRule
import org.onekash.kashcal.domain.quickadd.rule.WeekdayRule
import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import org.onekash.kashcal.domain.rrule.RruleBuilder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object QuickAddParser {

    private val normalizer = NormalizerChain()

    // A note is everything after the first whitespace-preceded "//". Requiring the
    // whitespace means a pasted URL ("https://…", "zoom.us//x") is never split.
    private val NOTE_DELIMITER = Regex("""\s//""")

    // RecurrenceRule runs before WeekdayRule so it claims "every Monday" as a recurrence.
    private val rules = listOf(
        RelativeDateRule,
        RecurrenceRule,
        WeekdayRule,
        // Before AbsoluteDateRule, which would take only a range's start date.
        DateRangeRule,
        AbsoluteDateRule,
        StructuredDateRule,
        TimeRule,
        RelativeOffsetRule,
        DurationRule,
        LocationRule
    )

    /**
     * Parses Quick Add [input] against [reference].
     *
     * @param zone the zone the event is saved in; a timed series' "until" day ends at 23:59:59
     *   there. Null means the device zone (where QuickAddViewModel.save writes the start), looked
     *   up only when a timed series has an end.
     */
    @JvmOverloads
    fun parse(
        input: String,
        reference: LocalDateTime = LocalDateTime.now(),
        locale: Locale = Locale.getDefault(),
        firstDayOfWeek: Int = 0,
        zone: ZoneId? = null,
    ): QuickAddResult {
        if (input.isBlank()) {
            return QuickAddResult(
                title = "",
                startDate = reference.toLocalDate(),
                startTime = null,
                confidence = ParseConfidence.LOW
            )
        }

        // Split the note off the raw input first. The note is kept verbatim apart from
        // trimming, and only the text before it is parsed, so date, time or location
        // words in the note can't move the event.
        val delimiter = NOTE_DELIMITER.find(input)
        val beforeNote = if (delimiter != null) input.substring(0, delimiter.range.first) else input
        val note = delimiter
            ?.let { input.substring(it.range.last + 1).trim() }
            ?.ifEmpty { null }

        // Take #tags before normalizing, whose character cleanup strips '#'. Doing it after
        // the note split keeps a '#tag' inside the note verbatim. Accepted tags are blanked to
        // spaces, so positions in the typed text stay as they were.
        val tagged = TagTokenizer.extractRanges(beforeNote)
        val categories = tagged.tags
        val source = blankRanges(beforeNote, tagged.ranges)

        // The rules read the normalized words; the title and location are cut out of the typed
        // text by each token's span, so they keep what the user typed.
        val normalized = normalizer.normalize(SpannedText.of(source))
        val tokens = WordTokenizer.tokenize(normalized, source, locale)

        if (tokens.isEmpty()) {
            // A tags-only input (e.g. "#work") has no tokens left but keeps its categories.
            return QuickAddResult(
                title = "",
                startDate = reference.toLocalDate(),
                startTime = null,
                categories = categories,
                note = note,
                confidence = ParseConfidence.LOW
            )
        }

        val context = ParseContext(reference, firstDayOfWeek = firstDayOfWeek, source = source)
        for (rule in rules) {
            rule.apply(tokens, context)
        }

        val title = extractTitle(tokens, context, source)
        val emoji = EmojiMatcher.getEmoji(title)
        val startDate = context.resolveDate()
        val startTime = context.resolveTime()
        val rrule = finishUntil(context, startDate, startTime, zone)

        val confidence = when {
            context.dateSet && context.timeSet -> ParseConfidence.HIGH
            context.dateSet || context.timeSet || rrule != null -> ParseConfidence.MEDIUM
            else -> ParseConfidence.LOW
        }

        return QuickAddResult(
            title = title,
            startDate = startDate,
            startTime = startTime,
            endTime = context.endTime,
            endDate = context.endDate,
            location = context.location,
            rrule = rrule,
            categories = categories,
            emoji = emoji,
            note = note,
            confidence = confidence
        )
    }

    /**
     * Returns [source] with every token the rules consumed cut out, plus the KEYWORD tokens
     * ("at", "on", "the") left at either end of what remains.
     */
    private fun extractTitle(tokens: List<Token>, context: ParseContext, source: String): String {
        val consumed = context.getConsumedIndices()
        val kept = tokens.indices
            .filter { i -> i !in consumed }
            .dropWhile { tokens[it].type == TokenType.KEYWORD }
            .dropLastWhile { tokens[it].type == TokenType.KEYWORD }
            .toSet()
        val removed = tokens.indices.filter { it !in kept }.mapNotNull { tokens[it].span }
        return TypedText.cut(source, 0, source.length, removed)
    }

    /**
     * Returns the rule with the UNTIL for [ParseContext.untilDate], written here because the time
     * is read after the rules that set it: a DATE for an all-day series (RFC 5545 §3.3.10 makes
     * UNTIL a DATE for a DATE start), 23:59:59 of that day in [zone], in UTC, for a timed one, so
     * the named day's occurrence stays. An end before the start date leaves no series.
     */
    private fun finishUntil(
        context: ParseContext,
        startDate: LocalDate,
        startTime: LocalTime?,
        zone: ZoneId?,
    ): String? {
        val rrule = context.rrule ?: return null
        val until = context.untilDate ?: return rrule
        if (until.isBefore(startDate)) return null
        if (startTime == null) return "$rrule;UNTIL=${until.format(DateTimeFormatter.BASIC_ISO_DATE)}"
        val saveZone = zone ?: ZoneId.systemDefault()
        val endOfDay = until.atTime(23, 59, 59).atZone(saveZone).toInstant().toEpochMilli()
        return RruleBuilder.withUntil(rrule, endOfDay)
    }

    private fun blankRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val chars = text.toCharArray()
        for (range in ranges) {
            for (i in range) chars[i] = ' '
        }
        return String(chars)
    }
}
