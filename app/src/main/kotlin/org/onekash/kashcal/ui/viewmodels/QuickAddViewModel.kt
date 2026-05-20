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
import java.util.UUID
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

    // Cached for synchronous reads in onInputChanged (parser WKST routing).
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
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
        val result = QuickAddParser.parse(
            input = text,
            reference = referenceTime,
            locale = Locale.getDefault(),
            firstDayOfWeek = firstDayOfWeek.value,
        )
        _parseResult.value = result
        _isSaveEnabled.value = result.title.isNotBlank()
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
                    uid = UUID.randomUUID().toString(),
                    calendarId = calendarId,
                    title = result.title,
                    startTs = startTs,
                    endTs = endTs,
                    timezone = timezone,
                    isAllDay = isAllDay,
                    location = result.location,
                    rrule = result.rrule,
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
            location = result.location,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            isAllDay = result.isAllDay,
            rrule = result.rrule
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

/** Signals that save should redirect to EventFormSheet (default calendar is a device calendar). */
class DeviceCalendarException : Exception("Default calendar is a device calendar")
