package org.onekash.kashcal.ui.components

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onekash.kashcal.util.location.AddressSuggestion
import org.onekash.kashcal.util.location.LocationSuggestionService
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.util.CalendarIntentData
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar as JavaCalendar
import org.onekash.kashcal.util.DateTimeUtils
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.shared.getReminderOptionsForEventType
import org.onekash.kashcal.ui.shared.ALL_DAY_REMINDER_OPTIONS
import org.onekash.kashcal.ui.shared.TIMED_REMINDER_OPTIONS
import org.onekash.kashcal.ui.components.pickers.CalendarPickerCard
import org.onekash.kashcal.ui.components.pickers.ReminderPickerCard
import org.onekash.kashcal.ui.components.pickers.RecurrencePickerCard
import org.onekash.kashcal.ui.components.pickers.DateTimePickerCard
import org.onekash.kashcal.ui.components.pickers.DateTimeSheet
import org.onekash.kashcal.ui.components.pickers.ActiveDateTimeSheet
import org.onekash.kashcal.ui.components.pickers.isMultiDay
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.domain.rrule.RecurrenceFrequency
import org.onekash.kashcal.domain.rrule.FrequencyOption
import org.onekash.kashcal.domain.rrule.MonthlyPattern
import org.onekash.kashcal.domain.rrule.EndCondition

private const val TAG = "EventFormSheet"

/**
 * Migrate a reminder value when toggling all-day.
 * If the reminder is OFF, keep it.
 * If the reminder is valid for the new type, keep it.
 * Otherwise, reset to REMINDER_OFF.
 */
private fun migrateReminderForAllDayToggle(
    reminderMinutes: Int,
    newIsAllDay: Boolean
): Int {
    if (reminderMinutes == REMINDER_OFF) return REMINDER_OFF

    val validOptions = if (newIsAllDay) ALL_DAY_REMINDER_OPTIONS else TIMED_REMINDER_OPTIONS
    val isValid = validOptions.any { it.minutes == reminderMinutes }

    return if (isValid) reminderMinutes else REMINDER_OFF
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
    val reminder1Minutes: Int = 15,

    // Advanced fields
    val isAllDay: Boolean = false,
    val location: String = "",
    val description: String = "",
    val reminder2Minutes: Int = REMINDER_OFF,
    val rrule: String? = null,
    val timezone: String? = null,  // null = device default

    // UI state
    val availableCalendars: List<Calendar> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,

    // Edit mode
    val editingEventId: Long? = null,
    val isEditMode: Boolean = false,
    val editingOccurrenceTs: Long? = null
)

// Reminder options moved to ui/shared/FormConstants.kt
// Use getReminderOptionsForEventType(isAllDay) to get correct options

