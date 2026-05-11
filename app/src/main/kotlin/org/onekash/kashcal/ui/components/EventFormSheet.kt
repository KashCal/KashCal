package org.onekash.kashcal.ui.components

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
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

internal fun shouldShowTitleSuggestions(
    currentText: String,
    initialText: String
): Boolean {
    if (currentText.length < MIN_TITLE_PREFIX) return false
    if (currentText == initialText) return false
    return true
}

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
    val editingOccurrenceTs: Long? = null
)

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
 * Event creation/editing bottom sheet with iOS-style UI.
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
    onDelete: (suspend (Long) -> Result<Unit>)? = null,
    onLoadEvent: (suspend (Long) -> Event?)? = null,
    defaultReminderTimed: Int = 15,
    defaultReminderAllDay: Int = 1440,
    defaultEventDuration: Int = 30,
    onRequestNotificationPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null,
    locationSuggestionService: LocationSuggestionService? = null,
    onSuggestTitles: (suspend (String) -> List<org.onekash.kashcal.data.db.dao.TitleSuggestion>)? = null,
    timeFormat: String = "system",
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    // Device calendar edit support
    deviceEventId: Long? = null,
    deviceOccurrenceTs: Long? = null,
    onLoadDeviceEvent: (suspend (Long) -> org.onekash.kashcal.ui.viewmodels.DeviceEventEditData?)? = null,
    onSaveDeviceEvent: (suspend (EventFormState) -> Result<Long>)? = null,
    onDeleteDeviceEvent: (suspend (EventFormState) -> Result<Unit>)? = null,
    deviceCalendarGroups: List<CalendarGroup> = emptyList()
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

    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var activeSheet by remember { mutableStateOf(ActiveDateTimeSheet.NONE) }
    var showColorPicker by remember { mutableStateOf(false) }

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

        // The actual save operation
        val doSave: () -> Unit = {
            coroutineScope.launch {
                state = state.copy(isSaving = true, error = null)
                try {
                    // Route to device event save if applicable
                    val result: Result<*> = if (state.isDeviceCalendar && onSaveDeviceEvent != null) {
                        onSaveDeviceEvent(state)
                    } else {
                        onSave(state)
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

                // For single occurrence edit (occurrenceTs != null), clear rrule
                // Exception events have rrule=null (no recurrence of their own)
                // Matches ical-app pattern: clear recurrence fields for single occurrence edit
                val effectiveRrule = if (occurrenceTs != null) null else event.rrule

                newState = newState.copy(
                    title = event.title,
                    dateMillis = displayStartTs,
                    endDateMillis = displayEndTs,
                    startHour = startCal.get(JavaCalendar.HOUR_OF_DAY),
                    startMinute = startCal.get(JavaCalendar.MINUTE),
                    endHour = endCal.get(JavaCalendar.HOUR_OF_DAY),
                    endMinute = endCal.get(JavaCalendar.MINUTE),
                    selectedCalendarId = event.calendarId,
                    selectedCalendarName = eventCalendar?.displayName.orEmpty(),
                    selectedCalendarColor = eventCalendar?.color,
                    isAllDay = event.isAllDay,
                    timezone = event.timezone,
                    location = event.location.orEmpty(),
                    description = event.description.orEmpty(),
                    rrule = effectiveRrule,
                    reminders = parsedReminders,
                    truncatedReminderCount = truncatedCount,
                    editingEventId = eventId,
                    isEditMode = true,
                    editingOccurrenceTs = occurrenceTs,
                    transp = event.transp,
                    eventColor = event.color
                )
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
                selectedCalendarName = resolvedCal.name,
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
                val sourceCalName = sourceCalendar?.displayName ?: resolvedCal.name
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
                    rrule = calendarIntentData.rrule
                )
            }
        }

        state = newState
    }

    // Reactive calendar update: handles async calendar loading on cold start.
    // The init LaunchedEffect above captures calendars at first composition,
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
                selectedCalendarName = resolved.name,
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
                    selectedCalendarName = cal.displayName,
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

    ModalBottomSheet(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
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
                        .padding(horizontal = 8.dp),
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
                    enabled = state.title.isNotBlank() && !state.isSaving && !hasTimeConflict,
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
                            text = stringResource(R.string.action_save),
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
                    val dropdownOpen = titleSuggestions.isNotEmpty()

                    ExposedDropdownMenuBox(
                        expanded = dropdownOpen,
                        onExpandedChange = { if (!it) titleSuggestions = emptyList() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = state.title,
                            onValueChange = { newValue ->
                                state = state.copy(title = newValue)
                                titleSearchJob?.cancel()
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
                            },
                            placeholder = { Text(stringResource(R.string.label_event_title), style = MaterialTheme.typography.headlineSmall) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(titleFocusRequester)
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineSmall,
                            colors = borderlessFieldColors
                        )

                        ExposedDropdownMenu(
                            expanded = dropdownOpen,
                            onDismissRequest = { titleSuggestions = emptyList() }
                        ) {
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

                    HorizontalDivider()


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
                        onAllDayToggle = toggleAllDay,
                        onStartClick = { activeSheet = ActiveDateTimeSheet.START },
                        onEndClick = { activeSheet = ActiveDateTimeSheet.END },
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
                            onToggle = { showTimezoneSheet = true }
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

                    HorizontalDivider()

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
                        enabled = !(state.isEditMode && state.editingOccurrenceTs != null),
                        onToggle = { expandedPicker = if (expandedPicker == "calendar") null else "calendar" },
                        onSelect = { id, name, color, isDevice ->
                            state = state.copy(
                                selectedCalendarId = id,
                                selectedCalendarName = name,
                                selectedCalendarColor = color,
                                isDeviceCalendar = isDevice
                            )
                            expandedPicker = null
                        }
                    )

                    EventFormRow(
                        icon = Icons.Default.Palette,
                        iconContentDescription = stringResource(R.string.label_event_color),
                        onToggle = { showColorPicker = true }
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

                    if (state.editingOccurrenceTs == null) {
                        RecurrencePickerRow(
                            selectedRrule = state.rrule,
                            startDateMillis = state.dateMillis,
                            isExpanded = expandedPicker == "repeat",
                            onToggle = { expandedPicker = if (expandedPicker == "repeat") null else "repeat" },
                            onSelect = { rrule ->
                                state = state.copy(rrule = rrule)
                            },
                            firstDayOfWeek = firstDayOfWeek
                        )
                    }

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
                                onClick = { state = state.copy(transp = "OPAQUE") },
                                label = { Text(stringResource(R.string.label_busy)) }
                            )
                            FilterChip(
                                selected = state.transp == "TRANSPARENT",
                                onClick = { state = state.copy(transp = "TRANSPARENT") },
                                label = { Text(stringResource(R.string.label_free)) }
                            )
                        }
                    }

                    HorizontalDivider()


                    var locationExpanded by remember { mutableStateOf(false) }
                    var locationSuggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
                    var isLoadingLocationSuggestions by remember { mutableStateOf(false) }
                    var locationSearchJob by remember { mutableStateOf<Job?>(null) }

                    EventFormRow(
                        icon = Icons.Default.LocationOn,
                        iconContentDescription = stringResource(R.string.label_location)
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = locationExpanded && locationSuggestions.isNotEmpty(),
                            onExpandedChange = { locationExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = state.location,
                                onValueChange = { newValue ->
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
                                singleLine = true,
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

                    EventFormRow(
                        icon = Icons.AutoMirrored.Filled.Notes,
                        iconContentDescription = stringResource(R.string.label_notes)
                    ) {
                        OutlinedTextField(
                            value = state.description,
                            onValueChange = { state = state.copy(description = it) },
                            placeholder = { Text(stringResource(R.string.label_notes)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent
                            )
                        )
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
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = state.error.orEmpty(),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    HorizontalDivider()


                    val canDeleteRoom = eventId != null && onDelete != null
                    val canDeleteDevice = state.editingDeviceEventId != null && onDeleteDeviceEvent != null
                    if (state.isEditMode && (canDeleteRoom || canDeleteDevice)) {
                        if (!showDeleteConfirmation) {
                            EventFormRow(
                                icon = Icons.Default.DeleteOutline,
                                iconTint = MaterialTheme.colorScheme.error,
                                iconContentDescription = stringResource(R.string.action_delete_event),
                                onToggle = { showDeleteConfirmation = true },
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
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            state = state.copy(isSaving = true)
                                            try {
                                                val result: Result<Unit> = if (canDeleteDevice && state.editingDeviceEventId != null) {
                                                    onDeleteDeviceEvent!!(state)
                                                } else if (canDeleteRoom && eventId != null) {
                                                    onDelete!!(eventId)
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
                                    },
                                    enabled = !state.isSaving,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    if (state.isSaving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onError
                                        )
                                    } else {
                                        Text(stringResource(R.string.action_confirm_delete))
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }

                // Sticky bottom save button
                HorizontalDivider()
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
                        enabled = state.title.isNotBlank() && !state.isSaving && !hasTimeConflict,
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
                                stringResource(R.string.action_save_event),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
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
 * Parse ISO 8601 duration string to minutes.
 * Supports formats like: "-PT15M", "-PT1H", "-PT1H30M", "-P1D", "-P1DT9H"
 * Returns REMINDER_OFF if the duration cannot be parsed.
 */
private fun parseIso8601DurationToMinutes(duration: String?): Int {
    if (duration.isNullOrBlank()) return REMINDER_OFF

    try {
        // Remove leading minus sign (reminder is always "before")
        val normalized = duration.trimStart('-')

        // Must start with P
        if (!normalized.startsWith("P")) return REMINDER_OFF

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
            remaining = remaining.substring(tIndex + 1)
        } else if (tIndex == 0) {
            remaining = remaining.substring(1)
        } else {
            // No T, could be just days like "P1D"
            val dayMatch = Regex("(\\d+)D").find(remaining)
            if (dayMatch != null) {
                totalMinutes += dayMatch.groupValues[1].toInt() * 1440
            }
            return if (totalMinutes > 0) totalMinutes else REMINDER_OFF
        }

        // Parse hours
        val hourMatch = Regex("(\\d+)H").find(remaining)
        if (hourMatch != null) {
            totalMinutes += hourMatch.groupValues[1].toInt() * 60
        }

        // Parse minutes
        val minuteMatch = Regex("(\\d+)M").find(remaining)
        if (minuteMatch != null) {
            totalMinutes += minuteMatch.groupValues[1].toInt()
        }

        return if (totalMinutes > 0) totalMinutes else 0 // 0 means "at time of event"
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse duration: $duration", e)
        return REMINDER_OFF
    }
}

/**
 * Parse reminders list from event into List<Int> of minutes.
 * Takes first MAX_REMINDERS (5), computes truncation count from alarmCount.
 * Returns Pair(reminderMinutes, truncatedCount).
 */
private fun parseRemindersFromEvent(reminders: List<String>?, alarmCount: Int = 0): Pair<List<Int>, Int> {
    if (reminders.isNullOrEmpty()) return Pair(emptyList(), 0)

    val parsed = reminders.take(MAX_REMINDERS).mapNotNull { duration ->
        val minutes = parseIso8601DurationToMinutes(duration)
        if (minutes >= 0) minutes else null
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

