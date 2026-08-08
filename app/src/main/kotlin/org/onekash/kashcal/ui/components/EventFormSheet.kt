package org.onekash.kashcal.ui.components

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import org.onekash.kashcal.util.TimezoneUtils
import org.onekash.kashcal.util.location.AddressSuggestion
import org.onekash.kashcal.util.location.LocationSuggestionService
import java.util.Calendar as JavaCalendar

private const val TAG = "EventFormSheet"

/**
 * Pure predicate: should the title-autocomplete dropdown appear?
 *
 * Returns true iff both:
 * - at least [MIN_TITLE_PREFIX] characters have been typed
 * - the user has changed the text from whatever was initially loaded (so
 *   opening an existing event to correct a typo doesn't flash a dropdown)
 *
 * The feature-enabled preference is enforced upstream in the ViewModel by
 * returning an empty suggestion list. The UI doesn't know about it.
 */
internal const val MIN_TITLE_PREFIX = 3

/** Wait this long after the last keystroke before querying the suggestion backend. */
private const val TITLE_SUGGEST_DEBOUNCE_MS = 150L

/**
 * Test tag on the divider between the personal group (notes/tags) and the
 * scheduling group (attendees/free-busy), so the group boundary is assertable.
 */
internal const val TAG_GROUP_DIVIDER = "form_group_divider"

/** Test tag on the sticky Save button's top divider. */
internal const val TAG_SAVE_DIVIDER = "form_save_divider"

/** Test tag on the delete section's leading divider (edit mode only). */
internal const val TAG_DELETE_DIVIDER = "form_delete_divider"

/**
 * Uniform breathing room above and below the content-section dividers, so their
 * spacing doesn't depend on which row (EventFormRow at 14dp, picker rows at
 * 8–12dp) happens to sit against them.
 */
private val SECTION_DIVIDER_SPACING = 6.dp

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
 * mode always shows it (the empty row carries an "Add …" affordance); the
 * read-only attendee viewer hides a blank field so a guest isn't shown an
 * "Add" prompt for something they can't edit.
 */
internal fun shouldShowReadOnlyOptionalField(value: String, isReadOnly: Boolean): Boolean =
    !isReadOnly || value.isNotBlank()

/**
 * True when [current] differs from [initial] regardless of input order.
 * Drives the Save-enabled predicate in the read-only attendee form path
 * (Save flips on as soon as the user changes their reminder set).
 *
 * Sorted-list comparison rather than set comparison: the picker doesn't
 * dedupe, so `[15, 15]` is intentionally distinct from `[15]`. Only the
 * order is normalized, not the multiset.
 */
internal fun remindersChanged(initial: List<Int>, current: List<Int>): Boolean =
    initial.sorted() != current.sorted()

/**
 * Whether the editable (add-only) attendee row should render — a tappable
 * Attendees row that opens the picker. Shown for new events, non-recurring
 * edits, recurring SERIES edits, AND single-occurrence edits including a
 * detached exception (every save scope now carries the edited guest set to
 * its write path). Suppressed when the user can't organize: a read-only
 * (invitee) event or a non-schedulable account, or when contact querying
 * isn't wired.
 *
 * @param hasContactQuery whether an onQueryContacts callback is available.
 */
internal fun canEditAttendees(
    isReadOnly: Boolean,
    isSchedulable: Boolean,
    hasContactQuery: Boolean,
): Boolean = !isReadOnly && isSchedulable && hasContactQuery

/**
 * Whether the "inviting unavailable" education text should render instead of
 * an attendee row. Shown only for the new / non-recurring flows that
 * historically showed it; a recurring edit (master or occurrence) on a
 * non-schedulable account falls through to the read-only chip display (or
 * nothing) rather than the unavailable text, matching its prior behaviour.
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
 * Migrate reminders when toggling all-day.
 * Swaps the default reminder value; keeps all custom values as-is.
 * Deduplicates after swap.
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
    // Whether the user actually changed the tag set. Mirrors [attendeesEdited]:
    // stays false when the form is merely seeded from a loaded event, so an
    // unedited open-and-save leaves the stored tag row untouched (passes null)
    // rather than rewriting — which would clobber tags a sync adapter added
    // between load and save, or wipe real tags if the load read came back empty.
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
    /** Number of reminders truncated when loading event (>5 reminders) */
    val truncatedReminderCount: Int = 0,

    // Edit mode
    val editingEventId: Long? = null,
    val isEditMode: Boolean = false,
    val editingOccurrenceTs: Long? = null,

    // Attendees the user is editing in the form (organizer flow). Holds Room
    // ENTITIES, not the lossy AttendeeUiModel — seeding from the real rows on
    // edit preserves role/cutype/rsvp/delegation that the UI projection drops.
    // The picker mutates this set; [attendeesEdited] records whether the user
    // actually changed it, so an unedited open-and-save passes null to the
    // domain layer (leave the table untouched) rather than a rebuilt list.
    val attendees: List<org.onekash.kashcal.data.db.entity.Attendee> = emptyList(),
    val attendeesEdited: Boolean = false
)

/**
 * Compute the stored (startTs, endTs) this form state would persist. All-day
 * events store UTC midnight (start) / end-of-day UTC (end); timed events
 * interpret the picker's wall-clock in the selected timezone (or device
 * default). Single source of truth shared by the save path and the
 * edit-notify banner so the banner's change detection matches what saves.
 */
fun EventFormState.toStartEndTs(): Pair<Long, Long> {
    return if (isAllDay) {
        val startUtc = DateTimeUtils.localDateToUtcMidnight(dateMillis)
        val endUtc = DateTimeUtils.localDateToUtcMidnight(endDateMillis)
        startUtc to DateTimeUtils.utcMidnightToEndOfDay(endUtc)
    } else {
        val tz = timezone?.let { java.util.TimeZone.getTimeZone(it) }
            ?: java.util.TimeZone.getDefault()
        val startCal = JavaCalendar.getInstance(tz).apply {
            timeInMillis = dateMillis
            set(JavaCalendar.HOUR_OF_DAY, startHour)
            set(JavaCalendar.MINUTE, startMinute)
            set(JavaCalendar.SECOND, 0)
            set(JavaCalendar.MILLISECOND, 0)
        }
        val endCal = JavaCalendar.getInstance(tz).apply {
            timeInMillis = endDateMillis
            set(JavaCalendar.HOUR_OF_DAY, endHour)
            set(JavaCalendar.MINUTE, endMinute)
            set(JavaCalendar.SECOND, 0)
            set(JavaCalendar.MILLISECOND, 0)
        }
        startCal.timeInMillis to endCal.timeInMillis
    }
}

// Reminder constants and helpers are in ui/shared/FormConstants.kt

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
 * The display name for a resolved default calendar, localized for Room calendars.
 *
 * [resolveDefaultCalendar] is kept pure (no Android [Resources]), so it returns the raw
 * stored name. Localization happens here: for a Room calendar we re-find the entity and
 * apply [localizedDisplayName] (which localizes the built-in on-device calendar). Device
 * calendars pass through unchanged — they carry their own name and their id lives in a
 * separate space from Room ids, so looking one up in the Room list could collide and
 * mislabel it.
 */
