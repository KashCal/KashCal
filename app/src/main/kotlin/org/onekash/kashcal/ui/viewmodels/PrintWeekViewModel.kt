package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import androidx.compose.runtime.Immutable
import androidx.core.text.layoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.planner.lookUpRoomSeriesAllDay
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.ui.components.formatDisplayEventTitle
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.print.PrintCalendar
import org.onekash.kashcal.ui.print.PrintCalendarKey
import org.onekash.kashcal.ui.print.PrintCalendarRow
import org.onekash.kashcal.ui.print.PrintCalendarSection
import org.onekash.kashcal.ui.print.PrintFormats
import org.onekash.kashcal.ui.print.PrintStrings
import org.onekash.kashcal.ui.print.buildPrintWeek
import org.onekash.kashcal.ui.print.formatPrintWeekLabel
import org.onekash.kashcal.ui.print.renderPrintHtml
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/**
 * The print sheet's state. [selected] holds the ticked calendars, always a subset of those in
 * [sections].
 */
@Immutable
data class PrintSheetUiState(
    val isOpen: Boolean = false,
    val weekStart: LocalDate? = null,
    val weekRangeLabel: String = "",
    val includeGrid: Boolean = true,
    val includeDetails: Boolean = true,
    val sections: List<PrintCalendarSection> = emptyList(),
    val selected: Set<PrintCalendarKey> = emptySet(),
    /** True from a Print until the print system takes the job, fails it, or it is cancelled. */
    val isPrinting: Boolean = false,
) {
    /** Every listed calendar row, in sheet order. */
    val rows: List<PrintCalendarRow> get() = sections.flatMap { s -> s.groups.flatMap { it.rows } }
    val listedCount: Int get() = rows.size
    val selectedCount: Int get() = selected.size
    val pageCount: Int get() = (if (includeGrid) 1 else 0) + (if (includeDetails) 1 else 0)
    val canPrint: Boolean get() = pageCount > 0 && selectedCount > 0 && !isPrinting
}

/** What the print sheet asks its host to do. */
sealed interface PrintEvent {
    /** Print [html] as the job [jobName]. */
    data class Request(val html: String, val jobName: String) : PrintEvent

    /** Show [message] in the print sheet, which stays open. */
    data class SheetError(val message: String) : PrintEvent

    /** Show [message] on the home screen; the sheet has closed. */
    data class HomeError(val message: String) : PrintEvent
}

/**
 * Holds the print sheet: the week, the pages and the calendars to print, and the document a
 * Print builds.
 *
 * Nothing is stored: each [open] starts from the week holding the date on screen, both pages,
 * and the calendars visible in KashCal. The week follows KashCal's first-day-of-week setting the
 * way the Week view does ([WeekViewUtils.getWeekStart]), and the document's times KashCal's time
 * format ([DateTimeUtils.getTimePattern]), with the phone's only under "system".
 *
 * Activity-scoped like the other hub sheets' ViewModels, so a recreated Activity keeps it: the
 * host reports every print outcome back ([onPrintStarted], [onPrintFailed], [onPrintCancelled])
 * so [PrintSheetUiState.isPrinting] never sticks.
 */
