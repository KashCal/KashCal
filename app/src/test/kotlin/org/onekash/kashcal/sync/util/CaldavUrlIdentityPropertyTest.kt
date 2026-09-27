package org.onekash.kashcal.sync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests for the resource-identity match the pull leans on for every server event.
 * [org.onekash.kashcal.sync.strategy.PullStrategy] matches a server href to a local row by
 * canonical-URL equality in the full-listing deletion and etag skip, the etag-comparison
 * changed/new/deleted split, and the resolver behind the sync-collection and etag-comparison
 * deletions. #333 and its push-side twin were failures of this match: two RFC-legal encodings
 * of one resource ('@' vs '%40') compared unequal, so a deletion or etag was never applied.
 *
 * [CaldavUrlNormalizerTest] pins hand-picked cases; this generalizes them over randomized
 * RFC 3986-legal encodings, seeded like
 * [org.onekash.kashcal.sync.strategy.PullStrategyPredicatePropertyTest] (override with
 * -Dfuzz.urlmatch.seed / .iterations), so failures reproduce.
 *
 * The contract:
 *  - Reflexive and idempotent: a URL always matches itself; folding twice is a no-op.
 *  - Encoding-insensitive for pchar octets: any two encodings of the same resource
 *    canonicalize equal, which is what makes '@' and '%40' (and the other pchar sub-delims)
 *    match.
 *  - Distinct stems stay distinct, and an encoded slash (%2F) is not decoded, so a
 *    one-segment resource never merges with a two-segment path (that would silently merge
 *    distinct server resources).
 *  - Membership: the `canonicalKey in canonicalServerKeys` predicate the deletion loop uses
 *    holds across encodings; a re-encoded href is present, an absent one is deleted.
 */
class CaldavUrlIdentityPropertyTest {

    private companion object {
        val SEED = System.getProperty("fuzz.urlmatch.seed")?.toLong() ?: 0xCA1DA5L
        val ITERATIONS = System.getProperty("fuzz.urlmatch.iterations")?.toInt() ?: 5_000

        // The pchar-legal reserved octets CaldavUrlNormalizer folds (RFC 3986 §3.3): the
        // sub-delims, ':' and '@'. Each has a literal form and a %XX form that must match.
        val PCHAR_RESERVED = listOf('@', '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', ':')
    }

    private fun pctEncode(c: Char, upperHex: Boolean): String {
        val hex = Integer.toHexString(c.code).uppercase().padStart(2, '0')
        return "%" + if (upperHex) hex else hex.lowercase()
    }

    /**
     * Emits one path segment for [stem], writing each pchar-reserved char either literally or
     * as %XX in random hex case; other chars stay literal. Two calls with the same stem give
     * two RFC-legal spellings of one resource, as a re-encoding server does.
     */
    private fun encodeSegment(stem: String, rnd: Random): String = buildString {
        for (ch in stem) {
            if (ch in PCHAR_RESERVED && rnd.nextBoolean()) {
                append(pctEncode(ch, upperHex = rnd.nextBoolean()))
            } else {
                append(ch)
            }
        }
    }

    /** Returns a filename stem: a hex head, 1 to 3 pchar-reserved chars and a domain tail. */
    private fun randomStem(rnd: Random): String {
        val head = (0 until rnd.nextInt(4, 12)).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")
        val reservedCount = rnd.nextInt(1, 4)
        val reserved = (0 until reservedCount).map { PCHAR_RESERVED[rnd.nextInt(PCHAR_RESERVED.size)] }.joinToString("")
        return "$head$reserved" + "kashcal.onekash.org"
    }

    @Test
    fun `two encodings of the same resource always canonicalize-equal`() {
        val rnd = Random(SEED)
        repeat(ITERATIONS) {
            val stem = randomStem(rnd)
            val base = "https://s.example/cal/"
            val a = base + encodeSegment(stem, rnd) + ".ics"
            val b = base + encodeSegment(stem, rnd) + ".ics"

            assertEquals(
                "encodings of the same logical resource must match: a=$a b=$b",
                CaldavUrlNormalizer.canonicalize(a),
                CaldavUrlNormalizer.canonicalize(b),
            )
        }
    }

