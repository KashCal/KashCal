package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.R

/**
 * Verifies the precedence of the event-state screen-reader label: cancelled
 * outranks declined outranks past, and a normal event has no label. Guards the
 * ordering so a future reshuffle of the `when` can't silently announce the
 * wrong state.
 */
class EventStateDescriptionTest {

    @Test
    fun `normal event has no state label`() {
        assertNull(eventStateRes(isPast = false, isDeclined = false, isCancelled = false))
    }

    @Test
    fun `past event announces past`() {
        assertEquals(
            R.string.cd_event_state_past,
            eventStateRes(isPast = true, isDeclined = false, isCancelled = false),
        )
    }

    @Test
    fun `declined event announces declined`() {
        assertEquals(
            R.string.cd_event_state_declined,
            eventStateRes(isPast = false, isDeclined = true, isCancelled = false),
        )
    }

    @Test
    fun `cancelled event announces cancelled`() {
        assertEquals(
            R.string.cd_event_state_cancelled,
            eventStateRes(isPast = false, isDeclined = false, isCancelled = true),
        )
    }

    @Test
    fun `cancelled outranks declined`() {
        assertEquals(
            R.string.cd_event_state_cancelled,
            eventStateRes(isPast = false, isDeclined = true, isCancelled = true),
        )
    }

    @Test
    fun `declined outranks past`() {
        assertEquals(
            R.string.cd_event_state_declined,
            eventStateRes(isPast = true, isDeclined = true, isCancelled = false),
        )
    }

    @Test
    fun `cancelled outranks both declined and past`() {
        assertEquals(
            R.string.cd_event_state_cancelled,
            eventStateRes(isPast = true, isDeclined = true, isCancelled = true),
        )
    }
}
