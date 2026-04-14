package org.onekash.kashcal.ui.viewmodels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
        val result = QuickAddParser.parse(text, referenceTime)
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
                    endTs = DateTimeUtils.utcMidnightToEndOfDay(startTs)
                    timezone = null
                } else {
                    val startTime = result.startTime ?: LocalTime.of(referenceTime.hour, referenceTime.minute)
                    startTs = result.startDate
                        .atTime(startTime)
                        .atZone(zone).toInstant().toEpochMilli()

                    endTs = if (result.endTime != null) {
                        resolveEndTimeMillis(result.startDate, startTime, result.endTime, zone)
                    } else {
                        val durationMinutes = dataStore.defaultEventDuration.first()
                        startTs + durationMinutes * 60 * 1000L
                    }
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

        if (result.title.isBlank() && result.startTime == null && result.location == null) {
            return CalendarIntentData()
        }

        val startTimeMillis = if (result.startTime != null) {
            result.startDate.atTime(result.startTime)
                .atZone(zone).toInstant().toEpochMilli()
        } else if (result.isAllDay) {
            result.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            null
        }

        val endTimeMillis = if (result.endTime != null && result.startTime != null) {
            resolveEndTimeMillis(result.startDate, result.startTime, result.endTime, zone)
        } else if (startTimeMillis != null && result.startTime != null) {
            val durationMinutes = dataStore.defaultEventDuration.first()
            startTimeMillis + durationMinutes * 60 * 1000L
        } else {
            null
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
}

/** Signals that save should redirect to EventFormSheet (default calendar is a device calendar). */
class DeviceCalendarException : Exception("Default calendar is a device calendar")