    @Test
    fun `canonicalize is reflexive and idempotent for random encodings`() {
        val rnd = Random(SEED xor 0x1111L)
        repeat(ITERATIONS) {
            val url = "https://s.example/cal/" + encodeSegment(randomStem(rnd), rnd) + ".ics"
            val once = CaldavUrlNormalizer.canonicalize(url)
            assertEquals("reflexive", once, CaldavUrlNormalizer.canonicalize(url))
            assertEquals("idempotent", once, CaldavUrlNormalizer.canonicalize(once))
        }
    }

    @Test
    fun `distinct logical stems never collapse to the same canonical form`() {
        val rnd = Random(SEED xor 0x2222L)
        repeat(ITERATIONS) {
            val stemA = randomStem(rnd)
            var stemB = randomStem(rnd)
            // Make the two stems different resources.
            if (stemA == stemB) stemB += "x"
            val a = "https://s.example/cal/" + encodeSegment(stemA, rnd) + ".ics"
            val b = "https://s.example/cal/" + encodeSegment(stemB, rnd) + ".ics"

            assertNotEquals(
                "distinct resources must not merge: a=$a b=$b",
                CaldavUrlNormalizer.canonicalize(a),
                CaldavUrlNormalizer.canonicalize(b),
            )
        }
    }

    @Test
    fun `an encoded slash never merges a segment-split resource into a single-segment one`() {
        val rnd = Random(SEED xor 0x3333L)
        repeat(ITERATIONS) {
            val head = (0 until rnd.nextInt(3, 8)).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")
            val tail = (0 until rnd.nextInt(3, 8)).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")
            // %2F = one segment "head/tail"; literal '/' = two segments "head" then "tail".
            val encodedSlash = "https://s.example/cal/$head%2F$tail.ics"
            val realSlash = "https://s.example/cal/$head/$tail.ics"

            assertNotEquals(
                "%2F must stay encoded so a one-segment resource never merges with a two-segment path",
                CaldavUrlNormalizer.canonicalize(encodedSlash),
                CaldavUrlNormalizer.canonicalize(realSlash),
            )
            // %2F itself survives canonicalization.
            assertTrue(
                "encoded slash must survive canonicalization",
                CaldavUrlNormalizer.canonicalize(encodedSlash)!!.contains("%2F", ignoreCase = true),
            )
        }
    }

    @Test
    fun `deletion-loop membership predicate holds across encodings`() {
        // Mirrors PullStrategy's `canonicalize(localUrl) !in canonicalServerKeys` check: a
        // re-encoded server href counts as present, an absent local URL as deleted.
        val rnd = Random(SEED xor 0x4444L)
        repeat(ITERATIONS) {
            val presentStem = randomStem(rnd)
            val absentStem = randomStem(rnd).let { if (it == presentStem) it + "z" else it }
            val base = "https://s.example/cal/"

            // The server reports the present resource in one encoding...
            val serverHref = base + encodeSegment(presentStem, rnd) + ".ics"
            val canonicalServerKeys = setOf(CaldavUrlNormalizer.canonicalize(serverHref)!!)

            // ...the local row stored it in a (possibly different) encoding.
            val localPresent = base + encodeSegment(presentStem, rnd) + ".ics"
            val localAbsent = base + encodeSegment(absentStem, rnd) + ".ics"

            assertTrue(
                "present resource must be found regardless of encoding: server=$serverHref local=$localPresent",
                (CaldavUrlNormalizer.canonicalize(localPresent) ?: localPresent) in canonicalServerKeys,
            )
            assertTrue(
                "absent resource must be classified deleted: local=$localAbsent",
                (CaldavUrlNormalizer.canonicalize(localAbsent) ?: localAbsent) !in canonicalServerKeys,
            )
        }
    }
}
