package org.onekash.kashcal.domain.insights

import android.content.Context

interface InsightGenerator {
    val id: InsightId
    fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Boolean
    fun generate(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long, periodStart: Long, periodEnd: Long, context: Context): Insight
    fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long): Float
}
