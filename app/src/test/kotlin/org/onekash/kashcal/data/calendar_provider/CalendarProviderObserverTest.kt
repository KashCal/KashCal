package org.onekash.kashcal.data.calendar_provider

import android.os.Handler
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for CalendarProviderObserver.
 *
 * Tests debounce behavior (3-second default) and callback dispatching.
 * Follows ContactBirthdayObserverTest pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarProviderObserverTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val handler: Handler = mockk(relaxed = true)

    // ========== Debounce Tests ==========

    @Test
    fun `onChange triggers callback after 3 second debounce`() = testScope.runTest {
        var callCount = 0
        val observer = CalendarProviderObserver(
            handler = handler,
            scope = this,
            debounceMs = 3000L,
            onCalendarChanged = { callCount++ }
        )

        observer.onChange(false)
        advanceTimeBy(2999)
        assertEquals("Callback should not fire before debounce", 0, callCount)

        advanceTimeBy(2)
        assertEquals("Callback should fire after debounce", 1, callCount)
    }

    @Test
    fun `rapid onChange calls are debounced to single callback`() = testScope.runTest {
        var callCount = 0
        val observer = CalendarProviderObserver(
            handler = handler,
            scope = this,
            debounceMs = 3000L,
            onCalendarChanged = { callCount++ }
        )

        // Simulate rapid changes from sync adapter
        observer.onChange(false)
        advanceTimeBy(500)
        observer.onChange(false)
        advanceTimeBy(500)
        observer.onChange(false)
        advanceTimeBy(500)
        observer.onChange(false)

        // Still within debounce of last call
        advanceTimeBy(2500)
        assertEquals("Should not fire during debounce", 0, callCount)

        advanceTimeBy(600)
        assertEquals("Should fire once after final debounce", 1, callCount)
    }

    @Test
    fun `cancelPending prevents pending callback from firing`() = testScope.runTest {
        var callCount = 0
        val observer = CalendarProviderObserver(
            handler = handler,
            scope = this,
            debounceMs = 3000L,
            onCalendarChanged = { callCount++ }
        )

        observer.onChange(false)
        advanceTimeBy(1000)
        observer.cancelPending()
        advanceTimeBy(3000)

        assertEquals("Cancelled callback should not fire", 0, callCount)
    }

    @Test
    fun `onChange triggers new callback after previous completes`() = testScope.runTest {
        var callCount = 0
        val observer = CalendarProviderObserver(
            handler = handler,
            scope = this,
            debounceMs = 3000L,
            onCalendarChanged = { callCount++ }
        )

        // First change
        observer.onChange(false)
        advanceTimeBy(3100)
        assertEquals(1, callCount)

        // Second change (new debounce cycle)
        observer.onChange(false)
        advanceTimeBy(3100)
        assertEquals(2, callCount)
    }

    @Test
    fun `selfChange true still triggers callback`() = testScope.runTest {
        // Unlike ContactBirthdayObserver, we DO want self-change callbacks
        // because KashCal writes to CalendarProvider and UI needs refresh
        var callCount = 0
        val observer = CalendarProviderObserver(
            handler = handler,
            scope = this,
            debounceMs = 3000L,
            onCalendarChanged = { callCount++ }
        )

        observer.onChange(true)
        advanceTimeBy(3100)
        assertEquals("selfChange=true should still trigger callback", 1, callCount)
    }

    @Test
    fun `cancelPending is safe to call without pending job`() = testScope.runTest {
        val observer = CalendarProviderObserver(
            handler = handler,
            scope = this,
            debounceMs = 3000L,
            onCalendarChanged = { }
        )

        // Should not throw
        observer.cancelPending()
        observer.cancelPending()
    }

    @Test
    fun `custom debounce period works`() = testScope.runTest {
        var callCount = 0
        val observer = CalendarProviderObserver(
            handler = handler,
            scope = this,
            debounceMs = 1000L,
            onCalendarChanged = { callCount++ }
        )

        observer.onChange(false)
        advanceTimeBy(999)
        assertEquals(0, callCount)

        advanceTimeBy(2)
        assertEquals(1, callCount)
    }
}
