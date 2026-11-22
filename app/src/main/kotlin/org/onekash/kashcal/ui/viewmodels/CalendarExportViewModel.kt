package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.ui.model.exportCalendarName
import org.onekash.kashcal.util.IcsExporter
import javax.inject.Inject

private const val TAG = "CalendarExportViewModel"

/** Where a calendar export started, which decides where its failure shows. */
enum class CalendarExportOrigin {
    /** The Export calendars sheet: it stays open, and a file failure shows under the row. */
    EXPORT_SHEET,

    /** An offline calendar's own sheet: it closes first, so every failure goes out as an event. */
    CALENDAR_SHEET,
}

/** Why a calendar export made no file, for a message in the user's language. */
enum class CalendarExportFailure { CALENDAR_NOT_FOUND, NO_EVENTS, FILE_FAILED }

/** Export results the screen acts on once each: open the share sheet, or show a snackbar. */
sealed interface CalendarExportEvent {
    data class Share(val uri: Uri) : CalendarExportEvent
    data class Failed(val reason: CalendarExportFailure) : CalendarExportEvent
}

/**
 * The Export calendars sheet's state: [busyCalendarId] is the calendar being exported, during
 * which no other export starts; [showPreparing] turns on once that export has run longer than
 * [CalendarExportViewModel.PREPARING_DELAY_MS]; [failedCalendarId] is the row whose file couldn't
 * be made.
 */
@Immutable
data class CalendarExportState(
    val busyCalendarId: Long? = null,
    val showPreparing: Boolean = false,
    val failedCalendarId: Long? = null,
)

/**
 * Exports one Room calendar to an .ics file and hands it to the screen to share, for the Export
 * calendars sheet and an offline calendar's sheet. The file is built on [ioDispatcher] in the
 * view model's scope, so a rotation doesn't cancel it and the sheet keeps drawing. Events wait in
 * [events] until the screen collects them; there is no success message, since the share sheet
 * itself confirms the export.
 */
@HiltViewModel
class CalendarExportViewModel @Inject constructor(
    private val eventCoordinator: EventCoordinator,
    private val icsExporter: IcsExporter,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarExportState())
    val state: StateFlow<CalendarExportState> = _state.asStateFlow()

    private val _events = Channel<CalendarExportEvent>(Channel.BUFFERED)
    val events: Flow<CalendarExportEvent> = _events.receiveAsFlow()

    /** Each calendar's event count by id, or null until the first count arrives. */
    val eventCounts: StateFlow<Map<Long, Int>?> = eventCoordinator.getCalendarEventCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Each account's login by account id, for headers two accounts share. */
    val accountLogins: StateFlow<Map<Long, String>> = eventCoordinator.getAllAccounts()
        .map { accounts -> accounts.associate { it.id to it.email } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    // Bumped when the sheet closes, so a failure from an export started before that is dropped.
    private var sheetSession = 0

    /**
     * Exports calendar [calendarId] unless an export is running. Starting clears any earlier
     * failure. A calendar that is gone or has no events leaves no failure in the Export calendars
     * sheet, whose live list already drops the row or shows "No events"; from [origin]
     * [CalendarExportOrigin.CALENDAR_SHEET] every failure is sent as [CalendarExportEvent.Failed].
     */
    fun export(calendarId: Long, origin: CalendarExportOrigin) {
        if (_state.value.busyCalendarId != null) return
        _state.value = CalendarExportState(busyCalendarId = calendarId)
        val session = sheetSession
        viewModelScope.launch {
            val preparing = launch {
                delay(PREPARING_DELAY_MS)
                _state.update { it.copy(showPreparing = true) }
            }
            try {
                val result = buildFile(calendarId)
                preparing.cancel()
                when (result) {
                    is CalendarExportEvent.Share -> _events.send(result)
                    is CalendarExportEvent.Failed -> when {
                        origin == CalendarExportOrigin.CALENDAR_SHEET -> _events.send(result)
                        result.reason == CalendarExportFailure.FILE_FAILED && session == sheetSession ->
                            _state.update { it.copy(failedCalendarId = calendarId) }
                    }
                }
            } finally {
                preparing.cancel()
                _state.update { it.copy(busyCalendarId = null, showPreparing = false) }
            }
        }
    }

    /** Forgets the failure shown under a row, and any still to come, when the sheet closes. */
    fun clearFailure() {
        sheetSession++
        _state.update { it.copy(failedCalendarId = null) }
    }

    private suspend fun buildFile(calendarId: Long): CalendarExportEvent = try {
        val calendar = eventCoordinator.getCalendarById(calendarId)
        val events = calendar?.let { eventCoordinator.getCalendarEventsForExport(calendarId) }
        when {
            calendar == null -> CalendarExportEvent.Failed(CalendarExportFailure.CALENDAR_NOT_FOUND)
            events.isNullOrEmpty() -> CalendarExportEvent.Failed(CalendarExportFailure.NO_EVENTS)
            else -> {
                val name = calendar.exportCalendarName(context.resources)
                val uri = withContext(ioDispatcher) { icsExporter.exportCalendar(context, events, name) }
                    .getOrThrow()
                CalendarExportEvent.Share(uri)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Export of calendar $calendarId failed: ${e.javaClass.simpleName}")
        CalendarExportEvent.Failed(CalendarExportFailure.FILE_FAILED)
    }

    companion object {
        /** How long an export runs before its row says "Preparing…". */
        const val PREPARING_DELAY_MS = 300L

        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
