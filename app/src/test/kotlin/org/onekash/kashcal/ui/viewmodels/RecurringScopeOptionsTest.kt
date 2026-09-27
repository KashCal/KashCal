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
 * Tests the scope-sheet option rules: which options [computeEditScopeOptions],
 * [computeDeleteScopeOptions] and [computeDragScopeOptions] enable and tint, the scopes
 * [dragScopesToGrey] greys for a pending drop, and [toScopeContext] on a pending form save.
 *
 * The rules take a small [ScopeContext], so no test fabricates an Event row.
 */
@RunWith(RobolectricTestRunner::class)
class RecurringScopeOptionsTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    // Master start: Tue Nov 14, 2023, 22:13:20 UTC.
    private val masterStart = 1_700_000_000_000L
    // Occurrence one week later.
    private val laterOccurrence = masterStart + 7L * 86_400_000L

    private fun ctx(
        masterStartTs: Long = masterStart,
        occurrenceTs: Long = laterOccurrence,
        isDetachedException: Boolean = false,
        isAllDay: Boolean = false,
        occurrenceDateChanged: Boolean = false,
    ): ScopeContext = ScopeContext(
        masterStartTs = masterStartTs,
        occurrenceTs = occurrenceTs,
        isDetachedException = isDetachedException,
        isAllDay = isAllDay,
        occurrenceDateChanged = occurrenceDateChanged,
    )

    private fun editOptions(context: ScopeContext) = computeEditScopeOptions(
        context = context,
        originalRrule = "FREQ=WEEKLY;COUNT=10",
        currentRrule = "FREQ=WEEKLY;COUNT=10",
        resources = resources,
    ).associate { it.scope to it.enabled }

    // ========== a changed date on a later occurrence ==========

    @Test
    fun `all events is not offered when a later occurrence's date was changed`() {
        val enabled = editOptions(ctx(occurrenceDateChanged = true))

        assertEquals(false, enabled[EditScope.ALL_EVENTS])
        assertEquals(true, enabled[EditScope.THIS_EVENT])
        assertEquals(true, enabled[EditScope.THIS_AND_FUTURE])
    }

    @Test
    fun `all events is still offered when the first occurrence's date was changed`() {
        val enabled = editOptions(ctx(occurrenceTs = masterStart, occurrenceDateChanged = true))

        assertEquals(true, enabled[EditScope.ALL_EVENTS])
    }

    @Test
    fun `a pending form save carries the date change into the scope context`() {
        val pending = PendingFormSave(
            formState = org.onekash.kashcal.ui.components.EventFormState(),
            occurrenceTs = laterOccurrence,
            originalRrule = "FREQ=WEEKLY",
            masterStartTs = masterStart,
            isDetachedException = true,
            isRecurringDevice = true,
            loadedIsAllDay = true,
            occurrenceDateChanged = true,
        )

        assertEquals(
            ScopeContext(
                masterStartTs = masterStart,
                occurrenceTs = laterOccurrence,
                isDetachedException = true,
                isAllDay = true,
                occurrenceDateChanged = true,
            ),
            pending.toScopeContext(),
        )
    }

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
        // The form is open on a later occurrence. A changed rule can't go to THIS_EVENT (an
        // exception carries no RRULE) or ALL_EVENTS (a new cadence at an off-master DTSTART is
        // ambiguous). THIS_AND_FUTURE is the "change it from here on" path and stays enabled.
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

    @Test
    fun `edit options on first occurrence with changed RRULE keep ALL_EVENTS enabled`() {
        // Correcting the series' end date (RRULE UNTIL) on the first occurrence edits the master
        // at its own DTSTART, so ALL_EVENTS must stay enabled or no option is left to save with
        // (#274). THIS_AND_FUTURE still collapses with ALL_EVENTS here, and THIS_EVENT still
        // can't carry an RRULE.
        val options = computeEditScopeOptions(
            context = ctx(occurrenceTs = masterStart), // first occurrence
            originalRrule = "FREQ=WEEKLY;UNTIL=20271231T000000Z",
            currentRrule = "FREQ=WEEKLY;UNTIL=20261231T000000Z", // end date corrected
            resources = resources,
        )

        assertTrue(
            "ALL_EVENTS must stay enabled so the corrected series can be saved",
            options.first { it.scope == EditScope.ALL_EVENTS }.enabled,
        )
        assertFalse(
            "THIS_EVENT still can't carry an RRULE",
            options.first { it.scope == EditScope.THIS_EVENT }.enabled,
        )
        assertFalse(
            "THIS_AND_FUTURE still collapses with ALL_EVENTS on the first occurrence",
            options.first { it.scope == EditScope.THIS_AND_FUTURE }.enabled,
        )
    }

    @Test
    fun `edit options treat a cosmetically reordered RRULE as unchanged`() {
        // The recurrence picker can re-emit the same rule with parts in a different order. That
        // isn't a user change, so all three options must stay enabled: rruleChanged compares by
        // meaning, not raw string equality.
        val options = computeEditScopeOptions(
            context = ctx(),
            originalRrule = "FREQ=WEEKLY;BYDAY=MO,WE",
            currentRrule = "BYDAY=WE,MO;FREQ=WEEKLY", // reordered, identical meaning
            resources = resources,
        )

        assertTrue(
            "A cosmetic-only RRULE difference must not disable any option",
            options.all { it.enabled },
        )
    }

    // The first-occurrence rule reads ScopeContext.masterStartTs, the master's own start. A
    // user-edited form date passed as the start would make a later occurrence read as the first
    // and disable THIS_AND_FUTURE.
    @Test
    fun `edit options use masterStartTs anchor not user-edited date`() {
        val options = computeEditScopeOptions(
            context = ctx(
                masterStartTs = masterStart,
                occurrenceTs = laterOccurrence,
            ),
            originalRrule = "FREQ=WEEKLY;COUNT=10",
            currentRrule = "FREQ=WEEKLY;COUNT=10",
            resources = resources,
        )

        // THIS_AND_FUTURE stays enabled because the rule keys off masterStartTs.
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

    // Passing occurrenceTs as the master start would make every occurrence read as the first
    // and disable THIS_AND_FUTURE for every device recurring delete; the caller passes the
    // master's own start as masterStartTs.
    @Test
    fun `delete options use masterStartTs not occurrenceTs anchor`() {
        // Mid-series delete: the master started a week before the occurrence being deleted.
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
    fun `drag options grey out the scopes a drop can't use`() {
        val options = computeDragScopeOptions(
            masterStartTs = masterStart,
            targetOccurrenceTs = laterOccurrence,
            isAllDay = false,
            isDevice = false,
            resources = resources,
            blockedScopes = setOf(EditScope.ALL_EVENTS),
        ).associate { it.scope to it.enabled }

        assertEquals(mapOf(EditScope.THIS_EVENT to true, EditScope.THIS_AND_FUTURE to true, EditScope.ALL_EVENTS to false), options)
    }

    @Test
    fun `a drop still being checked greys both series scopes`() {
        val pending = PendingDragReschedule(
            // The sheet's greying doesn't read the event.
            displayEvent = io.mockk.mockk<org.onekash.kashcal.domain.model.DisplayEvent>(),
            targetDate = java.time.LocalDate.of(2024, 3, 12),
            targetStartMinutes = 600,
            blockedScopes = null,
        )
        assertEquals(setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE), dragScopesToGrey(pending))
        assertEquals(emptySet<EditScope>(), dragScopesToGrey(pending.copy(blockedScopes = emptySet())))
    }

    @Test
    fun `drag options for device recurring hide ALL_EVENTS`() {
        // A device drag gets no ALL_EVENTS option; the reason is on [computeDragScopeOptions].
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
