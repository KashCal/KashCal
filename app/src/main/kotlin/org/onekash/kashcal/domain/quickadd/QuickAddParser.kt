package org.onekash.kashcal.domain.quickadd

import org.onekash.kashcal.domain.quickadd.normalizer.NormalizerChain
import org.onekash.kashcal.domain.EmojiMatcher
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

object QuickAddParser {

    private val normalizer = NormalizerChain()
    private val normalizerNoLowercase = NormalizerChain(lowercase = false)

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

    fun parse(input: String, reference: LocalDateTime = LocalDateTime.now()): QuickAddResult {
        if (input.isBlank()) {
            return QuickAddResult(
                title = "",
                startDate = reference.toLocalDate(),
                startTime = null,
                confidence = ParseConfidence.LOW
            )
        }

        val normalized = normalizer.normalize(input)
        // Apply same transforms without lowercase, for original-case title extraction
        val originalCased = normalizerNoLowercase.normalize(input)
        val originalWords = if (originalCased.isNotEmpty()) originalCased.split(" ") else emptyList()

        val tokens = WordTokenizer.tokenize(normalized, originalWords)

        if (tokens.isEmpty()) {
            return QuickAddResult(
                title = "",
                startDate = reference.toLocalDate(),
                startTime = null,
                confidence = ParseConfidence.LOW
            )
        }

        val context = ParseContext(reference)
        for (rule in rules) {
            rule.apply(tokens, context)
        }

        val title = extractTitle(tokens, context)
        val emoji = EmojiMatcher.getEmoji(title)
        val startDate = context.resolveDate()
        val startTime = context.resolveTime()

        val confidence = when {
            context.dateSet && context.timeSet -> ParseConfidence.HIGH
            context.dateSet || context.timeSet -> ParseConfidence.MEDIUM
            else -> ParseConfidence.LOW
        }

        return QuickAddResult(
            title = title,
            startDate = startDate,
            startTime = startTime,
            endTime = context.endTime,
            location = context.location,
            rrule = context.rrule,
            emoji = emoji,
            confidence = confidence
        )
    }

    private fun extractTitle(tokens: List<Token>, context: ParseContext): String {
        val consumed = context.getConsumedIndices()
        val titleTokens = tokens.indices
            .filter { i -> i !in consumed }
            .map { i -> tokens[i] }
            .filter { t -> t.type == TokenType.UNKNOWN || t.type == TokenType.NUMBER || t.type == TokenType.KEYWORD }
            .dropWhile { it.type == TokenType.KEYWORD }
            .dropLastWhile { it.type == TokenType.KEYWORD }
        return titleTokens.joinToString(" ") { it.originalText }.trim()
    }
}
