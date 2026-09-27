package org.onekash.kashcal.ui.viewmodels

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.ui.graphics.vector.ImageVector
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.ScopeOption
import org.onekash.kashcal.ui.components.ScopeTint
import org.onekash.kashcal.util.RruleUtils

/**
 * Computes the [ScopeOption]s for each recurring-event scope sheet. The rules live outside
 * `RecurringScopeSheet` so they are unit-testable and the sheet stays a stateless renderer.
 *
 * Each option has an icon so its scope reads at a glance. The sheet shows only the title, the
 * cards and Cancel: no description, and no echo of the date the user tapped. A greyed card says
 * the option doesn't apply.
 */

/**
 * Holds what the option rules need to decide which scopes are enabled, taken from the pending
 * save or delete.
 *
 * @param masterStartTs the master event's startTs, for the first-occurrence rule. Never pass
 *   occurrenceTs or a user-editable form value here.
 * @param occurrenceTs the occurrence the user opened.
 * @param isDetachedException true when the event is an exception row of a series.
 * @param isAllDay not read by the option rules; the sheet shows no date.
 * @param occurrenceDateChanged true when the user moved the opened occurrence to another day
 *   before saving. "All events" is then withheld for a later occurrence: applying the new date
 *   to the series would cut or move it.
 */
data class ScopeContext(
    val masterStartTs: Long,
    val occurrenceTs: Long,
    val isDetachedException: Boolean,
    val isAllDay: Boolean,
    val occurrenceDateChanged: Boolean = false,
)

/** The scope context for a form save awaiting its scope. */
fun PendingFormSave.toScopeContext(): ScopeContext = ScopeContext(
    masterStartTs = masterStartTs,
    occurrenceTs = occurrenceTs,
    isDetachedException = isDetachedException,
    isAllDay = loadedIsAllDay,
    occurrenceDateChanged = occurrenceDateChanged,
)

private val ICON_THIS: ImageVector = Icons.Default.CalendarToday
private val ICON_FUTURE: ImageVector = Icons.AutoMirrored.Filled.ArrowForward
private val ICON_ALL: ImageVector = Icons.Default.Repeat
private val ICON_DELETE_ALL: ImageVector = Icons.Default.DeleteOutline

/**
 * Returns the options for the form-save scope sheet on a recurring event opened at one
 * occurrence.
 *
 * Edge cases:
 * - First occurrence: THIS_AND_FUTURE is the same as ALL_EVENTS, so it is disabled.
 * - Detached exception: only THIS_EVENT applies; the others are disabled.
 * - RRULE changed on a later occurrence: THIS_EVENT and ALL_EVENTS are disabled. THIS_EVENT
 *   can't apply because an exception carries no RRULE. ALL_EVENTS can't, because rewriting the
 *   master's rule at a later DTSTART is ambiguous; THIS_AND_FUTURE is the "change it from here
 *   on" path.
 * - RRULE changed on the first occurrence: ALL_EVENTS stays enabled, since the form is open on
 *   the master at its own DTSTART. THIS_AND_FUTURE and THIS_EVENT stay disabled, so without
 *   this case no option would be left to save with (#274).
 * - Date changed on a later occurrence ([ScopeContext.occurrenceDateChanged]): ALL_EVENTS is
 *   disabled.
 *
 * An attendee change gates no scope: THIS_EVENT writes the guest set to the exception,
 * THIS_AND_FUTURE to the series split and ALL_EVENTS to the master update.
 *
 * "All events" is tinted Warn, a brake on misclicks.
 */
fun computeEditScopeOptions(
    context: ScopeContext,
    originalRrule: String?,
    currentRrule: String?,
    resources: Resources,
): List<ScopeOption> {
    val isFirstOccurrence = context.occurrenceTs <= context.masterStartTs
    // Compare by meaning, not bytes: the picker can re-emit the same rule with reordered parts,
    // different case or whitespace, which a raw compare would read as a user change and wrongly
    // disable save options.
    val rruleChanged = !RruleUtils.rrulesEquivalent(originalRrule, currentRrule)

    val thisEventEnabled = !rruleChanged
    val thisAndFutureEnabled = !context.isDetachedException && !isFirstOccurrence
    val allEventsEnabled = !context.isDetachedException && (!rruleChanged || isFirstOccurrence) &&
        (isFirstOccurrence || !context.occurrenceDateChanged)

    return listOf(
        ScopeOption(
            scope = EditScope.THIS_EVENT,
            label = resources.getString(R.string.recurring_this_event),
            icon = ICON_THIS,
            enabled = thisEventEnabled,
            tint = ScopeTint.Neutral,
        ),
        ScopeOption(
            scope = EditScope.THIS_AND_FUTURE,
            label = resources.getString(R.string.recurring_this_and_future),
            icon = ICON_FUTURE,
            enabled = thisAndFutureEnabled,
            tint = ScopeTint.Neutral,
        ),
        ScopeOption(
            scope = EditScope.ALL_EVENTS,
            label = resources.getString(R.string.recurring_all_events),
            icon = ICON_ALL,
            enabled = allEventsEnabled,
            tint = ScopeTint.Warn,
        ),
    )
}

