package org.onekash.kashcal.network.dns

/**
 * Orders SRV records for connection attempts per RFC 2782.
 *
 * Records are grouped into ascending-priority buckets; a lower priority value is
 * always tried before a higher one. Within a bucket, records are ordered by a
 * weighted random draw: the chance a record is placed next is proportional to
 * its weight relative to the not-yet-placed records in that bucket. The process
 * repeats until the bucket is emptied, producing a full ordering (not just a
 * single pick) so the caller can fail over down the list.
 *
 * Randomness is injected as [rng] (a supplier of values in `[0, 1)`) rather than
 * read from a global source, so the ordering is deterministic under test and the
 * code carries no `Math.random`.
 */
object SrvSelection {

    fun order(records: List<SrvRecord>, rng: () -> Double): List<SrvRecord> {
        if (records.size <= 1) return records

        val ordered = ArrayList<SrvRecord>(records.size)
        // Group into priority buckets (insertion order preserved within each),
        // then emit buckets in ascending priority: a lower value is always tried
        // before a higher one.
        val buckets = records.groupBy { it.priority }
        for (priority in buckets.keys.sorted()) {
            ordered.addAll(orderBucket(buckets.getValue(priority), rng))
        }
        return ordered
    }

    /**
     * Weighted-random ordering of one equal-priority bucket. RFC 2782's
     * zero-weight handling: a weight of 0 must still be selectable, so each
     * record contributes `weight + 1` to the running sum — this keeps the
     * arithmetic uniform whether some, none, or all weights are zero, while
     * heavier records are still proportionally more likely to come first.
     */
    private fun orderBucket(bucket: List<SrvRecord>, rng: () -> Double): List<SrvRecord> {
        // A single-element bucket needs no draw: the loop below is skipped and the
        // lone record is appended directly — no size==1 special case required.
        val remaining = bucket.toMutableList()
        val result = ArrayList<SrvRecord>(bucket.size)

        while (remaining.size > 1) {
            val total = remaining.sumOf { it.weight + 1 }
            // Half-open [0, total): a maximal draw (rng -> just under 1.0) still
            // lands strictly below total, so it never selects past the last cusum.
            val pick = rng() * total
            var running = 0.0
            var chosen = remaining.size - 1  // fallback guards against fp rounding
            for (i in remaining.indices) {
                running += remaining[i].weight + 1
                if (pick < running) {
                    chosen = i
                    break
                }
            }
            result.add(remaining.removeAt(chosen))
        }
        result.add(remaining[0])  // last one left needs no draw
        return result
    }
}
