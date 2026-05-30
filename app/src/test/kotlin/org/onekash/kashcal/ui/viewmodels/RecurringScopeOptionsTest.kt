package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.ScopeTint
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the option-set rules. The helpers are the testable
 * nucleus of the save-time scope sheet: they decide which options are
 * enabled, what the sub-copy says, and which option carries the
 * destructive tint.
 *
 * Helpers take a small [ScopeContext] rather than an Event row;
 * callers don't need to fabricate Events to drive the rules.
 */
@RunWith(RobolectricTestRunner::class)
class RecurringScopeOptionsTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    // Master start (Mon midnight UTC, Nov 14, 2023).
    private val masterStart = 1_700_000_000_000L
    // Occurrence one week later.
    private val laterOccurrence = masterStart + 7L * 86_400_000L

    private fun ctx(
        masterStartTs: Long = masterStart,
        occurrenceTs: Long = laterOccurrence,
        isDetachedException: Boolean = false,
        isAllDay: Boolean = false,
    ): ScopeContext = ScopeContext(
        masterStartTs = masterStartTs,
        occurrenceTs = occurrenceTs,
        isDetachedException = isDetachedException,
        isAllDay = isAllDay,
    )

    // ========== EDIT options ==========

    @Test
    fun `edit options show all three enabled in the typical case`() {
        val options = computeEditScopeOptions(
            context = ctx(),
            originalRrule = "FREQ=WEEKLY;COUNT=10",
            currentRrule = "FREQ=WEEKLY;COUNT=10",
            resources = resources,
        )

        assertEquals(3, options.size)
        assertEquals(EditScope.THIS_EVENT, options[0].scope)
        assertEquals(EditScope.THIS_AND_FUTURE, options[1].scope)
        assertEquals(EditScope.ALL_EVENTS, options[2].scope)
        assertTrue(options.all { it.enabled })
    }

    @Test
    fun `edit options tint ALL_EVENTS as Warn`() {
        val options = computeEditScopeOptions(
            context = ctx(),
            originalRrule = "FREQ=WEEKLY;COUNT=10",
            currentRrule = "FREQ=WEEKLY;COUNT=10",
            resources = resources,
        )

        val all = options.first { it.scope == EditScope.ALL_EVENTS }
        assertEquals(ScopeTint.Warn, all.tint)
    }

    @Test
    fun `edit options on first occurrence disable THIS_AND_FUTURE`() {
        val options = computeEditScopeOptions(
            context = ctx(occurrenceTs = masterStart), // first occurrence
            originalRrule = "FREQ=WEEKLY;COUNT=10",
            currentRrule = "FREQ=WEEKLY;COUNT=10",
            resources = resources,
        )

        val future = options.first { it.scope == EditScope.THIS_AND_FUTURE }
        assertFalse("This-and-future collapses with All on first occurrence", future.enabled)
        assertTrue(options.first { it.scope == EditScope.THIS_EVENT }.enabled)
        assertTrue(options.first { it.scope == EditScope.ALL_EVENTS }.enabled)
    }

    @Test
    fun `edit options on detached exception only enable THIS_EVENT`() {
        val options = computeEditScopeOptions(
            context = ctx(isDetachedException = true, occurrenceTs = masterStart),
            originalRrule = null,
            currentRrule = null,
            resources = resources,
        )

        assertTrue(options.first { it.scope == EditScope.THIS_EVENT }.enabled)
        assertFalse(options.first { it.scope == EditScope.THIS_AND_FUTURE }.enabled)
        assertFalse(options.first { it.scope == EditScope.ALL_EVENTS }.enabled)
    }

    @Test
    fun `edit options when caller changed RRULE disable THIS_EVENT and ALL_EVENTS`() {
        // Per-occurrence edits open the form on a tapped instance. If
        // the user changed the recurrence rule, neither THIS_EVENT
        // (RFC 5545 §3.8.5: exceptions strip RRULE) nor ALL_EVENTS
        // (applying a new cadence at an off-master DTSTART is
        // ambiguous) can apply. THIS_AND_FUTURE is the legitimate
        // "change cadence going forward" path and remains enabled.
        val options = computeEditScopeOptions(
            context = ctx(),
            originalRrule = "FREQ=WEEKLY;COUNT=10",
            currentRrule = "FREQ=DAILY;COUNT=10", // user changed it
            resources = resources,
        )

        val thisEvent = options.first { it.scope == EditScope.THIS_EVENT }
        val allEvents = options.first { it.scope == EditScope.ALL_EVENTS }
        assertFalse("RRULE changes can't apply to a single occurrence", thisEvent.enabled)
        assertTrue(options.first { it.scope == EditScope.THIS_AND_FUTURE }.enabled)
        assertFalse("RRULE changes can't apply to ALL_EVENTS at an off-master DTSTART", allEvents.enabled)
    }

    // Regression: finding #7 — the previous synth-Event approach used
    // formState.dateMillis as event.startTs, so editing the date later
    // spuriously made occurrenceTs <= startTs (false-positive
    // first-occurrence). With ScopeContext.masterStartTs anchored to
    // the master's true start, the rule fires correctly even when the
    // user has shifted the form date.
    @Test
    fun `edit options use masterStartTs anchor not user-edited date`() {
        // User shifted the start to a date AFTER the occurrence; under
        // the old synth-Event API this would have flipped
        // isFirstOccurrence to true.
        val options = computeEditScopeOptions(
            context = ctx(
                masterStartTs = masterStart,
                occurrenceTs = laterOccurrence,
            ),
            originalRrule = "FREQ=WEEKLY;COUNT=10",
            currentRrule = "FREQ=WEEKLY;COUNT=10",
            resources = resources,
        )

        // THIS_AND_FUTURE remains enabled because the rule keys off
        // masterStartTs, not a derived value.
        assertTrue(
            "THIS_AND_FUTURE must remain enabled when masterStartTs is anchored correctly",
            options.first { it.scope == EditScope.THIS_AND_FUTURE }.enabled
        )
    }

    // ========== DELETE options ==========

    @Test
    fun `delete options tint ALL_EVENTS as Destructive`() {
        val options = computeDeleteScopeOptions(
            context = ctx(),
            resources = resources,
        )

        val all = options.first { it.scope == EditScope.ALL_EVENTS }
        assertEquals(ScopeTint.Destructive, all.tint)
    }

    @Test
    fun `delete options on first occurrence disable THIS_AND_FUTURE`() {
        val options = computeDeleteScopeOptions(
            context = ctx(occurrenceTs = masterStart),
            resources = resources,
        )

        val future = options.first { it.scope == EditScope.THIS_AND_FUTURE }
        assertFalse(future.enabled)
        assertTrue(options.first { it.scope == EditScope.THIS_EVENT }.enabled)
        assertTrue(options.first { it.scope == EditScope.ALL_EVENTS }.enabled)
    }

    @Test
    fun `delete options on detached exception only enable THIS_EVENT`() {
        val options = computeDeleteScopeOptions(
            context = ctx(isDetachedException = true, occurrenceTs = masterStart),
            resources = resources,
        )

        assertTrue(options.first { it.scope == EditScope.THIS_EVENT }.enabled)
        assertFalse(options.first { it.scope == EditScope.THIS_AND_FUTURE }.enabled)
        assertFalse(options.first { it.scope == EditScope.ALL_EVENTS }.enabled)
    }

    // Regression: finding #8 — the previous synth-Event for device
    // delete set startTs = occurrenceTs, so isFirstOccurrence was
    // always true and THIS_AND_FUTURE was permanently disabled for
    // every device recurring delete. ScopeContext.masterStartTs lets
    // the consumer thread the actual master startTs through.
    @Test
    fun `delete options use masterStartTs not occurrenceTs anchor`() {
        // Mid-series delete: master started a month ago, we're
        // deleting from a recent occurrence.
        val options = computeDeleteScopeOptions(
            context = ctx(
                masterStartTs = masterStart,
                occurrenceTs = laterOccurrence,
            ),
            resources = resources,
        )

        assertTrue(
            "THIS_AND_FUTURE must be enabled for mid-series device deletes",
            options.first { it.scope == EditScope.THIS_AND_FUTURE }.enabled
        )
    }

    // ========== DRAG options ==========

    @Test
    fun `drag options for Room recurring offer all three scopes`() {
        val options = computeDragScopeOptions(
            masterStartTs = masterStart,
            targetOccurrenceTs = laterOccurrence,
            isAllDay = false,
            isDevice = false,
            resources = resources,
        )
        assertEquals(3, options.size)
        assertEquals(EditScope.THIS_EVENT, options[0].scope)
        assertEquals(EditScope.THIS_AND_FUTURE, options[1].scope)
        assertEquals(EditScope.ALL_EVENTS, options[2].scope)
        assertTrue(options.all { it.enabled })
    }

    @Test
    fun `drag options for device recurring hide ALL_EVENTS`() {
        // Device drag with ALL_EVENTS would shift the master's DTSTART
        // and move every past occurrence. The Room path can split via
        // materialized occurrences; the CalendarProvider can't.
        // Hide ALL_EVENTS entirely for device drags.
        val options = computeDragScopeOptions(
            masterStartTs = masterStart,
            targetOccurrenceTs = laterOccurrence,
            isAllDay = false,
            isDevice = true,
            resources = resources,
        )
        assertEquals(2, options.size)
        assertEquals(EditScope.THIS_EVENT, options[0].scope)
        assertEquals(EditScope.THIS_AND_FUTURE, options[1].scope)
        assertTrue(options.none { it.scope == EditScope.ALL_EVENTS })
    }

    @Test
    fun `drag options on first occurrence still disable THIS_AND_FUTURE`() {
        val options = computeDragScopeOptions(
            masterStartTs = masterStart,
            targetOccurrenceTs = masterStart,
            isAllDay = false,
            isDevice = false,
            resources = resources,
        )
        val future = options.first { it.scope == EditScope.THIS_AND_FUTURE }
        assertFalse(future.enabled)
    }

    @Test
    fun `drag options on first occurrence device first occurrence both options behave`() {
        val options = computeDragScopeOptions(
            masterStartTs = masterStart,
            targetOccurrenceTs = masterStart,
            isAllDay = false,
            isDevice = true,
            resources = resources,
        )
        // Device + first occurrence: only THIS_EVENT remains usable.
        assertEquals(2, options.size)
        assertTrue(options.first { it.scope == EditScope.THIS_EVENT }.enabled)
        assertFalse(options.first { it.scope == EditScope.THIS_AND_FUTURE }.enabled)
    }
}
