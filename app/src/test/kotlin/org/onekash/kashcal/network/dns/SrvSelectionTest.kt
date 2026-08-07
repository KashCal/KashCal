package org.onekash.kashcal.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Tests for RFC 2782 SRV selection ordering. Randomness is injected as
 * `rng: () -> Double`, so ordering is deterministic and exactly assertable —
 * and the production code contains no global `Math.random`.
 */
class SrvSelectionTest {

    /** Pops a fixed sequence of doubles; throws if the code draws more than supplied. */
    private class StubRng(vararg values: Double) : () -> Double {
        private val seq = values.toMutableList()
        override fun invoke(): Double {
            check(seq.isNotEmpty()) { "rng drawn more times than the test supplied" }
            return seq.removeAt(0)
        }
    }

    private fun rec(priority: Int, weight: Int, target: String) =
        SrvRecord(priority, weight, port = 443, target = target)

    private fun targets(records: List<SrvRecord>) = records.map { it.target }

    // ---- trivial cases -------------------------------------------------------

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<SrvRecord>(), SrvSelection.order(emptyList(), StubRng()))
    }

    @Test
    fun `single record is returned as-is without drawing rng`() {
        val one = listOf(rec(0, 0, "only.example.test"))
        // Same instance back: the size<=1 short-circuit returns the input list
        // untouched (no bucketing, no allocation) and never draws rng.
        assertSame(one, SrvSelection.order(one, StubRng()))  // StubRng throws if drawn
    }

    @Test
    fun `empty list is returned as the same instance`() {
        val none = emptyList<SrvRecord>()
        assertSame(none, SrvSelection.order(none, StubRng()))
    }

    // ---- priority buckets ----------------------------------------------------

    @Test
    fun `lower priority always precedes higher regardless of weight`() {
        val records = listOf(
            rec(20, 100, "low-prio.example.test"),
            rec(10, 1, "high-prio.example.test"),
        )
        // Single record per bucket -> no rng draws needed.
        assertEquals(
            listOf("high-prio.example.test", "low-prio.example.test"),
            targets(SrvSelection.order(records, StubRng())),
        )
    }

    @Test
    fun `three priority buckets ordered ascending`() {
        val records = listOf(
            rec(30, 0, "c.example.test"),
            rec(10, 0, "a.example.test"),
            rec(20, 0, "b.example.test"),
        )
        assertEquals(
            listOf("a.example.test", "b.example.test", "c.example.test"),
            targets(SrvSelection.order(records, StubRng())),
        )
    }

    // ---- weighted ordering within a bucket (deterministic under fixed rng) ---

    @Test
    fun `high draw selects the later record first within an equal-weight bucket`() {
        val records = listOf(
            rec(10, 1, "a.example.test"),
            rec(10, 1, "b.example.test"),
        )
        // RFC 2782 zero-weight rule adds 1 per record: running weights [2,4];
        // r=0.9*4=3.6 -> first cumsum>3.6 is b.
        assertEquals(
            listOf("b.example.test", "a.example.test"),
            targets(SrvSelection.order(records, StubRng(0.9))),
        )
    }

    @Test
    fun `low draw selects the earlier record first within an equal-weight bucket`() {
        val records = listOf(
            rec(10, 1, "a.example.test"),
            rec(10, 1, "b.example.test"),
        )
        // running weights [2,4]; r=0.1*4=0.4 -> first cumsum>0.4 is a.
        assertEquals(
            listOf("a.example.test", "b.example.test"),
            targets(SrvSelection.order(records, StubRng(0.1))),
        )
    }

    @Test
    fun `heavier weight is chosen first for a mid-range draw`() {
        val records = listOf(
            rec(10, 10, "light.example.test"),
            rec(10, 90, "heavy.example.test"),
        )
        // +1 per record -> running weights [11,102]; r=0.5*102=51 -> first cumsum>51 is heavy.
        assertEquals(
            "heavy.example.test",
            SrvSelection.order(records, StubRng(0.5)).first().target,
        )
    }

    // ---- zero-weight handling ------------------------------------------------

    @Test
    fun `all-zero-weight bucket is uniform and fully ordered`() {
        val records = listOf(
            rec(10, 0, "a.example.test"),
            rec(10, 0, "b.example.test"),
        )
        // All-zero -> each contributes weight+1=1: running weights [1,2].
        // r=0.9*2=1.8 -> b first, then a.
        assertEquals(
            listOf("b.example.test", "a.example.test"),
            targets(SrvSelection.order(records, StubRng(0.9))),
        )
    }

    @Test
    fun `draw landing exactly on a cumulative-sum boundary selects the later record`() {
        // Equal weights -> weight+1 running sums [2, 4]; rng 0.5 * total 4 = pick 2.0,
        // exactly the first record's running sum. RFC 2782 selects the first RR whose
        // running sum is strictly greater (the code uses half-open `pick < running`),
        // so a's sum of 2 does NOT win at pick 2.0 — b does. A boundary mutant that
        // flips this to `pick <= running` would pick a instead.
        val records = listOf(
            rec(10, 1, "a.example.test"),
            rec(10, 1, "b.example.test"),
        )
        assertEquals(
            listOf("b.example.test", "a.example.test"),
            targets(SrvSelection.order(records, StubRng(0.5))),
        )
    }

    @Test
    fun `a maximal draw of the full total falls back to the last record without overrun`() {
        // The defensive fallback (chosen = last index) exists for the floating-point
        // edge where pick rounds up to the total and no running sum strictly exceeds
        // it. Force that edge with a draw of exactly 1.0: no `pick < running` fires,
        // so the fallback index must land on the final remaining record. A wrong
        // fallback index (e.g. size instead of size-1) would throw out of bounds.
        val records = listOf(
            rec(10, 1, "a.example.test"),
            rec(10, 1, "b.example.test"),
        )
        assertEquals(
            listOf("b.example.test", "a.example.test"),
            targets(SrvSelection.order(records, StubRng(1.0))),
        )
    }

    @Test
    fun `max draw never over-selects past the last record`() {
        val records = listOf(
            rec(10, 1, "a.example.test"),
            rec(10, 1, "b.example.test"),
            rec(10, 1, "c.example.test"),
        )
        // Draw the largest value the half-open rng can yield at each step; the
        // selector must still terminate with a valid full ordering (no index
        // past the end).
        val result = SrvSelection.order(records, StubRng(0.999999, 0.999999))
        assertEquals(3, result.size)
        assertEquals(setOf("a.example.test", "b.example.test", "c.example.test"), targets(result).toSet())
    }

    // ---- content preservation ------------------------------------------------

    @Test
    fun `ordering is a permutation - no records dropped or duplicated`() {
        val records = listOf(
            rec(10, 5, "a.example.test"),
            rec(10, 0, "b.example.test"),
            rec(20, 3, "c.example.test"),
            rec(20, 3, "d.example.test"),
        )
        val result = SrvSelection.order(records, StubRng(0.3, 0.7))
        assertEquals(records.toSet(), result.toSet())
        assertEquals(records.size, result.size)
    }

    // ---- distribution sanity (seeded, deterministic, no Math.random) ---------

    @Test
    fun `heavier weight wins the first slot far more often over many seeded draws`() {
        val records = listOf(
            rec(10, 10, "light.example.test"),
            rec(10, 90, "heavy.example.test"),
        )
        val rnd = Random(42)
        var heavyFirst = 0
        val trials = 10_000
        repeat(trials) {
            if (SrvSelection.order(records) { rnd.nextDouble() }.first().target == "heavy.example.test") {
                heavyFirst++
            }
        }
        // Expected ~90%; assert a wide-margin lower bound to stay non-flaky.
        assertTrue("heavy won first only $heavyFirst/$trials", heavyFirst > trials * 8 / 10)
    }
}
