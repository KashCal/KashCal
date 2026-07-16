package org.onekash.kashcal.domain.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [CategoryNameValidator] — the single source of truth for
 * tag-name rules, reused by the form chip input, the "+ New" field, the inline
 * "#" autocomplete, and the Quick Add "#tag" extraction.
 */
class CategoryNameValidatorTest {

    // ==================== validate() outcomes ====================

    @Test
    fun `plain name is valid and preserved`() {
        assertEquals(
            CategoryName.Valid("Work"),
            CategoryNameValidator.validate("Work"),
        )
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals(
            CategoryName.Valid("Work"),
            CategoryNameValidator.validate("  Work  "),
        )
    }

    @Test
    fun `empty string is rejected`() {
        assertEquals(
            CategoryName.Invalid(CategoryNameError.EMPTY),
            CategoryNameValidator.validate(""),
        )
    }

    @Test
    fun `whitespace-only string is rejected as empty`() {
        assertEquals(
            CategoryName.Invalid(CategoryNameError.EMPTY),
            CategoryNameValidator.validate("   "),
        )
    }

    @Test
    fun `a lone hash is rejected as empty after stripping`() {
        assertEquals(
            CategoryName.Invalid(CategoryNameError.EMPTY),
            CategoryNameValidator.validate("#"),
        )
    }

    @Test
    fun `single leading hash is stripped`() {
        assertEquals(
            CategoryName.Valid("work"),
            CategoryNameValidator.validate("#work"),
        )
    }

    @Test
    fun `only one leading hash is stripped`() {
        // "##work" -> strip one '#' -> "#work"; a remaining '#' is not a comma
        // and not a leading-strip target, so it stays as an ordinary character.
        assertEquals(
            CategoryName.Valid("#work"),
            CategoryNameValidator.validate("##work"),
        )
    }

    @Test
    fun `comma is rejected`() {
        assertEquals(
            CategoryName.Invalid(CategoryNameError.COMMA),
            CategoryNameValidator.validate("foo,bar"),
        )
    }

    @Test
    fun `name at 64 chars is valid`() {
        val name = "a".repeat(64)
        assertEquals(
            CategoryName.Valid(name),
            CategoryNameValidator.validate(name),
        )
    }

    @Test
    fun `name over 64 chars is rejected`() {
        val name = "a".repeat(65)
        assertEquals(
            CategoryName.Invalid(CategoryNameError.TOO_LONG),
            CategoryNameValidator.validate(name),
        )
    }

    @Test
    fun `length is measured after trim and hash strip`() {
        // 64 'a's wrapped in a leading '#' and surrounding spaces: after
        // trim + strip the effective length is exactly 64 -> valid.
        val name = "a".repeat(64)
        assertEquals(
            CategoryName.Valid(name),
            CategoryNameValidator.validate("  #$name  "),
        )
    }

    // ==================== case-insensitive dedup against existing ====================

    @Test
    fun `differently-cased duplicate resolves to the existing first-seen casing`() {
        // User already has "work"; typing "Work" must resolve to the existing tag.
        assertEquals(
            CategoryName.Valid("work"),
            CategoryNameValidator.validate("Work", existing = setOf("work")),
        )
    }

    @Test
    fun `exact existing match returns the existing value`() {
        assertEquals(
            CategoryName.Valid("Work"),
            CategoryNameValidator.validate("Work", existing = setOf("Work")),
        )
    }

    @Test
    fun `name not in existing keeps its own casing`() {
        assertEquals(
            CategoryName.Valid("Personal"),
            CategoryNameValidator.validate("Personal", existing = setOf("work")),
        )
    }

    // ==================== normalize() convenience for the parser path ====================

    @Test
    fun `normalize returns the cleaned name for a valid input`() {
        assertEquals("work", CategoryNameValidator.normalize("#Work")?.lowercase())
    }

    @Test
    fun `normalize returns null for an invalid input`() {
        assertNull(CategoryNameValidator.normalize(","))
        assertNull(CategoryNameValidator.normalize("   "))
    }

    @Test
    fun `normalize preserves first-seen casing`() {
        val result = CategoryNameValidator.normalize("Work")
        assertTrue(result == "Work")
    }
}
