package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType

object RelativeDateRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.DATE_KEYWORD) continue

            val keyword = token.value as? String ?: continue
            val refDate = context.reference.toLocalDate()

            if (keyword == "ALL_DAY") {
                context.consume(index)
                continue
            }

            val date = when (keyword) {
                "today" -> refDate
                "tomorrow" -> refDate.plusDays(1)
                "yesterday" -> refDate.minusDays(1)
                "day_after_tomorrow" -> refDate.plusDays(2)
                "day_before_yesterday" -> refDate.minusDays(2)
                else -> continue
            }

            context.dateKeywordDate = date
            context.dateSet = true
            context.consume(index)
            return
        }
    }
}
