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

/**
 * Pure helpers that compute the list of [ScopeOption]s for a given
 * recurring-event flow. Splitting the option-set logic out of the
 * sheet itself keeps the rules unit-testable; the sheet stays a
 * stateless renderer.
 *
 * Each option carries an icon for instant scope recognition. The
 * sheet itself shows just the title + the cards + Cancel — no
 * sub-copy, no body description. The user just tapped a date so
 * we don't echo it back; greyed-state speaks for itself when an
 * option doesn't apply.
 */

/**
 * Minimal context the option-set rules need to decide which scopes
 * are enabled. Built from the live event at request time by the
 * ViewModel — the helper itself doesn't fabricate any data.
 *
 * @param masterStartTs The master event's true startTs. Used by the
 *   first-occurrence rule; do NOT pass occurrenceTs or any
 *   user-editable form value here.
 * @param occurrenceTs The instance the user tapped (or, for drag,
 *   the source of the drag).
 * @param isDetachedException True when the event is an exception
 *   row already detached from its master series.
 * @param isAllDay Reserved for downstream consumers; the sheet
 *   itself doesn't render a date.
 */
data class ScopeContext(
    val masterStartTs: Long,
    val occurrenceTs: Long,
    val isDetachedException: Boolean,
    val isAllDay: Boolean,
)

private val ICON_THIS: ImageVector = Icons.Default.CalendarToday
private val ICON_FUTURE: ImageVector = Icons.AutoMirrored.Filled.ArrowForward
private val ICON_ALL: ImageVector = Icons.Default.Repeat
private val ICON_DELETE_ALL: ImageVector = Icons.Default.DeleteOutline

/**
 * Options for the form-save scope sheet on an editable recurring
 * event opened via per-occurrence edit.
 *
 * Edge cases:
 * - First occurrence: THIS_AND_FUTURE collapses with ALL_EVENTS;
 *   disable it.
 * - Detached exception: only THIS_EVENT applies; disable the others.
 * - Caller changed RRULE: THIS_EVENT and ALL_EVENTS are both
 *   disabled. THIS_EVENT can't apply because exception events strip
 *   RRULE per RFC 5545 §3.8.5. ALL_EVENTS can't apply because the
 *   form was opened on an off-master DTSTART occurrence and rewriting
 *   the master's cadence at that anchor is ambiguous; THIS_AND_FUTURE
 *   is the legitimate "change cadence going forward" path.
 *
 * "All events" is tinted Warn — a subtle visual brake on misclicks.
 */
fun computeEditScopeOptions(
    context: ScopeContext,
    originalRrule: String?,
    currentRrule: String?,
    resources: Resources,
): List<ScopeOption> {
    val isFirstOccurrence = context.occurrenceTs <= context.masterStartTs
    val rruleChanged = originalRrule != currentRrule

    val thisEventEnabled = !rruleChanged
    val thisAndFutureEnabled = !context.isDetachedException && !isFirstOccurrence
    val allEventsEnabled = !context.isDetachedException && !rruleChanged

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
 * Options for the drag-to-reschedule scope sheet. Thinner than the
 * form-save flow — the user can't have changed the RRULE on a drop
 * and the drag always lands on a real occurrence (so the
 * detached-exception rule doesn't apply).
 *
 * Device branch hides ALL_EVENTS entirely. The Room path can split a
 * series cleanly via materialized occurrences; the CalendarProvider
 * can't, so an ALL_EVENTS device drag would shift the master's
 * DTSTART and move every past occurrence with it.
 *
 * "All events" gets the Warn tint when present — a misclick brake.
 */
fun computeDragScopeOptions(
    masterStartTs: Long,
    targetOccurrenceTs: Long,
    isAllDay: Boolean,
    isDevice: Boolean,
    resources: Resources,
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
            enabled = !isFirstOccurrence,
            tint = ScopeTint.Neutral,
        ),
    )
    if (isDevice) return baseOptions
    return baseOptions + ScopeOption(
        scope = EditScope.ALL_EVENTS,
        label = resources.getString(R.string.recurring_all_events),
        icon = ICON_ALL,
        enabled = true,
        tint = ScopeTint.Warn,
    )
}

/**
 * Options for the delete scope sheet on a recurring event.
 *
 * Edge cases:
 * - First occurrence: THIS_AND_FUTURE collapses with ALL_EVENTS;
 *   disable it (matches the edit flow).
 * - Detached exception: only THIS_EVENT applies; disable the others.
 *
 * "All events" is tinted Destructive (red) since delete actually
 * removes data — the stronger visual weight matches the consequence.
 * The trash icon doubles the warning weight.
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
