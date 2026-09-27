package org.onekash.kashcal.ui.components

import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import android.content.res.Resources
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.domain.mapper.toFormState
import org.onekash.kashcal.ui.components.pickers.ActiveDateTimeSheet
import org.onekash.kashcal.ui.components.pickers.CalendarPickerRow
import org.onekash.kashcal.ui.components.pickers.DateTimeDisplayRow
import org.onekash.kashcal.ui.components.pickers.DateTimeSheet
import org.onekash.kashcal.ui.components.pickers.EventColorSheet
import org.onekash.kashcal.ui.components.pickers.EventFormRow
import org.onekash.kashcal.ui.components.pickers.RecurrencePickerRow
import org.onekash.kashcal.ui.components.pickers.ReminderPickerRow
import org.onekash.kashcal.ui.components.pickers.TimezonePickerSheet
import org.onekash.kashcal.ui.components.pickers.untilDisplayMillis
import org.onekash.kashcal.ui.components.pickers.untilForPickedDate
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.PickerCalendar
import org.onekash.kashcal.ui.model.localizedDisplayName
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.MAX_REMINDERS
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.contrastForegroundOn
import org.onekash.kashcal.ui.shared.deduplicateAndSortReminders
import org.onekash.kashcal.util.CalendarIntentData
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.RruleUtils
import org.onekash.kashcal.util.TimezoneUtils
import org.onekash.kashcal.util.location.AddressSuggestion
import org.onekash.kashcal.util.location.LocationSuggestionService
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar as JavaCalendar

private const val TAG = "EventFormSheet"

/** Characters a title needs before [shouldShowTitleSuggestions] queries suggestions. */
internal const val MIN_TITLE_PREFIX = 3

/** Wait this long after the last keystroke before querying the suggestion backend. */
private const val TITLE_SUGGEST_DEBOUNCE_MS = 150L

/** Test tag on the divider between the notes/tags group and the attendees/free-busy group. */
internal const val TAG_GROUP_DIVIDER = "form_group_divider"

/** Test tag on the sticky Save button's top divider. */
internal const val TAG_SAVE_DIVIDER = "form_save_divider"

/** Test tag on the delete section's leading divider (edit mode only). */
internal const val TAG_DELETE_DIVIDER = "form_delete_divider"

/** Test tag on the title field, so its focus state on open is assertable. */
internal const val TAG_TITLE_FIELD = "form_title_field"

/**
 * Whether a scroll came from the user dragging or flinging the form, not from a
 * programmatic scroll such as Compose's bring-into-view of a focused field.
 * Dismissing the keyboard on bring-into-view would eject the field the user just
 * tapped.
 */
internal fun isUserDrivenScroll(source: NestedScrollSource): Boolean =
    source == NestedScrollSource.UserInput

/**
 * Whether a scroll dismisses the keyboard: only a user-driven scroll with a finger
 * pressed. When a field gains focus, the animating IME inset fires a scroll that is
 * also dispatched as [NestedScrollSource.UserInput]; it has no finger down (the tap
 * has released), so the finger check keeps the keyboard the user just raised.
 */
internal fun shouldDismissKeyboardOnScroll(
    source: NestedScrollSource,
    isFingerDown: Boolean,
): Boolean = isUserDrivenScroll(source) && isFingerDown

/**
 * Space above and below the section dividers, so it doesn't depend on which row
 * (EventFormRow at 14dp, picker rows at 8-12dp) sits against them.
 */
private val SECTION_DIVIDER_SPACING = 6.dp

/**
 * Whether the title-suggestion dropdown should appear: at least [MIN_TITLE_PREFIX]
 * characters typed, and the text changed from what the form loaded (so opening an
 * event to fix a typo doesn't flash a dropdown). The suggestions setting isn't
 * checked here: `HomeViewModel.suggestTitles` returns an empty list when it is off.
 */
internal fun shouldShowTitleSuggestions(
    currentText: String,
    initialText: String
): Boolean {
    if (currentText.length < MIN_TITLE_PREFIX) return false
    if (currentText == initialText) return false
    return true
}

/**
 * Whether an optional event field (location, notes) should render. Editable
 * mode always shows it (the empty row is the place to add one); the read-only
 * attendee view hides a blank field so a guest isn't prompted to add something
 * they can't edit.
 */
internal fun shouldShowReadOnlyOptionalField(value: String, isReadOnly: Boolean): Boolean =
    !isReadOnly || value.isNotBlank()

/**
 * True when [current] differs from [initial] regardless of order. Enables Save in
 * the read-only attendee form once the user changes their reminder set.
 *
 * Compares sorted lists, not sets: the picker doesn't dedupe, so `[15, 15]` is
 * distinct from `[15]`.
 */
internal fun remindersChanged(initial: List<Int>, current: List<Int>): Boolean =
    initial.sorted() != current.sorted()

/**
 * Whether the editable attendee row (a tappable row that opens the picker)
 * renders. An existing device event has its own gate in the form. The row shows
 * for new events and for every edit scope, series and single occurrence alike (a
 * detached exception included), since each save scope carries the edited guest
 * set to its write path. Hidden when the user can't organize (a read-only
 * invitee event or a non-schedulable account) or when contact lookup isn't wired.
 *
 * @param hasContactQuery whether an onQueryContacts callback is available.
 */
internal fun canEditAttendees(
    isReadOnly: Boolean,
    isSchedulable: Boolean,
    hasContactQuery: Boolean,
): Boolean = !isReadOnly && isSchedulable && hasContactQuery

/**
 * Whether the "inviting unavailable" text renders in place of an attendee row, on
 * a non-schedulable account. Only new events and non-recurring edits show it; a
 * recurring edit (master or occurrence) shows the read-only guest chips, or
 * nothing when there are no guests.
 *
 * @param hasContactQuery whether an onQueryContacts callback is available.
 */
internal fun showSchedulingUnavailable(
    isReadOnly: Boolean,
    isSchedulable: Boolean,
    hasContactQuery: Boolean,
    isEditMode: Boolean,
    wasRecurringAtLoad: Boolean,
): Boolean = !isReadOnly && !isSchedulable && hasContactQuery && !(isEditMode && wasRecurringAtLoad)

/**
 * Swaps [currentDefault] for [newDefault] in [reminders] when all-day is toggled,
 * keeping every other value, then dedupes and sorts.
 */
private fun migrateRemindersForAllDayToggle(
    reminders: List<Int>,
    currentDefault: Int,
    newDefault: Int
): List<Int> {
    if (reminders.isEmpty()) return reminders
    return reminders.map { minutes ->
        if (minutes == currentDefault) newDefault else minutes
    }.let { deduplicateAndSortReminders(it) }
}

/**
 * Form state for event creation/editing.
 *
 * Dates and times: for a timed form, [dateMillis] and [endDateMillis] hold the
 * calendar date of the start and end in the form's [timezone], stored as that
 * date's midnight in the device's own zone (the encoding the date row, the date
 * picker and the all-day logic read), and the hour/minute fields hold the clock
 * time in [timezone]. [toStartEndTs] turns them back into the stored instants.
 */
data class EventFormState(
    // Essential fields
    val title: String = "",
    val dateMillis: Long = System.currentTimeMillis(),
    val endDateMillis: Long = System.currentTimeMillis(),
    val startHour: Int = JavaCalendar.getInstance().get(JavaCalendar.HOUR_OF_DAY),
    val startMinute: Int = 0,
    val endHour: Int = JavaCalendar.getInstance().get(JavaCalendar.HOUR_OF_DAY),
    val endMinute: Int = 20,
    val selectedCalendarId: Long? = null,
    val selectedCalendarName: String = "",
    val selectedCalendarColor: Int? = null,
    val reminders: List<Int> = listOf(15),

    // Advanced fields
    val isAllDay: Boolean = false,
    val location: String = "",
    val description: String = "",
    val rrule: String? = null,
    val timezone: String? = null,  // null = device default
    val transp: String = "OPAQUE",
    val eventColor: Int? = null,
    val categories: List<String> = emptyList(),
    // Whether the user changed the tag set; seeding from a loaded event leaves it
    // false. The device save passes null tags when it is false, so an unedited
    // open-and-save leaves the stored tag row alone: a rewrite would clobber tags a
    // sync adapter added between load and save, or wipe real tags if the load read
    // came back empty. The Room save writes [categories] either way.
    val categoriesEdited: Boolean = false,

    // UI state
    val calendarGroups: List<CalendarGroup> = emptyList(),
    val deviceCalendarGroups: List<CalendarGroup> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,

    // Device calendar state
    val isDeviceCalendar: Boolean = false,
    val editingDeviceEventId: Long? = null,
    /** Reminders beyond [MAX_REMINDERS] that the load dropped. */
    val truncatedReminderCount: Int = 0,

    // Edit mode
    val editingEventId: Long? = null,
    val isEditMode: Boolean = false,
    val editingOccurrenceTs: Long? = null,

    // Attendees the user is editing (organizer flow), as Room entities: seeding
    // from the real rows preserves the role, cutype, rsvp and delegation that the
    // AttendeeUiModel projection drops. The picker mutates this set;
    // [attendeesEdited] records whether the user changed it, and when it is false
    // the save passes null so the stored attendees stay untouched.
    val attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList(),
    val attendeesEdited: Boolean = false,

    // The stored start and end the form was loaded from (or last computed).
    // When the date and clock time still name exactly that moment, the save
    // reuses it, which keeps the second of two repeated clock times at a
    // daylight saving change from sliding to the first. Not user-editable.
    val startOffsetHintTs: Long? = null,
    val endOffsetHintTs: Long? = null,
    // A device event's timezone ID that the app can't resolve (the form then
    // uses the device zone). Kept so a save that doesn't pick a zone writes the
    // original ID back instead of replacing it. Cleared by the timezone picker.
    val sourceTimezoneId: String? = null,
    // The repeat rule before an all-day toggle re-expressed its end date, and the
    // rule that toggle produced. Toggling back with the rule untouched restores the
    // original exactly, so on-then-off never rewrites it. Not user-editable.
    val repeatRuleBeforeAllDayToggle: String? = null,
    val repeatRuleAfterAllDayToggle: String? = null,
)

/**
 * True when the user has edited any substantive field since the form loaded.
 * Compares only user-editable fields against the load-time baseline; UI-only
 * fields (loading/saving flags, error, the picker's calendar group list, and
 * the calendar's display name/color, which fill in asynchronously) are
 * excluded so an unedited open-and-close is not mistaken for a change.
 */
internal fun eventFormHasUnsavedChanges(
    initial: EventFormState,
    current: EventFormState,
): Boolean =
    current.title != initial.title ||
        !sameDeviceDay(current.dateMillis, initial.dateMillis) ||
        !sameDeviceDay(current.endDateMillis, initial.endDateMillis) ||
        current.startHour != initial.startHour ||
        current.startMinute != initial.startMinute ||
        current.endHour != initial.endHour ||
        current.endMinute != initial.endMinute ||
        current.selectedCalendarId != initial.selectedCalendarId ||
        current.isAllDay != initial.isAllDay ||
        current.location != initial.location ||
        current.description != initial.description ||
        current.reminders != initial.reminders ||
        current.rrule != initial.rrule ||
        current.timezone != initial.timezone ||
        current.sourceTimezoneId != initial.sourceTimezoneId ||
        current.eventColor != initial.eventColor ||
        current.transp != initial.transp ||
        current.categories != initial.categories ||
        current.attendeesEdited != initial.attendeesEdited

/** Outcome of a dismiss attempt (Cancel tap or system back press). */
internal enum class FormDismissAction { DISMISS, SHOW_DISCARD_CONFIRM, BLOCKED }

/**
 * Resolve what a dismiss attempt should do. A save in progress blocks dismiss
 * entirely; with no unsaved changes it dismisses immediately; with unsaved
 * changes the first attempt asks for confirmation and the second (once the
 * confirmation is already showing) dismisses.
 */
internal fun resolveFormDismiss(
    isSaving: Boolean,
    hasUnsavedChanges: Boolean,
    discardConfirmShowing: Boolean,
): FormDismissAction = when {
    isSaving -> FormDismissAction.BLOCKED
    !hasUnsavedChanges -> FormDismissAction.DISMISS
    discardConfirmShowing -> FormDismissAction.DISMISS
    else -> FormDismissAction.SHOW_DISCARD_CONFIRM
}

/**
 * Whether the load-time baseline should be re-synced when the default calendar
 * auto-resolves. On a cold start the calendar list arrives after the form is
 * seeded, so the initial baseline has no calendar id yet; re-baselining then
 * keeps that async resolution from reading as a user change.
 */
internal fun shouldRebaselineOnCalendarResolve(initial: EventFormState?): Boolean =
    initial == null || initial.selectedCalendarId == null

/**
 * Compute the stored (startTs, endTs) this form state would persist. All-day
 * events store UTC midnight (start) / end-of-day UTC (end); timed events read
 * the date from [EventFormState.dateMillis] in the device's zone and the clock
 * time in the selected timezone (or device default). The save path, the form's
 * checks and the Save-and-notify label all use it, so they agree with what saves.
 */
fun EventFormState.toStartEndTs(): Pair<Long, Long> {
    return if (isAllDay) {
        val startUtc = DateTimeUtils.localDateToUtcMidnight(dateMillis)
        val endUtc = DateTimeUtils.localDateToUtcMidnight(endDateMillis)
        startUtc to DateTimeUtils.utcMidnightToEndOfDay(endUtc)
    } else {
        val zone = TimezoneUtils.resolveZone(timezone)
        resolveFormTime(dateMillis, startHour, startMinute, zone, startOffsetHintTs) to
            resolveFormTime(endDateMillis, endHour, endMinute, zone, endOffsetHintTs)
    }
}