/**
 * Event creation/editing bottom sheet with iOS-style UI.
 *
 * @param eventId Event ID for edit mode, null for create mode
 * @param initialStartTs Initial start timestamp (epoch seconds) for new events
 * @param occurrenceTs Occurrence timestamp when editing single occurrence of recurring event
 * @param calendars Available calendars
 * @param defaultCalendarId Default calendar ID for new events
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
    defaultCalendarId: Long?,
    onDismiss: () -> Unit,
    onSave: suspend (EventFormState) -> Result<Event>,
    onDelete: (suspend (Long) -> Result<Unit>)? = null,
    onLoadEvent: (suspend (Long) -> Event?)? = null,
    defaultReminderTimed: Int = 15,
    defaultReminderAllDay: Int = 1440,
    defaultEventDuration: Int = 30,
    onRequestNotificationPermission: ((onResult: (Boolean) -> Unit) -> Unit)? = null,
    locationSuggestionService: LocationSuggestionService? = null,
    timeFormat: String = "system",
    firstDayOfWeek: Int = java.util.Calendar.SUNDAY
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

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

    // Two-tap discard confirmation state
    var initialState by remember { mutableStateOf<EventFormState?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var activeSheet by remember { mutableStateOf(ActiveDateTimeSheet.NONE) }

    // Auto-focus title field
    val titleFocusRequester = remember { FocusRequester() }

    // Perform save with result handling
    val performSave: () -> Unit = {
        // Check if event has a reminder set
        val hasReminder = state.reminder1Minutes != REMINDER_OFF ||
            state.reminder2Minutes != REMINDER_OFF

        // The actual save operation
        val doSave: () -> Unit = {
            coroutineScope.launch {
                state = state.copy(isSaving = true, error = null)
                try {
                    val result = onSave(state)
                    result.fold(
                        onSuccess = { onDismiss() },
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
    LaunchedEffect(eventId) {
        // Filter out read-only calendars (ICS subscriptions) for event creation/editing
        val writableCalendars = calendars.filter { !it.isReadOnly }

        // Default calendar: prefer user's choice, fall back to first writable calendar
        val defaultCalendar = writableCalendars.find { it.id == defaultCalendarId }
            ?: writableCalendars.firstOrNull()

        var newState = state.copy(
            availableCalendars = writableCalendars,
            isLoading = false
        )

        if (eventId != null && onLoadEvent != null) {
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
                val (reminder1, reminder2) = parseRemindersFromEvent(event.reminders)

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
                    selectedCalendarName = eventCalendar?.displayName ?: "",
                    selectedCalendarColor = eventCalendar?.color,
                    isAllDay = event.isAllDay,
                    timezone = event.timezone,
                    location = event.location ?: "",
                    description = event.description ?: "",
                    rrule = effectiveRrule,
                    reminder1Minutes = reminder1,
                    reminder2Minutes = reminder2,
                    editingEventId = eventId,
                    isEditMode = true,
                    editingOccurrenceTs = occurrenceTs
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
                selectedCalendarId = defaultCalendar?.id,
                selectedCalendarName = defaultCalendar?.displayName ?: "",
                selectedCalendarColor = defaultCalendar?.color,
                reminder1Minutes = defaultReminderTimed,
                endHour = computedEndHour,
                endMinute = computedEndMinute
            )

            // Handle initial start time (overrides defaults if provided)
            if (initialStartTs != null) {
                val calendar = JavaCalendar.getInstance()
                calendar.timeInMillis = initialStartTs * 1000
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

                // Parse reminders from event
                val (reminder1, reminder2) = parseRemindersFromEvent(duplicateFrom.reminders)

                // Use source calendar if writable, otherwise fall back to default
                val sourceCalendar = writableCalendars.find { it.id == duplicateFrom.calendarId }
                    ?: defaultCalendar

                newState = newState.copy(
                    title = duplicateFrom.title,
                    location = duplicateFrom.location ?: "",
                    description = duplicateFrom.description ?: "",
                    isAllDay = duplicateFrom.isAllDay,
                    dateMillis = displayStartTs,
                    endDateMillis = displayEndTs,
                    startHour = startCal.get(JavaCalendar.HOUR_OF_DAY),
                    startMinute = startCal.get(JavaCalendar.MINUTE),
                    endHour = endCal.get(JavaCalendar.HOUR_OF_DAY),
                    endMinute = endCal.get(JavaCalendar.MINUTE),
                    selectedCalendarId = sourceCalendar?.id,
                    selectedCalendarName = sourceCalendar?.displayName ?: "",
                    selectedCalendarColor = sourceCalendar?.color,
                    reminder1Minutes = reminder1,
                    reminder2Minutes = reminder2,
                    rrule = null  // Don't copy recurrence (creates independent event)
                )
            }

            // Handle calendar intent - pre-fill from external app (Gmail, Chrome, etc.)
            if (calendarIntentData != null && eventId == null) {
                val startTs = calendarIntentData.startTimeMillis ?: System.currentTimeMillis()
                val endTs = calendarIntentData.endTimeMillis
                    ?: (startTs + 60 * 60 * 1000) // Default 1 hour

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
                    title = calendarIntentData.title ?: "",
                    location = calendarIntentData.location ?: "",
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

        // Store initial state for change detection
        if (initialState == null) {
            initialState = newState
        }
    }

    // Detect changes for discard confirmation
    val hasChanges by remember {
        derivedStateOf {
            val initial = initialState ?: return@derivedStateOf false
            state.title != initial.title ||
                state.dateMillis != initial.dateMillis ||
                state.endDateMillis != initial.endDateMillis ||
                state.startHour != initial.startHour ||
                state.startMinute != initial.startMinute ||
                state.endHour != initial.endHour ||
                state.endMinute != initial.endMinute ||
                state.selectedCalendarId != initial.selectedCalendarId ||
                state.isAllDay != initial.isAllDay ||
                state.location != initial.location ||
                state.description != initial.description ||
                state.reminder1Minutes != initial.reminder1Minutes ||
                state.reminder2Minutes != initial.reminder2Minutes ||
                state.rrule != initial.rrule
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

    // Sheet state with swipe-to-dismiss protection
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            when {
                // Allow any non-dismiss state changes
                newValue != SheetValue.Hidden -> true
                // Allow dismiss while saving (onDismiss will be called after save succeeds)
                state.isSaving -> false
                // No changes - allow dismiss freely
                !hasChanges -> true
                // Has changes + already showing confirm - allow dismiss
                showDiscardConfirm -> true
                // Has changes + first attempt - block & show confirm
                else -> {
                    showDiscardConfirm = true
                    false
                }
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = {
            // Handle scrim tap and back button with same logic as Cancel button
            when {
                state.isSaving -> { /* Block dismiss while saving */ }
                !hasChanges -> onDismiss()
                showDiscardConfirm -> onDismiss()
                else -> showDiscardConfirm = true
            }
        },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .imePadding()
        ) {
            // Header with Cancel/Save buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    when {
                        !hasChanges -> onDismiss()
                        showDiscardConfirm -> onDismiss()
                        else -> showDiscardConfirm = true
                    }
                }) {
                    Text(
                        text = if (showDiscardConfirm) "Discard Changes?" else "Cancel",
                        color = if (showDiscardConfirm) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
                Text(
                    text = if (state.isEditMode) "Edit Event" else "New Event",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                val saveButtonAlpha by animateFloatAsState(
                    targetValue = if (showDiscardConfirm) 0f else 1f,
                    animationSpec = tween(150),
                    label = "saveButtonAlpha"
                )
                TextButton(
                    onClick = { performSave() },
                    enabled = !showDiscardConfirm && state.title.isNotBlank() && !state.isSaving && !hasTimeConflict,
                    modifier = Modifier.alpha(saveButtonAlpha)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save", fontWeight = FontWeight.Bold)
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
                    Spacer(modifier = Modifier.height(16.dp))

                    // Title field
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { state = state.copy(title = it) },
                        label = { Text("Event title") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(titleFocusRequester),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    // All-day toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val newIsAllDay = !state.isAllDay
                                val currentDefault = if (state.isAllDay) defaultReminderAllDay else defaultReminderTimed
                                val newDefault = if (newIsAllDay) defaultReminderAllDay else defaultReminderTimed
                                // Migrate reminder1: if matches current default, use new default; otherwise validate
                                val newReminder1 = if (state.reminder1Minutes == currentDefault) {
                                    newDefault
                                } else {
                                    migrateReminderForAllDayToggle(state.reminder1Minutes, newIsAllDay)
                                }
                                // Migrate reminder2: validate for new type
                                val newReminder2 = migrateReminderForAllDayToggle(state.reminder2Minutes, newIsAllDay)
                                // Normalize to midnight when toggling all-day ON to prevent timezone date shift
                                val normalizedDate = if (newIsAllDay) normalizeToLocalMidnight(state.dateMillis) else state.dateMillis
                                val normalizedEndDate = if (newIsAllDay) normalizeToLocalMidnight(state.endDateMillis) else state.endDateMillis
                                state = state.copy(
                                    isAllDay = newIsAllDay,
                                    dateMillis = normalizedDate,
                                    endDateMillis = normalizedEndDate,
                                    reminder1Minutes = newReminder1,
                                    reminder2Minutes = newReminder2
                                )
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("All-day", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = state.isAllDay,
                            onCheckedChange = { isAllDay ->
                                val currentDefault = if (state.isAllDay) defaultReminderAllDay else defaultReminderTimed
                                val newDefault = if (isAllDay) defaultReminderAllDay else defaultReminderTimed
                                // Migrate reminder1: if matches current default, use new default; otherwise validate
                                val newReminder1 = if (state.reminder1Minutes == currentDefault) {
                                    newDefault
                                } else {
                                    migrateReminderForAllDayToggle(state.reminder1Minutes, isAllDay)
                                }
                                // Migrate reminder2: validate for new type
                                val newReminder2 = migrateReminderForAllDayToggle(state.reminder2Minutes, isAllDay)
                                // Normalize to midnight when toggling all-day ON to prevent timezone date shift
                                val normalizedDate = if (isAllDay) normalizeToLocalMidnight(state.dateMillis) else state.dateMillis
                                val normalizedEndDate = if (isAllDay) normalizeToLocalMidnight(state.endDateMillis) else state.endDateMillis
                                state = state.copy(
                                    isAllDay = isAllDay,
                                    dateMillis = normalizedDate,
                                    endDateMillis = normalizedEndDate,
                                    reminder1Minutes = newReminder1,
                                    reminder2Minutes = newReminder2
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Starts card - opens combined date + time picker
                    DateTimePickerCard(
                        label = "Starts",
                        dateMillis = state.dateMillis,
                        hour = state.startHour,
                        minute = state.startMinute,
                        isAllDay = state.isAllDay,
                        onClick = { activeSheet = ActiveDateTimeSheet.START },
                        timezone = state.timezone,
                        timePattern = timePattern
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Ends card - opens combined date + time picker
                    DateTimePickerCard(
                        label = "Ends",
                        dateMillis = state.endDateMillis,
                        hour = state.endHour,
                        minute = state.endMinute,
                        isAllDay = state.isAllDay,
                        onClick = { activeSheet = ActiveDateTimeSheet.END },
                        isError = hasTimeConflict,
                        errorMessage = if (hasTimeConflict) "End time must be after start time" else null,
                        timezone = state.timezone,
                        timePattern = timePattern
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Calendar picker
                    // Disabled for single occurrence edits (CalDAV doesn't support moving exception to different calendar)
                    CalendarPickerCard(
                        selectedCalendarId = state.selectedCalendarId,
                        selectedCalendarName = state.selectedCalendarName,
                        selectedCalendarColor = state.selectedCalendarColor,
                        availableCalendars = state.availableCalendars,
                        isExpanded = expandedPicker == "calendar",
                        enabled = !(state.isEditMode && state.editingOccurrenceTs != null),
                        onToggle = { expandedPicker = if (expandedPicker == "calendar") null else "calendar" },
                        onSelect = { id, name, color ->
                            state = state.copy(
                                selectedCalendarId = id,
                                selectedCalendarName = name,
                                selectedCalendarColor = color
                            )
                            expandedPicker = null
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Combined alert picker (handles both first and second reminders)
                    // Uses extracted ReminderPickerCard which shows correct options based on isAllDay
                    ReminderPickerCard(
                        reminder1Minutes = state.reminder1Minutes,
                        reminder2Minutes = state.reminder2Minutes,
                        isAllDay = state.isAllDay,
                        onReminder1Change = { minutes ->
                            state = state.copy(reminder1Minutes = minutes)
                        },
                        onReminder2Change = { minutes ->
                            state = state.copy(reminder2Minutes = minutes)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Recurrence picker - only show when NOT editing a single occurrence
                    // Single occurrence edits create exception events which don't have RRULE
                    if (state.editingOccurrenceTs == null) {
                        RecurrencePickerCard(
                            selectedRrule = state.rrule,
                            startDateMillis = state.dateMillis,
                            isExpanded = expandedPicker == "repeat",
                            onToggle = { expandedPicker = if (expandedPicker == "repeat") null else "repeat" },
                            onSelect = { rrule ->
                                state = state.copy(rrule = rrule)
                                // Don't auto-close - let user configure all options
                            },
                            firstDayOfWeek = firstDayOfWeek
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Location field with address autocomplete
                    var locationExpanded by remember { mutableStateOf(false) }
                    var locationSuggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
                    var isLoadingLocationSuggestions by remember { mutableStateOf(false) }
                    var locationSearchJob by remember { mutableStateOf<Job?>(null) }

                    ExposedDropdownMenuBox(
                        expanded = locationExpanded && locationSuggestions.isNotEmpty(),
                        onExpandedChange = { locationExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = state.location,
                            onValueChange = { newValue ->
                                state = state.copy(location = newValue)

                                // Cancel previous search
                                locationSearchJob?.cancel()

                                // Only search if service available AND meaningful input
                                // (5+ chars with letters - supports addresses AND place names)
                                if (locationSuggestionService != null &&
                                    newValue.length >= 5 &&
                                    newValue.any { it.isLetter() }
                                ) {
                                    locationSearchJob = coroutineScope.launch {
                                        delay(300) // Debounce
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
                            label = { Text("Location") },
                            placeholder = { Text("Address, room, or meeting link") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable),
                            singleLine = true,
                            trailingIcon = {
                                if (isLoadingLocationSuggestions) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else if (state.location.isNotEmpty() && locationSuggestionService != null) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
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
                                    modifier = Modifier.height(48.dp) // Material touch target
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Notes field - always visible
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = { state = state.copy(description = it) },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    // Error message
                    if (state.error != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = state.error ?: "",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    // Delete button (edit mode only)
                    if (state.isEditMode && eventId != null && onDelete != null) {
                        Spacer(modifier = Modifier.height(24.dp))

                        if (!showDeleteConfirmation) {
                            OutlinedButton(
                                onClick = { showDeleteConfirmation = true },
                                enabled = !state.isSaving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Delete Event")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showDeleteConfirmation = false },
                                    enabled = !state.isSaving,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            state = state.copy(isSaving = true)
                                            try {
                                                val result = onDelete(eventId)
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
                                        Text("Confirm Delete")
                                    }
                                }
                            }
                        }
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
                    Button(
                        onClick = { performSave() },
                        enabled = state.title.isNotBlank() && !state.isSaving && !hasTimeConflict,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                "Save Event",
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
            selectedDateMillis = state.dateMillis,
            selectedHour = state.startHour,
            selectedMinute = state.startMinute,
            selectedTimezone = state.timezone,
            isAllDay = state.isAllDay,
            use24Hour = use24Hour,
            firstDayOfWeek = firstDayOfWeek,
            onConfirm = { dateMillis, hour, minute, timezone ->
                // Normalize to midnight for all-day events to prevent timezone date shift
                val normalizedDateMillis = if (state.isAllDay) normalizeToLocalMidnight(dateMillis) else dateMillis

                if (state.isAllDay) {
                    // ALL-DAY: Simple clamp - no duration preservation
                    val normalizedEndDate = normalizeToLocalMidnight(state.endDateMillis)
                    val newEndDateMillis = if (normalizedDateMillis > normalizedEndDate) {
                        normalizedDateMillis  // Clamp: end can't be before start
                    } else {
                        normalizedEndDate     // Keep end unchanged
                    }
                    state = state.copy(
                        dateMillis = normalizedDateMillis,
                        endDateMillis = newEndDateMillis,
                        timezone = timezone
                    )
                } else {
                    // TIMED: Simple clamp - no duration preservation
                    val startDateOnly = normalizeToLocalMidnight(normalizedDateMillis)
                    val endDateOnly = normalizeToLocalMidnight(state.endDateMillis)

                    // Helper: calculate end = start + duration, handling midnight overflow
                    fun calcEndPlusDuration(dateMls: Long, startHr: Int, startMin: Int): Triple<Long, Int, Int> {
                        val endMins = startHr * 60 + startMin + defaultEventDuration
                        return if (endMins >= 24 * 60) {
                            val nextDay = dateMls + (24 * 60 * 60 * 1000)
                            Triple(nextDay, (endMins - 24 * 60) / 60, (endMins - 24 * 60) % 60)
                        } else {
                            Triple(dateMls, endMins / 60, endMins % 60)
                        }
                    }

                    val (newEndDate, newEndHour, newEndMinute) = when {
                        // Start date > end date: set end = start + duration
                        startDateOnly > endDateOnly -> {
                            calcEndPlusDuration(normalizedDateMillis, hour, minute)
                        }
                        // Same date: check if start time > end time
                        startDateOnly == endDateOnly -> {
                            val startMins = hour * 60 + minute
                            val endMins = state.endHour * 60 + state.endMinute
                            if (startMins > endMins) {
                                calcEndPlusDuration(state.endDateMillis, hour, minute)
                            } else {
                                Triple(state.endDateMillis, state.endHour, state.endMinute)
                            }
                        }
                        // Start date < end date: keep end unchanged
                        else -> {
                            Triple(state.endDateMillis, state.endHour, state.endMinute)
                        }
                    }

                    state = state.copy(
                        dateMillis = normalizedDateMillis,
                        startHour = hour,
                        startMinute = minute,
                        endDateMillis = newEndDate,
                        endHour = newEndHour,
                        endMinute = newEndMinute,
                        timezone = timezone
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
            selectedDateMillis = state.endDateMillis,
            selectedHour = state.endHour,
            selectedMinute = state.endMinute,
            selectedTimezone = state.timezone,
            isAllDay = state.isAllDay,
            use24Hour = use24Hour,
            firstDayOfWeek = firstDayOfWeek,
            onConfirm = { dateMillis, hour, minute, timezone ->
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
                        endMinute = minute,
                        timezone = timezone
                    )
                } else {
                    state = state.copy(
                        endDateMillis = if (state.isAllDay) normalizeToLocalMidnight(finalEndDateMillis) else finalEndDateMillis,
                        endHour = hour,
                        endMinute = minute,
                        timezone = timezone
                    )
                }
                activeSheet = ActiveDateTimeSheet.NONE
            },
            onDismiss = { activeSheet = ActiveDateTimeSheet.NONE }
        )
    }
}

// ExpandablePickerCard moved to ui/components/pickers/ExpandablePickerCard.kt
// CalendarPickerCard moved to ui/components/pickers/CalendarPicker.kt
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
 * Parse reminders list from event into reminder1Minutes and reminder2Minutes.
 * Returns Pair(reminder1, reminder2) where reminder2 defaults to REMINDER_OFF.
 */
private fun parseRemindersFromEvent(reminders: List<String>?): Pair<Int, Int> {
    if (reminders.isNullOrEmpty()) return Pair(REMINDER_OFF, REMINDER_OFF)

    val reminder1 = parseIso8601DurationToMinutes(reminders.getOrNull(0))
    val reminder2 = parseIso8601DurationToMinutes(reminders.getOrNull(1))

    return Pair(reminder1, reminder2)
}

/**
 * Format date for display with correct timezone handling.
 *
 * Uses DateTimeUtils for proper all-day event handling:
 * - All-day events: Uses UTC to preserve calendar date
 * - Timed events: Uses local timezone
 *
 * @param millis Timestamp in milliseconds
 * @param isAllDay Whether this is for an all-day event
 */
private fun formatDate(millis: Long, isAllDay: Boolean = false): String {
    return DateTimeUtils.formatEventDate(millis, isAllDay)
}

private fun formatTime(hour: Int, minute: Int): String {
    val calendar = JavaCalendar.getInstance()
    calendar.set(JavaCalendar.HOUR_OF_DAY, hour)
    calendar.set(JavaCalendar.MINUTE, minute)
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(calendar.time)
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

// DateTimePickerCard, DateTimeSheet, and InlineDatePickerContent moved to ui/components/pickers/DateTimePicker.kt
// Import DateTimePickerCard, DateTimeSheet, ActiveDateTimeSheet from there

// RecurrencePickerCard and helper components moved to ui/components/pickers/RecurrencePicker.kt
// Import RecurrencePickerCard from there

// Duration presets and time conversion utilities moved to ui/components/pickers/DateTimePicker.kt
// Import isMultiDay, shouldShowSeparatePickers, to24Hour, to12Hour from there

// Recurrence utilities moved to domain/rrule/RruleBuilder.kt and domain/rrule/RruleModels.kt
// Import RruleBuilder, RecurrenceFrequency, FrequencyOption, MonthlyPattern, EndCondition from there