/**
 * Returns the options for the drag-to-reschedule scope sheet. A drop can't change the RRULE and
 * always starts from a real occurrence, so the RRULE and detached-exception rules of
 * [computeEditScopeOptions] don't apply. THIS_EVENT is always enabled. THIS_AND_FUTURE is
 * disabled on the first occurrence, and THIS_AND_FUTURE or ALL_EVENTS when in [blockedScopes]
 * (the caller passes [dragScopesToGrey]).
 *
 * A device event gets no ALL_EVENTS option. The Room path can split a series through its
 * materialized occurrences; the CalendarProvider can't, so an ALL_EVENTS device drag would
 * shift the master's DTSTART and move every past occurrence with it.
 *
 * "All events", when present, is tinted Warn as a brake on misclicks.
 */
fun computeDragScopeOptions(
    masterStartTs: Long,
    targetOccurrenceTs: Long,
    isAllDay: Boolean,
    isDevice: Boolean,
    resources: Resources,
    blockedScopes: Set<EditScope> = emptySet(),
): List<ScopeOption> {
    val isFirstOccurrence = targetOccurrenceTs <= masterStartTs

    val baseOptions = listOf(
        ScopeOption(
            scope = EditScope.THIS_EVENT,
            label = resources.getString(R.string.recurring_this_event),
            icon = ICON_THIS,
            enabled = true,
            tint = ScopeTint.Neutral,
        ),
        ScopeOption(
            scope = EditScope.THIS_AND_FUTURE,
            label = resources.getString(R.string.recurring_this_and_future),
            icon = ICON_FUTURE,
            enabled = !isFirstOccurrence && EditScope.THIS_AND_FUTURE !in blockedScopes,
            tint = ScopeTint.Neutral,
        ),
    )
    if (isDevice) return baseOptions
    return baseOptions + ScopeOption(
        scope = EditScope.ALL_EVENTS,
        label = resources.getString(R.string.recurring_all_events),
        icon = ICON_ALL,
        enabled = EditScope.ALL_EVENTS !in blockedScopes,
        tint = ScopeTint.Warn,
    )
}

/**
 * The scopes the drag sheet greys out for [pending]: its checked result, or
 * both series scopes while the check is still running.
 */
fun dragScopesToGrey(pending: PendingDragReschedule): Set<EditScope> =
    pending.blockedScopes ?: setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE)

/**
 * Returns the options for the delete scope sheet on a recurring event.
 *
 * Edge cases, as in [computeEditScopeOptions]:
 * - First occurrence: THIS_AND_FUTURE is the same as ALL_EVENTS, so it is disabled.
 * - Detached exception: only THIS_EVENT applies; the others are disabled.
 *
 * "All events" is tinted Destructive and has a trash icon, since this delete removes data.
 */
fun computeDeleteScopeOptions(
    context: ScopeContext,
    resources: Resources,
): List<ScopeOption> {
    val isFirstOccurrence = context.occurrenceTs <= context.masterStartTs

    val thisAndFutureEnabled = !context.isDetachedException && !isFirstOccurrence
    val allEventsEnabled = !context.isDetachedException

    return listOf(
        ScopeOption(
            scope = EditScope.THIS_EVENT,
            label = resources.getString(R.string.recurring_this_event),
            icon = ICON_THIS,
            enabled = true,
            tint = ScopeTint.Neutral,
        ),
        ScopeOption(
            scope = EditScope.THIS_AND_FUTURE,
            label = resources.getString(R.string.recurring_this_and_future),
            icon = ICON_FUTURE,
            enabled = thisAndFutureEnabled,
            tint = ScopeTint.Neutral,
        ),
        ScopeOption(
            scope = EditScope.ALL_EVENTS,
            label = resources.getString(R.string.recurring_all_events),
            icon = ICON_DELETE_ALL,
            enabled = allEventsEnabled,
            tint = ScopeTint.Destructive,
        ),
    )
}