@HiltViewModel
class PrintWeekViewModel(
    private val displayEventRepository: DisplayEventRepository,
    private val eventReader: EventReader,
    private val dataStore: KashCalDataStore,
    private val context: Context,
    private val workDispatcher: CoroutineDispatcher,
    private val is24HourDevice: () -> Boolean,
    private val localeProvider: () -> Locale,
    private val todayProvider: () -> LocalDate,
) : ViewModel() {

    @Inject
    constructor(
        displayEventRepository: DisplayEventRepository,
        eventReader: EventReader,
        dataStore: KashCalDataStore,
        @ApplicationContext context: Context,
    ) : this(
        displayEventRepository = displayEventRepository,
        eventReader = eventReader,
        dataStore = dataStore,
        context = context,
        workDispatcher = Dispatchers.Default,
        is24HourDevice = { DateFormat.is24HourFormat(context) },
        localeProvider = { Locale.getDefault() },
        todayProvider = { LocalDate.now() },
    )

    private val _uiState = MutableStateFlow(PrintSheetUiState())
    val uiState: StateFlow<PrintSheetUiState> = _uiState.asStateFlow()

    private val _events = Channel<PrintEvent>(Channel.BUFFERED)

    // The document being built, cancelled by dismiss and open so a stale one is never sent.
    private var buildJob: Job? = null

    /** One-shot requests for the host, buffered so none is lost while the host isn't collecting. */
    val events: Flow<PrintEvent> = _events.receiveAsFlow()

    /**
     * Opens the sheet on the week holding [anchor], by KashCal's [firstDayOfWeek] setting (0 for
     * the locale's), with both pages and the visible calendars of [sections] ticked.
     */
    fun open(anchor: LocalDate, firstDayOfWeek: Int, sections: List<PrintCalendarSection>) {
        buildJob?.cancel()
        val weekStart = WeekViewUtils.getWeekStart(anchor, firstDayOfWeek)
        _uiState.value = PrintSheetUiState(
            isOpen = true,
            weekStart = weekStart,
            weekRangeLabel = formatPrintWeekLabel(context, weekStart),
            sections = sections,
            selected = sections.flatMap { s -> s.groups.flatMap { it.rows } }.filterNot { it.hidden }.map { it.key }.toSet(),
        )
    }

    fun previousWeek() = moveWeek(-7)

    fun nextWeek() = moveWeek(7)

    private fun moveWeek(days: Long) = _uiState.update { state ->
        val start = state.weekStart?.plusDays(days) ?: return@update state
        state.copy(weekStart = start, weekRangeLabel = formatPrintWeekLabel(context, start))
    }

    fun toggleGrid() = _uiState.update { it.copy(includeGrid = !it.includeGrid) }

    fun toggleDetails() = _uiState.update { it.copy(includeDetails = !it.includeDetails) }

    fun toggleCalendar(key: PrintCalendarKey) = _uiState.update {
        it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key)
    }

    /**
     * Closes the sheet and drops any print under way: a document still being built is never
     * sent, and the host cancels a launcher that hasn't reached the print system.
     */
    fun dismiss() {
        buildJob?.cancel()
        _uiState.update { it.copy(isOpen = false, isPrinting = false) }
    }

    /**
     * Builds the document off the main thread and sends a [PrintEvent.Request]. Does nothing
     * while [PrintSheetUiState.canPrint] is false, so a second tap while one is under way is
     * ignored. A failure sends [PrintEvent.SheetError] and leaves the sheet usable.
     */
    fun print() {
        val state = _uiState.value
        val weekStart = state.weekStart ?: return
        if (!state.canPrint) return
        _uiState.update { it.copy(isPrinting = true) }
        buildJob = viewModelScope.launch {
            try {
                val request = withContext(workDispatcher) { buildRequest(state, weekStart) }
                _events.send(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Print build failed: ${e.javaClass.simpleName}")
                _uiState.update { it.copy(isPrinting = false) }
                _events.send(PrintEvent.SheetError(context.getString(R.string.print_failed)))
            }
        }
    }

    /** The print system took the job: closes the sheet. */
    fun onPrintStarted() = _uiState.update { it.copy(isOpen = false, isPrinting = false) }

    /**
     * The print failed: the error shows in the sheet while it is open, else on the home screen
     * (a renderer that died after the job was handed over).
     */
    fun onPrintFailed() {
        val open = _uiState.value.isOpen
        _uiState.update { it.copy(isPrinting = false) }
        val message = context.getString(R.string.print_failed)
        viewModelScope.launch {
            _events.send(if (open) PrintEvent.SheetError(message) else PrintEvent.HomeError(message))
        }
    }

    /** The host dropped the print (sheet dismissed, Activity destroyed): Print works again. */
    fun onPrintCancelled() = _uiState.update { it.copy(isPrinting = false) }

    private suspend fun buildRequest(state: PrintSheetUiState, weekStart: LocalDate): PrintEvent.Request {
        val zone = ZoneId.systemDefault()
        val locale = localeProvider()
        val resources = context.resources
        val startMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val roomIds = state.selected.filterIsInstance<PrintCalendarKey.Room>().map { it.id }.toSet()
        val deviceIds = state.selected.filterIsInstance<PrintCalendarKey.Device>().map { it.id }.toSet()

        val events = displayEventRepository.getDisplayEventsForPrint(startMs, endMs, roomIds, deviceIds)
        val seriesAllDay = eventReader.lookUpRoomSeriesAllDay(events)
        val timePattern = DateTimeUtils.getTimePattern(dataStore.timeFormat.first(), is24HourDevice())
        val showEmojis = dataStore.showEventEmojis.first()
        val strip = dataStore.showMultiDayTimedInAllDayStrip.first()

        val printed = state.rows
            .filter { it.key in state.selected }
            .map { PrintCalendar(it.key, it.name, it.color) }
        val formats = PrintFormats(
            locale = locale,
            timePattern = timePattern,
            weekdayPattern = DateTimeUtils.localizedPattern("EEE", locale),
            shortDatePattern = DateTimeUtils.localizedPattern("MMMd", locale),
            dayHeaderPattern = DateTimeUtils.localizedPattern("EEEMMMd", locale),
            allDayLabel = resources.getString(R.string.label_all_day),
            join = { a, b -> resources.getString(R.string.planner_time_range, a, b) },
            moved = { resources.getString(R.string.planner_moved_was, it) },
        )
        val week = buildPrintWeek(
            events = events,
            weekStart = weekStart,
            includeGrid = state.includeGrid,
            includeDetails = state.includeDetails,
            showMultiDayTimedInAllDayStrip = strip,
            roomSeriesAllDayById = seriesAllDay,
            calendars = printed,
            formats = formats,
            title = { formatDisplayEventTitle(it, showEmojis, resources) },
        )

        val shortDate = DateTimeFormatter.ofPattern(formats.shortDatePattern, locale)
        val fullDate = DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("yMMMd", locale), locale)
        val jobName = resources.getString(
            R.string.print_job_name, resources.getString(R.string.app_name), shortDate.format(weekStart)
        )
        val strings = PrintStrings(
            languageTag = locale.toLanguageTag(),
            rtl = locale.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            documentTitle = jobName,
            weekRange = state.weekRangeLabel,
            weekLabel = resources.getString(R.string.print_doc_week),
            detailsLabel = resources.getString(R.string.print_page_details),
            calendarsSummary = resources.getQuantityString(
                R.plurals.print_summary_calendars, state.listedCount, printed.size, state.listedCount
            ),
            allDay = resources.getString(R.string.label_all_day),
            repeats = resources.getString(R.string.rrule_repeats),
            declined = resources.getString(R.string.attendee_status_declined),
            cancelled = resources.getString(R.string.recent_changes_cancelled),
            freeDay = resources.getString(R.string.planner_free_day),
            numbersNote = resources.getString(R.string.print_doc_numbers_note),
            timesShownIn = resources.getString(R.string.print_doc_times_in, zone.getDisplayName(TextStyle.FULL, locale)),
            printedOn = resources.getString(R.string.print_doc_printed, fullDate.format(todayProvider())),
            pageOf = resources.getString(R.string.print_doc_page_of),
        )
        return PrintEvent.Request(renderPrintHtml(week, strings), jobName)
    }

    private companion object {
        const val TAG = "PrintWeekViewModel"
    }
}
