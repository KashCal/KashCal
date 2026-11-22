package org.onekash.kashcal.calendarbackup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Scripted [OfflineCalendarSource] shared by the backup tests. */
class FakeOfflineCalendarSource : OfflineCalendarSource {

    /** The calendars [observeCalendars] emits; replace the list to emit a change. */
    val calendarsFlow = MutableStateFlow<List<OfflineCalendar>>(emptyList())

    /** Edits the calendar list in place; each edit emits on [observeCalendars]. */
    val calendars = object : AbstractMutableList<OfflineCalendar>() {
        override val size get() = calendarsFlow.value.size
        override fun get(index: Int) = calendarsFlow.value[index]
        override fun add(index: Int, element: OfflineCalendar) {
            calendarsFlow.value = calendarsFlow.value.toMutableList().apply { add(index, element) }
        }
        override fun removeAt(index: Int) = calendarsFlow.value[index].also {
            calendarsFlow.value = calendarsFlow.value.toMutableList().apply { removeAt(index) }
        }
        override fun set(index: Int, element: OfflineCalendar) = calendarsFlow.value[index].also {
            calendarsFlow.value = calendarsFlow.value.toMutableList().apply { set(index, element) }
        }
    }

    /** Each calendar's ICS text; a calendar without one has no events. */
    val texts = mutableMapOf<Long, String>()

    /** Runs before each [calendarText] call, to change state in the middle of a run. */
    var onCalendarText: suspend (Long) -> Unit = {}

    /** Throws for these calendars instead of returning text. */
    val failing = mutableSetOf<Long>()

    fun add(id: Long, name: String, text: String?, builtIn: Boolean = false) {
        calendars += OfflineCalendar(id, name, builtIn)
        if (text != null) texts[id] = text
    }

    override fun observeCalendars(): Flow<List<OfflineCalendar>> = calendarsFlow

    override suspend fun calendarText(calendar: OfflineCalendar): String? {
        onCalendarText(calendar.id)
        if (calendar.id in failing) throw IllegalStateException("export failed")
        return texts[calendar.id]
    }
}
