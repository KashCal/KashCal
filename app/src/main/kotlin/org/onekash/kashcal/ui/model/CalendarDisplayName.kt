package org.onekash.kashcal.ui.model

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.data.contacts.ContactEventType
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer

/**
 * Returns the user-facing name of a calendar.
 *
 * The built-in local calendar is stored with a fixed English name
 * ([LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME]) that must not be changed (it is the
 * stable DB fallback), so its localized label is resolved here from [R.string.calendar_local].
 * Contact event calendars show the localized name their repositories store at creation. All
 * other calendars use their stored name.
 */
fun Calendar.localizedDisplayName(resources: Resources): String = when (caldavUrl) {
    LocalCalendarInitializer.LOCAL_CALENDAR_URL -> resources.getString(R.string.calendar_local)
    else -> ContactEventType.fromCaldavUrl(caldavUrl)
        ?.calendarDisplayName(resources)
        ?: displayName
}

/**
 * Returns true for the built-in offline calendar, which can be recolored but not renamed or
 * deleted ([org.onekash.kashcal.domain.initializer.LocalCalendarRefusal.BUILT_IN]).
 */
fun Calendar.isBuiltInLocal(): Boolean = caldavUrl == LocalCalendarInitializer.LOCAL_CALENDAR_URL

/**
 * Returns the name an exported .ics file and its calendar header use: the user-facing name
 * ([localizedDisplayName]), so the built-in calendar exports under its translated name.
 */
fun Calendar.exportCalendarName(resources: Resources): String = localizedDisplayName(resources)
