package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalTime

object TimeRule : ParseRule {

    private val fuzzyTimeKeywords = setOf("morning", "afternoon", "evening", "night", "tonight")

    override fun apply(tokens: List<Token>, context: ParseContext) {
        if (handleQuarterHalfPattern(tokens, context)) return

        // Two-pass: explicit times + precise keywords first, fuzzy keywords second
        if (handleExplicitTime(tokens, context)) return
        handleFuzzyTimeKeyword(tokens, context)
    }

    private fun handleExplicitTime(tokens: List<Token>, context: ParseContext): Boolean {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue

            when (token.type) {
                TokenType.TIME_RANGE -> {
                    val range = token.value as? WordTokenizer.TimeRange ?: continue
                    context.time = range.start
                    context.endTime = range.end
                    context.timeSet = true
                    context.consume(index)
                    consumeFollowingTimezone(tokens, index, context)
                    consumePrecedingModifier(tokens, index, context)
                    return true
                }
                TokenType.TIME -> {
                    var time = token.value as? LocalTime ?: continue
                    // Check for following MERIDIEM token (e.g., "3:30 PM")
                    val nextIdx = index + 1
                    if (nextIdx < tokens.size && !context.isConsumed(nextIdx)) {
                        val nextToken = tokens[nextIdx]
                        if (nextToken.type == TokenType.MERIDIEM) {
                            val meridiem = nextToken.text.lowercase().replace(".", "")
                            val adjusted = resolveWithMeridiem(time.hour, time.minute, meridiem)
                            if (adjusted != null) {
                                time = adjusted
                                context.consume(nextIdx)
                            }
                        }
                    }

                    // Check for "TIME to TIME" pattern (e.g., "2pm to 4pm")
                    val toIdx = context.findNextUnconsumed(tokens, index + 1)
                    if (toIdx != null && tokens[toIdx].type == TokenType.KEYWORD && tokens[toIdx].value == "TO") {
                        val endTimeIdx = context.findNextUnconsumed(tokens, toIdx + 1)
                        if (endTimeIdx != null && tokens[endTimeIdx].type == TokenType.TIME) {
                            val endTime = tokens[endTimeIdx].value as? LocalTime
                            if (endTime != null) {
                                context.time = time
                                context.endTime = endTime
                                context.timeSet = true
                                context.consume(index)
                                context.consume(toIdx)
                                context.consume(endTimeIdx)
                                consumeFollowingTimezone(tokens, endTimeIdx, context)
                                consumePrecedingModifier(tokens, index, context)
                                return true
                            }
                        }
                    }

                    context.time = time
                    context.timeSet = true
                    context.consume(index)
                    consumeFollowingTimezone(tokens, index, context)
                    consumePrecedingModifier(tokens, index, context)
                    return true
                }
                TokenType.TIME_KEYWORD -> {
                    if (token.text.lowercase() in fuzzyTimeKeywords) continue
                    val time = token.value as? LocalTime ?: continue
                    context.time = time
                    context.timeSet = true
                    context.consume(index)
                    consumePrecedingModifier(tokens, index, context)
                    return true
                }
                TokenType.STRUCTURED_DATE -> {
                    if (!isDotOnly(token.text)) continue
                    val nextIdx = context.findNextUnconsumed(tokens, index + 1)
                    if (nextIdx == null || tokens[nextIdx].type != TokenType.MERIDIEM) continue
                    val dotParts = token.text.split('.')
                    if (dotParts.size != 2) continue
                    val hour = dotParts[0].toIntOrNull() ?: continue
                    val minute = dotParts[1].toIntOrNull() ?: continue
                    if (minute > 59) continue
                    val meridiem = tokens[nextIdx].text.lowercase().replace(".", "")
                    val time = resolveWithMeridiem(hour, minute, meridiem) ?: continue
                    context.time = time
                    context.timeSet = true
                    context.consume(index)
                    context.consume(nextIdx)
                    consumeFollowingTimezone(tokens, nextIdx, context)
                    consumePrecedingModifier(tokens, index, context)
                    return true
                }
                TokenType.NUMBER -> {
                    val hour = token.value as? Int ?: continue
                    val nextIndex = index + 1
                    if (nextIndex < tokens.size && !context.isConsumed(nextIndex)) {
                        val nextToken = tokens[nextIndex]

                        // "NUMBER MERIDIEM" pattern (e.g., "3 pm")
                        if (nextToken.type == TokenType.MERIDIEM) {
                            val meridiem = nextToken.text.lowercase().replace(".", "")
                            val time = resolveWithMeridiem(hour, 0, meridiem) ?: continue
                            context.time = time
                            context.timeSet = true
                            context.consume(index)
                            context.consume(nextIndex)
                            consumeFollowingTimezone(tokens, nextIndex, context)
                            consumePrecedingModifier(tokens, index, context)
                            return true
                        }

                        // "NUMBER NUMBER [MERIDIEM]" pattern (e.g., "2 30 pm", "10 15")
                        if (nextToken.type == TokenType.NUMBER) {
                            val minute = nextToken.value as? Int ?: continue
                            if (minute > 59) continue
                            val meridiemIndex = nextIndex + 1
                            if (meridiemIndex < tokens.size && !context.isConsumed(meridiemIndex)
                                && tokens[meridiemIndex].type == TokenType.MERIDIEM
                            ) {
                                // "2 30 pm" → 14:30
                                val meridiem = tokens[meridiemIndex].text.lowercase().replace(".", "")
                                val time = resolveWithMeridiem(hour, minute, meridiem) ?: continue
                                context.time = time
                                context.timeSet = true
                                context.consume(index)
                                context.consume(nextIndex)
                                context.consume(meridiemIndex)
                                consumeFollowingTimezone(tokens, meridiemIndex, context)
                                consumePrecedingModifier(tokens, index, context)
                                return true
                            } else if (hour in 0..23 && hasPrecedingAt(tokens, index, context)) {
                                // "at 10 15" → 10:15 (24h, requires "at" prefix)
                                context.time = LocalTime.of(hour, minute)
                                context.timeSet = true
                                context.consume(index)
                                context.consume(nextIndex)
                                consumeFollowingTimezone(tokens, nextIndex, context)
                                consumePrecedingModifier(tokens, index, context)
                                return true
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        return false
    }

    private fun handleFuzzyTimeKeyword(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.TIME_KEYWORD) continue
            if (token.text.lowercase() !in fuzzyTimeKeywords) continue
            val time = token.value as? LocalTime ?: continue
            context.time = time
            context.timeSet = true
            context.consume(index)
            if (token.text.lowercase() == "tonight") {
                context.dateKeywordDate = context.reference.toLocalDate()
                context.dateSet = true
            }
            consumePrecedingModifier(tokens, index, context)
            return
        }
    }

    private fun handleQuarterHalfPattern(tokens: List<Token>, context: ParseContext): Boolean {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue
            if (token.type != TokenType.KEYWORD) continue
            val keyword = token.value as? String ?: continue
            if (keyword !in listOf("QUARTER_PAST", "HALF_PAST", "QUARTER_TO")) continue

            val hourIdx = context.findNextUnconsumed(tokens, index + 1) ?: continue
            val hourToken = tokens[hourIdx]
            if (hourToken.type != TokenType.NUMBER) continue
            var hour = hourToken.value as? Int ?: continue
            if (hour < 0 || hour > 23) continue

            val consumed = mutableListOf(index, hourIdx)

            val meridiemIdx = context.findNextUnconsumed(tokens, hourIdx + 1)
            if (meridiemIdx != null && tokens[meridiemIdx].type == TokenType.MERIDIEM) {
                val meridiem = tokens[meridiemIdx].text.lowercase().replace(".", "")
                val resolved = WordTokenizer.resolveMeridiemHour(hour, meridiem) ?: continue
                hour = resolved
                consumed.add(meridiemIdx)
            }

            val time = when (keyword) {
                "QUARTER_PAST" -> LocalTime.of(hour, 15)
                "HALF_PAST" -> LocalTime.of(hour, 30)
                "QUARTER_TO" -> {
                    val adjustedHour = if (hour == 0) 23 else hour - 1
                    LocalTime.of(adjustedHour, 45)
                }
                else -> continue
            }

            context.time = time
            context.timeSet = true
            context.consume(consumed)
            consumeFollowingTimezone(tokens, consumed.last(), context)
            return true
        }
        return false
    }

    private fun hasPrecedingAt(tokens: List<Token>, currentIndex: Int, context: ParseContext): Boolean {
        if (currentIndex == 0) return false
        val prev = tokens[currentIndex - 1]
        return !context.isConsumed(currentIndex - 1) && prev.type == TokenType.KEYWORD &&
            (prev.value == "AT" || prev.value == "THIS")
    }

    private fun consumePrecedingModifier(tokens: List<Token>, currentIndex: Int, context: ParseContext) {
        if (currentIndex == 0) return
        val prev = tokens[currentIndex - 1]
        if (!context.isConsumed(currentIndex - 1) && prev.type == TokenType.KEYWORD &&
            (prev.value == "AT" || prev.value == "THIS")) {
            context.consume(currentIndex - 1)
        }
    }

    private fun consumeFollowingTimezone(tokens: List<Token>, lastConsumedIndex: Int, context: ParseContext) {
        val tzIdx = context.findNextUnconsumed(tokens, lastConsumedIndex + 1) ?: return
        val tzToken = tokens[tzIdx]
        if (tzToken.type == TokenType.TIMEZONE) {
            context.timezone = tzToken.value as? String ?: return
            context.consume(tzIdx)
        }
    }

    private fun resolveWithMeridiem(hour: Int, minute: Int, meridiem: String): LocalTime? {
        val resolvedHour = WordTokenizer.resolveMeridiemHour(hour, meridiem) ?: return null
        return LocalTime.of(resolvedHour, minute)
    }
}
