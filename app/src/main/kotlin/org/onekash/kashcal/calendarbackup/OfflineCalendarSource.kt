package org.onekash.kashcal.calendarbackup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.ui.model.exportCalendarName
import org.onekash.kashcal.ui.model.isBuiltInLocal
import org.onekash.kashcal.util.IcsExporter
import javax.inject.Inject

/** An offline calendar as automatic backup sees it. */
data class OfflineCalendar(
    val id: Long,
    /** The name the user sees, the built-in's translated label included. */
    val shownName: String,
    val isBuiltIn: Boolean,
    val color: Int = 0,
)

/** Gives automatic backup the offline calendars and the ICS text of each. */
interface OfflineCalendarSource {

    /** Emits every offline calendar, sorted by shown name, and again when one changes. */
    fun observeCalendars(): Flow<List<OfflineCalendar>>

    /** Returns every offline calendar now, as [observeCalendars] emits them. */
    suspend fun calendars(): List<OfflineCalendar> = observeCalendars().first()

    /**
     * Returns [calendar]'s ICS text as Export calendars builds it, named by its shown name, or
     * null when it has no events.
     */
    suspend fun calendarText(calendar: OfflineCalendar): String?
}

/** Reads offline calendars through [EventCoordinator] and builds their text with [IcsExporter]. */
class EventCoordinatorOfflineCalendarSource @Inject constructor(
    private val coordinator: EventCoordinator,
    private val exporter: IcsExporter,
    @ApplicationContext private val context: Context,
) : OfflineCalendarSource {

    override fun observeCalendars(): Flow<List<OfflineCalendar>> =
        coordinator.getLocalCalendarSummaries { it.exportCalendarName(context.resources) }
            .map { summaries ->
                summaries.map { summary ->
                    OfflineCalendar(
                        id = summary.calendar.id,
                        shownName = summary.calendar.exportCalendarName(context.resources),
                        isBuiltIn = summary.calendar.isBuiltInLocal(),
                        color = summary.calendar.color,
                    )
                }
            }
            // The summaries carry event counts, which change on every event write; the backup
            // only needs the calendars themselves.
            .distinctUntilChanged()

    override suspend fun calendarText(calendar: OfflineCalendar): String? {
        val events = coordinator.getCalendarEventsForExport(calendar.id)
        if (events.isEmpty()) return null
        return exporter.buildCalendarText(events, calendar.shownName)
    }
}