/**
 * The instant for a form date (device-zone midnight encoding) at [hour]:[minute]
 * in [zone]. When [hintTs] names exactly that clock time, it is returned as is.
 * Otherwise RFC 5545 section 3.3.5 applies: a repeated clock time (when the
 * clocks go back) is its first occurrence, and a skipped one (when they go
 * forward) is read with the offset before the gap. ZonedDateTime.of does both.
 */
private fun resolveFormTime(dateMillis: Long, hour: Int, minute: Int, zone: ZoneId, hintTs: Long?): Long {
    val local = deviceLocalDate(dateMillis).atTime(hour, minute)
    if (hintTs != null) {
        val hinted = Instant.ofEpochMilli(hintTs).atZone(zone)
        if (hinted.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) == local) return hintTs
    }
    return ZonedDateTime.of(local, zone).toInstant().toEpochMilli()
}

/** The calendar date [millis] falls on in the device's zone. */
private fun deviceLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** True when two form dates fall on one device-local day; producers encode dates differently. */
private fun sameDeviceDay(a: Long, b: Long): Boolean = deviceLocalDate(a) == deviceLocalDate(b)

/** [date]'s midnight in the device's zone: how the form stores a date. */
private fun deviceMidnight(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** The timed form fields that describe a start and end instant. */
internal data class FormDateFields(
    val dateMillis: Long,
    val endDateMillis: Long,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val startOffsetHintTs: Long?,
    val endOffsetHintTs: Long?,
)

/** Form fields for a timed event at [startTs]..[endTs], shown in [timezone]. */
internal fun timedFormDateFields(startTs: Long, endTs: Long, timezone: String?): FormDateFields {
    val zone = TimezoneUtils.resolveZone(timezone)
    val start = Instant.ofEpochMilli(startTs).atZone(zone)
    val end = Instant.ofEpochMilli(endTs).atZone(zone)
    return FormDateFields(
        dateMillis = deviceMidnight(start.toLocalDate()),
        endDateMillis = deviceMidnight(end.toLocalDate()),
        startHour = start.hour,
        startMinute = start.minute,
        endHour = end.hour,
        endMinute = end.minute,
        startOffsetHintTs = startTs,
        endOffsetHintTs = endTs,
    )
}

/**
 * Form fields for an all-day event stored as UTC midnights: the stored dates
 * shown as device-local dates, with no loaded-time hints.
 */
internal fun allDayFormDateFields(startUtcMidnight: Long, endUtc: Long): FormDateFields {
    val localStart = DateTimeUtils.utcMidnightToLocalDate(startUtcMidnight)
    val localEnd = DateTimeUtils.utcMidnightToLocalDate(endUtc)
    val start = Instant.ofEpochMilli(localStart).atZone(ZoneId.systemDefault())
    val end = Instant.ofEpochMilli(localEnd).atZone(ZoneId.systemDefault())
    return FormDateFields(
        dateMillis = localStart,
        endDateMillis = localEnd,
        startHour = start.hour,
        startMinute = start.minute,
        endHour = end.hour,
        endMinute = end.minute,
        startOffsetHintTs = null,
        endOffsetHintTs = null,
    )
}

internal fun EventFormState.withDateFields(fields: FormDateFields): EventFormState = copy(
    dateMillis = fields.dateMillis,
    endDateMillis = fields.endDateMillis,
    startHour = fields.startHour,
    startMinute = fields.startMinute,
    endHour = fields.endHour,
    endMinute = fields.endMinute,
    startOffsetHintTs = fields.startOffsetHintTs,
    endOffsetHintTs = fields.endOffsetHintTs,
)

/**
 * Form fields for editing a Room event, or one occurrence of it at
 * [occurrenceTs]. A re-edited exception keeps its own (modified) start. All-day
 * events show their stored UTC date as a device-local date.
 */
internal fun org.onekash.kashcal.data.db.entity.Event.toFormDateFields(occurrenceTs: Long?): FormDateFields {
    val duration = endTs - startTs
    val actualStartTs = if (isException) startTs else (occurrenceTs ?: startTs)
    val actualEndTs = actualStartTs + duration
    return if (isAllDay) {
        allDayFormDateFields(actualStartTs, actualEndTs)
    } else {
        timedFormDateFields(actualStartTs, actualEndTs, timezone)
    }
}

/**
 * True when a timed form's end is before its start. Compares the instants
 * [toStartEndTs] would store, so the check agrees with the save even when the
 * form's timezone puts the event on a different date than the phone's.
 */
internal fun EventFormState.endsBeforeStart(): Boolean {
    if (isAllDay) return false
    val (start, end) = toStartEndTs()
    return end < start
}

/**
 * Moves a timed form's start to the date of [dateMillis] at [hour]:[minute],
 * keeping the event's duration. The duration is real elapsed time between the
 * stored instants, so a daylight saving change inside the event doesn't
 * stretch or shrink it; a form whose end is before its start gets
 * [defaultDurationMinutes].
 */
internal fun EventFormState.withTimedStart(
    dateMillis: Long,
    hour: Int,
    minute: Int,
    defaultDurationMinutes: Int,
): EventFormState {
    val (oldStart, oldEnd) = toStartEndTs()
    val durationMs = (oldEnd - oldStart).takeIf { it >= 0 } ?: (defaultDurationMinutes * 60_000L)
    val moved = copy(
        dateMillis = deviceMidnight(deviceLocalDate(dateMillis)),
        startHour = hour,
        startMinute = minute,
    )
    val newEnd = moved.toStartEndTs().first + durationMs
    val end = Instant.ofEpochMilli(newEnd).atZone(TimezoneUtils.resolveZone(timezone))
    return moved.copy(
        endDateMillis = deviceMidnight(end.toLocalDate()),
        endHour = end.hour,
        endMinute = end.minute,
        endOffsetHintTs = newEnd,
    )
}

/**
 * Sets a timed form's end to the date of [dateMillis] at [hour]:[minute]. An end
 * date before the start date swaps them: the start moves to the picked date and
 * the end takes the old start date, with the picked time.
 */
internal fun EventFormState.withTimedEnd(dateMillis: Long, hour: Int, minute: Int): EventFormState {
    val picked = deviceLocalDate(dateMillis)
    val startDate = deviceLocalDate(this.dateMillis)
    return if (picked < startDate) {
        copy(
            dateMillis = deviceMidnight(picked),
            endDateMillis = deviceMidnight(startDate),
            endHour = hour,
            endMinute = minute,
        )
    } else {
        copy(endDateMillis = deviceMidnight(picked), endHour = hour, endMinute = minute)
    }
}

/**
 * Switches a timed form to [newTimezone] (null = device default), keeping the
 * event at the same moment: the start and end are re-expressed as the date and
 * clock time they fall on in the new zone. Any preserved unrecognised source
 * timezone is dropped, since the user has now chosen one.
 */
internal fun EventFormState.withTimezone(newTimezone: String?): EventFormState {
    val (start, end) = toStartEndTs()
    return copy(timezone = newTimezone, sourceTimezoneId = null)
        .withDateFields(timedFormDateFields(start, end, newTimezone))
}

/**
 * Turns all-day on or off, swapping the default reminder. Turning it on keeps
 * the form's dates (already device-local midnights) and turning it off keeps the
 * clock times, so on-then-off leaves the event where it was. A repeat end date
 * is kept on the same day in the new form.
 */
internal fun EventFormState.withAllDay(
    newIsAllDay: Boolean,
    defaultReminderTimed: Int,
    defaultReminderAllDay: Int,
): EventFormState {
    val currentDefault = if (isAllDay) defaultReminderAllDay else defaultReminderTimed
    val newDefault = if (newIsAllDay) defaultReminderAllDay else defaultReminderTimed
    // Toggling back with the rule as the last toggle left it restores the rule it
    // replaced; otherwise the end date is re-expressed for the new form.
    val restoring = newIsAllDay != isAllDay && repeatRuleBeforeAllDayToggle != null && rrule == repeatRuleAfterAllDayToggle
    val newRrule = if (restoring) {
        repeatRuleBeforeAllDayToggle
    } else {
        reanchorRepeatEnd(rrule, wasAllDay = isAllDay, isAllDay = newIsAllDay, timezone = timezone)
    }
    // Remember the rule on every flip, even one that leaves it unchanged, so the
    // flip back restores it instead of re-expressing it.
    val rewritten = !restoring && newIsAllDay != isAllDay && rrule != null
    return copy(
        isAllDay = newIsAllDay,
        dateMillis = if (newIsAllDay) normalizeToLocalMidnight(dateMillis) else dateMillis,
        endDateMillis = if (newIsAllDay) normalizeToLocalMidnight(endDateMillis) else endDateMillis,
        reminders = migrateRemindersForAllDayToggle(reminders, currentDefault, newDefault),
        rrule = newRrule,
        repeatRuleBeforeAllDayToggle = if (rewritten) rrule else null,
        repeatRuleAfterAllDayToggle = if (rewritten) newRrule else null,
    )
}

private val UNTIL_VALUE = Regex("UNTIL=([0-9]{8}(?:T[0-9]{6}Z?)?)(?=;|$)")

/**
 * Re-expresses a repeat rule's end date when all-day is switched, keeping the
 * same end date. UNTIL takes DTSTART's value type (RFC 5545 section 3.3.10): a
 * date for all-day, a UTC date-time for timed. A date UNTIL is taken as the date
 * written; a floating local-time or malformed UNTIL is left as is.
 */
private fun reanchorRepeatEnd(rrule: String?, wasAllDay: Boolean, isAllDay: Boolean, timezone: String?): String? {
    if (rrule == null || wasAllDay == isAllDay) return rrule
    val value = UNTIL_VALUE.find(rrule)?.groups?.get(1) ?: return rrule
    val endDate = try {
        when {
            value.value.length == 8 ->
                deviceMidnight(LocalDate.parse(value.value, DateTimeFormatter.BASIC_ISO_DATE))
            value.value.endsWith("Z") -> {
                val until = LocalDateTime.parse(value.value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                untilDisplayMillis(until.toInstant(ZoneOffset.UTC).toEpochMilli(), wasAllDay, timezone)
            }
            else -> return rrule
        }
    } catch (_: Exception) {
        return rrule
    }
    val newUntil = untilForPickedDate(endDate, isAllDay, timezone)
    return rrule.replaceRange(value.range, RruleUtils.formatUntilDate(newUntil, isAllDay))
}

internal data class ResolvedCalendar(
    val id: Long?,
    val name: String,
    val color: Int?,
    val isDevice: Boolean
)

internal fun resolveDefaultCalendar(
    defaultCalendar: DefaultCalendar?,
    writableCalendars: List<Calendar>,
    deviceCalendarGroups: List<CalendarGroup>
): ResolvedCalendar {
    return when (defaultCalendar) {
        is DefaultCalendar.Room -> {
            val cal = writableCalendars.find { it.id == defaultCalendar.calendarId }
            if (cal != null) {
                ResolvedCalendar(cal.id, cal.displayName, cal.color, isDevice = false)
            } else {
                val fallback = writableCalendars.firstOrNull()
                ResolvedCalendar(fallback?.id, fallback?.displayName.orEmpty(), fallback?.color, isDevice = false)
            }
        }
        is DefaultCalendar.Device -> {
            val deviceCal = deviceCalendarGroups
                .flatMap { it.pickerCalendars }
                .filterIsInstance<PickerCalendar.Device>()
                .map { it.calendar }
                .find { it.id == defaultCalendar.calendarId }
            if (deviceCal != null) {
                ResolvedCalendar(deviceCal.id, deviceCal.displayName, deviceCal.color, isDevice = true)
            } else {
                val fallback = writableCalendars.firstOrNull()
                ResolvedCalendar(fallback?.id, fallback?.displayName.orEmpty(), fallback?.color, isDevice = false)
            }
        }
        null -> {
            val fallback = writableCalendars.firstOrNull()
            ResolvedCalendar(fallback?.id, fallback?.displayName.orEmpty(), fallback?.color, isDevice = false)
        }
    }
}

/**
 * Resolves the calendar a duplicated event defaults to, keeping the source
 * calendar the way editing does.
 *
 * A Room source carries its calendar id on [Event.calendarId]; a device source
 * zeroes it (device calendar ids are a separate namespace from Room ids) and
 * passes the source device calendar id as [duplicateFromDeviceCalendarId].
 * Resolution order: the Room source calendar if it is in [writableCalendars],
 * then the source device calendar if it still exists and is writable in
 * [deviceCalendarGroups], else [resolvedDefault].
 *
 * Takes no [Resources], like [resolveDefaultCalendar]: the returned
 * [ResolvedCalendar] carries the raw name, which the caller localizes with
 * [ResolvedCalendar.localizedName].
 */
internal fun resolveDuplicateSourceCalendar(
    duplicateFrom: Event,
    duplicateFromDeviceCalendarId: Long?,
    writableCalendars: List<Calendar>,
    deviceCalendarGroups: List<CalendarGroup>,
    resolvedDefault: ResolvedCalendar
): ResolvedCalendar {
    val roomSource = writableCalendars.find { it.id == duplicateFrom.calendarId }
    if (roomSource != null) {
        return ResolvedCalendar(roomSource.id, roomSource.displayName, roomSource.color, isDevice = false)
    }

    val deviceSource = duplicateFromDeviceCalendarId?.let { deviceId ->
        deviceCalendarGroups
            .flatMap { it.pickerCalendars }
            .filterIsInstance<PickerCalendar.Device>()
            .filter { it.isWritable }
            .map { it.calendar }
            .find { it.id == deviceId }
    }
    if (deviceSource != null) {
        return ResolvedCalendar(deviceSource.id, deviceSource.displayName, deviceSource.color, isDevice = true)
    }

    return resolvedDefault
}

/**
 * Returns the display name for a resolved calendar, localized for Room calendars.
 *
 * [resolveDefaultCalendar] takes no [Resources] and returns the raw stored name. A Room
 * calendar is looked up again and named with [localizedDisplayName] (which localizes the
 * built-in on-device calendar). A device calendar keeps its own name: its id is in a
 * separate space from Room ids, so a lookup in the Room list could collide and mislabel it.
 */
private fun ResolvedCalendar.localizedName(
    writableCalendars: List<Calendar>,
    resources: Resources
): String = if (isDevice) name
    else writableCalendars.find { it.id == id }?.localizedDisplayName(resources) ?: name

/**
 * Shows the event create and edit form full screen, in a Dialog that owns the dismiss
 * guard ([resolveFormDismiss]). Every parameter goes to [EventFormContent], which
 * documents them.
 */
@Composable
fun EventFormSheet(
    eventId: Long? = null,
    initialStartTs: Long? = null,
    occurrenceTs: Long? = null,
    duplicateFrom: Event? = null,
    duplicateFromDeviceCalendarId: Long? = null,
    calendarIntentData: CalendarIntentData? = null,
    calendarIntentInvitees: List<String> = emptyList(),
    calendars: List<Calendar>,
    calendarGroups: List<CalendarGroup>,
    defaultCalendar: DefaultCalendar?,
    onDismiss: () -> Unit,
    onSave: suspend (EventFormState) -> Result<Event>,
    onRequestRecurringSave: ((
        formState: EventFormState,
        occurrenceTs: Long,
        originalRrule: String?,
        masterStartTs: Long,
        isDetachedException: Boolean,
        isRecurringDevice: Boolean,
        loadedIsAllDay: Boolean,
    ) -> Unit)? = null,
    scopeSaveFailedTick: Int = 0,
    onDelete: (suspend (eventId: Long, occurrenceTs: Long?) -> Result<Unit>)? = null,
    onLoadEvent: (suspend (Long) -> Event?)? = null,
    onLoadAttendees: (suspend (Long) -> List<org.onekash.kashcal.data.db.entity.Attendee>)? = null,
    defaultReminderTimed: Int = 15,
    defaultReminderAllDay: Int = 1440,
    defaultEventDuration: Int = 30,
    onRequestNotificationPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null,
    locationSuggestionService: LocationSuggestionService? = null,
    onSuggestTitles: (suspend (String) -> List<org.onekash.kashcal.data.db.dao.TitleSuggestion>)? = null,
    categorySuggestions: List<String> = emptyList(),
    timeFormat: String = "system",
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    // Device calendar edit support
    deviceEventId: Long? = null,
    deviceOccurrenceTs: Long? = null,
    onLoadDeviceEvent: (suspend (Long) -> org.onekash.kashcal.ui.viewmodels.DeviceEventEditData?)? = null,
    onSaveDeviceEvent: (suspend (EventFormState) -> Result<Long>)? = null,
    onDeleteDeviceEvent: (suspend (EventFormState) -> Result<Unit>)? = null,
    deviceCalendarGroups: List<CalendarGroup> = emptyList(),
    attendees: List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel> = emptyList(),
    isCurrentUserOnList: Boolean = false,
    isReadOnly: Boolean = false,
    onRsvp: (org.onekash.kashcal.ui.components.attendees.AttendeeStatus) -> Unit = {},
    onSaveAttendeeReminders: (suspend (List<Int>) -> Result<Unit>)? = null,
    attendeeAccount: org.onekash.kashcal.data.db.entity.Account? = null,
    isSchedulable: Boolean = true,
    onCalendarSelected: ((Long) -> Unit)? = null,
    onQueryContacts: (suspend (String) -> List<org.onekash.kashcal.data.contacts.ContactEmail>)? = null,
    contactsPermissionState: org.onekash.kashcal.ui.permission.ContactsPermissionState =
        org.onekash.kashcal.ui.permission.ContactsPermissionState.NotRequested,
    onRequestContactsPermission: (() -> Unit)? = null,
    contactsDeclined: Boolean = false,
    onDeclineContacts: (() -> Unit)? = null,
    tagsAboveNotes: Boolean = false,
    onSetTagsAboveNotes: ((Boolean) -> Unit)? = null,
) {
    // Mirrored up from EventFormContent, which owns the form state, for the dismiss guard.
    var isSaving by remember { mutableStateOf(false) }

    // Two-tap discard confirmation: the content mirrors up whether the form has
    // unsaved edits, and this shell owns the confirmation flag, so the Cancel
    // button and the back button (both in the content) drive one state machine.
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val attemptDismiss: () -> Unit = {
        when (resolveFormDismiss(isSaving, hasUnsavedChanges, showDiscardConfirm)) {
            FormDismissAction.DISMISS -> onDismiss()
            FormDismissAction.SHOW_DISCARD_CONFIRM -> showDiscardConfirm = true
            FormDismissAction.BLOCKED -> {}
        }
    }

    // A full-screen Dialog with no slide-in, not a ModalBottomSheet, so the keyboard
    // can rise at once instead of following a sheet up. decorFitsSystemWindows = false
    // with safeDrawingPadding handles the status bar, IME and nav bar insets, as in
    // QuickAddDialog. The platform back dismissal is off (dismissOnBackPress = false):
    // back goes through the content's BackHandler to attemptDismiss, like Cancel, so
    // the discard guard still fires.
    Dialog(
        onDismissRequest = attemptDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
        EventFormContent(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            isHostSheetSettled = true,
            onSavingChange = { isSaving = it },
            onHasChangesChange = { hasUnsavedChanges = it },
            showDiscardConfirm = showDiscardConfirm,
            onRequestDismiss = attemptDismiss,
            eventId = eventId,
            initialStartTs = initialStartTs,
            occurrenceTs = occurrenceTs,
            duplicateFrom = duplicateFrom,
            duplicateFromDeviceCalendarId = duplicateFromDeviceCalendarId,
            calendarIntentData = calendarIntentData,
            calendarIntentInvitees = calendarIntentInvitees,
            calendars = calendars,
            calendarGroups = calendarGroups,
            defaultCalendar = defaultCalendar,
            onDismiss = onDismiss,
            onSave = onSave,
            onRequestRecurringSave = onRequestRecurringSave,
            scopeSaveFailedTick = scopeSaveFailedTick,
            onDelete = onDelete,
            onLoadEvent = onLoadEvent,
            onLoadAttendees = onLoadAttendees,
            defaultReminderTimed = defaultReminderTimed,
            defaultReminderAllDay = defaultReminderAllDay,
            defaultEventDuration = defaultEventDuration,
            onRequestNotificationPermission = onRequestNotificationPermission,
            locationSuggestionService = locationSuggestionService,
            onSuggestTitles = onSuggestTitles,
            categorySuggestions = categorySuggestions,
            timeFormat = timeFormat,
            firstDayOfWeek = firstDayOfWeek,
            deviceEventId = deviceEventId,
            deviceOccurrenceTs = deviceOccurrenceTs,
            onLoadDeviceEvent = onLoadDeviceEvent,
            onSaveDeviceEvent = onSaveDeviceEvent,
            onDeleteDeviceEvent = onDeleteDeviceEvent,
            deviceCalendarGroups = deviceCalendarGroups,
            attendees = attendees,
            isCurrentUserOnList = isCurrentUserOnList,
            isReadOnly = isReadOnly,
            onRsvp = onRsvp,
            onSaveAttendeeReminders = onSaveAttendeeReminders,
            attendeeAccount = attendeeAccount,
            isSchedulable = isSchedulable,
            onCalendarSelected = onCalendarSelected,
            onQueryContacts = onQueryContacts,
            contactsPermissionState = contactsPermissionState,
            onRequestContactsPermission = onRequestContactsPermission,
            contactsDeclined = contactsDeclined,
            onDeclineContacts = onDeclineContacts,
            tagsAboveNotes = tagsAboveNotes,
            onSetTagsAboveNotes = onSetTagsAboveNotes,
        )
        }
    }
}

/**
 * Renders the event form: its state, load and save logic, and fields. [EventFormSheet]
 * adds the Dialog and dismiss guard; this part renders and tests without a real window.
 *
 * @param onSavingChange reports the in-flight save flag up to the host, so the host can
 *   block dismissal mid-save without owning the form state. Required (no default): a host
 *   that drops it can tear the form down mid-write.
 * @param eventId the Room event to edit; null to create or to edit a device event
 *   ([deviceEventId]).
 * @param initialStartTs the start for a new event; the form takes its date and hour.
 * @param occurrenceTs the occurrence being edited when the form opened on one occurrence of
 *   a recurring Room event.
 * @param defaultCalendar the calendar for a new event, Room or device.
 * @param onDelete deletes the event; the delete row shows only in edit mode.
 * @param defaultReminderTimed default reminder for timed events, in minutes.
 * @param defaultReminderAllDay default reminder for all-day events, in minutes.
 * @param onRequestNotificationPermission asked before a save with reminders; it must call
 *   its result callback (true when granted). The save runs whatever the result. Null skips
 *   the check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormContent(
    onSavingChange: (Boolean) -> Unit,
    /** Mirror whether the form has unsaved edits up to the shell's dismiss guard. */
    onHasChangesChange: (Boolean) -> Unit = {},
    /** When true, the Cancel button renders the discard-confirmation label. */
    showDiscardConfirm: Boolean = false,
    /** Invoked by Cancel and by back ([BackHandler]); the host runs its dismiss guard. */
    onRequestDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    eventId: Long? = null,
    initialStartTs: Long? = null,
    occurrenceTs: Long? = null,
    duplicateFrom: Event? = null,
    /**
     * Source device calendar id when duplicating a device event. Device calendar ids
     * are a separate namespace from Room ids, so [DisplayEvent.Device.toEventForDuplicate]
     * zeroes [Event.calendarId] and the id comes here instead. Null for Room duplicates
     * and when not duplicating.
     */
    duplicateFromDeviceCalendarId: Long? = null,
    calendarIntentData: CalendarIntentData? = null,
    calendarIntentInvitees: List<String> = emptyList(),
    calendars: List<Calendar>,
    calendarGroups: List<CalendarGroup>,
    defaultCalendar: DefaultCalendar?,
    onDismiss: () -> Unit,
    onSave: suspend (EventFormState) -> Result<Event>,
    /**
     * Hands a save of a recurring occurrence edit to the host, whose scope sheet asks how
     * the change applies across the series. The metadata is captured at load:
     * - `originalRrule`: the loaded rrule, to detect a rule change.
     * - `masterStartTs`: the loaded event's start, which anchors the first-occurrence rule.
     * - `isDetachedException`: whether the loaded row is itself an exception.
     * - `isRecurringDevice`: whether the event is a device event, so the host picks the
     *   save path.
     * - `loadedIsAllDay`: the loaded all-day flag, so toggling all-day in the form doesn't
     *   change how the scope sheet reads the occurrence date.
     *
     * Null saves directly.
     */
    onRequestRecurringSave: ((
        formState: EventFormState,
        occurrenceTs: Long,
        originalRrule: String?,
        masterStartTs: Long,
        isDetachedException: Boolean,
        isRecurringDevice: Boolean,
        loadedIsAllDay: Boolean,
    ) -> Unit)? = null,
    /**
     * Increments when a deferred save fails or the user cancels the scope sheet. The form
     * then clears the `isSaving` flag the deferral set, so Save re-enables for a retry.
     */
    scopeSaveFailedTick: Int = 0,
    onDelete: (suspend (eventId: Long, occurrenceTs: Long?) -> Result<Unit>)? = null,
    onLoadEvent: (suspend (Long) -> Event?)? = null,
    /**
     * Loads a Room event's attendee rows for the picker to seed from, so an edit keeps the
     * role, cutype, rsvp and delegation the UI projection drops. Null disables seeding. A
     * device event seeds from the guests in its [DeviceEventEditData] instead.
     */
    onLoadAttendees: (suspend (Long) -> List<org.onekash.kashcal.data.db.entity.Attendee>)? = null,
    defaultReminderTimed: Int = 15,
    defaultReminderAllDay: Int = 1440,
    defaultEventDuration: Int = 30,
    onRequestNotificationPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null,
    locationSuggestionService: LocationSuggestionService? = null,
    onSuggestTitles: (suspend (String) -> List<org.onekash.kashcal.data.db.dao.TitleSuggestion>)? = null,
    categorySuggestions: List<String> = emptyList(),
    timeFormat: String = "system",
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    // Device calendar edit support
    deviceEventId: Long? = null,
    deviceOccurrenceTs: Long? = null,
    onLoadDeviceEvent: (suspend (Long) -> org.onekash.kashcal.ui.viewmodels.DeviceEventEditData?)? = null,
    onSaveDeviceEvent: (suspend (EventFormState) -> Result<Long>)? = null,
    onDeleteDeviceEvent: (suspend (EventFormState) -> Result<Unit>)? = null,
    deviceCalendarGroups: List<CalendarGroup> = emptyList(),
    attendees: List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel> = emptyList(),
    isCurrentUserOnList: Boolean = false,
    /**
     * When true, renders the attendee view: organizer-owned fields can't be edited, but
     * reminders can. An attendee may change their own VALARMs (RFC 6638 §3.2.2.1), and
     * [onSaveAttendeeReminders] saves them locally.
     *
     * The app enforces this itself: some CalDAV servers silently accept an attendee's edits
     * to organizer-owned fields.
     */
    isReadOnly: Boolean = false,
    /** Invoked when the user picks an RSVP answer in the read-only view. */
    onRsvp: (org.onekash.kashcal.ui.components.attendees.AttendeeStatus) -> Unit = {},
    /**
     * Saves the attendee's reminder set, in minutes and possibly empty, in the read-only
     * view: a local write that re-arms the alarms and sends nothing to the server. Null keeps
     * that view's Save disabled.
     */
    onSaveAttendeeReminders: (suspend (List<Int>) -> Result<Unit>)? = null,
    /**
     * The event's account, used to mark "You" among the attendees. Null for new local events
     * with no resolved account.
     */
    attendeeAccount: org.onekash.kashcal.data.db.entity.Account? = null,
    /**
     * True when the account has an address to write as ORGANIZER. When false the form offers
     * no editable attendee list ([canEditAttendees]; an existing device event has its own
     * gate) and [showSchedulingUnavailable] decides where the notice shows, so the form never
     * creates attendees without an ORGANIZER, which RFC 6638 §3.1 requires of a scheduling
     * object.
     */
    isSchedulable: Boolean = true,
    /**
     * Fired when the user picks another calendar, so the host re-resolves [attendeeAccount]
     * and [isSchedulable] for its account. Without it both stay on the calendar the form
     * opened with.
     */
    onCalendarSelected: ((Long) -> Unit)? = null,
    /** Debounced contact-email lookup for the picker's type-ahead. */
    onQueryContacts: (suspend (String) -> List<org.onekash.kashcal.data.contacts.ContactEmail>)? = null,
    /** READ_CONTACTS state, for the picker's permission banner. */
    contactsPermissionState: org.onekash.kashcal.ui.permission.ContactsPermissionState =
        org.onekash.kashcal.ui.permission.ContactsPermissionState.NotRequested,
    /** Requests READ_CONTACTS from the picker's banner; null makes the request a no-op. */
    onRequestContactsPermission: (() -> Unit)? = null,
    /** True when the user permanently declined contact suggestions; hides the picker banner. */
    contactsDeclined: Boolean = false,
    /** Persist a permanent decline of contact suggestions ("No thanks"). */
    onDeclineContacts: (() -> Unit)? = null,
    /** True when the tag row renders above notes; otherwise it sits below them. */
    tagsAboveNotes: Boolean = false,
    /** Persist a new tag-row position (above or below notes). */
    onSetTagsAboveNotes: ((Boolean) -> Unit)? = null,
    /**
     * Whether the host has finished its open animation. The title auto-focus, and the
     * keyboard it raises, waits for it so the keyboard doesn't rise mid-entrance.
     * [EventFormSheet] opens without animation and passes true.
     */
    isHostSheetSettled: Boolean = true,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val hapticFeedback = LocalHapticFeedback.current

    val context = LocalContext.current
    val is24HourDevice = DateFormat.is24HourFormat(context)
    val timePattern = remember(timeFormat, is24HourDevice) {
        DateTimeUtils.getTimePattern(timeFormat, is24HourDevice)
    }
    // 24-hour mode for the time picker wheels.
    val use24Hour = remember(timeFormat, is24HourDevice) {
        when (timeFormat) {
            "12h" -> false
            "24h" -> true
            else -> is24HourDevice  // "system" follows device setting
        }
    }

    // Form state
    var state by remember { mutableStateOf(EventFormState()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Baseline captured when the form finishes loading; unsaved-change detection
    // diffs the live state against it. Null until the load effect seeds it.
    var initialFormState by remember { mutableStateOf<EventFormState?>(null) }

    /**
     * Reminders at a Room edit load, the same value as state.reminders. The read-only
     * attendee view enables Save only when the current set differs from it.
     */
    var initialReminders by remember { mutableStateOf<List<Int>>(emptyList()) }

    /**
     * The rrule at load. The scope sheet compares against it and disables "This event" when
     * the rule changed, since a changed occurrence carries no rrule ([computeEditScopeOptions]).
     */
    var initialRrule by remember { mutableStateOf<String?>(null) }

    /**
     * Whether the loaded event has an rrule or is an exception. The scope-sheet deferral, the
     * delete routing and [showSchedulingUnavailable] key off this, not `state.rrule`, which the
     * user can clear and which is null on an exception.
     */
    var wasRecurringAtLoad by remember { mutableStateOf(false) }

    /**
     * The loaded event's start and exception flag, passed to `onRequestRecurringSave` so the
     * host's scope rules don't derive them from the user-edited form state.
     */
    var loadedMasterStartTs by remember { mutableStateOf(0L) }
    var loadedIsDetachedException by remember { mutableStateOf(false) }

    /** The loaded `isAllDay`, so an all-day toggle doesn't change what the scope sheet reads. */
    var loadedIsAllDay by remember { mutableStateOf(false) }

    /**
     * The Room event as loaded. The edit-notify check compares it with a candidate built from
     * the form to decide whether saving notifies attendees, which relabels Save.
     */
    var loadedEvent by remember { mutableStateOf<Event?>(null) }
    // Canonical addresses of the attendees at load, to detect removals this session.
    var loadedAttendeeAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Guests of a loaded device event, for the read-only display and the attendee
    // sheet. The Room `attendees` param is keyed on a Room event id and stays empty
    // for device events. An editable device guest list works on state.attendees.
    var deviceAttendees by remember {
        mutableStateOf<List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>>(emptyList())
    }
    // Whether the loaded device event's calendar allows writes; gates whether its
    // guest list is editable.
    var deviceEventWritable by remember { mutableStateOf(false) }
    // In-session dismissal of the local-account "no invitation sent" notice.
    var deviceNoticeDismissed by remember { mutableStateOf(false) }

    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var activeSheet by remember { mutableStateOf(ActiveDateTimeSheet.NONE) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showAttendeeSheet by remember { mutableStateOf(false) }
    var showAttendeePicker by remember { mutableStateOf(false) }
    // In-session dismissal of the picker's permission banner.
    var contactsBannerDismissed by remember { mutableStateOf(false) }

    val borderlessFieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Color.Transparent,
        focusedBorderColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedContainerColor = Color.Transparent
    )

    val titleFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Whether a finger is pressed on the form; see [shouldDismissKeyboardOnScroll].
    val isFingerDown = remember { mutableStateOf(false) }

    // Focus the title and raise the keyboard once, when a new blank event opens.
    // Skipped for edits, for events that open with a title (duplicate, share, Quick
    // Add), in the read-only view and on a load error. Waits for the load to finish
    // and for isHostSheetSettled; both are effect keys and in the guard, so the
    // one-shot isn't spent early. Clearing the title later never re-grabs focus.
    var didAutoFocusTitle by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading, isHostSheetSettled) {
        if (!state.isLoading && isHostSheetSettled && !didAutoFocusTitle) {
            didAutoFocusTitle = true
            if (!state.isEditMode && state.title.isBlank() && !isReadOnly && state.error == null) {
                runCatching { titleFocusRequester.requestFocus() }
                keyboardController?.show()
            }
        }
    }

    // A user swipe clears focus and lowers the keyboard so the fields below aren't
    // hidden behind it; [shouldDismissKeyboardOnScroll] tells a swipe from the scroll
    // the IME inset fires when a field gains focus.
    val dismissKeyboardOnUserScroll = remember(focusManager, keyboardController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (shouldDismissKeyboardOnScroll(source, isFingerDown.value)) {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
                return Offset.Zero
            }
        }
    }

    val performSave: () -> Unit = {
        val hasReminder = state.reminders.isNotEmpty()

        // Defer to the host's scope sheet when the host registered
        // onRequestRecurringSave, the form edits an existing event opened on one
        // occurrence, the loaded event was recurring ([wasRecurringAtLoad]), and
        // the form isn't the read-only attendee view (which saves reminders only).
        val deferToScopeSheet = onRequestRecurringSave != null &&
            !isReadOnly &&
            state.isEditMode &&
            state.editingOccurrenceTs != null &&
            wasRecurringAtLoad

        val doSave: () -> Unit = saveImpl@ {
            if (deferToScopeSheet) {
                // The form stays open so a Cancel from the scope sheet returns
                // to it. isSaving disables Save at once: a double tap would
                // otherwise stage two pendingFormSave snapshots before the sheet
                // renders.
                state = state.copy(isSaving = true, error = null)
                onRequestRecurringSave!!(
                    state,
                    state.editingOccurrenceTs!!,
                    initialRrule,
                    loadedMasterStartTs,
                    loadedIsDetachedException,
                    state.isDeviceCalendar,
                    loadedIsAllDay,
                )
                return@saveImpl
            }
            coroutineScope.launch {
                state = state.copy(isSaving = true, error = null)
                try {
                    // The read-only attendee view saves only its reminders, locally;
                    // a device calendar saves through onSaveDeviceEvent; anything
                    // else through onSave.
                    val result: Result<*> = when {
                        isReadOnly && onSaveAttendeeReminders != null ->
                            onSaveAttendeeReminders(state.reminders)
                        state.isDeviceCalendar && onSaveDeviceEvent != null ->
                            onSaveDeviceEvent(state)
                        else -> onSave(state)
                    }
                    result.fold(
                        onSuccess = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Error saving event", e)
                            state = state.copy(
                                isSaving = false,
                                error = "Failed to save: ${e.message}"
                            )
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving event", e)
                    state = state.copy(
                        isSaving = false,
                        error = "Failed to save: ${e.message}"
                    )
                }
            }
        }

        // With reminders, ask for the notification permission first; the save runs
        // whatever the answer.
        if (hasReminder && onRequestNotificationPermission != null) {
            onRequestNotificationPermission { _ ->
                doSave()
            }
        } else {
            doSave()
        }
    }

    // Seeds the form on first composition and when the event to edit changes.
    LaunchedEffect(eventId, deviceEventId) {
        // Read-only calendars (ICS subscriptions among them) can't take an event.
        val writableCalendars = calendars.filter { !it.isReadOnly }
        val writableGroups = calendarGroups.mapNotNull { group ->
            val writableCals = group.calendars.filter { !it.isReadOnly }
            if (writableCals.isNotEmpty()) group.copy(calendars = writableCals) else null
        }

        val resolvedCal =
            resolveDefaultCalendar(defaultCalendar, writableCalendars, deviceCalendarGroups)

        var newState = state.copy(
            calendarGroups = writableGroups,
            deviceCalendarGroups = deviceCalendarGroups,
            isLoading = false
        )

        if (deviceEventId != null && onLoadDeviceEvent != null) {
            val editData = onLoadDeviceEvent(deviceEventId)
            if (editData != null) {
                val mappedState = editData.event.toFormState(
                    reminders = editData.reminders,
                    calendarColor = editData.calendarColor,
                    calendarName = editData.calendarName,
                    deviceCalendarGroups = deviceCalendarGroups,
                    occurrenceTs = deviceOccurrenceTs
                )
                newState = mappedState.copy(
                    calendarGroups = writableGroups,
                    deviceCalendarGroups = deviceCalendarGroups,
                    editingOccurrenceTs = deviceOccurrenceTs
                )
                initialRrule = mappedState.rrule
                wasRecurringAtLoad = editData.event.rrule != null || editData.event.originalId != null
                loadedMasterStartTs = editData.event.startTs
                loadedIsDetachedException = editData.event.originalId != null
                loadedIsAllDay = editData.event.isAllDay
                deviceAttendees = editData.attendees
                deviceEventWritable = editData.isWritable
                // Seed the picker from the device event's guests, organizer
                // excluded (the repository owns that row), so a whole-event guest
                // edit diffs against the real set. attendeesEdited stays false
                // until the user changes it, so an open-and-save leaves the rows.
                newState = newState.copy(
                    attendees = org.onekash.kashcal.ui.viewmodels.deviceGuestsToPickerSeed(editData.attendees)
                )
            } else {
                // Null, for example when the event was deleted elsewhere: the form
                // shows the error and the user dismisses it.
                newState = newState.copy(
                    error = "Event no longer exists",
                    isLoading = false
                )
            }
        } else if (eventId != null && onLoadEvent != null) {
            val event = onLoadEvent(eventId)
            if (event != null) {
                val eventCalendar = calendars.find { it.id == event.calendarId }

                // Dates and clock times for the occurrence being edited (a
                // re-edited exception keeps its own start), in the event's timezone.
                val dateFields = event.toFormDateFields(occurrenceTs)

                val (parsedReminders, truncatedCount) = parseRemindersFromEvent(event.reminders, event.alarmCount)

                // Show the loaded event's rrule as stored: the master's rule for a
                // master opened on an occurrence, null for an exception row. A
                // "This event" save drops the rrule whatever state.rrule holds
                // (EventWriter.editSingleOccurrence); "This and following" and "All
                // events" carry the edited rule.
                newState = newState.withDateFields(dateFields).copy(
                    title = event.title,
                    selectedCalendarId = event.calendarId,
                    selectedCalendarName = eventCalendar?.localizedDisplayName(context.resources).orEmpty(),
                    selectedCalendarColor = eventCalendar?.color,
                    isAllDay = event.isAllDay,
                    // An ID the app can't resolve is shown as the device zone; the
                    // save keeps the stored one when no zone is picked.
                    timezone = event.timezone?.takeIf { TimezoneUtils.resolveZoneOrNull(it) != null },
                    location = event.location.orEmpty(),
                    description = event.description.orEmpty(),
                    rrule = event.rrule,
                    reminders = parsedReminders,
                    truncatedReminderCount = truncatedCount,
                    editingEventId = eventId,
                    isEditMode = true,
                    editingOccurrenceTs = occurrenceTs,
                    transp = event.transp,
                    eventColor = event.color,
                    categories = event.categories.orEmpty()
                )
                initialReminders = parsedReminders
                initialRrule = event.rrule
                wasRecurringAtLoad = event.rrule != null || event.originalEventId != null
                loadedMasterStartTs = event.startTs
                loadedIsDetachedException = event.originalEventId != null
                loadedIsAllDay = event.isAllDay
                loadedEvent = event
                // Seed the picker from the attendee rows so an edit keeps their
                // wire fields; attendeesEdited stays false until the user changes
                // the set.
                if (onLoadAttendees != null) {
                    val loaded = onLoadAttendees(eventId)
                    newState = newState.copy(attendees = loaded)
                    loadedAttendeeAddresses = loaded.map {
                        org.onekash.kashcal.util.AddressNormalizer.canonical(it.address)
                    }.toSet()
                }
            }
        } else {
            // Create: the end is the start plus the default duration, capped at 23:59.
            val currentStartHour = newState.startHour
            val currentStartMinute = newState.startMinute
            val endTotalMinutes = currentStartHour * 60 + currentStartMinute + defaultEventDuration
            val computedEndHour = (endTotalMinutes / 60).coerceAtMost(23)
            val computedEndMinute = if (endTotalMinutes >= 24 * 60) 59 else endTotalMinutes % 60

            newState = newState.copy(
                selectedCalendarId = resolvedCal.id,
                selectedCalendarName = resolvedCal.localizedName(writableCalendars, context.resources),
                selectedCalendarColor = resolvedCal.color,
                isDeviceCalendar = resolvedCal.isDevice,
                reminders = if (defaultReminderTimed == REMINDER_OFF) emptyList() else listOf(defaultReminderTimed),
                endHour = computedEndHour,
                endMinute = computedEndMinute
            )

            // A given start replaces the default start, at the top of its hour.
            if (initialStartTs != null) {
                val calendar = JavaCalendar.getInstance()
                calendar.timeInMillis = initialStartTs
                val startHour = calendar.get(JavaCalendar.HOUR_OF_DAY)
                val endMinutes = (0 + defaultEventDuration) % 60
                val endHour = startHour + (0 + defaultEventDuration) / 60
                newState = newState.copy(
                    dateMillis = calendar.timeInMillis,
                    endDateMillis = calendar.timeInMillis,
                    startHour = startHour,
                    startMinute = 0,
                    endHour = if (endHour > 23) 23 else endHour,
                    endMinute = if (endHour > 23) 59 else endMinutes
                )
            }

            if (duplicateFrom != null) {
                // All-day events store UTC midnights; the form holds device-local dates.
                val displayStartTs = if (duplicateFrom.isAllDay) {
                    DateTimeUtils.utcMidnightToLocalDate(duplicateFrom.startTs)
                } else {
                    duplicateFrom.startTs
                }
                val displayEndTs = if (duplicateFrom.isAllDay) {
                    DateTimeUtils.utcMidnightToLocalDate(duplicateFrom.endTs)
                } else {
                    duplicateFrom.endTs
                }

                val startCal = JavaCalendar.getInstance().apply { timeInMillis = displayStartTs }
                val endCal = JavaCalendar.getInstance().apply { timeInMillis = displayEndTs }

                // A duplicate keeps no truncation notice.
                val (dupReminders, _) = parseRemindersFromEvent(duplicateFrom.reminders, duplicateFrom.alarmCount)

                // Keep the source calendar (Room or device); fall back to the
                // resolved default only if it's gone or not writable.
                val sourceCal = resolveDuplicateSourceCalendar(
                    duplicateFrom = duplicateFrom,
                    duplicateFromDeviceCalendarId = duplicateFromDeviceCalendarId,
                    writableCalendars = writableCalendars,
                    deviceCalendarGroups = deviceCalendarGroups,
                    resolvedDefault = resolvedCal
                )
                val sourceCalId = sourceCal.id
                val sourceCalName = sourceCal.localizedName(writableCalendars, context.resources)
                val sourceCalColor = sourceCal.color

                newState = newState.copy(
                    title = duplicateFrom.title,
                    location = duplicateFrom.location.orEmpty(),
                    description = duplicateFrom.description.orEmpty(),
                    isAllDay = duplicateFrom.isAllDay,
                    dateMillis = displayStartTs,
                    endDateMillis = displayEndTs,
                    startHour = startCal.get(JavaCalendar.HOUR_OF_DAY),
                    startMinute = startCal.get(JavaCalendar.MINUTE),
                    endHour = endCal.get(JavaCalendar.HOUR_OF_DAY),
                    endMinute = endCal.get(JavaCalendar.MINUTE),
                    selectedCalendarId = sourceCalId,
                    selectedCalendarName = sourceCalName,
                    selectedCalendarColor = sourceCalColor,
                    isDeviceCalendar = sourceCal.isDevice,
                    reminders = dupReminders,
                    rrule = null,  // A duplicate is a one-off event.
                    transp = duplicateFrom.transp,
                    eventColor = duplicateFrom.color,
                    categories = duplicateFrom.categories.orEmpty()
                )
            }

            // Pre-fill from another app's calendar intent.
            if (calendarIntentData != null && eventId == null) {
                val startTs = calendarIntentData.startTimeMillis ?: run {
                    // No parsed time: the next full hour.
                    val now = JavaCalendar.getInstance()
                    val nextHour = (now.get(JavaCalendar.HOUR_OF_DAY) + 1) % 24
                    JavaCalendar.getInstance().apply {
                        set(JavaCalendar.HOUR_OF_DAY, nextHour)
                        set(JavaCalendar.MINUTE, 0)
                        set(JavaCalendar.SECOND, 0)
                        set(JavaCalendar.MILLISECOND, 0)
                    }.timeInMillis
                }
                val endTs = calendarIntentData.endTimeMillis
                    ?: (startTs + defaultEventDuration * 60 * 1000L)

                val displayStartTs = if (calendarIntentData.isAllDay) {
                    DateTimeUtils.utcMidnightToLocalDate(startTs)
                } else {
                    startTs
                }
                val displayEndTs = if (calendarIntentData.isAllDay) {
                    DateTimeUtils.utcMidnightToLocalDate(endTs)
                } else {
                    endTs
                }

                val startCal = JavaCalendar.getInstance().apply { timeInMillis = displayStartTs }
                val endCal = JavaCalendar.getInstance().apply { timeInMillis = displayEndTs }

                // The intent's invitees go into the description.
                val fullDescription = calendarIntentData.getDescriptionWithInvitees(calendarIntentInvitees)

                newState = newState.copy(
                    title = calendarIntentData.title.orEmpty(),
                    location = calendarIntentData.location.orEmpty(),
                    description = fullDescription,
                    isAllDay = calendarIntentData.isAllDay,
                    dateMillis = displayStartTs,
                    endDateMillis = displayEndTs,
                    startHour = startCal.get(JavaCalendar.HOUR_OF_DAY),
                    startMinute = startCal.get(JavaCalendar.MINUTE),
                    endHour = endCal.get(JavaCalendar.HOUR_OF_DAY),
                    endMinute = endCal.get(JavaCalendar.MINUTE),
                    rrule = calendarIntentData.rrule,
                    categories = calendarIntentData.categories
                )
            }
        }

        state = newState
        initialFormState = newState
    }

    // Re-enables Save after a deferred save fails or the user cancels the scope
    // sheet; without it the form stays locked.
    LaunchedEffect(scopeSaveFailedTick) {
        if (state.isSaving && scopeSaveFailedTick > 0) {
            state = state.copy(isSaving = false)
        }
    }

    // The load effect above reads the calendars at first composition, when they may
    // not have loaded yet on a cold start. This one updates the calendar state as the
    // lists arrive.
    LaunchedEffect(calendars, calendarGroups, deviceCalendarGroups) {
        val writableCalendars = calendars.filter { !it.isReadOnly }
        val writableGroups = calendarGroups.mapNotNull { group ->
            val writableCals = group.calendars.filter { !it.isReadOnly }
            if (writableCals.isNotEmpty()) group.copy(calendars = writableCals) else null
        }

        // Nothing changed: skip the state update (the lists re-emit during sync).
        if (writableGroups == state.calendarGroups &&
            deviceCalendarGroups == state.deviceCalendarGroups) return@LaunchedEffect

        state = state.copy(
            calendarGroups = writableGroups,
            deviceCalendarGroups = deviceCalendarGroups
        )

        // No calendar selected yet (a create whose lists were empty at load):
        // resolve the default now.
        if (state.selectedCalendarId == null && writableCalendars.isNotEmpty()) {
            val resolved = resolveDefaultCalendar(defaultCalendar, writableCalendars, deviceCalendarGroups)
            state = state.copy(
                selectedCalendarId = resolved.id,
                selectedCalendarName = resolved.localizedName(writableCalendars, context.resources),
                selectedCalendarColor = resolved.color,
                isDeviceCalendar = resolved.isDevice
            )
            // An async resolution, not a user action, so it joins the baseline. An
            // edit the user made in this sub-second cold-start window joins it too.
            if (shouldRebaselineOnCalendarResolve(initialFormState)) {
                initialFormState = state
            }
        }

        // An edit loaded before the calendars: the load set selectedCalendarId from
        // the event but found no name or color.
        if (state.selectedCalendarId != null &&
            state.selectedCalendarName.isEmpty() &&
            writableCalendars.isNotEmpty()) {
            val cal = calendars.find { it.id == state.selectedCalendarId }
            if (cal != null) {
                state = state.copy(
                    selectedCalendarName = cal.localizedDisplayName(context.resources),
                    selectedCalendarColor = cal.color
                )
            }
        }

        // A device duplicate that fell back to the Room default because the device
        // groups hadn't loaded: re-resolve to the source device calendar once they
        // arrive. Only a resolvable source (resolved.isDevice) replaces the fallback;
        // a gone or read-only one keeps it. The !isDeviceCalendar guard makes this
        // fire at most once.
        if (duplicateFrom != null &&
            duplicateFromDeviceCalendarId != null &&
            !state.isDeviceCalendar &&
            deviceCalendarGroups.isNotEmpty() &&
            writableCalendars.isNotEmpty()) {
            val resolvedDefault = resolveDefaultCalendar(defaultCalendar, writableCalendars, deviceCalendarGroups)
            val resolved = resolveDuplicateSourceCalendar(
                duplicateFrom = duplicateFrom,
                duplicateFromDeviceCalendarId = duplicateFromDeviceCalendarId,
                writableCalendars = writableCalendars,
                deviceCalendarGroups = deviceCalendarGroups,
                resolvedDefault = resolvedDefault
            )
            if (resolved.isDevice) {
                state = state.copy(
                    selectedCalendarId = resolved.id,
                    selectedCalendarName = resolved.localizedName(writableCalendars, context.resources),
                    selectedCalendarColor = resolved.color,
                    isDeviceCalendar = true
                )
                // Always re-baseline: the baseline already holds the Room fallback id,
                // so [shouldRebaselineOnCalendarResolve] would say no, and the switch
                // would read as an unsaved edit and ask to discard an untouched form.
                initialFormState = state
            }
        }
    }

    val hasTimeConflict by remember {
        derivedStateOf { state.endsBeforeStart() }
    }

    // Saving a scheduling-significant change on an event with attendees sends them
    // an update, so Save reads "Save & notify" before the tap. The decision is
    // [org.onekash.kashcal.domain.scheduling.shouldNotifyAttendees], which uses
    // SequenceBumper.shouldBump, so the candidate must carry every field that reads.
    //
    // The count is the set that will be saved: state.attendees once the user edited
    // it, else the loaded display list, so adding the first guest and moving the time
    // in one session relabels Save.
    val notifyAttendeeCount = if (state.attendeesEdited) state.attendees.size else attendees.size
    // Guests removed from the loaded set this session, by canonical address; each
    // gets a CANCEL on save.
    val removedAttendeeCount = remember(state.attendees, state.attendeesEdited, loadedAttendeeAddresses) {
        if (!state.attendeesEdited) 0 else {
            val current = state.attendees.map {
                org.onekash.kashcal.util.AddressNormalizer.canonical(it.address)
            }.toSet()
            loadedAttendeeAddresses.count { it !in current }
        }
    }
    val willNotifyAttendees by remember(state, loadedEvent, notifyAttendeeCount, removedAttendeeCount) {
        derivedStateOf {
            val original = loadedEvent ?: return@derivedStateOf false
            val (candStart, candEnd) = state.toStartEndTs()
            val candidate = original.copy(
                // The same ifBlank normalization as HomeViewModel's save, so the
                // prediction matches what is written.
                title = state.title.ifBlank { "Untitled" },
                location = state.location.ifBlank { null },
                startTs = candStart,
                endTs = candEnd,
                isAllDay = state.isAllDay,
                rrule = state.rrule,
                status = original.status,
            )
            org.onekash.kashcal.domain.scheduling.shouldNotifyAttendees(
                old = original,
                new = candidate,
                attendeeCount = notifyAttendeeCount,
                // An add sends the new guest a REQUEST and a removal sends the
                // dropped guest a CANCEL. attendeesEdited is the flag the save uses
                // to pass the set; a removal notifies even with no guests left.
                attendeeSetChanged = state.attendeesEdited,
                attendeeRemoved = removedAttendeeCount > 0,
            )
        }
    }

    // The read-only attendee view edits only reminders, so Save needs a changed
    // reminder set; otherwise it needs a title and an end not before the start. Both
    // need no save in progress. Shared by the header and the sticky bottom Save.
    val saveEnabled = if (isReadOnly) {
        onSaveAttendeeReminders != null &&
            remindersChanged(initialReminders, state.reminders) &&
            !state.isSaving
    } else {
        state.title.isNotBlank() && !state.isSaving && !hasTimeConflict
    }

    // Reports the save flag to the wrapper's dismiss guard. SideEffect, not
    // LaunchedEffect, updates it at commit, before any later input frame, so a
    // dismiss tap after a save starts never sees a value a coroutine dispatch behind.
    SideEffect { onSavingChange(state.isSaving) }

    // Reported to the dismiss guard at commit, the same way as isSaving.
    val hasUnsavedChanges by remember {
        derivedStateOf {
            initialFormState?.let { eventFormHasUnsavedChanges(it, state) } ?: false
        }
    }
    SideEffect { onHasChangesChange(hasUnsavedChanges) }

    // Back runs the same dismiss guard as Cancel. This content sits in the form's
    // own dialog window, whose built-in back dismissal is off.
    BackHandler(onBack = onRequestDismiss)

    val paneTitleText = if (state.isEditMode) {
        stringResource(R.string.dialog_edit_event_title)
    } else {
        stringResource(R.string.dialog_new_event_title)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Announce the form (New event / Edit event) when the sheet opens.
            .semantics { paneTitle = paneTitleText }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dirty-form dismissal is a two-tap confirm: the plain Cancel text
            // button turns into a tonal error-container box reading "Discard?" so
            // the state change reads as a question to answer, not a subtle recolor.
            if (showDiscardConfirm) {
                Button(
                    onClick = onRequestDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_discard_confirm),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else {
                TextButton(onClick = onRequestDismiss) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Text(
                text = if (state.isEditMode) stringResource(R.string.dialog_edit_event_title) else stringResource(R.string.dialog_new_event_title),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val primaryColor = MaterialTheme.colorScheme.primary
            val calColor = remember(state.selectedCalendarColor, primaryColor) {
                state.selectedCalendarColor?.let { Color(it) } ?: primaryColor
            }
            val contrastColor = remember(calColor) { contrastForegroundOn(calColor) }
            Button(
                onClick = { performSave() },
                enabled = saveEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = calColor,
                    contentColor = contrastColor
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = contrastColor
                    )
                } else {
                    Text(
                        text = if (willNotifyAttendees) {
                            stringResource(R.string.action_save_and_notify)
                        } else {
                            stringResource(R.string.action_save)
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        HorizontalDivider()

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // Observes pointer presses without consuming them, for the
                    // dismiss-on-scroll check.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                isFingerDown.value = event.changes.any { it.pressed }
                            }
                        }
                    }
                    .nestedScroll(dismissKeyboardOnUserScroll)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
            ) {
                // The title once loaded, so an edit doesn't flash a dropdown before
                // the user types.
                var titleInitial by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(state.isLoading, state.title) {
                    if (!state.isLoading && titleInitial == null) {
                        titleInitial = state.title
                    }
                }
                var titleSuggestions by remember { mutableStateOf<List<org.onekash.kashcal.data.db.dao.TitleSuggestion>>(emptyList()) }
                var titleSearchJob by remember { mutableStateOf<Job?>(null) }

                // Inline "#tag" autocomplete: the in-progress "#prefix" fragment
                // at the end of the title (null when the user isn't typing a tag).
                var tagPrefix by remember { mutableStateOf<String?>(null) }
                val tagMatches = remember(tagPrefix, categorySuggestions, state.categories) {
                    val prefix = tagPrefix ?: return@remember emptyList()
                    categorySuggestions
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                        .filter { s -> state.categories.none { it.equals(s, ignoreCase = true) } }
                }

                val dropdownOpen = titleSuggestions.isNotEmpty() || tagPrefix != null

                if (isReadOnly) {
                    // Attendee viewer: plain readable text, not a dimmed,
                    // clip-prone disabled field with autocomplete chrome.
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                ExposedDropdownMenuBox(
                    expanded = dropdownOpen,
                    onExpandedChange = { if (!it) titleSuggestions = emptyList() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { raw ->
                            // Without singleLine the Enter key inserts a
                            // newline; strip it so a title stays one logical
                            // line and never needs escaping on the wire.
                            val newValue = raw.replace("\n", "")
                            state = state.copy(title = newValue)
                            titleSearchJob?.cancel()
                            // If the user is mid-#tag, show tag autocomplete and
                            // suppress the title-suggestion query for this keystroke.
                            tagPrefix = org.onekash.kashcal.domain.category.TagTokenizer
                                .trailingHashPrefix(newValue)
                            if (tagPrefix != null) {
                                titleSuggestions = emptyList()
                            } else {
                                val shouldQuery = shouldShowTitleSuggestions(
                                    currentText = newValue,
                                    initialText = titleInitial.orEmpty()
                                )
                                if (shouldQuery && onSuggestTitles != null) {
                                    titleSearchJob = coroutineScope.launch {
                                        delay(TITLE_SUGGEST_DEBOUNCE_MS)
                                        titleSuggestions = onSuggestTitles(newValue)
                                    }
                                } else {
                                    titleSuggestions = emptyList()
                                }
                            }
                        },
                        placeholder = { Text(stringResource(R.string.label_event_title), style = MaterialTheme.typography.headlineSmall) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TAG_TITLE_FIELD)
                            .focusRequester(titleFocusRequester)
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                            // Horizontal only: the field's box has vertical
                            // padding, and more would make the title row
                            // taller than location.
                            .padding(horizontal = 16.dp),
                        // Wraps a long title to a second line, like the
                        // quick-view title. There is no title length cap.
                        maxLines = 2,
                        enabled = !isReadOnly,
                        textStyle = MaterialTheme.typography.headlineSmall,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                        colors = borderlessFieldColors
                    )

                    ExposedDropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = {
                            titleSuggestions = emptyList()
                            tagPrefix = null
                        }
                    ) {
                        // Commit a #tag: add it to categories and strip the
                        // in-progress "#prefix" token from the title.
                        val commitTag: (String) -> Unit = { name ->
                            when (val outcome = org.onekash.kashcal.domain.category
                                .CategoryNameValidator.validate(name, state.categories.toSet())) {
                                is org.onekash.kashcal.domain.category.CategoryName.Valid -> {
                                    val stripped = org.onekash.kashcal.domain.category.TagTokenizer
                                        .stripToken(state.title, "#${tagPrefix.orEmpty()}")
                                    val nextCategories =
                                        if (state.categories.any { it.equals(outcome.value, ignoreCase = true) }) {
                                            state.categories
                                        } else {
                                            state.categories + outcome.value
                                        }
                                    state = state.copy(title = stripped, categories = nextCategories, categoriesEdited = true)
                                }
                                is org.onekash.kashcal.domain.category.CategoryName.Invalid -> Unit
                            }
                            tagPrefix = null
                        }

                        if (tagPrefix != null) {
                            tagMatches.forEach { match ->
                                DropdownMenuItem(
                                    text = { Text("#$match") },
                                    onClick = { commitTag(match) },
                                    modifier = Modifier.height(48.dp)
                                )
                            }
                            val prefix = tagPrefix.orEmpty()
                            val exactExists = tagMatches.any { it.equals(prefix, ignoreCase = true) }
                            if (prefix.isNotBlank() && !exactExists) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tags_autocomplete_create_new, prefix)) },
                                    onClick = { commitTag(prefix) },
                                    modifier = Modifier.height(48.dp)
                                )
                            }
                        } else {
                            titleSuggestions.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = { Text(suggestion.title) },
                                    onClick = {
                                        state = state.copy(title = suggestion.title)
                                        titleSuggestions = emptyList()
                                    },
                                    modifier = Modifier.height(48.dp)
                                )
                            }
                        }
                    }
                }
                }

                // Location sits under the title, above the date and time.
                var locationExpanded by remember { mutableStateOf(false) }
                var locationSuggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
                var isLoadingLocationSuggestions by remember { mutableStateOf(false) }
                var locationSearchJob by remember { mutableStateOf<Job?>(null) }

                if (shouldShowReadOnlyOptionalField(state.location, isReadOnly)) {
                EventFormRow(
                    icon = Icons.Default.LocationOn,
                    iconContentDescription = stringResource(R.string.label_location)
                ) {
                    if (isReadOnly) {
                        Text(
                            text = state.location,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                    ExposedDropdownMenuBox(
                        expanded = locationExpanded && locationSuggestions.isNotEmpty(),
                        onExpandedChange = { locationExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = state.location,
                            onValueChange = { raw ->
                                // A location is one logical line; without
                                // singleLine the Enter key inserts a newline.
                                val newValue = raw.replace("\n", "")
                                state = state.copy(location = newValue)
                                locationSearchJob?.cancel()
                                if (locationSuggestionService != null &&
                                    newValue.length >= 5 &&
                                    newValue.any { it.isLetter() }
                                ) {
                                    locationSearchJob = coroutineScope.launch {
                                        delay(300)
                                        isLoadingLocationSuggestions = true
                                        locationSuggestions = locationSuggestionService.getSuggestions(newValue)
                                        isLoadingLocationSuggestions = false
                                        locationExpanded = locationSuggestions.isNotEmpty()
                                    }
                                } else {
                                    locationSuggestions = emptyList()
                                    locationExpanded = false
                                }
                            },
                            placeholder = { Text(stringResource(R.string.label_location_hint)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                            // Wraps a long address to a second line.
                            maxLines = 2,
                            enabled = !isReadOnly,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent
                            ),
                            trailingIcon = {
                                if (isLoadingLocationSuggestions) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else if (state.location.isNotEmpty() && locationSuggestionService != null) {
                                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                                }
                            }
                        )

                        ExposedDropdownMenu(
                            expanded = locationExpanded && locationSuggestions.isNotEmpty(),
                            onDismissRequest = { locationExpanded = false }
                        ) {
                            locationSuggestions.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = { Text(suggestion.displayName) },
                                    onClick = {
                                        state = state.copy(location = suggestion.displayName)
                                        locationExpanded = false
                                        locationSuggestions = emptyList()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Place, contentDescription = null)
                                    },
                                    modifier = Modifier.height(48.dp)
                                )
                            }
                        }
                    }
                    }
                }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = SECTION_DIVIDER_SPACING))


                val toggleAllDay = { newIsAllDay: Boolean ->
                    state = state.withAllDay(newIsAllDay, defaultReminderTimed, defaultReminderAllDay)
                }

                DateTimeDisplayRow(
                    startDateMillis = state.dateMillis,
                    startHour = state.startHour,
                    startMinute = state.startMinute,
                    endDateMillis = state.endDateMillis,
                    endHour = state.endHour,
                    endMinute = state.endMinute,
                    isAllDay = state.isAllDay,
                    onAllDayToggle = if (isReadOnly) ({ }) else toggleAllDay,
                    onStartClick = { if (!isReadOnly) activeSheet = ActiveDateTimeSheet.START },
                    onEndClick = { if (!isReadOnly) activeSheet = ActiveDateTimeSheet.END },
                    isEndError = hasTimeConflict,
                    endErrorMessage = if (hasTimeConflict) stringResource(R.string.error_end_before_start) else null,
                    timezone = state.timezone,
                    timePattern = timePattern
                )

                if (!state.isAllDay) {
                    var showTimezoneSheet by remember { mutableStateOf(false) }
                    val tzId = state.timezone
                    val effectiveTzId = tzId ?: remember { TimezoneUtils.getDeviceTimezone() }
                    val tzAbbrev = remember(effectiveTzId) { TimezoneUtils.getAbbreviation(effectiveTzId) }
                    val tzDisplayName = remember(effectiveTzId) {
                        TimezoneUtils.getTimezoneInfo(effectiveTzId)?.displayName
                            ?: effectiveTzId.substringAfterLast('/').replace('_', ' ')
                    }
                    val timezoneLabel = "$tzDisplayName ($tzAbbrev)"

                    EventFormRow(
                        icon = Icons.Default.Public,
                        iconContentDescription = stringResource(R.string.label_timezone),
                        showExpandIcon = true,
                        onToggle = { if (!isReadOnly) showTimezoneSheet = true }
                    ) {
                        Text(
                            timezoneLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (tzId == null)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (showTimezoneSheet) {
                        TimezonePickerSheet(
                            selectedTimezone = state.timezone,
                            onTimezoneSelected = { newTimezone ->
                                state = state.withTimezone(newTimezone)
                                showTimezoneSheet = false
                            },
                            onDismiss = { showTimezoneSheet = false }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = SECTION_DIVIDER_SPACING))

                CalendarPickerRow(
                    selectedCalendarId = state.selectedCalendarId,
                    selectedCalendarName = state.selectedCalendarName,
                    selectedCalendarColor = state.selectedCalendarColor,
                    calendarGroups = if (state.isEditMode && state.isDeviceCalendar) {
                        emptyList()
                    } else {
                        state.calendarGroups
                    },
                    deviceCalendarGroups = if (state.isEditMode && !state.isDeviceCalendar) {
                        emptyList()
                    } else {
                        state.deviceCalendarGroups
                    },
                    isSelectedDeviceCalendar = state.isDeviceCalendar,
                    isExpanded = expandedPicker == "calendar",
                    // Disabled in the read-only view, for an edit opened on one
                    // occurrence, and for a recurring device event: Android treats
                    // CALENDAR_ID as fixed at creation, so a device move is a create
                    // and delete that only non-recurring events support (a pick
                    // would do nothing). A Room series moves and stays enabled.
                    //
                    // Also disabled for a Room event with attendees: a move to
                    // another account would carry the source account's ORGANIZER
                    // and misdeliver invitations, which the domain layer rejects.
                    // Without per-option disabling the picker can't offer only
                    // same-account targets; the user can duplicate the event onto
                    // the other account instead. A device event with guests moves
                    // through the device path, which drops the source organizer.
                    enabled = !isReadOnly &&
                        !(state.isEditMode && state.editingOccurrenceTs != null) &&
                        !(state.isEditMode && state.isDeviceCalendar && state.rrule != null) &&
                        !(state.isEditMode && !state.isDeviceCalendar && state.attendees.isNotEmpty()),
                    onToggle = { expandedPicker = if (expandedPicker == "calendar") null else "calendar" },
                    onSelect = { id, name, color, isDevice ->
                        state = state.copy(
                            selectedCalendarId = id,
                            selectedCalendarName = name,
                            selectedCalendarColor = color,
                            isDeviceCalendar = isDevice
                        )
                        // Re-resolves the attendee context for the new
                        // calendar's account.
                        onCalendarSelected?.invoke(id)
                        expandedPicker = null
                    }
                )

                EventFormRow(
                    icon = Icons.Default.Palette,
                    iconContentDescription = stringResource(R.string.label_event_color),
                    onToggle = { if (!isReadOnly) showColorPicker = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        val fallbackArgb = MaterialTheme.colorScheme.primary.toArgb()
                        val dotColor = state.eventColor
                            ?: state.selectedCalendarColor
                            ?: fallbackArgb
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(dotColor))
                        )
                        Text(
                            stringResource(
                                if (state.eventColor == null) R.string.label_event_color
                                else EventColorPalette.stringResIdForColor(state.eventColor)
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ReminderPickerRow(
                    reminders = state.reminders,
                    isAllDay = state.isAllDay,
                    use24Hour = use24Hour,
                    isExpanded = expandedPicker == "reminders",
                    onToggle = { expandedPicker = if (expandedPicker == "reminders") null else "reminders" },
                    onRemindersChange = { newReminders ->
                        state = state.copy(reminders = newReminders)
                    },
                    truncatedReminderCount = state.truncatedReminderCount
                )

                RecurrencePickerRow(
                    selectedRrule = state.rrule,
                    startDateMillis = state.dateMillis,
                    isExpanded = expandedPicker == "repeat",
                    onToggle = { if (!isReadOnly) expandedPicker = if (expandedPicker == "repeat") null else "repeat" },
                    onSelect = { rrule ->
                        state = state.copy(rrule = rrule)
                    },
                    firstDayOfWeek = firstDayOfWeek,
                    isAllDay = state.isAllDay,
                    timezone = state.timezone,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = SECTION_DIVIDER_SPACING))

                // The tag row sits above or below notes, a saved preference the user
                // flips from its ⋮ menu. Defined once, rendered in the chosen position.
                var showTagsMenu by remember { mutableStateOf(false) }
                val tagsRow: @Composable () -> Unit = {
                    EventFormRow(
                        icon = Icons.Default.LocalOffer,
                        iconContentDescription = stringResource(R.string.label_categories),
                        // Top-aligned so the icon and the ⋮ stay by the chip
                        // line when the picker's field and suggestion list open.
                        // The offset centers the icon on the resting chip row.
                        verticalAlignment = Alignment.Top,
                        iconTopPadding = 6.dp,
                    ) {
                        org.onekash.kashcal.ui.components.category.TagChipRow(
                            selected = state.categories.toSet(),
                            suggestions = categorySuggestions,
                            onToggle = { tag ->
                                val current = state.categories
                                state = if (current.any { it.equals(tag, ignoreCase = true) }) {
                                    state.copy(categories = current.filterNot { it.equals(tag, ignoreCase = true) }, categoriesEdited = true)
                                } else {
                                    state.copy(categories = current + tag, categoriesEdited = true)
                                }
                            },
                            onAdd = { tag ->
                                if (state.categories.none { it.equals(tag, ignoreCase = true) }) {
                                    state = state.copy(categories = state.categories + tag, categoriesEdited = true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (onSetTagsAboveNotes != null) {
                            // Matches the left icon (24dp glyph, 6dp top offset)
                            // so the ⋮ sits on the chip line, not low in a 48dp
                            // button box.
                            Box(modifier = Modifier.padding(top = 6.dp)) {
                                CompositionLocalProvider(
                                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                                ) {
                                IconButton(
                                    onClick = { showTagsMenu = true },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.cd_tags_move_menu),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                }
                                DropdownMenu(
                                    expanded = showTagsMenu,
                                    onDismissRequest = { showTagsMenu = false },
                                ) {
                                    // The current position's item is disabled.
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tags_move_above_notes)) },
                                        enabled = !tagsAboveNotes,
                                        onClick = {
                                            onSetTagsAboveNotes(true)
                                            showTagsMenu = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tags_move_below_notes)) },
                                        enabled = tagsAboveNotes,
                                        onClick = {
                                            onSetTagsAboveNotes(false)
                                            showTagsMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isReadOnly && tagsAboveNotes) {
                    tagsRow()
                }

                if (shouldShowReadOnlyOptionalField(state.description, isReadOnly)) {
                EventFormRow(
                    icon = Icons.AutoMirrored.Filled.Notes,
                    iconContentDescription = stringResource(R.string.label_notes),
                    // Multi-line field: the icon is top-aligned and offset by the
                    // field's top padding so it meets the first line of text.
                    verticalAlignment = Alignment.Top,
                    iconTopPadding = 16.dp,
                ) {
                    if (isReadOnly) {
                        Text(
                            text = state.description,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = { state = state.copy(description = it) },
                        placeholder = { Text(stringResource(R.string.label_notes)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        enabled = !isReadOnly,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent
                        )
                    )
                    }
                }
                }

                // Default position: below notes, keeping notes and tags together
                // above attendees and free/busy.
                if (!isReadOnly && !tagsAboveNotes) {
                    tagsRow()
                }

                // Separates notes and tags from attendees and free/busy. Drawn only
                // when that group rendered something: in the read-only view with
                // blank notes it is empty, and the divider would stack against the
                // section divider above.
                if (!isReadOnly || state.description.isNotBlank()) {
                    HorizontalDivider(
                        modifier = Modifier
                            .padding(vertical = SECTION_DIVIDER_SPACING)
                            .testTag(TAG_GROUP_DIVIDER)
                    )
                }

                val isDeviceEvent = deviceEventId != null
                // A device event's guest list is editable only on a whole-event
                // edit on a writable calendar. An edit opened on an occurrence or
                // an exception stays read-only: the provider doesn't store
                // per-occurrence guest divergence.
                val isDeviceOccurrenceEdit =
                    state.editingOccurrenceTs != null || loadedIsDetachedException
                val canEditDeviceAttendees = isDeviceEvent &&
                    deviceEventWritable &&
                    !isDeviceOccurrenceEdit &&
                    onQueryContacts != null
                // Whether the selected device calendar delivers invitations; true
                // when no device calendar has the selected id. Drives the
                // local-account notice.
                val deviceCanDeliverInvites = deviceCalendarGroups
                    .asSequence()
                    .flatMap { it.pickerCalendars.asSequence() }
                    .filterIsInstance<PickerCalendar.Device>()
                    .firstOrNull { it.calendar.id == state.selectedCalendarId }
                    ?.calendar
                    ?.canDeliverInvites
                    ?: true
                // The "no invitation sent" notice, for guests on a device calendar
                // that can't deliver. Shared by both editable branches: an existing
                // device event and a new event on a device calendar.
                val showDeviceLocalNotice = (isDeviceEvent || state.isDeviceCalendar) &&
                    !deviceCanDeliverInvites &&
                    state.attendees.isNotEmpty() &&
                    !deviceNoticeDismissed
                val canEditAttendees = !isDeviceEvent && canEditAttendees(
                    isReadOnly = isReadOnly,
                    isSchedulable = isSchedulable,
                    hasContactQuery = onQueryContacts != null,
                )
                val showSchedulingUnavailable = !isDeviceEvent && showSchedulingUnavailable(
                    isReadOnly = isReadOnly,
                    isSchedulable = isSchedulable,
                    hasContactQuery = onQueryContacts != null,
                    isEditMode = state.isEditMode,
                    wasRecurringAtLoad = wasRecurringAtLoad,
                )
                if (canEditDeviceAttendees) {
                    // The picker edits state.attendees (Room entities), and
                    // saveDeviceEvent turns them into provider rows. A local
                    // calendar still allows editing, with the notice.
                    EventFormRow(
                        icon = Icons.Default.Group,
                        iconContentDescription = stringResource(R.string.label_attendees)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            EditableAttendeesRow(
                                attendees = state.attendees,
                                account = attendeeAccount,
                                onClick = { showAttendeePicker = true },
                            )
                            if (showDeviceLocalNotice) {
                                DeviceLocalNoDeliveryNotice(onDismiss = { deviceNoticeDismissed = true })
                            }
                        }
                    }
                } else if (isDeviceEvent && deviceAttendees.isNotEmpty()) {
                    // Read-only device guests (an occurrence edit, a read-only
                    // calendar, or no contact lookup), shown as in the quick view.
                    EventFormRow(
                        icon = Icons.Default.Group,
                        iconContentDescription = stringResource(R.string.label_attendees)
                    ) {
                        org.onekash.kashcal.ui.components.attendees.InviteesBlock(
                            attendees = deviceAttendees,
                            isCurrentUserOnList = deviceAttendees.any { it.isYou },
                            isCurrentUserOrganizer = deviceAttendees.any { it.isYou && it.isOrganizer },
                            onRsvp = {},
                            onDrillIntoAttendees = { showAttendeeSheet = true },
                            suppressRsvp = true,
                            alwaysExpanded = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (canEditAttendees) {
                    EventFormRow(
                        icon = Icons.Default.Group,
                        iconContentDescription = stringResource(R.string.label_attendees)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            EditableAttendeesRow(
                                attendees = state.attendees,
                                account = attendeeAccount,
                                onClick = { showAttendeePicker = true },
                            )
                            // A new event on a local device calendar: nothing
                            // is delivered.
                            if (showDeviceLocalNotice) {
                                DeviceLocalNoDeliveryNotice(onDismiss = { deviceNoticeDismissed = true })
                            }
                        }
                    }
                } else if (showSchedulingUnavailable) {
                    EventFormRow(
                        icon = Icons.Default.Group,
                        iconContentDescription = stringResource(R.string.label_attendees)
                    ) {
                        Text(
                            text = stringResource(R.string.attendee_scheduling_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (attendees.isNotEmpty()) {
                    EventFormRow(
                        icon = Icons.Default.Group,
                        iconContentDescription = stringResource(R.string.label_attendees)
                    ) {
                        val you = attendees.firstOrNull { it.isYou }
                        // An RSVP changes only the loaded row: series-wide on a
                        // master (state.rrule != null), one occurrence on a
                        // detached exception, where the series disclosure would
                        // be false.
                        val rsvpAppliesToSeries =
                            state.rrule != null && !loadedIsDetachedException
                        val seriesDisclosure = if (
                            isReadOnly &&
                            org.onekash.kashcal.ui.components.attendees.shouldShowSeriesRsvpDisclosure(
                                currentUserPartstat = you?.status,
                                isOrganizer = you?.isOrganizer == true,
                                isRecurring = rsvpAppliesToSeries,
                            )
                        ) stringResource(R.string.rsvp_series_disclosure) else null

                        // The editable form hides the RSVP cards, but doesn't
                        // label the user as organizer: on a delegated calendar
                        // they may be an attendee, and the flag shapes the
                        // summary line.
                        val suppressRsvp = !isReadOnly
                        val actualIsOrganizer = you?.isOrganizer == true

                        org.onekash.kashcal.ui.components.attendees.InviteesBlock(
                            attendees = attendees,
                            isCurrentUserOnList = isCurrentUserOnList,
                            isCurrentUserOrganizer = actualIsOrganizer,
                            onRsvp = onRsvp,
                            onDrillIntoAttendees = { showAttendeeSheet = true },
                            suppressRsvp = suppressRsvp,
                            seriesDisclosure = seriesDisclosure,
                            alwaysExpanded = isReadOnly,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (showAttendeeSheet) {
                    org.onekash.kashcal.ui.components.attendees.AttendeeListSheet(
                        attendees = if (deviceEventId != null) deviceAttendees else attendees,
                        onDismiss = { showAttendeeSheet = false },
                    )
                }

                if (showAttendeePicker && onQueryContacts != null) {
                    org.onekash.kashcal.ui.components.attendees.AttendeePickerSheet(
                        seed = state.attendees,
                        account = attendeeAccount,
                        permissionState = contactsPermissionState,
                        // A saved decline or this session's ✕ hides the banner.
                        bannerDismissed = contactsDeclined || contactsBannerDismissed,
                        onQueryContacts = onQueryContacts,
                        onRequestPermission = { onRequestContactsPermission?.invoke() },
                        onDeclineContacts = { onDeclineContacts?.invoke() },
                        onDismissPermissionBanner = { contactsBannerDismissed = true },
                        // Each add or remove writes back to the form at once,
                        // so back or the scrim only closes the picker.
                        onSelectionChanged = { merged ->
                            state = state.copy(attendees = merged, attendeesEdited = true)
                        },
                        onDismiss = { showAttendeePicker = false },
                    )
                }

                // Free/busy, grouped with attendees. The read-only view shows it
                // too, with the chips disabled, so it sits outside any !isReadOnly
                // gate.
                EventFormRow(
                    icon = Icons.Default.EventAvailable,
                    iconContentDescription = stringResource(R.string.label_availability)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = state.transp == "OPAQUE",
                            onClick = { if (!isReadOnly) state = state.copy(transp = "OPAQUE") },
                            enabled = !isReadOnly,
                            label = { Text(stringResource(R.string.label_busy)) }
                        )
                        FilterChip(
                            selected = state.transp == "TRANSPARENT",
                            onClick = { if (!isReadOnly) state = state.copy(transp = "TRANSPARENT") },
                            enabled = !isReadOnly,
                            label = { Text(stringResource(R.string.label_free)) }
                        )
                    }
                }

                if (showColorPicker) {
                    EventColorSheet(
                        selectedArgb = state.eventColor,
                        calendarDefaultArgb = state.selectedCalendarColor ?: MaterialTheme.colorScheme.primary.toArgb(),
                        onColorSelected = { color ->
                            state = state.copy(eventColor = color)
                            showColorPicker = false
                        },
                        onDismiss = { showColorPicker = false }
                    )
                }

                if (state.error != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val errorText = state.error.orEmpty()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errorText,
                            // The error blocks the user's action, so TalkBack
                            // announces it at once, as an error. Set on the Text,
                            // which carries the label; the Card doesn't merge it.
                            modifier = Modifier
                                .padding(16.dp)
                                .semantics {
                                    liveRegion = LiveRegionMode.Assertive
                                    error(errorText)
                                },
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                val canDeleteRoom = eventId != null && onDelete != null
                val canDeleteDevice = state.editingDeviceEventId != null && onDeleteDeviceEvent != null
                if (state.isEditMode && (canDeleteRoom || canDeleteDevice)) {
                    // Inside the edit-mode guard, so create mode doesn't draw a
                    // divider that stacks against the sticky Save divider.
                    HorizontalDivider(modifier = Modifier.testTag(TAG_DELETE_DIVIDER))

                    // Deletes through the host's callback, after the inline
                    // confirmation (one-off events and exceptions) or directly
                    // for a recurring master, whose scope sheet is the
                    // confirmation.
                    val commitDelete: () -> Unit = {
                        coroutineScope.launch {
                            state = state.copy(isSaving = true)
                            try {
                                val result: Result<Unit> = if (canDeleteDevice && state.editingDeviceEventId != null) {
                                    onDeleteDeviceEvent!!(state)
                                } else if (canDeleteRoom && eventId != null) {
                                    onDelete!!(eventId, state.editingOccurrenceTs)
                                } else {
                                    Result.failure(IllegalStateException("No delete handler"))
                                }
                                result.fold(
                                    onSuccess = { onDismiss() },
                                    onFailure = { e ->
                                        Log.e(TAG, "Error deleting event", e)
                                        state = state.copy(
                                            isSaving = false,
                                            error = "Failed to delete: ${e.message}"
                                        )
                                        showDeleteConfirmation = false
                                    }
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Error deleting event", e)
                                state = state.copy(
                                    isSaving = false,
                                    error = "Failed to delete: ${e.message}"
                                )
                                showDeleteConfirmation = false
                            }
                        }
                    }
                    if (!showDeleteConfirmation) {
                        EventFormRow(
                            icon = Icons.Default.DeleteOutline,
                            iconTint = MaterialTheme.colorScheme.error,
                            iconContentDescription = stringResource(R.string.action_delete_event),
                            onToggle = {
                                if (wasRecurringAtLoad && !loadedIsDetachedException) {
                                    // Recurring master: the host's scope
                                    // sheet pick is the confirmation. An
                                    // exception skips the sheet and
                                    // deletes its occurrence directly, so
                                    // it keeps the inline guard.
                                    commitDelete()
                                } else {
                                    showDeleteConfirmation = true
                                }
                            },
                            enabled = !state.isSaving
                        ) {
                            Text(
                                stringResource(R.string.action_delete_event),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showDeleteConfirmation = false },
                                enabled = !state.isSaving,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(R.string.action_cancel),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Button(
                                onClick = { commitDelete() },
                                enabled = !state.isSaving,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                if (state.isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onError
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.action_confirm),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // Sticky bottom Save. Its own top divider ends the scroll content, so
            // content sections must not add a trailing divider (it would stack).
            HorizontalDivider(modifier = Modifier.testTag(TAG_SAVE_DIVIDER))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                val stickyPrimary = MaterialTheme.colorScheme.primary
                val stickyCalColor = remember(state.selectedCalendarColor, stickyPrimary) {
                    state.selectedCalendarColor?.let { Color(it) } ?: stickyPrimary
                }
                val stickyContrastColor = remember(stickyCalColor) { contrastForegroundOn(stickyCalColor) }
                Button(
                    onClick = { performSave() },
                    enabled = saveEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = stickyCalColor,
                        contentColor = stickyContrastColor
                    )
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = stickyContrastColor
                        )
                    } else {
                        Text(
                            text = if (willNotifyAttendees) {
                                stringResource(R.string.action_save_and_notify)
                            } else {
                                stringResource(R.string.action_save_event)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }


    if (activeSheet == ActiveDateTimeSheet.START) {
        DateTimeSheet(
            label = stringResource(R.string.label_starts),
            selectedDateMillis = state.dateMillis,
            selectedHour = state.startHour,
            selectedMinute = state.startMinute,
            selectedTimezone = state.timezone,
            isAllDay = state.isAllDay,
            use24Hour = use24Hour,
            firstDayOfWeek = firstDayOfWeek,
            onConfirm = { dateMillis, hour, minute ->
                if (state.isAllDay) {
                    // Keep the day span when the start date moves.
                    val normalizedDateMillis = normalizeToLocalMidnight(dateMillis)
                    val normalizedOldStart = normalizeToLocalMidnight(state.dateMillis)
                    val normalizedOldEnd = normalizeToLocalMidnight(state.endDateMillis)
                    val daySpanMs = (normalizedOldEnd - normalizedOldStart).coerceAtLeast(0)
                    val newEndDateMillis = normalizedDateMillis + daySpanMs
                    state = state.copy(
                        dateMillis = normalizedDateMillis,
                        endDateMillis = newEndDateMillis
                    )
                } else {
                    state = state.withTimedStart(dateMillis, hour, minute, defaultEventDuration)
                }
                activeSheet = ActiveDateTimeSheet.NONE
            },
            onDismiss = { activeSheet = ActiveDateTimeSheet.NONE }
        )
    }

    if (activeSheet == ActiveDateTimeSheet.END) {
        DateTimeSheet(
            label = stringResource(R.string.label_ends),
            selectedDateMillis = state.endDateMillis,
            selectedHour = state.endHour,
            selectedMinute = state.endMinute,
            selectedTimezone = state.timezone,
            isAllDay = state.isAllDay,
            use24Hour = use24Hour,
            firstDayOfWeek = firstDayOfWeek,
            onConfirm = { dateMillis, hour, minute ->
                if (state.isAllDay) {
                    val normalizedDateMillis = normalizeToLocalMidnight(dateMillis)
                    // An end date before the start date swaps them.
                    state = if (normalizedDateMillis < state.dateMillis) {
                        state.copy(
                            dateMillis = normalizedDateMillis,
                            endDateMillis = state.dateMillis,
                            endHour = hour,
                            endMinute = minute
                        )
                    } else {
                        state.copy(endDateMillis = normalizedDateMillis, endHour = hour, endMinute = minute)
                    }
                } else {
                    state = state.withTimedEnd(dateMillis, hour, minute)
                }
                activeSheet = ActiveDateTimeSheet.NONE
            },
            onDismiss = { activeSheet = ActiveDateTimeSheet.NONE }
        )
    }
}


/**
 * Parses an ISO 8601 duration trigger into signed minutes before the start.
 *
 * The sign follows the Android CalendarContract convention: a negative iCal trigger
 * ("-PT15H", before the start) gives 900, a positive one ("PT9H", after the start) gives
 * -540, and "PT0M" gives 0. Returns null for a blank value, one not starting with P, or
 * a parse exception, which is distinct from [REMINDER_OFF]; unknown units add nothing.
 */
internal fun parseIso8601DurationToMinutes(duration: String?): Int? {
    if (duration.isNullOrBlank()) return null

    try {
        // A leading '-' means before the start, so positive minutes.
        val isBefore = duration.startsWith("-")
        val normalized = duration.removePrefix("-").removePrefix("+")

        if (!normalized.startsWith("P")) return null

        var totalMinutes = 0
        var remaining = normalized.substring(1)

        // Weeks and days sit before the T.
        val tIndex = remaining.indexOf('T')
        if (tIndex > 0) {
            val datePart = remaining.substring(0, tIndex)
            val dayMatch = Regex("(\\d+)D").find(datePart)
            if (dayMatch != null) {
                totalMinutes += dayMatch.groupValues[1].toInt() * 1440 // 24 * 60
            }
            val weekMatch = Regex("(\\d+)W").find(datePart)
            if (weekMatch != null) {
                totalMinutes += weekMatch.groupValues[1].toInt() * 10080 // 7 * 24 * 60
            }
            remaining = remaining.substring(tIndex + 1)
        } else if (tIndex == 0) {
            remaining = remaining.substring(1)
        } else {
            // No T: date only, like "P1D" or "P1W".
            Regex("(\\d+)D").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() * 1440 }
            Regex("(\\d+)W").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() * 10080 }
            return if (isBefore) totalMinutes else -totalMinutes
        }

        Regex("(\\d+)H").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() * 60 }
        Regex("(\\d+)M").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() }

        return if (isBefore) totalMinutes else -totalMinutes
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse duration: $duration", e)
        return null
    }
}

/**
 * Parses an event's first [MAX_REMINDERS] reminders into signed minutes, paired with the
 * count beyond that limit (from [alarmCount]). Unparseable entries are dropped; after-start
 * (negative) values are kept.
 */
private fun parseRemindersFromEvent(reminders: List<String>?, alarmCount: Int = 0): Pair<List<Int>, Int> {
    if (reminders.isNullOrEmpty()) return Pair(emptyList(), 0)

    val parsed = reminders.take(MAX_REMINDERS).mapNotNull { duration ->
        parseIso8601DurationToMinutes(duration)
    }
    val truncatedCount = (alarmCount - MAX_REMINDERS).coerceAtLeast(0)

    return Pair(parsed, truncatedCount)
}



/**
 * Returns the device-local midnight of [millis]'s day. An all-day form holds its dates
 * this way, so a late-evening time (Feb 20 18:00 PST is Feb 21 02:00 UTC) never shows as
 * the next day.
 */
private fun normalizeToLocalMidnight(millis: Long): Long = deviceMidnight(deviceLocalDate(millis))

/**
 * Shows the dismissible notice under the attendee row when the device calendar is on a
 * local account (no sync adapter): guests are saved but nobody is invited. It is inline,
 * not a dialog, so the guest row above stays usable either way.
 */
@Composable
private fun DeviceLocalNoDeliveryNotice(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.device_attendee_local_no_delivery),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_dismiss),
            )
        }
    }
}

/**
 * Renders the editable attendee row: tapping opens the picker. Shows the invitees as
 * chips, or an add prompt when there are none. Takes Room attendee entities, so labels
 * and colors derive from the same address the picker edits.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditableAttendeesRow(
    attendees: List<org.onekash.kashcal.data.db.entity.Attendee>,
    account: org.onekash.kashcal.data.db.entity.Account?,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        // No "Attendees" label: the leading icon names the row (its
        // contentDescription), like the label-less location and time rows.
        if (attendees.isEmpty()) {
            Text(
                text = stringResource(R.string.attendee_pick_add_people),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Up to ATTENDEE_PREVIEW_LIMIT chips, "You" first, then a "+N more"
            // count, so a long list doesn't sprawl; the picker lists everyone.
            val ordered = remember(attendees, account) {
                val (you, others) = attendees.partition { account?.matchesAttendee(it.address) == true }
                you + others
            }
            val visible = ordered.take(ATTENDEE_PREVIEW_LIMIT)
            val overflow = ordered.size - visible.size
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                visible.forEach { att ->
                    val label = att.displayName?.takeIf { it.isNotBlank() }
                        ?: org.onekash.kashcal.util.AddressNormalizer.stripMailto(att.address)
                    val isYou = account?.matchesAttendee(att.address) == true
                    org.onekash.kashcal.ui.components.attendees.AttendeePickChip(
                        label = if (isYou) stringResource(R.string.attendee_you_marker) else label,
                        address = att.address,
                        initialsSource = label,
                    )
                }
                if (overflow > 0) {
                    Text(
                        text = androidx.compose.ui.res.pluralStringResource(
                            R.plurals.attendee_overflow_count, overflow, overflow
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

/** Attendee chips shown on the form's attendee row before a "+N more" count. */
private const val ATTENDEE_PREVIEW_LIMIT = 3

