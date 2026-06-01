package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Test

class StripeLabelsTest {

    @Test
    fun `12-hour locale produces 12a 6a 12p 6p 12a labels`() {
        val labels = StripeLabels.labelsFor(is24Hour = false)
        assertEquals(listOf("12a", "6a", "12p", "6p", "12a"), labels)
    }

    @Test
    fun `24-hour locale produces 00 06 12 18 24 labels`() {
        val labels = StripeLabels.labelsFor(is24Hour = true)
        assertEquals(listOf("00", "06", "12", "18", "24"), labels)
    }
}
