package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the date and time sheet's chip fit rule, [sheetChipsThatFit]. */
class DateTimeSheetFitTest {

    // ---- whole chips only ----

    @Test
    fun `all chips show when they fit`() {
        assertEquals(3, sheetChipsThatFit(available = 328f, chipWidth = 80f, gap = 8f, total = 3))
    }

    @Test
    fun `three chips that fit exactly all show`() {
        // 3 x 80 + 2 x 8 = 256
        assertEquals(3, sheetChipsThatFit(available = 256f, chipWidth = 80f, gap = 8f, total = 3))
    }

    @Test
    fun `one dp short drops the last chip`() {
        assertEquals(2, sheetChipsThatFit(available = 255f, chipWidth = 80f, gap = 8f, total = 3))
    }

    @Test
    fun `at least one chip shows even when none fits`() {
        assertEquals(1, sheetChipsThatFit(available = 50f, chipWidth = 80f, gap = 8f, total = 3))
    }

    @Test
    fun `never more chips than there are`() {
        assertEquals(3, sheetChipsThatFit(available = 1000f, chipWidth = 80f, gap = 8f, total = 3))
    }
}
