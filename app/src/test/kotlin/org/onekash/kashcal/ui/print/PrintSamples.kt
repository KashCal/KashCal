package org.onekash.kashcal.ui.print

import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sample weeks for checking printed documents in a browser: several calendars, overlaps, a
 * short event, all-day and multi-day events, a cancelled and a declined event, a right-to-left
 * title, a contact birthday, and events around midnight.
 */
internal class PrintSamples(private val zone: ZoneId, private val sunday: LocalDate) {

    private data class Cal(val id: Long, val name: String, val color: Int)

    private val cals = listOf(
        Cal(1, "Work", 0xFF1E88E5.toInt()),
        Cal(2, "Family", 0xFF43A047.toInt()),
        Cal(3, "Personal", 0xFF8E24AA.toInt()),
        Cal(4, "Kids school", 0xFFFB8C00.toInt()),
        Cal(5, "Birthdays", 0xFF00897B.toInt()),
    )

    val calendars: List<PrintCalendar> = cals.map { PrintCalendar(PrintCalendarKey.Room(it.id), it.name, it.color) }

    private var nextId = 100L

    private fun ev(
        cal: String,
        day: LocalDate,
        h: Int,
        m: Int,
        endDay: LocalDate = day,
        eh: Int,
        em: Int,
        title: String,
        location: String? = null,
        rrule: String? = null,
        status: String = "CONFIRMED",
        declined: Boolean = false,
    ): DisplayEvent.Room = onCalendar(
        PlannerTestEvents.room(
            id = nextId++, startTs = at(day, h, m, zone = zone), endTs = at(endDay, eh, em, zone = zone),
            zone = zone, title = title, rrule = rrule, status = status, declined = declined,
        ),
        cal, location,
    )

    private fun allDay(cal: String, day: LocalDate, days: Int = 1, title: String): DisplayEvent.Room =
        onCalendar(PlannerTestEvents.roomAllDay(id = nextId++, date = day, zone = zone, days = days), cal, null, title)

    private fun onCalendar(event: DisplayEvent.Room, cal: String, location: String?, title: String? = null): DisplayEvent.Room {
        val c = cals.first { it.name == cal }
        return event.copy(
            event = event.event.copy(calendarId = c.id, location = location, title = title ?: event.event.title),
            occurrence = event.occurrence.copy(calendarId = c.id),
            calendar = event.calendar?.copy(id = c.id, displayName = c.name, color = c.color),
        )
    }

    /** Returns the printed title: the age title for the sample birthday, else the stored one. */
    fun title(event: DisplayEvent): String = if (event.title == "Alex") "Alex's 5th Birthday" else event.title

    private fun d(n: Int) = sunday.plusDays(n.toLong())

    fun normalWeek(): List<DisplayEvent> = listOf(
        ev("Family", d(0), 10, 0, eh = 11, em = 30, title = "Brunch with Maya", location = "Café Lumière, 41 Harbor St"),
        ev("Personal", d(0), 16, 0, eh = 17, em = 0, title = "Grocery run"),
        ev("Work", d(1), 9, 0, eh = 9, em = 15, title = "Standup", location = "Meet", rrule = "FREQ=DAILY"),
        ev("Work", d(1), 9, 30, eh = 10, em = 30, title = "Design review: print layouts for the planner and week views", location = "Conf room 4B / Meet"),
        ev("Personal", d(1), 10, 0, eh = 11, em = 0, title = "Dentist (cleaning)", location = "Dr. Okafor, 220 Elm Ave, Suite 3"),
        ev("Personal", d(1), 15, 0, eh = 15, em = 10, title = "Call pharmacy about refill"),
        ev("Kids school", d(1), 18, 30, eh = 19, em = 30, title = "Soccer practice", location = "Riverside Field 2"),
        ev("Work", d(2), 12, 0, eh = 13, em = 0, title = "Optional: brown-bag talk on accessibility testing", declined = true),
        ev("Work", d(2), 13, 0, eh = 17, em = 0, title = "Offsite workshop: 2027 roadmap", location = "Harborview Hotel, Salon B"),
        ev("Work", d(2), 14, 0, eh = 14, em = 30, title = "1:1 with Priya"),
        ev("Work", d(2), 14, 0, eh = 15, em = 0, title = "Vendor call: renewal terms", location = "+1 555 0100, PIN 4471"),
        ev("Kids school", d(2), 14, 15, eh = 14, em = 45, title = "Confirm school pick-up slot"),
        allDay("Kids school", d(3), title = "Parent–teacher conferences"),
        allDay("Birthdays", d(3), title = "Alex"),
        ev("Work", d(3), 9, 0, eh = 9, em = 15, title = "Standup", rrule = "FREQ=DAILY", status = "CANCELLED"),
        ev("Family", d(3), 17, 0, eh = 18, em = 0, title = "اجتماع العائلة"),
        ev("Personal", d(3), 19, 0, eh = 21, em = 0, title = "Book club: The Left Hand of Darkness", location = "Maple Street Library, Community Room (2nd floor)"),
        allDay("Work", d(4), days = 2, title = "Open Source Summit (Lisbon)"),
        ev("Work", d(4), 7, 5, eh = 15, em = 25, title = "Flight TP 1351 BOS → LIS", location = "Logan Terminal E"),
        ev("Work", d(5), 9, 30, eh = 10, em = 15, title = "Keynote", location = "Main hall"),
        ev("Work", d(5), 13, 0, eh = 13, em = 45, title = "Talk: Offline-first calendars", location = "Room 1.08"),
        ev("Work", d(5), 19, 30, eh = 22, em = 0, title = "Speaker dinner", location = "Time Out Market"),
        allDay("Family", d(6), title = "Halloween"),
        ev("Personal", d(6), 10, 0, eh = 10, em = 45, title = "Haircut"),
        ev("Family", d(6), 17, 0, eh = 19, em = 0, title = "Trick-or-treat with the kids"),
    )

    /** A Monday-start week with events running past midnight. */
    fun midnightWeek(monday: LocalDate): List<DisplayEvent> {
        val m = { n: Int -> monday.plusDays(n.toLong()) }
        return listOf(
            ev("Work", m(-1), 22, 0, endDay = m(0), eh = 6, em = 0, title = "Night shift", location = "St. Mary's, Ward 4"),
            ev("Work", m(0), 9, 0, eh = 9, em = 15, title = "Standup", rrule = "FREQ=DAILY"),
            ev("Work", m(2), 0, 15, eh = 1, em = 0, title = "Server maintenance window"),
            ev("Family", m(4), 23, 0, endDay = m(5), eh = 1, em = 30, title = "Fado night", location = "Clube de Fado, Alfama"),
            ev("Family", m(5), 19, 0, endDay = m(6), eh = 1, em = 0, title = "Neighborhood party"),
            ev("Work", m(6), 23, 30, endDay = m(7), eh = 0, em = 30, title = "Red-eye check-in", location = "Logan Terminal E"),
        )
    }
}
