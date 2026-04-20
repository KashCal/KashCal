package org.onekash.kashcal.domain.insights

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
class InsightEngineTest {

    private lateinit var engine: InsightEngine
    private val context: Context = mockk(relaxed = true)
    private val emptyOccurrences = emptyList<InsightOccurrence>()
    private val emptyStats = PeriodStats.EMPTY

    @Before
    fun setup() {
        every { context.getString(any()) } returns "test"
        every { context.getString(any(), *anyVararg()) } returns "test"
    }

    @Test
    fun `returns top 5 insights sorted by surprise score descending`() {
        val generators = (1..8).map { i ->
            createMockGenerator(
                id = InsightId.entries[i % InsightId.entries.size],
                shouldEmit = true,
                surprise = i / 10f,
                text = "Insight $i"
            )
        }.toSet()

        engine = InsightEngine(generators)
        val results = engine.computeInsights(emptyStats, emptyOccurrences, 0L, context)

        assertEquals(5, results.size)
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].surpriseScore >= results[i + 1].surpriseScore)
        }
    }

    @Test
    fun `excludes insights where shouldEmit is false`() {
        val generators = setOf(
            createMockGenerator(InsightId.BUSIEST_DAY, shouldEmit = true, surprise = 0.5f),
            createMockGenerator(InsightId.LIGHTEST_DAY, shouldEmit = false, surprise = 0.9f),
            createMockGenerator(InsightId.WEEKEND_LOAD, shouldEmit = true, surprise = 0.3f)
        )

        engine = InsightEngine(generators)
        val results = engine.computeInsights(emptyStats, emptyOccurrences, 0L, context)

        assertEquals(2, results.size)
        assertTrue(results.none { it.id == InsightId.LIGHTEST_DAY })
    }

    @Test
    fun `returns fewer than 5 when fewer generators emit`() {
        val generators = setOf(
            createMockGenerator(InsightId.BUSIEST_DAY, shouldEmit = true, surprise = 0.5f),
            createMockGenerator(InsightId.WEEKEND_LOAD, shouldEmit = true, surprise = 0.3f)
        )

        engine = InsightEngine(generators)
        val results = engine.computeInsights(emptyStats, emptyOccurrences, 0L, context)

        assertEquals(2, results.size)
    }

    @Test
    fun `returns empty list when no generators emit`() {
        val generators = setOf(
            createMockGenerator(InsightId.BUSIEST_DAY, shouldEmit = false, surprise = 0.5f)
        )

        engine = InsightEngine(generators)
        val results = engine.computeInsights(emptyStats, emptyOccurrences, 0L, context)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `works with empty generator set`() {
        engine = InsightEngine(emptySet())
        val results = engine.computeInsights(emptyStats, emptyOccurrences, 0L, context)
        assertTrue(results.isEmpty())
    }

    private fun createMockGenerator(
        id: InsightId,
        shouldEmit: Boolean,
        surprise: Float,
        text: String = "Test insight"
    ): InsightGenerator = object : InsightGenerator {
        override val id = id
        override fun shouldEmit(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long) = shouldEmit
        override fun generate(
            stats: PeriodStats, occurrences: List<InsightOccurrence>,
            now: Long, periodStart: Long, periodEnd: Long, context: Context
        ) = Insight(id = id, text = text, icon = InsightIcon.CHART_BAR, surpriseScore = surprise)
        override fun surpriseScore(stats: PeriodStats, occurrences: List<InsightOccurrence>, now: Long) = surprise
    }
}