private fun ResolvedCalendar.localizedName(
    writableCalendars: List<Calendar>,
    resources: Resources
): String = if (isDevice) name
    else writableCalendars.find { it.id == id }?.localizedDisplayName(resources) ?: name

/**
 * Event creation/editing bottom sheet with a wheel-picker UI.
 *
 * @param eventId Event ID for edit mode, null for create mode
 * @param initialStartTs Initial start timestamp (epoch milliseconds) for new events
 * @param occurrenceTs Occurrence timestamp when editing single occurrence of recurring event
 * @param calendars Available calendars
 * @param defaultCalendar Default calendar for new events (supports Room and Device)
 * @param onDismiss Called when sheet is dismissed
 * @param onSave Called to save the event with form state
 * @param onDelete Called to delete the event (edit mode only)
 * @param onLoadEvent Called to load event data for edit mode
 * @param defaultReminderTimed Default reminder for timed events (minutes)
 * @param defaultReminderAllDay Default reminder for all-day events (minutes)
 * @param onRequestNotificationPermission Called when saving an event with reminders to request
 *        notification permission. The callback receives a result callback that must be invoked
 *        with the permission result (true=granted, false=denied). The event is saved regardless
 *        of the permission result (graceful degradation). Pass null to skip permission check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormSheet(
    eventId: Long? = null,
    initialStartTs: Long? = null,
    occurrenceTs: Long? = null,
    duplicateFrom: Event? = null,
    calendarIntentData: CalendarIntentData? = null,
    calendarIntentInvitees: List<String> = emptyList(),
    calendars: List<Calendar>,
    calendarGroups: List<CalendarGroup>,
    defaultCalendar: DefaultCalendar?,
    onDismiss: () -> Unit,
    onSave: suspend (EventFormState) -> Result<Event>,
    /**
     * Defer save to the host so a save-time scope sheet can ask the
     * user how the change should apply across the recurring series.
     *
     * Carries metadata captured at form-load time:
     * - `originalRrule` — the master's rrule before per-occurrence
     *   stripping (used to detect rrule changes).
     * - `masterStartTs` — the master's true startTs, anchors the
     *   first-occurrence rule.
     * - `isDetachedException` — whether the loaded event row is itself
     *   an exception (originalEventId != null).
     * - `isRecurringDevice` — whether this is a device-calendar event,
     *   so the host knows which save path to invoke.
     *
     * Null disables the deferral (legacy direct-save behavior).
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
     * Tick that increments whenever a deferred save fails or the user
     * cancels from the scope sheet. The form observes this via
     * `LaunchedEffect` to clear its `isSaving = true` flag (which is
     * set when the deferral fires) so the Save button re-enables for
     * retry.
     */
    scopeSaveFailedTick: Int = 0,
    onDelete: (suspend (eventId: Long, occurrenceTs: Long?) -> Result<Unit>)? = null,
    onLoadEvent: (suspend (Long) -> Event?)? = null,
    /**
     * Load the event's existing attendee ENTITIES for the picker to seed
     * from. Returns Room rows (not the lossy UI projection) so the picker
     * preserves role/cutype/rsvp/delegation on edit. Null disables seeding
     * (e.g. device-calendar events, which have no CalDAV attendee table).
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
     * When true, the form renders in read-only mode: a banner with
     * inline RSVP chips appears at the top, and substantive fields are
     * not editable. Reminders remain editable per RFC 5545 §3.6.6
     * (per-attendee VALARMs); the user can change their own alarms
     * even though they can't edit organizer-owned fields.
     *
     * Client-enforced — some CalDAV servers silently accept attendee
     * substantive edits, so server enforcement is unreliable.
     */
    isReadOnly: Boolean = false,
    /** Invoked when the user taps a chip inside the read-only banner. */
    onRsvp: (org.onekash.kashcal.ui.components.attendees.AttendeeStatus) -> Unit = {},
    /**
     * Save callback for the read-only attendee path. Receives the
     * (possibly empty) reminder set in minutes. The callback writes
     * locally and reschedules AlarmManager — no server PUT. When null,
     * the read-only Save button stays disabled.
     */
    onSaveAttendeeReminders: (suspend (List<Int>) -> Result<Unit>)? = null,
    /**
     * The event's account, used to mark "You" in the picker and to gate the
     * editable picker on whether the account can send invitations
     * ([isSchedulable]). Null for new local events with no resolved account.
     */
    attendeeAccount: org.onekash.kashcal.data.db.entity.Account? = null,
    /**
     * True when the account has a mailto-emittable address (an ORGANIZER can
     * be resolved). When false the picker surfaces an inline "inviting isn't
     * available" notice instead of an editable list, so the UI never creates
     * an ATTENDEE-without-ORGANIZER event (RFC 6638 §3.1).
     */
    isSchedulable: Boolean = true,
    /**
     * Fired when the user changes the target calendar while the form is open,
     * so the host can re-resolve [attendeeAccount]/[isSchedulable] for the new
     * calendar's account. Without it the attendee context would stay pinned to
     * the calendar the sheet opened with (stale "You"/schedulable state).
     */
    onCalendarSelected: ((Long) -> Unit)? = null,
    /** Debounced contact-email lookup for the picker's type-ahead. */
    onQueryContacts: (suspend (String) -> List<org.onekash.kashcal.data.contacts.ContactEmail>)? = null,
    /**
     * Request READ_CONTACTS. Receives the picker's current rationale-flip
     * sampling and reports the resulting [ContactsPermissionState]. Null
     * leaves the picker in manual-entry-only mode.
     */
    contactsPermissionState: org.onekash.kashcal.ui.permission.ContactsPermissionState =
        org.onekash.kashcal.ui.permission.ContactsPermissionState.NotRequested,
    onRequestContactsPermission: (() -> Unit)? = null,
    /** True when the user permanently declined contact suggestions — hides the picker banner for good. */
    contactsDeclined: Boolean = false,
    /** Persist a permanent decline of contact suggestions ("No thanks"). */
    onDeclineContacts: (() -> Unit)? = null,
    /** True when the tag row should render above the notes/attendees block. */
    tagsAboveNotes: Boolean = false,
    /** Persist a new tag-row position (above/below the notes/attendees block). */
    onSetTagsAboveNotes: ((Boolean) -> Unit)? = null,
) {
    // Sheet state — gestural dismiss disabled via sheetGesturesEnabled below.
    // Using confirmValueChange to block drag-to-hide causes a flicker: the sheet
    // tracks the finger, then reverse-animates back when the transition is rejected.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Pin the sheet height so IME open/close doesn't re-trigger ModalBottomSheet's
    // height animation (fillMaxHeight(fraction) recomputes against the IME-shrunk
    // window, producing a visible up-then-down hop on every focus/picker transition).
    // The configuration-keyed remember ensures rotation still resizes correctly.
    val configuration = LocalConfiguration.current
    val sheetHeight = remember(configuration.orientation, configuration.screenWidthDp) {
        (configuration.screenHeightDp * 0.95f).dp
    }

    // Mirror isSaving up to the shell so the modal's dismiss guard can read it
    // without holding the full form state (which lives in EventFormContent).
    var isSaving by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false
    ) {
        EventFormContent(
            modifier = Modifier.height(sheetHeight),
            onSavingChange = { isSaving = it },
            eventId = eventId,
            initialStartTs = initialStartTs,
            occurrenceTs = occurrenceTs,
            duplicateFrom = duplicateFrom,
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

/**
 * Content of [EventFormSheet], extracted so it can be rendered and tested
 * without the ModalBottomSheet wrapper (whose animation timing makes UI tests
 * flaky). The sheet chrome (sheet state, pinned height, dismiss guard) stays in
 * [EventFormSheet]; everything else — form state, load/save logic, and the
 * field UI — lives here.
 *
 * @param onSavingChange reports the in-flight save flag up to the host, so the
 *   host can block dismissal/teardown mid-save without owning the form state.
 *   Required (no default): a host that drops it can tear the form down mid-write,
 *   so every caller must decide how to guard dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormContent(
    onSavingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    eventId: Long? = null,
    initialStartTs: Long? = null,
    occurrenceTs: Long? = null,
    duplicateFrom: Event? = null,
    calendarIntentData: CalendarIntentData? = null,
    calendarIntentInvitees: List<String> = emptyList(),
    calendars: List<Calendar>,
    calendarGroups: List<CalendarGroup>,
    defaultCalendar: DefaultCalendar?,
    onDismiss: () -> Unit,
    onSave: suspend (EventFormState) -> Result<Event>,
    /**
     * Defer save to the host so a save-time scope sheet can ask the
     * user how the change should apply across the recurring series.
     *
     * Carries metadata captured at form-load time:
     * - `originalRrule` — the master's rrule before per-occurrence
     *   stripping (used to detect rrule changes).
     * - `masterStartTs` — the master's true startTs, anchors the
     *   first-occurrence rule.
     * - `isDetachedException` — whether the loaded event row is itself
     *   an exception (originalEventId != null).
     * - `isRecurringDevice` — whether this is a device-calendar event,
     *   so the host knows which save path to invoke.
     *
     * Null disables the deferral (legacy direct-save behavior).
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
     * Tick that increments whenever a deferred save fails or the user
     * cancels from the scope sheet. The form observes this via
     * `LaunchedEffect` to clear its `isSaving = true` flag (which is
     * set when the deferral fires) so the Save button re-enables for
     * retry.
     */
    scopeSaveFailedTick: Int = 0,
    onDelete: (suspend (eventId: Long, occurrenceTs: Long?) -> Result<Unit>)? = null,
    onLoadEvent: (suspend (Long) -> Event?)? = null,
    /**
     * Load the event's existing attendee ENTITIES for the picker to seed
     * from. Returns Room rows (not the lossy UI projection) so the picker
     * preserves role/cutype/rsvp/delegation on edit. Null disables seeding
     * (e.g. device-calendar events, which have no CalDAV attendee table).
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
     * When true, the form renders in read-only mode: a banner with
     * inline RSVP chips appears at the top, and substantive fields are
     * not editable. Reminders remain editable per RFC 5545 §3.6.6
     * (per-attendee VALARMs); the user can change their own alarms
     * even though they can't edit organizer-owned fields.
     *
     * Client-enforced — some CalDAV servers silently accept attendee
     * substantive edits, so server enforcement is unreliable.
     */
    isReadOnly: Boolean = false,
    /** Invoked when the user taps a chip inside the read-only banner. */
    onRsvp: (org.onekash.kashcal.ui.components.attendees.AttendeeStatus) -> Unit = {},
    /**
     * Save callback for the read-only attendee path. Receives the
     * (possibly empty) reminder set in minutes. The callback writes
     * locally and reschedules AlarmManager — no server PUT. When null,
     * the read-only Save button stays disabled.
     */
    onSaveAttendeeReminders: (suspend (List<Int>) -> Result<Unit>)? = null,
    /**
     * The event's account, used to mark "You" in the picker and to gate the
     * editable picker on whether the account can send invitations
     * ([isSchedulable]). Null for new local events with no resolved account.
     */
    attendeeAccount: org.onekash.kashcal.data.db.entity.Account? = null,
    /**
     * True when the account has a mailto-emittable address (an ORGANIZER can
     * be resolved). When false the picker surfaces an inline "inviting isn't
     * available" notice instead of an editable list, so the UI never creates
     * an ATTENDEE-without-ORGANIZER event (RFC 6638 §3.1).
     */
    isSchedulable: Boolean = true,
    /**
     * Fired when the user changes the target calendar while the form is open,
     * so the host can re-resolve [attendeeAccount]/[isSchedulable] for the new
     * calendar's account. Without it the attendee context would stay pinned to
     * the calendar the sheet opened with (stale "You"/schedulable state).
     */
    onCalendarSelected: ((Long) -> Unit)? = null,
    /** Debounced contact-email lookup for the picker's type-ahead. */
    onQueryContacts: (suspend (String) -> List<org.onekash.kashcal.data.contacts.ContactEmail>)? = null,
    /**
     * Request READ_CONTACTS. Receives the picker's current rationale-flip
     * sampling and reports the resulting [ContactsPermissionState]. Null
     * leaves the picker in manual-entry-only mode.
     */
    contactsPermissionState: org.onekash.kashcal.ui.permission.ContactsPermissionState =
        org.onekash.kashcal.ui.permission.ContactsPermissionState.NotRequested,
    onRequestContactsPermission: (() -> Unit)? = null,
    /** True when the user permanently declined contact suggestions — hides the picker banner for good. */
    contactsDeclined: Boolean = false,
    /** Persist a permanent decline of contact suggestions ("No thanks"). */
    onDeclineContacts: (() -> Unit)? = null,
    /** True when the tag row should render above the notes/attendees block. */
    tagsAboveNotes: Boolean = false,
    /** Persist a new tag-row position (above/below the notes/attendees block). */
    onSetTagsAboveNotes: ((Boolean) -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val hapticFeedback = LocalHapticFeedback.current

    // Compute time pattern from preference
    val context = LocalContext.current
    val is24HourDevice = DateFormat.is24HourFormat(context)
    val timePattern = remember(timeFormat, is24HourDevice) {
        DateTimeUtils.getTimePattern(timeFormat, is24HourDevice)
    }
    // Determine if 24-hour mode should be used (for time picker wheels)
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

    /**
     * Snapshot of reminders at form-load time. Used by the read-only
     * attendee path to gate the Save button: enabled only when the
     * current set differs from this snapshot. Initialised to the same
     * value as state.reminders during edit-load so a no-op tap doesn't
     * show Save as enabled.
     */
    var initialReminders by remember { mutableStateOf<List<Int>>(emptyList()) }

    /**
     * Snapshot of the rrule at form-load time. Used by the
     * save-time scope sheet to detect "user changed the recurrence
     * rule" so it can disable the THIS_EVENT option (per RFC 5545
     * §3.8.5 exceptions cannot carry an rrule).
     */
    var initialRrule by remember { mutableStateOf<String?>(null) }

    /**
     * Recurrence presence at load time. The save-time deferral
     * predicate keys off this rather than `state.rrule`/`initialRrule`,
     * because the load path strips rrule on per-occurrence edits
     * (effectiveRrule = null when occurrenceTs != null), which would
     * otherwise hide every Room recurring occurrence edit from the
     * scope sheet.
     */
    var wasRecurringAtLoad by remember { mutableStateOf(false) }

    /**
     * Master event metadata captured at form-load time. Threaded
     * through `onRequestRecurringSave` so the host's option-set
     * rules see the master's true startTs and detached-exception
     * status — not values derived from the (possibly user-edited)
     * form state.
     */
    var loadedMasterStartTs by remember { mutableStateOf(0L) }
    var loadedIsDetachedException by remember { mutableStateOf(false) }

    /**
     * The master/loaded event's `isAllDay` at form-load time, frozen
     * here so the scope-sheet sub-copy date format doesn't flip if
     * the user toggles all-day in the form before saving.
     */
    var loadedIsAllDay by remember { mutableStateOf(false) }

    /**
     * The event as loaded, snapshotted for the edit-notify predicate. Compared
     * against a candidate built from the current form fields to decide whether
     * saving will notify attendees (drives the inline banner + Save relabel).
     */
    var loadedEvent by remember { mutableStateOf<Event?>(null) }
    // Canonical addresses of the attendees present at load — the baseline for
    // detecting removals (uninvites) this session.
    var loadedAttendeeAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Guests loaded for a device-calendar event (read-only display). The Room
    // `attendees` param doesn't populate for device events (it's keyed on a
    // Room event id), so the device edit path carries them here. Editing the
    // device guest list is a separate write path; this is display-only.
    var deviceAttendees by remember {
        mutableStateOf<List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>>(emptyList())
    }
    // Whether the loaded device event's calendar allows writes — gates whether
    // the guest list is editable (vs read-only) for an existing device event.
    var deviceEventWritable by remember { mutableStateOf(false) }
    // In-session dismissal of the LOCAL-calendar "no invitation sent" notice.
    var deviceNoticeDismissed by remember { mutableStateOf(false) }

    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var activeSheet by remember { mutableStateOf(ActiveDateTimeSheet.NONE) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showAttendeeSheet by remember { mutableStateOf(false) }
    var showAttendeePicker by remember { mutableStateOf(false) }
    // Tracks an in-session "Not now" dismissal of the picker's permission
    // banner so it doesn't reappear within the same picker open.
    var contactsBannerDismissed by remember { mutableStateOf(false) }

    val borderlessFieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Color.Transparent,
        focusedBorderColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedContainerColor = Color.Transparent
    )

    // Auto-focus title field
    val titleFocusRequester = remember { FocusRequester() }

    // Perform save with result handling
    val performSave: () -> Unit = {
        // Check if event has a reminder set
        val hasReminder = state.reminders.isNotEmpty()

        // Detect a recurring edit that should defer to the save-time
        // scope sheet. Conditions:
        //   - host registered onRequestRecurringSave
        //   - editing an existing event (not a create)
        //   - the event was opened on a specific occurrence
        //   - either the form's current rrule is non-null OR the
        //     original was (handles the "remove RRULE" case via the
        //     ALL_EVENTS option)
        //   - not read-only (attendees route directly to attendee
        //     reminder save)
        // Defer to the host's scope sheet when:
        //   - the host registered onRequestRecurringSave
        //   - we're in edit mode (not create)
        //   - the form was opened on a specific occurrence
        //   - the loaded event was actually recurring (its master had
        //     an rrule). Keys off the load-time snapshot rather than
        //     state.rrule/initialRrule because the form's load path
        //     strips rrule for per-occurrence edits — both would be
        //     null and this predicate would always evaluate false.
        //   - not in read-only attendee mode (which routes to
        //     onSaveAttendeeReminders directly).
        val deferToScopeSheet = onRequestRecurringSave != null &&
            !isReadOnly &&
            state.isEditMode &&
            state.editingOccurrenceTs != null &&
            wasRecurringAtLoad

        // The actual save operation. Defers to the host-supplied
        // recurring-save callback when applicable; otherwise fires
        // the existing direct-save path.
        val doSave: () -> Unit = saveImpl@ {
            if (deferToScopeSheet) {
                // Stays visible so a Cancel from the scope sheet
                // returns to the dirty form. isSaving flips to true
                // immediately so the Save button disables — a
                // double-tap would otherwise stage two pendingFormSave
                // snapshots before the sheet renders.
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
                    // Route by mode:
                    //  - read-only attendee path: only reminders are
                    //    persisted, locally; no organizer-owned fields
                    //    leave the device.
                    //  - device calendar: existing onSaveDeviceEvent path.
                    //  - default: existing onSave full-event path.
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

        // If event has a reminder and permission callback is provided, request permission first
        // Then always save regardless of permission result (graceful degradation)
        if (hasReminder && onRequestNotificationPermission != null) {
            onRequestNotificationPermission { _ ->
                // Always save regardless of permission result
                doSave()
            }
        } else {
            doSave()
        }
    }

    // Load data on first composition
    LaunchedEffect(eventId, deviceEventId) {
        // Filter out read-only calendars (ICS subscriptions) for event creation/editing
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
            // Device calendar edit mode - load device event
            val editData = onLoadDeviceEvent(deviceEventId)
            if (editData != null) {
                // Use the mapper to convert DeviceEvent to EventFormState
                val mappedState = editData.event.toFormState(
                    reminders = editData.reminders,
                    calendarColor = editData.calendarColor,
                    calendarName = editData.calendarName,
                    deviceCalendarGroups = deviceCalendarGroups,
                    occurrenceTs = deviceOccurrenceTs
                )
                // Merge with writable groups and set occurrence timestamp if editing single occurrence
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
                // Seed the picker from the device event's existing guests
                // (organizer excluded — the repository owns that row) so a
                // whole-event guest edit diffs against the real set. Stays
                // unedited (attendeesEdited=false) until the user touches it,
                // so an open-and-save passes null and leaves rows untouched.
                newState = newState.copy(
                    attendees = org.onekash.kashcal.ui.viewmodels.deviceGuestsToPickerSeed(editData.attendees)
                )
            } else {
                // Event not found (deleted externally)
                newState = newState.copy(
                    error = "Event no longer exists",
                    isLoading = false
                )
                // Will show error, user can dismiss
            }
        } else if (eventId != null && onLoadEvent != null) {
            // Edit mode - load event
            val event = onLoadEvent(eventId)
            if (event != null) {
                val eventCalendar = calendars.find { it.id == event.calendarId }

                // For single occurrence edit:
                // - Re-editing exception: use exception's startTs (already has modified time)
                // - Creating new exception: use occurrenceTs (the specific occurrence being edited)
                val eventDuration = event.endTs - event.startTs
                val actualStartTs = if (event.isException) event.startTs else (occurrenceTs ?: event.startTs)
                val actualEndTs = actualStartTs + eventDuration

                // CRITICAL: All-day events are stored as UTC midnight. For display in the
                // date picker (which uses local time), convert UTC midnight to local midnight
                // to preserve the calendar date.
                val displayStartTs = if (event.isAllDay) {
                    DateTimeUtils.utcMidnightToLocalDate(actualStartTs)
                } else {
                    actualStartTs
                }
                val displayEndTs = if (event.isAllDay) {
                    DateTimeUtils.utcMidnightToLocalDate(actualEndTs)
                } else {
                    actualEndTs
                }

                // Use event's timezone when parsing times (not device timezone)
                // This ensures events with specific timezone display correct wall clock time
                val eventTz = event.timezone?.let { java.util.TimeZone.getTimeZone(it) }
                    ?: java.util.TimeZone.getDefault()
                val startCal = JavaCalendar.getInstance(eventTz).apply { timeInMillis = displayStartTs }
                val endCal = JavaCalendar.getInstance(eventTz).apply { timeInMillis = displayEndTs }

                // Parse reminders from event
                val (parsedReminders, truncatedCount) = parseRemindersFromEvent(event.reminders, event.alarmCount)

                // Show the loaded event's rrule verbatim. For a recurring
                // master tapped via an occurrence, that's master.rrule; for
                // an exception row it's null (exceptions strip rrule per
                // RFC 5545 §3.8.5). The save side strips rrule for THIS_EVENT
                // exceptions regardless of state.rrule (EventWriter.editSingleOccurrence)
                // and the scope sheet's THIS_AND_FUTURE / ALL_EVENTS branches
                // route the user-edited rrule through the helper.
                newState = newState.copy(
                    title = event.title,
                    dateMillis = displayStartTs,
                    endDateMillis = displayEndTs,
                    startHour = startCal.get(JavaCalendar.HOUR_OF_DAY),
                    startMinute = startCal.get(JavaCalendar.MINUTE),
                    endHour = endCal.get(JavaCalendar.HOUR_OF_DAY),
                    endMinute = endCal.get(JavaCalendar.MINUTE),
                    selectedCalendarId = event.calendarId,
                    selectedCalendarName = eventCalendar?.localizedDisplayName(context.resources).orEmpty(),
                    selectedCalendarColor = eventCalendar?.color,
                    isAllDay = event.isAllDay,
                    timezone = event.timezone,
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
                // Capture the loaded reminder set so the read-only path
                // can detect "user changed reminders" via remindersChanged.
                initialReminders = parsedReminders
                initialRrule = event.rrule
                wasRecurringAtLoad = event.rrule != null || event.originalEventId != null
                loadedMasterStartTs = event.startTs
                loadedIsDetachedException = event.originalEventId != null
                loadedIsAllDay = event.isAllDay
                loadedEvent = event
                // Seed the picker from the event's existing attendee ENTITIES
                // (not the lossy UI projection) so editing preserves their wire
                // fields. attendeesEdited stays false: an open-and-save with no
                // picker change still passes null to the domain layer.
                if (onLoadAttendees != null) {
                    val loaded = onLoadAttendees(eventId)
                    newState = newState.copy(attendees = loaded)
                    // Snapshot the originally-invited addresses so a later
                    // removal can be detected (and the dropped guests counted
                    // for the uninvite banner) via a canonical-address diff.
                    loadedAttendeeAddresses = loaded.map {
                        org.onekash.kashcal.util.AddressNormalizer.canonical(it.address)
                    }.toSet()
                }
            }
        } else {
            // Create mode - set default end time based on duration setting
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

            // Handle initial start time (overrides defaults if provided)
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

            // Handle duplicate event - copy data from source event
            if (duplicateFrom != null) {
                // For all-day events: UTC timestamps need conversion for date picker
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

                // Parse reminders from event (ignore truncation for duplicates)
                val (dupReminders, _) = parseRemindersFromEvent(duplicateFrom.reminders, duplicateFrom.alarmCount)

                // Use source calendar if writable, otherwise fall back to resolved default
                val sourceCalendar = writableCalendars.find { it.id == duplicateFrom.calendarId }
                val sourceCalId = sourceCalendar?.id ?: resolvedCal.id
                val sourceCalName = sourceCalendar?.localizedDisplayName(context.resources)
                    ?: resolvedCal.localizedName(writableCalendars, context.resources)
                val sourceCalColor = sourceCalendar?.color ?: resolvedCal.color

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
                    isDeviceCalendar = sourceCalendar == null && resolvedCal.isDevice,
                    reminders = dupReminders,
                    rrule = null,  // Don't copy recurrence (creates independent event)
                    transp = duplicateFrom.transp,
                    eventColor = duplicateFrom.color
                )
            }

            // Handle calendar intent - pre-fill from external app (email client, browser, etc.)
            if (calendarIntentData != null && eventId == null) {
                val startTs = calendarIntentData.startTimeMillis ?: run {
                    // No parsed time — snap to next hour (matches FAB create behavior)
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

                // Append invitees to description (user preference)
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
    }

    // Reactive calendar update: handles async calendar loading on cold start.
    // The init LaunchedEffect above captures calendars at first composition,
    // Reset isSaving when a deferred save fails or the user cancels
    // from the scope sheet. Save button gates on `!state.isSaving`,
    // so without this the form stays locked after a failure.
    LaunchedEffect(scopeSaveFailedTick) {
        if (state.isSaving && scopeSaveFailedTick > 0) {
            state = state.copy(isSaving = false)
        }
    }

    // which may be empty if HomeViewModel hasn't loaded them yet (race condition).
    // This effect updates calendar state when the list becomes available.
    LaunchedEffect(calendars, calendarGroups, deviceCalendarGroups) {
        val writableCalendars = calendars.filter { !it.isReadOnly }
        val writableGroups = calendarGroups.mapNotNull { group ->
            val writableCals = group.calendars.filter { !it.isReadOnly }
            if (writableCals.isNotEmpty()) group.copy(calendars = writableCals) else null
        }

        // Early return if nothing changed (prevents unnecessary state updates during sync)
        if (writableGroups == state.calendarGroups &&
            deviceCalendarGroups == state.deviceCalendarGroups) return@LaunchedEffect

        // Always update the calendar groups (picker needs current list)
        state = state.copy(
            calendarGroups = writableGroups,
            deviceCalendarGroups = deviceCalendarGroups
        )

        // Case 1: Create mode — no calendar selected yet (empty on first composition)
        // Resolve default now that calendars are available
        if (state.selectedCalendarId == null && writableCalendars.isNotEmpty()) {
            val resolved = resolveDefaultCalendar(defaultCalendar, writableCalendars, deviceCalendarGroups)
            state = state.copy(
                selectedCalendarId = resolved.id,
                selectedCalendarName = resolved.localizedName(writableCalendars, context.resources),
                selectedCalendarColor = resolved.color,
                isDeviceCalendar = resolved.isDevice
            )
        }

        // Case 2: Edit mode — calendar ID already set but metadata missing (cold start)
        // The init LaunchedEffect set selectedCalendarId from event.calendarId,
        // but calendar name/color were null because calendars list was empty
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
    }

    // Time validation: end time must not be before start time on same date
    val hasTimeConflict by remember {
        derivedStateOf {
            if (state.isAllDay) {
                false // All-day events don't have time conflicts
            } else {
                val startDateOnly = normalizeToLocalMidnight(state.dateMillis)
                val endDateOnly = normalizeToLocalMidnight(state.endDateMillis)
                if (startDateOnly == endDateOnly) {
                    val startMins = state.startHour * 60 + state.startMinute
                    val endMins = state.endHour * 60 + state.endMinute
                    endMins < startMins
                } else {
                    false // Different dates - no time conflict possible
                }
            }
        }
    }

    // Edit-notify: saving a scheduling-significant change (title, location,
    // time, recurrence, cancellation) on an event with attendees will email
    // them an updated invite. Surface that consequence inline before the tap —
    // the predicate delegates to SequenceBumper so the banner matches the wire
    // behaviour, so the candidate must carry every field SequenceBumper reads.
    //
    // attendeeCount is the set that WILL be saved: the picker-edited
    // state.attendees once the user has touched it (attendeesEdited), else the
    // persisted display projection. Using the live edited set is essential —
    // adding the first attendee + changing the time in one session must surface
    // the banner, and removing everyone must hide it.
    val notifyAttendeeCount = if (state.attendeesEdited) state.attendees.size else attendees.size
    // Guests dropped from the originally-loaded set this session — each owes a
    // CANCEL on save. Computed by canonical-address diff so the uninvite banner
    // counts the removed guests (not the surviving set, which may be empty).
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
                // Mirror the save-path normalization (HomeViewModel applies the
                // same ifBlank transforms) so the banner's bump prediction
                // matches the value that will actually be written.
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
                // An add sends the new guest a REQUEST; a removal sends the
                // dropped guest a CANCEL. attendeesEdited is the same flag the
                // save path uses to decide the authoritative set; the removal
                // flag relaxes the empty-set gate so uninviting the last guest
                // still surfaces.
                attendeeSetChanged = state.attendeesEdited,
                attendeeRemoved = removedAttendeeCount > 0,
            )
        }
    }

    // Save predicate splits by mode. In read-only (attendee) mode the
    // user can only edit reminders, so Save gates on the reminder-set
    // having actually changed. In normal mode the existing full-form
    // validation applies. Hoisted above the Column so both the header
    // Save button and the sticky bottom Save button share one predicate.
    val saveEnabled = if (isReadOnly) {
        onSaveAttendeeReminders != null &&
            remindersChanged(initialReminders, state.reminders) &&
            !state.isSaving
    } else {
        state.title.isNotBlank() && !state.isSaving && !hasTimeConflict
    }

    // Propagate the save flag to the wrapper for its dismiss guard. SideEffect
    // (not LaunchedEffect) so the wrapper's mirror is updated synchronously at
    // commit, before any later input frame: a dismiss tap arriving after a save
    // starts then sees the up-to-date flag rather than a value lagging by a
    // coroutine dispatch.
    SideEffect { onSavingChange(state.isSaving) }

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
            TextButton(onClick = { onDismiss() }) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    maxLines = 1,
                    softWrap = false
                )
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
            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
            ) {
                // titleInitial captures the pre-filled value once after load so
                // edit mode doesn't flash a dropdown before the user types.
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
                            // maxLines=2 wraps long titles, but with
                            // singleLine gone the Enter key would insert a
                            // literal newline — strip it so a title stays a
                            // single logical line (and never needs escaping
                            // on the wire).
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
                            .focusRequester(titleFocusRequester)
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                            // Horizontal only: the field's own box already
                            // supplies vertical padding, so an extra vertical
                            // pad here just made the title taller than location.
                            .padding(horizontal = 16.dp),
                        // Wrap a long title to a second line (matching the
                        // quick-view title) instead of scrolling it off the
                        // start on one line. There is no title length cap.
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

                // Location sits directly under the title (matching common
                // calendar apps), above the date/time section.
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
                                // Strip newlines: with singleLine gone the
                                // Enter key would otherwise insert one, and
                                // a location is a single logical line.
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
                            // Wrap a long address to a second line instead of
                            // scrolling it off the start on one line.
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
                    val currentDefault = if (state.isAllDay) defaultReminderAllDay else defaultReminderTimed
                    val newDefault = if (newIsAllDay) defaultReminderAllDay else defaultReminderTimed
                    val migratedReminders = migrateRemindersForAllDayToggle(state.reminders, currentDefault, newDefault)
                    val normalizedDate = if (newIsAllDay) normalizeToLocalMidnight(state.dateMillis) else state.dateMillis
                    val normalizedEndDate = if (newIsAllDay) normalizeToLocalMidnight(state.endDateMillis) else state.endDateMillis
                    state = state.copy(
                        isAllDay = newIsAllDay,
                        dateMillis = normalizedDate,
                        endDateMillis = normalizedEndDate,
                        reminders = migratedReminders
                    )
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
                                val oldTz = state.timezone?.let { java.util.TimeZone.getTimeZone(it) }
                                    ?: java.util.TimeZone.getDefault()
                                val newTz = newTimezone?.let { java.util.TimeZone.getTimeZone(it) }
                                    ?: java.util.TimeZone.getDefault()

                                val (newStartDate, newStartH, newStartM) = convertTimezone(
                                    oldTz, newTz, state.dateMillis, state.startHour, state.startMinute
                                )
                                val (newEndDate, newEndH, newEndM) = convertTimezone(
                                    oldTz, newTz, state.endDateMillis, state.endHour, state.endMinute
                                )
                                state = state.copy(
                                    timezone = newTimezone,
                                    dateMillis = newStartDate,
                                    startHour = newStartH,
                                    startMinute = newStartM,
                                    endDateMillis = newEndDate,
                                    endHour = newEndH,
                                    endMinute = newEndM
                                )
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
                    // Recurring DEVICE events can't be moved between calendars:
                    // Android treats CALENDAR_ID as create-time, so a move is a
                    // delete+recreate that we only support for non-recurring
                    // device events. Disable the picker for a recurring device
                    // edit rather than let a pick silently do nothing. (Room
                    // recurring events move fine and stay enabled.)
                    //
                    // A synced event WITH ATTENDEES can't be moved to another
                    // account (the move would carry the source account's ORGANIZER
                    // and misdeliver invitations — the domain layer rejects it).
                    // We can't selectively offer only same-account targets without
                    // per-option disabling, so disable the whole picker for such
                    // an edit; the user can duplicate the event onto the other
                    // account instead. (Device attendee events are covered by the
                    // recurring/occurrence clauses and the device move path.)
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
                        // Re-resolve the attendee/organizer context for the
                        // newly chosen calendar's account (schedulable gate
                        // + "You" detection must not stay pinned to the
                        // calendar the sheet opened with).
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
                    firstDayOfWeek = firstDayOfWeek
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = SECTION_DIVIDER_SPACING))

                // The tag row can sit above or below the notes/attendees block
                // (a persisted preference the user flips from its ⋮ menu). Its
                // content is defined once and rendered in the chosen position.
                var showTagsMenu by remember { mutableStateOf(false) }
                val tagsRow: @Composable () -> Unit = {
                    EventFormRow(
                        icon = Icons.Default.LocalOffer,
                        iconContentDescription = stringResource(R.string.label_categories),
                        // Top-align so the icon and the ⋮ stay by the chip line;
                        // otherwise engaging the picker (field + suggestion list)
                        // floats them into the middle of the list. The small
                        // offset centers the icon against the resting chip row.
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
                            // Mirror the left icon (24dp glyph, same 6dp top
                            // nudge) so the ⋮ shares the chip baseline instead of
                            // sitting low inside a 48dp button box.
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
                                    // The current position's item is disabled —
                                    // only the move to the other position acts.
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
                    // Notes is a multi-line field; top-align and drop the icon by
                    // the field's own internal top padding so it meets the first
                    // line of text rather than the field's top edge.
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

                // Tags row (default position): directly below notes, so the
                // "personal" group (notes + tags) stays together above the
                // "scheduling" group (attendees + free/busy). The user can flip
                // it above notes via the row's ⋮ menu.
                if (!isReadOnly && !tagsAboveNotes) {
                    tagsRow()
                }

                // Separates the personal group (notes/tags) from the scheduling
                // group (attendees/free-busy) below. Only drawn when the
                // personal group actually rendered something — in read-only
                // mode with blank notes the whole group is empty, and an
                // unconditional divider would stack against the section divider
                // above it.
                if (!isReadOnly || state.description.isNotBlank()) {
                    HorizontalDivider(
                        modifier = Modifier
                            .padding(vertical = SECTION_DIVIDER_SPACING)
                            .testTag(TAG_GROUP_DIVIDER)
                    )
                }

                // Editable organizer flow: an always-present, tappable
                // Attendees row that opens the picker. Available for new
                // events, non-recurring edits, recurring SERIES edits, and
                // single-occurrence edits (a detached exception included) —
                // every save scope carries the edited guest set to its
                // write path. Not-organizer (isReadOnly) and non-schedulable
                // accounts keep the read-only display.
                val isDeviceEvent = deviceEventId != null
                // A device event's guest list is editable on a whole-event
                // edit of a writable calendar (not a single-occurrence /
                // this-and-future edit — the provider doesn't store
                // per-occurrence guest divergence, so those stay read-only).
                val isDeviceOccurrenceEdit =
                    state.editingOccurrenceTs != null || loadedIsDetachedException
                val canEditDeviceAttendees = isDeviceEvent &&
                    deviceEventWritable &&
                    !isDeviceOccurrenceEdit &&
                    onQueryContacts != null
                // Selected device calendar's delivery capability — drives the
                // LOCAL-account inline notice (editing is still allowed).
                val deviceCanDeliverInvites = deviceCalendarGroups
                    .asSequence()
                    .flatMap { it.pickerCalendars.asSequence() }
                    .filterIsInstance<PickerCalendar.Device>()
                    .firstOrNull { it.calendar.id == state.selectedCalendarId }
                    ?.calendar
                    ?.canDeliverInvites
                    ?: true
                // The LOCAL "no invitation sent" notice shows whenever the
                // user is editing guests on a device calendar that can't
                // deliver. Computed once so both editable branches (existing
                // device event, and new event on a device calendar) gate it
                // identically.
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
                    // Editable device guest list. The picker mutates
                    // state.attendees (Room entities); saveDeviceEvent
                    // bridges them to provider rows. On a LOCAL calendar
                    // editing is still offered, with an inline notice that
                    // no invitation will be sent.
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
                    // Device-calendar guest list, read-only (occurrence edit
                    // or non-writable calendar). Surface the existing guests
                    // so the form matches the device quick-view's visibility.
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
                            // New event on a LOCAL device calendar: editing
                            // is allowed but nothing is delivered.
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
                        // RSVP write path mutates only the loaded entity:
                        // - master (state.rrule != null) → series-wide
                        // - detached exception (loadedIsDetachedException) →
                        //   per-occurrence; the disclosure would lie
                        // - non-recurring → not applicable
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

                        // Editable form: the user changes attendance by
                        // editing the event itself, so the RSVP cards
                        // are unnecessary noise. Suppress them — but
                        // do NOT label the user as the organizer (they
                        // may be editing a delegated calendar where
                        // they're an attendee), since that flows into
                        // the summary line phrasing.
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
                        // Persisted decline OR this-session ✕ both hide the banner.
                        bannerDismissed = contactsDeclined || contactsBannerDismissed,
                        onQueryContacts = onQueryContacts,
                        onRequestPermission = { onRequestContactsPermission?.invoke() },
                        onDeclineContacts = { onDeclineContacts?.invoke() },
                        onDismissPermissionBanner = { contactsBannerDismissed = true },
                        // Auto-commit: each add/remove writes straight back to
                        // the form (back/scrim just closes — nothing to confirm).
                        onSelectionChanged = { merged ->
                            state = state.copy(attendees = merged, attendeesEdited = true)
                        },
                        onDismiss = { showAttendeePicker = false },
                    )
                }

                // Free/Busy availability — the last content row, paired with
                // attendees in the "scheduling" group. Rendered in read-only
                // mode too (chips disabled), so it stays outside any
                // !isReadOnly gate.
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

                // Error message
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
                            // A save/validation error blocks the user's action, so
                            // interrupt TalkBack to announce it immediately, and mark
                            // it as an error. On the Text (which carries the label),
                            // not the Card, since the Card doesn't merge its child.
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
                    // Leading separator for the delete section. Lives inside the
                    // edit-mode guard so create mode doesn't draw a divider that
                    // then stacks against the sticky Save button's own divider.
                    HorizontalDivider(modifier = Modifier.testTag(TAG_DELETE_DIVIDER))

                    // Commits the actual delete via the host's
                    // callback. Used by both the inline-confirmation
                    // path (non-recurring) and the direct path
                    // (recurring — the host's scope sheet IS the
                    // confirmation, so an extra inline tap would be
                    // redundant friction).
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
                                    // Recurring master only: the host's
                                    // scope sheet picks THIS_EVENT /
                                    // THIS_AND_FUTURE / ALL_EVENTS and
                                    // that deliberate pick is the
                                    // confirmation. Exception events
                                    // skip the scope sheet and route
                                    // straight to single-occurrence
                                    // delete, so they still need the
                                    // inline two-tap guard.
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

            // Sticky bottom save button. Its own top divider separates it from
            // whatever the scroll content ended with, so content sections must
            // not add a trailing divider here (it would stack against this one).
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


    // Start DateTime sheet - combined date + time picker
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
                // Normalize to midnight for all-day events to prevent timezone date shift
                val normalizedDateMillis = if (state.isAllDay) normalizeToLocalMidnight(dateMillis) else dateMillis

                if (state.isAllDay) {
                    // ALL-DAY: Preserve day span when start date changes
                    val normalizedOldStart = normalizeToLocalMidnight(state.dateMillis)
                    val normalizedOldEnd = normalizeToLocalMidnight(state.endDateMillis)
                    val daySpanMs = (normalizedOldEnd - normalizedOldStart).coerceAtLeast(0)
                    val newEndDateMillis = normalizedDateMillis + daySpanMs
                    state = state.copy(
                        dateMillis = normalizedDateMillis,
                        endDateMillis = newEndDateMillis
                    )
                } else {
                    // TIMED: Preserve actual duration when start changes
                    val oldStartMins = state.startHour * 60 + state.startMinute
                    val oldEndMins = state.endHour * 60 + state.endMinute
                    val oldStartDateOnly = normalizeToLocalMidnight(state.dateMillis)
                    val oldEndDateOnly = normalizeToLocalMidnight(state.endDateMillis)
                    val dayGapMinutes = ((oldEndDateOnly - oldStartDateOnly) / (60 * 1000)).toInt()
                    val currentDurationMins = (oldEndMins - oldStartMins) + dayGapMinutes
                    val durationMins = if (currentDurationMins >= 0) currentDurationMins else defaultEventDuration

                    val newEndTotalMins = hour * 60 + minute + durationMins
                    val dayOverflowMs = (newEndTotalMins / (24 * 60)).toLong() * 24L * 60 * 60 * 1000
                    val remainderMins = newEndTotalMins % (24 * 60)

                    state = state.copy(
                        dateMillis = normalizedDateMillis,
                        startHour = hour,
                        startMinute = minute,
                        endDateMillis = normalizedDateMillis + dayOverflowMs,
                        endHour = remainderMins / 60,
                        endMinute = remainderMins % 60
                    )
                }
                activeSheet = ActiveDateTimeSheet.NONE
            },
            onDismiss = { activeSheet = ActiveDateTimeSheet.NONE }
        )
    }

    // End DateTime sheet - combined date + time picker
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
                // Normalize to midnight for all-day events to prevent timezone date shift
                val normalizedDateMillis = if (state.isAllDay) normalizeToLocalMidnight(dateMillis) else dateMillis

                // End date logic: only clamp if user selected date before start
                // Time validation (hasTimeConflict) handles invalid times with error UI
                val finalEndDateMillis = when {
                    normalizedDateMillis < state.dateMillis -> state.dateMillis  // Can't end before start date
                    else -> normalizedDateMillis  // Use user's selection
                }

                // If user selected date before start, swap
                if (normalizedDateMillis < state.dateMillis) {
                    state = state.copy(
                        dateMillis = normalizedDateMillis,
                        endDateMillis = state.dateMillis,
                        endHour = hour,
                        endMinute = minute
                    )
                } else {
                    state = state.copy(
                        endDateMillis = if (state.isAllDay) normalizeToLocalMidnight(finalEndDateMillis) else finalEndDateMillis,
                        endHour = hour,
                        endMinute = minute
                    )
                }
                activeSheet = ActiveDateTimeSheet.NONE
            },
            onDismiss = { activeSheet = ActiveDateTimeSheet.NONE }
        )
    }
}


