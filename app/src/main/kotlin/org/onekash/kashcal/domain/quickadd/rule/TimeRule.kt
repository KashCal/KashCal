package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import org.onekash.kashcal.domain.quickadd.tokenizer.WordTokenizer
import java.time.LocalTime

object TimeRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        for ((index, token) in tokens.withIndex()) {
            if (context.isConsumed(index)) continue

            when (token.type) {
                TokenType.TIME_RANGE -> {
                    val range = token.value as? WordTokenizer.TimeRange ?: continue
                    context.time = range.start
                    context.endTime = range.end
                    context.timeSet = true
                    context.consume(index)
                    consumePrecedingAt(tokens, index, context)
                    return
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
                    val toIdx = findNextUnconsumed(tokens, index + 1, context)
                    if (toIdx != null && tokens[toIdx].type == TokenType.KEYWORD && tokens[toIdx].value == "TO") {
                        val endTimeIdx = findNextUnconsumed(tokens, toIdx + 1, context)
                        if (endTimeIdx != null && tokens[endTimeIdx].type == TokenType.TIME) {
                            val endTime = tokens[endTimeIdx].value as? LocalTime
                            if (endTime != null) {
                                context.time = time
                                context.endTime = endTime
                                context.timeSet = true
                                context.consume(index)
                                context.consume(toIdx)
                                context.consume(endTimeIdx)
                                consumePrecedingAt(tokens, index, context)
                                return
                            }
                        }
                    }

                    context.time = time
                    context.timeSet = true
                    context.consume(index)
                    consumePrecedingAt(tokens, index, context)
                    return
                }
                TokenType.TIME_KEYWORD -> {
                    val time = token.value as? LocalTime ?: continue
                    context.time = time
                    context.timeSet = true
                    context.consume(index)
                    consumePrecedingAt(tokens, index, context)
                    return
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
                            consumePrecedingAt(tokens, index, context)
                            return
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
                                consumePrecedingAt(tokens, index, context)
                                return
                            } else if (hour in 0..23 && hasPrecedingAt(tokens, index, context)) {
                                // "at 10 15" → 10:15 (24h, requires "at" prefix)
                                context.time = LocalTime.of(hour, minute)
                                context.timeSet = true
                                context.consume(index)
                                context.consume(nextIndex)
                                consumePrecedingAt(tokens, index, context)
                                return
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }

    private fun hasPrecedingAt(tokens: List<Token>, currentIndex: Int, context: ParseContext): Boolean {
        if (currentIndex == 0) return false
        val prev = tokens[currentIndex - 1]
        return !context.isConsumed(currentIndex - 1) && prev.type == TokenType.KEYWORD && prev.value == "AT"
    }

    private fun consumePrecedingAt(tokens: List<Token>, currentIndex: Int, context: ParseContext) {
        if (currentIndex == 0) return
        val prev = tokens[currentIndex - 1]
        if (!context.isConsumed(currentIndex - 1) && prev.type == TokenType.KEYWORD && prev.value == "AT") {
            context.consume(currentIndex - 1)
        }
    }

    private fun findNextUnconsumed(tokens: List<Token>, fromIndex: Int, context: ParseContext): Int? {
        for (i in fromIndex until tokens.size) {
            if (!context.isConsumed(i)) return i
        }
        return null
    }

    private fun resolveWithMeridiem(hour: Int, minute: Int, meridiem: String): LocalTime? {
        val resolvedHour = WordTokenizer.resolveMeridiemHour(hour, meridiem) ?: return null
        return LocalTime.of(resolvedHour, minute)
    }
}
