package org.onekash.kashcal.sync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests for the resource-identity invariant the pull and push paths lean on
 * for EVERY server event. [PullStrategy] matches a server-echoed href to a local row
 * by canonical-URL equality at four sites (etag skip, deletion reconciliation on both
 * the incremental and sync-collection loops, and the UID-or-URL fallback). #333 and its
 * push-side twin were both failures of this match: two RFC-legal encodings of one
 * logical resource ('@' vs '%40') compared unequal, so a deletion or etag was never
 * applied.
 *
 * [CaldavUrlNormalizerTest] pins 14 hand-picked cases; this generalizes them into the
 * matching CONTRACT over randomized, RFC-3986-legal encodings, in the seeded-generator
 * idiom already used by [org.onekash.kashcal.sync.strategy.PullStrategyPredicatePropertyTest]
 * (override with -Dfuzz.urlmatch.seed / .iterations). Failures reproduce deterministically.
 *
 * The contract (each is a real matching guarantee, not an implementation detail):
 *  - REFLEXIVE / IDEMPOTENT: a URL always matches itself; folding twice is a no-op.
 *  - ENCODING-INSENSITIVE for pchar octets: any two encodings of the SAME logical
 *    resource canonicalize-equal — this is what makes '@'<->'%40' (and the other
 *    pchar sub-delims) match. Grounds the #333 fix as a property, not an example.
 *  - STRUCTURE-PRESERVING: an encoded slash (%2F) is NOT decoded, so two resources
 *    that differ only by a real segment boundary vs an encoded one never collapse
 *    into one row (would silently merge distinct server resources).
 *  - MEMBERSHIP: the exact `canonicalKey in canonicalServerKeys` predicate the
 *    deletion loop uses holds across encodings — a re-encoded href is "present", a
 *    genuinely-absent one is "deleted".
 *
 * Pure function under test; no servers, no Room, no mocks. No production change implied.
 */
class CaldavUrlIdentityPropertyTest {

    private companion object {
        val SEED = System.getProperty("fuzz.urlmatch.seed")?.toLong() ?: 0xCA1DA5L
        val ITERATIONS = System.getProperty("fuzz.urlmatch.iterations")?.toInt() ?: 5_000

        // The pchar-legal reserved octets CaldavUrlNormalizer folds (RFC 3986 §3.3):
        // sub-delims + ':' + '@'. Each has a literal form and a %XX form that MUST match.
        val PCHAR_RESERVED = listOf('@', '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', ':')
    }

    private fun pctEncode(c: Char, upperHex: Boolean): String {
        val hex = Integer.toHexString(c.code).uppercase().padStart(2, '0')
        return "%" + if (upperHex) hex else hex.lowercase()
    }

    /**
     * Emit one path segment for a logical stem, encoding each pchar-reserved char
     * either literally or as %XX (random hex case). Unreserved chars stay literal.
     * Two independent calls with the same stem produce two RFC-legal spellings of the
     * SAME logical resource — the exact situation a re-encoding server creates.
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

    /** A logical filename stem: a uuid-ish head + a random pick of pchar-reserved chars + a domain tail. */
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
            // Ensure the two stems are genuinely different logical resources.
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
            // And %2F specifically is preserved verbatim (not decoded to '/').
            assertTrue(
                "encoded slash must survive canonicalization",
                CaldavUrlNormalizer.canonicalize(encodedSlash)!!.contains("%2F", ignoreCase = true),
            )
        }
    }

    @Test
    fun `deletion-loop membership predicate holds across encodings`() {
        // Mirrors PullStrategy's `canonicalize(localUrl) !in canonicalServerKeys` check:
        // a re-encoded server href must count as PRESENT; a genuinely-absent local URL
        // must count as DELETED.
        val rnd = Random(SEED xor 0x4444L)
        repeat(ITERATIONS) {
            val presentStem = randomStem(rnd)
            val absentStem = randomStem(rnd).let { if (it == presentStem) it + "z" else it }
            val base = "https://s.example/cal/"

            // Server reports the present resource in one encoding...
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