// ExpandablePickerCard moved to ui/components/pickers/ExpandablePickerCard.kt
// CalendarPickerRow lives in ui/components/pickers/CalendarPicker.kt
// ReminderPickerCard and formatReminderLabel moved to ui/components/pickers/ReminderPicker.kt
// Import these components from their respective locations

// Helper functions

/**
 * Parse an ISO 8601 duration trigger into signed "minutes before start".
 *
 * Sign is preserved (Android CalendarContract convention): a negative iCal trigger
 * ("-PT15H", before start) yields a positive Int (900); a positive trigger ("PT9H",
 * after start) yields a negative Int (-540); "PT0M" yields 0 (at start).
 * Returns null if the duration cannot be parsed (distinct from REMINDER_OFF).
 */
internal fun parseIso8601DurationToMinutes(duration: String?): Int? {
    if (duration.isNullOrBlank()) return null

    try {
        // A leading '-' means "before start" -> positive minutes-before (Int).
        val isBefore = duration.startsWith("-")
        val normalized = duration.removePrefix("-").removePrefix("+")

        // Must start with P
        if (!normalized.startsWith("P")) return null

        var totalMinutes = 0
        var remaining = normalized.substring(1) // Remove 'P'

        // Parse days if present (before T)
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
            // No T: date-only like "P1D" or "P1W"
            Regex("(\\d+)D").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() * 1440 }
            Regex("(\\d+)W").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() * 10080 }
            return if (isBefore) totalMinutes else -totalMinutes
        }

        // Parse hours
        Regex("(\\d+)H").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() * 60 }
        // Parse minutes
        Regex("(\\d+)M").find(remaining)?.let { totalMinutes += it.groupValues[1].toInt() }

        // 0 means "at time of event"; otherwise apply the before/after sign.
        return if (isBefore) totalMinutes else -totalMinutes
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse duration: $duration", e)
        return null
    }
}

