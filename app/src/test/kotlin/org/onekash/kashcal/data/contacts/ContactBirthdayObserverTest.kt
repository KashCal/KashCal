package org.onekash.kashcal.data.contacts

import android.os.Handler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import io.mockk.mockk

/**
 * Unit tests for ContactBirthdayObserver.
 *
 * Tests debounce behavior and callback dispatching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactBirthdayObserverTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val handler: Handler = mockk(relaxed = true)

    // ========== Debounce Tests ==========

    @Test
    fun `onChange triggers callback after debounce period`() = testScope.runTest {
        var callCount = 0
        val observer = ContactBirthdayObserver(
            handler = handler,
            scope = this,
            debounceMs = 500L,
            onContactsChanged = { callCount++ }
        )

        observer.onChange(false)
        advanceTimeBy(499)
        assertEquals("Callback should not fire before debounce", 0, callCount)

        advanceTimeBy(2)
        assertEquals("Callback should fire after debounce", 1, callCount)
    }

    @Test
    fun `rapid onChange calls are debounced to single callback`() = testScope.runTest {
        var callCount = 0
        val observer = ContactBirthdayObserver(
            handler = handler,
            scope = this,
            debounceMs = 500L,
            onContactsChanged = { callCount++ }
        )

        // Simulate rapid changes (one per field edit)
        observer.onChange(false)
        advanceTimeBy(100)
        observer.onChange(false)
        advanceTimeBy(100)
        observer.onChange(false)
        advanceTimeBy(100)
        observer.onChange(false)

        // Still within debounce of last call
        advanceTimeBy(400)
        assertEquals("Should not fire during debounce", 0, callCount)

        advanceTimeBy(200)
        assertEquals("Should fire once after final debounce", 1, callCount)
    }

    @Test
    fun `onChange with custom debounce period`() = testScope.runTest {
        var callCount = 0
        val observer = ContactBirthdayObserver(
            handler = handler,
            scope = this,
            debounceMs = 1000L,
            onContactsChanged = { callCount++ }
        )

        observer.onChange(false)
        advanceTimeBy(999)
        assertEquals(0, callCount)

        advanceTimeBy(2)
        assertEquals(1, callCount)
    }

    @Test
    fun `cancelPending prevents pending callback from firing`() = testScope.runTest {
        var callCount = 0
        val observer = ContactBirthdayObserver(
            handler = handler,
            scope = this,
            debounceMs = 500L,
            onContactsChanged = { callCount++ }
        )

        observer.onChange(false)
        advanceTimeBy(200)
        observer.cancelPending()
        advanceTimeBy(500)

        assertEquals("Cancelled callback should not fire", 0, callCount)
    }

    @Test
    fun `onChange triggers new callback after previous completes`() = testScope.runTest {
        var callCount = 0
        val observer = ContactBirthdayObserver(
            handler = handler,
            scope = this,
            debounceMs = 500L,
            onContactsChanged = { callCount++ }
        )

        // First change
        observer.onChange(false)
        advanceTimeBy(600)
        assertEquals(1, callCount)

        // Second change (new debounce cycle)
        observer.onChange(false)
        advanceTimeBy(600)
        assertEquals(2, callCount)
    }

    @Test
    fun `cancelPending is safe to call without pending job`() = testScope.runTest {
        val observer = ContactBirthdayObserver(
            handler = handler,
            scope = this,
            debounceMs = 500L,
            onContactsChanged = { }
        )

        // Should not throw
        observer.cancelPending()
        observer.cancelPending()
    }

    @Test
    fun `onChange with selfChange true still triggers callback`() = testScope.runTest {
        var callCount = 0
        val observer = ContactBirthdayObserver(
            handler = handler,
            scope = this,
            debounceMs = 500L,
            onContactsChanged = { callCount++ }
        )

        observer.onChange(true) // selfChange = true
        advanceTimeBy(600)
        assertEquals("selfChange=true should still trigger callback", 1, callCount)
    }
}
