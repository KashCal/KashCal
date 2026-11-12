package org.onekash.kashcal.domain.search

import android.icu.lang.UCharacter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holds [SearchFold] to ICU simple case folding, which the phone's SQLite and the platform
 * calendar search use, so a highlight lands wherever the search matched.
 *
 * The two foldings are compared by which code points they treat as equal, not by their images:
 * ICU folds some scripts to capitals (Cherokee), which is fine while the same code points still
 * match each other. Holding `fold(foldCase(cp)) == fold(cp)` and
 * `foldCase(fold(cp)) == foldCase(cp)` for every code point makes the two equal-classes the
 * same: two code points fold together under one exactly when they do under the other.
 *
 * Runs under Robolectric for [android.icu.lang.UCharacter]. Its ICU (Android 14) predates the
 * folding the Android 17 phone does for [phoneFolds], so those pairs are checked by example
 * and left out of the comparison while the test's ICU still folds them apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SearchFoldPropertiesTest {

    private fun icuFold(codePoint: Int): Int = UCharacter.foldCase(codePoint, UCharacter.FOLD_CASE_DEFAULT)

    private fun hex(codePoint: Int): String = "U+%04X".format(codePoint)

    /**
     * The pairs [SearchFold] joins beyond lowercasing, as the Android 17 phone's SQLite LIKE
     * does (checked on the API 37 emulator). Kept here as the test's own list, not read from
     * [SearchFold].
     */
    private val phoneFolds = listOf(0x1FD3 to 0x0390, 0x1FE3 to 0x03B0, 0xFB05 to 0xFB06)

    /** The [phoneFolds] code points this test's ICU still folds apart. */
    private val olderIcuOnly: Set<Int> =
        phoneFolds.filter { (a, b) -> icuFold(a) != icuFold(b) }.flatMap { listOf(it.first, it.second) }.toSet()

    /** Returns whether both the JDK and ICU assign [codePoint], so both foldings know it. */
    private fun definedInBoth(codePoint: Int): Boolean =
        Character.isDefined(codePoint) && UCharacter.isDefined(codePoint)

    @Test
    fun `every code point folds into the same match class as ICU simple folding`() {
        val failures = mutableListOf<String>()
        for (cp in 0..Character.MAX_CODE_POINT) {
            if (cp in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) continue
            if (!definedInBoth(cp) || cp in olderIcuOnly) continue
            val ours = SearchFold.fold(cp)
            val icu = icuFold(cp)
            val oursOfIcu = SearchFold.fold(icu)
            val icuOfOurs = icuFold(ours)
            if (oursOfIcu != ours || icuOfOurs != icu) {
                failures += "${hex(cp)} ${Character.getName(cp)}: fold=${hex(ours)} " +
                    "foldCase=${hex(icu)} fold(foldCase)=${hex(oursOfIcu)} " +
                    "foldCase(fold)=${hex(icuOfOurs)}"
            }
        }
        assertTrue(
            "${failures.size} code points fold apart from ICU simple folding:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `the pairs the phone folds together fold together`() {
        for ((a, b) in phoneFolds) {
            assertEquals("${hex(a)} and ${hex(b)}", SearchFold.fold(a), SearchFold.fold(b))
            assertTrue(SearchFold.contains("x" + String(Character.toChars(a)) + "y", String(Character.toChars(b))))
            assertTrue(SearchFold.contains("x" + String(Character.toChars(b)) + "y", String(Character.toChars(a))))
        }
    }

    @Test
    fun `dotless i stays apart from i and I`() {
        val dotless = 0x0131
        assertEquals(dotless, SearchFold.fold(dotless))
        assertEquals(dotless, icuFold(dotless))
        assertNotEquals(SearchFold.fold('i'.code), SearchFold.fold(dotless))
        assertNotEquals(SearchFold.fold('I'.code), SearchFold.fold(dotless))
        assertFalse(SearchFold.contains("ılık", "ilik"))
        assertFalse(SearchFold.contains("Istanbul", "ıstanbul"))
        assertTrue(SearchFold.contains("ılık", "ılık"))
    }

    @Test
    fun `dotted capital I stays apart from i and I`() {
        val dotted = 0x0130
        assertEquals(dotted, SearchFold.fold(dotted))
        assertEquals(dotted, icuFold(dotted))
        assertNotEquals(SearchFold.fold('i'.code), SearchFold.fold(dotted))
        assertNotEquals(SearchFold.fold('I'.code), SearchFold.fold(dotted))
        assertNotEquals(SearchFold.fold(0x0131), SearchFold.fold(dotted))
        assertFalse(SearchFold.contains("İzmir", "izmir"))
        assertFalse(SearchFold.contains("izmir", "İzmir"))
        assertTrue(SearchFold.contains("İZMİR", "İzmİr"))
    }
}