/**
 * Parse reminders list from event into List<Int> of signed minutes.
 * Takes first MAX_REMINDERS (5), computes truncation count from alarmCount.
 * Returns Pair(reminderMinutes, truncatedCount). Unparseable entries are dropped;
 * after-start (negative) values are kept.
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
 * Normalize timestamp to local midnight (00:00:00.000).
 * Used for all-day events to ensure consistent date handling.
 *
 * When all-day toggle is ON, dateMillis should be at local midnight.
 * This prevents timezone issues where a late-evening local time
 * (e.g., Feb 20 18:00 PST = Feb 21 02:00 UTC) displays as the next day.
 */
private fun normalizeToLocalMidnight(millis: Long): Long {
    val cal = JavaCalendar.getInstance()
    cal.timeInMillis = millis
    cal.set(JavaCalendar.HOUR_OF_DAY, 0)
    cal.set(JavaCalendar.MINUTE, 0)
    cal.set(JavaCalendar.SECOND, 0)
    cal.set(JavaCalendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun convertTimezone(
    oldTz: java.util.TimeZone,
    newTz: java.util.TimeZone,
    dateMillis: Long,
    hour: Int,
    minute: Int
): Triple<Long, Int, Int> {
    val oldCal = JavaCalendar.getInstance(oldTz).apply {
        timeInMillis = dateMillis
        set(JavaCalendar.HOUR_OF_DAY, hour)
        set(JavaCalendar.MINUTE, minute)
        set(JavaCalendar.SECOND, 0)
        set(JavaCalendar.MILLISECOND, 0)
    }
    val newCal = JavaCalendar.getInstance(newTz).apply { timeInMillis = oldCal.timeInMillis }
    // Normalize to midnight in the target timezone, not device timezone
    val midnightCal = JavaCalendar.getInstance(newTz).apply {
        timeInMillis = newCal.timeInMillis
        set(JavaCalendar.HOUR_OF_DAY, 0)
        set(JavaCalendar.MINUTE, 0)
        set(JavaCalendar.SECOND, 0)
        set(JavaCalendar.MILLISECOND, 0)
    }
    return Triple(midnightCal.timeInMillis, newCal.get(JavaCalendar.HOUR_OF_DAY), newCal.get(JavaCalendar.MINUTE))
}

/**
 * Dismissible inline notice shown under the device attendee row when the
 * target calendar is on a LOCAL account (no sync adapter): guests are saved
 * but no invitation is delivered. Inline and dismissible per the app's
 * inline-over-modal UX — the guest-editing affordance above stays usable
 * whether the notice shows or is dismissed.
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
 * The editable Attendees row body for the organizer flow. Always tappable
 * (opens the picker); shows the current invitees as compact chips, or an
 * "Add people" placeholder when empty. Holds [Attendee] entities so its
 * label/colour derive from the same canonical address the picker edits.
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
        // No "Attendees" text label — the leading 👥 icon already names the
        // row (kept as its contentDescription), matching the label-less
        // location/time rows. Empty state reads "Add attendees".
        if (attendees.isEmpty()) {
            Text(
                text = stringResource(R.string.attendee_pick_add_people),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Preview a few chips (You pinned first) and collapse the rest to
            // "+N more" so a large invite list doesn't sprawl down the form —
            // tapping the row opens the picker where every name is listed.
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

/** Max attendee chips previewed on the form's Attendees row before collapsing to "+N more". */
private const val ATTENDEE_PREVIEW_LIMIT = 3

