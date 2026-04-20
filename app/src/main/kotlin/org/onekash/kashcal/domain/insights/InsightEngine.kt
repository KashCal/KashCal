package org.onekash.kashcal.domain.insights

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InsightEngine @Inject constructor(
    private val generators: Set<@JvmSuppressWildcards InsightGenerator>
) {

    fun computeInsights(
        stats: PeriodStats,
        occurrences: List<InsightOccurrence>,
        now: Long,
        context: Context
    ): List<Insight> {
        return generators
            .filter { it.shouldEmit(stats, occurrences, now) }
            .map { gen ->
                gen.generate(stats, occurrences, now, stats.periodStart, stats.periodEnd, context)
                    .copy(surpriseScore = gen.surpriseScore(stats, occurrences, now))
            }
            .sortedByDescending { it.surpriseScore }
            .take(5)
    }
}
