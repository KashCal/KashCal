package org.onekash.kashcal.domain.quickadd

import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.category.TagTokenizer
import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.quickadd.rule.AbsoluteDateRule
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
import java.time.LocalDateTime
import java.util.Locale

object QuickAddParser {

    private val normalizer = NormalizerChain()
    private val normalizerNoLowercase = NormalizerChain(lowercase = false)

    // A note is everything after the first whitespace-preceded "//". The leading
    // whitespace is required so pasted URLs ("https://…", "zoom.us//x") — which
    // have no space before the slashes — are never split.
    private val NOTE_DELIMITER = Regex("""\s//""")

    // Order matters: RecurrenceRule before WeekdayRule so "every Monday" is claimed as recurrence
    private val rules = listOf(
        RelativeDateRule,
        RecurrenceRule,
        WeekdayRule,
        AbsoluteDateRule,
        StructuredDateRule,
        TimeRule,
        RelativeOffsetRule,
        DurationRule,
        LocationRule
    )

    fun parse(
        input: String,
        reference: LocalDateTime = LocalDateTime.now(),
        locale: Locale = Locale.getDefault(),
        firstDayOfWeek: Int = 0,
    ): QuickAddResult {
        if (input.isBlank()) {
            return QuickAddResult(
                title = "",
                startDate = reference.toLocalDate(),
                startTime = null,
                confidence = ParseConfidence.LOW
            )
        }

        // Split the inline note off the RAW input before anything else: the text
        // after the first whitespace-preceded "//" is the note (stored verbatim,
        // surrounding whitespace trimmed), and only the part before it is parsed —
        // so date/time/location words inside the note can't move the schedule.
        val delimiter = NOTE_DELIMITER.find(input)
        val beforeNote = if (delimiter != null) input.substring(0, delimiter.range.first) else input
        val note = delimiter
            ?.let { input.substring(it.range.last + 1).trim() }
            ?.ifEmpty { null }

        // Extract #tags from the RAW (pre-note) input first: the normalizer's
        // character cleanup strips '#', so a parse rule downstream could never see
        // it. Running after the note split keeps a '#tag' inside the note verbatim.
        val (detagged, categories) = TagTokenizer.extract(beforeNote)

        val normalized = normalizer.normalize(detagged)
        // Apply same transforms without lowercase, for original-case title extraction
        val originalCased = normalizerNoLowercase.normalize(detagged)
        val originalWords = if (originalCased.isNotEmpty()) originalCased.split(" ") else emptyList()

        val tokens = WordTokenizer.tokenize(normalized, originalWords, locale)

        if (tokens.isEmpty()) {
            // A tags-only input (e.g. "#work") has no remaining tokens but must
            // still carry the extracted categories through.
            return QuickAddResult(
                title = "",
                startDate = reference.toLocalDate(),
                startTime = null,
                categories = categories,
                note = note,
                confidence = ParseConfidence.LOW
            )
        }

        val context = ParseContext(reference, firstDayOfWeek = firstDayOfWeek)
        for (rule in rules) {
            rule.apply(tokens, context)
        }

        val title = extractTitle(tokens, context)
        val emoji = EmojiMatcher.getEmoji(title)
        val startDate = context.resolveDate()
        val startTime = context.resolveTime()

        val confidence = when {
            context.dateSet && context.timeSet -> ParseConfidence.HIGH
            context.dateSet || context.timeSet || context.rrule != null -> ParseConfidence.MEDIUM
            else -> ParseConfidence.LOW
        }

        return QuickAddResult(
            title = title,
            startDate = startDate,
            startTime = startTime,
            endTime = context.endTime,
            endDate = context.endDate,
            timezone = context.timezone,
            location = context.location,
            rrule = context.rrule,
            categories = categories,
            emoji = emoji,
            note = note,
            confidence = confidence
        )
    }

    private fun extractTitle(tokens: List<Token>, context: ParseContext): String {
        val consumed = context.getConsumedIndices()
        val titleTokens = tokens.indices
            .filter { i -> i !in consumed }
            .map { i -> tokens[i] }
            .dropWhile { it.type == TokenType.KEYWORD }
            .dropLastWhile { it.type == TokenType.KEYWORD }
        return titleTokens.joinToString(" ") { it.originalText }.trim()
    }
}
