package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import java.time.temporal.ChronoUnit

object RelativeOffsetRule : ParseRule {

    private val TIME_UNITS = setOf(ChronoUnit.SECONDS, ChronoUnit.MINUTES, ChronoUnit.HOURS)

    override fun apply(tokens: List<Token>, context: ParseContext) {
        // Pattern 1: "in NUMBER UNIT" (forward offset)
        if (tryInPattern(tokens, context)) return

        // Pattern 2: "NUMBER UNIT ago" (backward offset)
        if (tryAgoPattern(tokens, context)) return

        // Pattern 3: "NUMBER UNIT from now" / "NUMBER UNIT later" (forward offset)
        tryForwardTrailerPattern(tokens, context)
    }

    /**
     * "NUMBER UNIT from now" and "NUMBER UNIT later" — forward offsets equivalent
     * to "in NUMBER UNIT". "from"/"now" are KEYWORDs; "later" is UNKNOWN.
     */
    private fun tryForwardTrailerPattern(tokens: List<Token>, context: ParseContext): Boolean {
        for ((i, token) in tokens.withIndex()) {
            if (context.isConsumed(i)) continue
            if (token.type != TokenType.NUMBER) continue

            val unitIndex = i + 1
            if (unitIndex >= tokens.size) continue
            if (context.isConsumed(unitIndex)) continue
            val unitToken = tokens[unitIndex]
            if (unitToken.type != TokenType.UNIT) continue

            val amount = token.value as? Int ?: continue
            val unit = unitToken.value as? ChronoUnit ?: continue

            // "... from now"
            val trailerIndex = unitIndex + 1
            if (trailerIndex >= tokens.size) continue
            val trailer = tokens[trailerIndex]
            if (context.isConsumed(trailerIndex)) continue

            val consumed = mutableListOf(i, unitIndex)
            val matched = when {
                trailer.type == TokenType.KEYWORD && trailer.value == "FROM" -> {
                    val nowIndex = trailerIndex + 1
                    if (nowIndex >= tokens.size || context.isConsumed(nowIndex)) false
                    else {
                        val nowToken = tokens[nowIndex]
                        if (nowToken.type == TokenType.KEYWORD && nowToken.value == "NOW") {
                            consumed.add(trailerIndex)
                            consumed.add(nowIndex)
                            true
                        } else false
                    }
                }
                trailer.type == TokenType.UNKNOWN && trailer.text.lowercase() == "later" -> {
                    consumed.add(trailerIndex)
                    true
                }
                else -> false
            }
            if (!matched) continue

            applyOffset(context, amount.toLong(), unit, forward = true)
            context.consume(consumed)
            return true
        }
        return false
    }

    private fun tryInPattern(tokens: List<Token>, context: ParseContext): Boolean {
        for ((i, token) in tokens.withIndex()) {
            if (context.isConsumed(i)) continue
            if (token.type != TokenType.KEYWORD || token.value != "IN") continue

            val numIndex = i + 1
            val unitIndex = i + 2
            if (unitIndex >= tokens.size) continue
            if (context.isConsumed(numIndex) || context.isConsumed(unitIndex)) continue

            val numToken = tokens[numIndex]
            val unitToken = tokens[unitIndex]
            if (numToken.type != TokenType.NUMBER || unitToken.type != TokenType.UNIT) continue

            val amount = numToken.value as? Int ?: continue
            val unit = unitToken.value as? ChronoUnit ?: continue

            applyOffset(context, amount.toLong(), unit, forward = true)
            context.consume(i)
            context.consume(numIndex)
            context.consume(unitIndex)
            return true
        }
        return false
    }

    private fun tryAgoPattern(tokens: List<Token>, context: ParseContext): Boolean {
        for ((i, token) in tokens.withIndex()) {
            if (context.isConsumed(i)) continue
            if (token.type != TokenType.NUMBER) continue

            val unitIndex = i + 1
            val agoIndex = i + 2
            if (agoIndex >= tokens.size) continue
            if (context.isConsumed(unitIndex) || context.isConsumed(agoIndex)) continue

            val unitToken = tokens[unitIndex]
            val agoToken = tokens[agoIndex]
            if (unitToken.type != TokenType.UNIT) continue
            if (agoToken.type != TokenType.KEYWORD || agoToken.value != "AGO") continue

            val amount = token.value as? Int ?: continue
            val unit = unitToken.value as? ChronoUnit ?: continue

            applyOffset(context, amount.toLong(), unit, forward = false)
            context.consume(i)
            context.consume(unitIndex)
            context.consume(agoIndex)
            return true
        }
        return false
    }

    private fun applyOffset(context: ParseContext, amount: Long, unit: ChronoUnit, forward: Boolean) {
        val ref = context.reference
        val signedAmount = if (forward) amount else -amount

        val result = when (unit) {
            ChronoUnit.MINUTES -> ref.plusMinutes(signedAmount)
            ChronoUnit.HOURS -> ref.plusHours(signedAmount)
            ChronoUnit.DAYS -> ref.plusDays(signedAmount)
            ChronoUnit.WEEKS -> ref.plusWeeks(signedAmount)
            ChronoUnit.MONTHS -> ref.plusMonths(signedAmount)
            ChronoUnit.YEARS -> ref.plusYears(signedAmount)
            ChronoUnit.SECONDS -> ref.plusSeconds(signedAmount)
            else -> return
        }

        context.relativeDateTime = result
        context.dateSet = true

        // Time units also set the time component
        if (unit in TIME_UNITS) {
            context.timeSet = true
        }
    }
}
