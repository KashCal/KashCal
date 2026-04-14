package org.onekash.kashcal.domain.quickadd.rule

import org.onekash.kashcal.domain.quickadd.tokenizer.Token
import org.onekash.kashcal.domain.quickadd.tokenizer.TokenType
import java.time.Month

object AbsoluteDateRule : ParseRule {

    override fun apply(tokens: List<Token>, context: ParseContext) {
        // Try patterns in order:
        // 1. NUMBER "of" MONTH [YEAR]  — "15th of March 2027"
        // 2. MONTH NUMBER [YEAR]       — "January 15 2027"
        // 3. NUMBER MONTH [YEAR]       — "15 January 2027"

        if (tryOrdinalOfMonth(tokens, context)) return
        if (tryMonthDay(tokens, context)) return
        if (tryDayMonth(tokens, context)) return
    }

    /**
     * Pattern: NUMBER "of" MONTH [YEAR] — "15th of March", "21st of March 2027"
     */
    private fun tryOrdinalOfMonth(tokens: List<Token>, context: ParseContext): Boolean {
        for (i in tokens.indices) {
            if (context.isConsumed(i)) continue
            if (tokens[i].type != TokenType.NUMBER) continue

            val day = tokens[i].value as? Int ?: continue
            if (day < 1 || day > 31) continue

            // Look for "of" MONTH
            val ofIndex = i + 1
            if (ofIndex >= tokens.size) continue
            if (context.isConsumed(ofIndex)) continue
            val ofToken = tokens[ofIndex]
            if (ofToken.type != TokenType.KEYWORD || ofToken.value != "OF") continue

            val monthIndex = ofIndex + 1
            if (monthIndex >= tokens.size) continue
            if (context.isConsumed(monthIndex)) continue
            if (tokens[monthIndex].type != TokenType.MONTH) continue

            val month = tokens[monthIndex].value as? Month ?: continue

            // Optional year
            val yearIndex = monthIndex + 1
            val year = findYear(tokens, yearIndex, context)

            val date = context.resolveFutureDate(day, month.value, year?.first)
            if (date != null) {
                context.absoluteDate = date
                context.dateSet = true
                context.consume(listOf(i, ofIndex, monthIndex))
                year?.let { context.consume(it.second) }
            }
            return true
        }
        return false
    }

    /**
     * Pattern: MONTH NUMBER [YEAR] — "January 15", "Jan 15 2027"
     */
    private fun tryMonthDay(tokens: List<Token>, context: ParseContext): Boolean {
        for (i in tokens.indices) {
            if (context.isConsumed(i)) continue
            if (tokens[i].type != TokenType.MONTH) continue

            val month = tokens[i].value as? Month ?: continue

            // Look for NUMBER after month
            val dayIndex = i + 1
            if (dayIndex >= tokens.size) continue
            if (context.isConsumed(dayIndex)) continue
            if (tokens[dayIndex].type != TokenType.NUMBER) continue

            val day = tokens[dayIndex].value as? Int ?: continue
            if (day < 1 || day > 31) continue

            // Optional year
            val yearIndex = dayIndex + 1
            val year = findYear(tokens, yearIndex, context)

            val date = context.resolveFutureDate(day, month.value, year?.first)
            if (date != null) {
                context.absoluteDate = date
                context.dateSet = true
                context.consume(listOf(i, dayIndex))
                year?.let { context.consume(it.second) }
            }
            return true
        }
        return false
    }

    /**
     * Pattern: NUMBER MONTH [YEAR] — "15 January", "15 jan 2027", "25 december 2026"
     * (without "of" keyword)
     */
    private fun tryDayMonth(tokens: List<Token>, context: ParseContext): Boolean {
        for (i in tokens.indices) {
            if (context.isConsumed(i)) continue
            if (tokens[i].type != TokenType.NUMBER) continue

            val day = tokens[i].value as? Int ?: continue
            if (day < 1 || day > 31) continue

            // Look for MONTH directly after number (no "of" keyword)
            val monthIndex = i + 1
            if (monthIndex >= tokens.size) continue
            if (context.isConsumed(monthIndex)) continue
            if (tokens[monthIndex].type != TokenType.MONTH) continue

            val month = tokens[monthIndex].value as? Month ?: continue

            // Optional year
            val yearIndex = monthIndex + 1
            val year = findYear(tokens, yearIndex, context)

            val date = context.resolveFutureDate(day, month.value, year?.first)
            if (date != null) {
                context.absoluteDate = date
                context.dateSet = true
                context.consume(listOf(i, monthIndex))
                year?.let { context.consume(it.second) }
            }
            return true
        }
        return false
    }

    private fun findYear(tokens: List<Token>, startIndex: Int, context: ParseContext): Pair<Int, Int>? {
        if (startIndex >= tokens.size) return null
        if (context.isConsumed(startIndex)) return null
        val token = tokens[startIndex]
        if (token.type != TokenType.YEAR) return null
        return (token.value as? Int)?.let { it to startIndex }
    }
}
