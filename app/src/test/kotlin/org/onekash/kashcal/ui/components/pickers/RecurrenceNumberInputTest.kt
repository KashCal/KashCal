package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the typing rules shared by the recurrence picker's number fields: [acceptsNumberEdit],
 * [positiveNumberOrNull], [stripLeadingZeros] and [numberText].
 */
class RecurrenceNumberInputTest {

    // Arabic-Indic digits: U+0660 is zero, U+0663 three, U+0664 four, U+0665 five, U+0667 seven.
    private val arabic354 = "٣٥٤"
    private val arabic007 = "٠٠٧"
    private val arabic7 = "٧"

    // Bengali digits: U+09E9 three, U+09EB five, U+09EA four.
    private val bengali354 = "৩৫৪"

    @Test
    fun `cap and typed digit limit`() {
        assertEquals(999, RECURRENCE_INTERVAL_MAX)
        assertEquals(3, RECURRENCE_MAX_TYPED_DIGITS)
    }

    // ==================== acceptsNumberEdit ====================

    @Test
    fun `typing up to three digits is accepted`() {
        assertTrue(acceptsNumberEdit("", "354"))
        assertTrue(acceptsNumberEdit("35", "354"))
        assertTrue(acceptsNumberEdit("1200", "7"))
    }

    @Test
    fun `a fourth digit is rejected`() {
        assertFalse(acceptsNumberEdit("354", "3540"))
    }

    @Test
    fun `a synced four-digit value can only shrink`() {
        assertFalse(acceptsNumberEdit("1200", "12000"))
        assertFalse(acceptsNumberEdit("1200", "1201"))
        assertTrue(acceptsNumberEdit("1200", "120"))
        assertTrue(acceptsNumberEdit("1200", ""))
    }

    @Test
    fun `a selection-only change on a four-digit value is accepted`() {
        // Moving the cursor or selection runs the same filter with the text unchanged.
        assertTrue(acceptsNumberEdit("1200", "1200"))
    }

    @Test
    fun `non-digit characters are rejected`() {
        assertFalse(acceptsNumberEdit("12", "1,2"))
        assertFalse(acceptsNumberEdit("12", "1.2"))
        assertFalse(acceptsNumberEdit("12", "-12"))
        assertFalse(acceptsNumberEdit("12", "1 2"))
        assertFalse(acceptsNumberEdit("12", "+12"))
    }

    @Test
    fun `native digits are accepted`() {
        assertTrue(acceptsNumberEdit("", arabic354))
        assertTrue(acceptsNumberEdit("", bengali354))
    }

    // ==================== positiveNumberOrNull ====================

    @Test
    fun `empty and zero are not positive numbers`() {
        assertNull(positiveNumberOrNull(""))
        assertNull(positiveNumberOrNull("0"))
        assertNull(positiveNumberOrNull("000"))
    }

    @Test
    fun `digits parse to their value`() {
        assertEquals(7, positiveNumberOrNull("007"))
        assertEquals(354, positiveNumberOrNull("354"))
        assertEquals(1200, positiveNumberOrNull("1200"))
    }

    @Test
    fun `native digits parse to their value`() {
        assertEquals(354, positiveNumberOrNull(arabic354))
        assertEquals(354, positiveNumberOrNull(bengali354))
    }

    @Test
    fun `signs and overflow give null`() {
        assertNull(positiveNumberOrNull("+5"))
        assertNull(positiveNumberOrNull("-5"))
        assertNull(positiveNumberOrNull("12345678901234567890"))
    }

    // ==================== stripLeadingZeros ====================

    @Test
    fun `leading zeros are removed while a digit remains`() {
        assertEquals("7", stripLeadingZeros("007"))
        assertEquals("0", stripLeadingZeros("0"))
        assertEquals("0", stripLeadingZeros("000"))
        assertEquals("", stripLeadingZeros(""))
        assertEquals("120", stripLeadingZeros("120"))
    }

    @Test
    fun `leading zeros are removed in the typed digit script`() {
        assertEquals(arabic7, stripLeadingZeros(arabic007))
    }

    // ==================== numberText ====================

    @Test
    fun `numberText writes the value`() {
        assertEquals("354", numberText(354))
    }
}
