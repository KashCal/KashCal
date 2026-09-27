package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.quickadd.QuickAddParser
import org.onekash.kashcal.domain.quickadd.QuickAddResult
import org.onekash.kashcal.util.CalendarIntentData
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val eventCoordinator: EventCoordinator,
    private val dataStore: KashCalDataStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    private val _parseResult = MutableStateFlow(
        QuickAddResult(
            title = "",
            startDate = LocalDate.now()
        )
    )
    val parseResult: StateFlow<QuickAddResult> = _parseResult

    private val _isSaveEnabled = MutableStateFlow(false)
    val isSaveEnabled: StateFlow<Boolean> = _isSaveEnabled

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private var referenceTime: LocalDateTime = LocalDateTime.now()

    // Share-target fallback location, pinned to the seeded text. Applies only while the input
    // matches the seed, so the snapshotFlow re-emit after seedInput() keeps it. Cleared on edit.
    private var seededFallbackLocation: String? = null
    private var seededText: String? = null

    // Read synchronously in onInputChanged and seedInput; the parser uses it as a weekly
    // rule's WKST.
    private val firstDayOfWeek: StateFlow<Int> = dataStore.firstDayOfWeek
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.FIRST_DAY_SYSTEM)

    fun setReferenceTime(reference: LocalDateTime) {
        referenceTime = reference
    }

    fun resetState() {
        _inputText.value = ""
        _parseResult.value = QuickAddResult(title = "", startDate = LocalDate.now())
        _isSaveEnabled.value = false
        _isSaving.value = false
        referenceTime = LocalDateTime.now()
        seededFallbackLocation = null
        seededText = null
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
        // The user edited the seed: drop the share-supplied fallback so later edits don't
        // silently inherit a URL from the original share.
        if (seededText != null && text != seededText) {
            seededFallbackLocation = null
            seededText = null
        }
        val result = QuickAddParser.parse(
            input = text,
            reference = referenceTime,
            locale = Locale.getDefault(),
            firstDayOfWeek = firstDayOfWeek.value,
        )
        val merged = applySeededFallback(result)
        _parseResult.value = merged
        _isSaveEnabled.value = merged.title.isNotBlank()
    }

    /**
     * Seeds the dialog from shared text (the `Intent.ACTION_SEND` share target).
     *
     * Parses [text] against the current reference time and uses [location] only when the parser
     * finds no location of its own. The parser wins, so "Lunch at Mission Cantina" with a
     * fallback URL still records the parsed venue.
     *
     * The fallback [location] is pinned to [text]: a later [onInputChanged] with the same string
     * keeps it (so the dialog's snapshotFlow first emit doesn't drop it), and any edit clears it.
     *
     * Callers should call [setReferenceTime] first so "tomorrow" in the shared text resolves
     * against the share's arrival, not the date the calling UI was showing.
     */
    fun seedInput(text: String, location: String?) {
        seededFallbackLocation = location
        seededText = text
        _inputText.value = text
        val result = QuickAddParser.parse(
            input = text,
            reference = referenceTime,
            locale = Locale.getDefault(),
            firstDayOfWeek = firstDayOfWeek.value,
        )
        val merged = applySeededFallback(result)
        _parseResult.value = merged
        _isSaveEnabled.value = merged.title.isNotBlank()
    }

    private fun applySeededFallback(result: QuickAddResult): QuickAddResult {
        val fallback = seededFallbackLocation
        return if (result.location == null && fallback != null) {
            result.copy(location = fallback)
        } else {
            result
        }
    }

    suspend fun save(): Result<Event> {
        if (!_isSaving.compareAndSet(expect = false, update = true)) {
            return Result.failure(IllegalStateException("Save already in progress"))
        }
        return runCatching {
            withContext(ioDispatcher) {
                val result = _parseResult.value
                val zone = ZoneId.systemDefault()
                val isAllDay = result.isAllDay

                val startTs: Long
                val endTs: Long
                val timezone: String?

                if (isAllDay) {
                    startTs = result.startDate
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    endTs = utcEndOfDayFor(result.endDate ?: result.startDate)
                    timezone = null
                } else {
                    val startTime = result.startTime ?: LocalTime.of(referenceTime.hour, referenceTime.minute)
                    startTs = result.startDate
                        .atTime(startTime)
                        .atZone(zone).toInstant().toEpochMilli()
                    endTs = resolveTimedEndMillis(result, startTs, startTime, zone)
                    timezone = zone.id
                }

                val reminderMinutes = if (isAllDay) {
                    dataStore.defaultAllDayReminder.first()
                } else {
                    dataStore.defaultReminderMinutes.first()
                }
                val reminders = if (reminderMinutes == KashCalDataStore.REMINDER_OFF) {
                    null
                } else {
                    listOf(ContactEventUtils.minutesToIsoDuration(reminderMinutes))
                }

                val defaultCalPref = dataStore.getDefaultCalendar()
                val calendarId = when (defaultCalPref) {
                    is DefaultCalendar.Room -> defaultCalPref.calendarId
                    is DefaultCalendar.Device -> throw DeviceCalendarException()
                    null -> eventCoordinator.getLocalCalendarId()
                }

                val now = System.currentTimeMillis()
                val event = Event(
                    // Blank uid: EventWriter mints the @kashcal.onekash.org UID, so the
                    // UI layer isn't a second minting authority.
                    uid = "",
                    calendarId = calendarId,
                    title = result.title,
                    startTs = startTs,
                    endTs = endTs,
                    timezone = timezone,
                    isAllDay = isAllDay,
                    location = result.location,
                    description = result.note,
                    rrule = result.rrule,
                    categories = result.categories.ifEmpty { null },
                    reminders = reminders,
                    dtstamp = now,
                    createdAt = now,
                    updatedAt = now
                )

                eventCoordinator.createEvent(event, calendarId)
            }
        }.also {
            _isSaving.value = false
        }
    }

    suspend fun toCalendarIntentData(): CalendarIntentData {
        val result = _parseResult.value
        val zone = ZoneId.systemDefault()

        // Nothing parsed: open the full form timed at the next hour on the result's start date.
        // QuickAddResult.isAllDay defaults to (startTime == null), which would otherwise make
        // it all-day whenever the user taps Expand without typing.
        val nothingParsed = result.title.isBlank() && result.startTime == null &&
            result.location == null && result.endDate == null && result.rrule == null &&
            result.categories.isEmpty() && result.note == null
        if (nothingParsed) {
            val nextHour = (LocalTime.now().hour + 1) % 24
            val startMs = result.startDate.atTime(LocalTime.of(nextHour, 0))
                .atZone(zone).toInstant().toEpochMilli()
            return CalendarIntentData(startTimeMillis = startMs)
        }

        val startTimeMillis = when {
            result.startTime != null ->
                result.startDate.atTime(result.startTime).atZone(zone).toInstant().toEpochMilli()
            result.isAllDay ->
                result.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            else -> {
                val nextHour = (LocalTime.now().hour + 1) % 24
                result.startDate.atTime(LocalTime.of(nextHour, 0))
                    .atZone(zone).toInstant().toEpochMilli()
            }
        }

        val endTimeMillis = when {
            result.isAllDay -> utcEndOfDayFor(result.endDate ?: result.startDate)
            result.startTime != null ->
                resolveTimedEndMillis(result, startTimeMillis, result.startTime, zone)
            else -> null
        }

        return CalendarIntentData(
            title = result.title.ifBlank { null },
            description = result.note,
            location = result.location,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            isAllDay = result.isAllDay,
            rrule = result.rrule,
            categories = result.categories
        )
    }

    private fun resolveEndTimeMillis(
        startDate: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        zone: ZoneId
    ): Long {
        val endDate = if (endTime < startTime) startDate.plusDays(1) else startDate
        return endDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
    }

    private fun utcEndOfDayFor(date: LocalDate): Long {
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return DateTimeUtils.utcMidnightToEndOfDay(utcMidnight)
    }

    private suspend fun resolveTimedEndMillis(
        result: QuickAddResult,
        startMillis: Long,
        startTime: LocalTime,
        zone: ZoneId
    ): Long = when {
        result.endDate != null && result.endTime != null ->
            result.endDate.atTime(result.endTime).atZone(zone).toInstant().toEpochMilli()
        result.endDate != null ->
            result.endDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli() +
                dataStore.defaultEventDuration.first() * 60 * 1000L
        result.endTime != null ->
            resolveEndTimeMillis(result.startDate, startTime, result.endTime, zone)
        else ->
            startMillis + dataStore.defaultEventDuration.first() * 60 * 1000L
    }
}

/**
 * Returned as the failure of [QuickAddViewModel.save] when the default calendar is a device
 * calendar; MainActivity then opens the event form with [QuickAddViewModel.toCalendarIntentData].
 */
class DeviceCalendarException : Exception("Default calendar is a device calendar")
