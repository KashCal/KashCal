package org.onekash.kashcal.network.dns

/**
 * Orders SRV records for connection attempts per RFC 2782.
 *
 * Records are grouped by priority, and a lower value is always tried before a higher one.
 * Within a priority, a weighted random draw repeatedly picks the next record, with a chance
 * proportional to its weight plus one ([orderBucket]) among the records not yet placed. The
 * result is a full ordering, so the caller can fail over down the list.
 *
 * Randomness comes from `rng`, a supplier of values in `[0, 1)`, so the ordering is
 * deterministic under test.
 */
object SrvSelection {

    fun order(records: List<SrvRecord>, rng: () -> Double): List<SrvRecord> {
        if (records.size <= 1) return records

        val ordered = ArrayList<SrvRecord>(records.size)
        // Buckets keep insertion order and are emitted in ascending priority.
        val buckets = records.groupBy { it.priority }
        for (priority in buckets.keys.sorted()) {
            ordered.addAll(orderBucket(buckets.getValue(priority), rng))
        }
        return ordered
    }

    /**
     * Orders one equal-priority bucket by weighted random draws. RFC 2782 requires a weight
     * of 0 to stay selectable, so each record counts `weight + 1`: the arithmetic stays the
     * same whether some, none or all weights are zero, and heavier records still tend to come
     * first.
     */
    private fun orderBucket(bucket: List<SrvRecord>, rng: () -> Double): List<SrvRecord> {
        // A single-element bucket skips the loop and appends its record without a draw.
        val remaining = bucket.toMutableList()
        val result = ArrayList<SrvRecord>(bucket.size)

        while (remaining.size > 1) {
            val total = remaining.sumOf { it.weight + 1 }
            // A draw in [0, total) lands below the last running sum, so it never selects
            // past the last record.
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
