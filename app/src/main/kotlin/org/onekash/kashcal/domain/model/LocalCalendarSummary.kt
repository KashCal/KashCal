package org.onekash.kashcal.domain.model

import org.onekash.kashcal.data.db.entity.Calendar

/**
 * An offline calendar and how many events it holds, counted as
 * [org.onekash.kashcal.data.db.dao.EventsDao.countUserEventsInCalendar] counts them.
 */
data class LocalCalendarSummary(
    val calendar: Calendar,
    val eventCount: Int,
)
