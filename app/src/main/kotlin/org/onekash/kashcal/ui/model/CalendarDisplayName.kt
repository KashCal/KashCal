package org.onekash.kashcal.ui.model

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.data.contacts.ContactEventType
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer

/**
 * Resolves the user-facing display name for a calendar.
 *
 * The built-in on-device calendar is stored with a fixed English display name
 * ([LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME]) that must not be mutated
 * (it is the stable DB fallback), so its localized label is resolved here at display
 * time via [R.string.calendar_local]. Contact event calendars keep the localized name
 * their repositories persist at creation. All other calendars use their stored name.
 */
fun Calendar.localizedDisplayName(resources: Resources): String = when (caldavUrl) {
    LocalCalendarInitializer.LOCAL_CALENDAR_URL -> resources.getString(R.string.calendar_local)
    else -> ContactEventType.fromCaldavUrl(caldavUrl)
        ?.calendarDisplayName(resources)
        ?: displayName
}
