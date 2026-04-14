package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import java.time.temporal.ChronoUnit

object DurationRule : ParseRule {

    private val TIME_SCALE_UNITS = setOf(ChronoUnit.MINUTES, ChronoUnit.HOURS)

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((i, token) in tokens.withIndex()) {
            if (context.isConsumed(i)) continue
            if (token.type != TokenType.KEYWORD || token.value != "FOR") continue

            val numIndex = i + 1
            val unitIndex = i + 2
            if (unitIndex >= tokens.size) continue
            if (context.isConsumed(unitIndex)) continue

            val numToken = tokens[numIndex]
            val unitToken = tokens[unitIndex]
            if (unitToken.type != TokenType.UNIT) continue

            // Number token may be consumed by StructuredDateRule (e.g., "2.5"),
            // but FOR + X + UNIT pattern takes priority — skip only plain consumed NUMBERs
            if (context.isConsumed(numIndex) && numToken.type != TokenType.STRUCTURED_DATE) continue

            val unit = unitToken.value as? ChronoUnit ?: continue
            if (unit !in TIME_SCALE_UNITS) continue

            // Get the amount — either a plain NUMBER or a decimal via STRUCTURED_DATE
            val totalMinutes = when (numToken.type) {
                TokenType.NUMBER -> {
                    val amount = numToken.value as? Int ?: continue
                    when (unit) {
                        ChronoUnit.HOURS -> amount.toLong() * 60
                        ChronoUnit.MINUTES -> amount.toLong()
                        else -> continue
                    }
                }
                TokenType.STRUCTURED_DATE -> {
                    // Decimal like "2.5" matched as structured date — re-parse as double
                    val amount = numToken.text.toDoubleOrNull() ?: continue
                    when (unit) {
                        ChronoUnit.HOURS -> (amount * 60).toLong()
                        ChronoUnit.MINUTES -> amount.toLong()
                        else -> continue
                    }
                }
                else -> continue
            }

            val startTime = context.resolveTime() ?: context.reference.toLocalTime()
            context.endTime = startTime.plusMinutes(totalMinutes)

            // If we're reclaiming a STRUCTURED_DATE that StructuredDateRule interpreted as a date,
            // undo that misinterpretation
            if (numToken.type == TokenType.STRUCTURED_DATE && context.isConsumed(numIndex)) {
                context.absoluteDate = null
                context.dateSet = false
            }

            context.consume(i)
            context.consume(numIndex)
            context.consume(unitIndex)
            return
        }
    }
}
