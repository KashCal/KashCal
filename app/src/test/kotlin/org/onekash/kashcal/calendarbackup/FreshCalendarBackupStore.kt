package org.onekash.kashcal.calendarbackup

import android.content.Context
import org.robolectric.RuntimeEnvironment

/** Returns a [CalendarBackupStore] over the real preferences file, emptied first (Robolectric). */
fun freshCalendarBackupStore(): CalendarBackupStore {
    val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences(CalendarBackupStore.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    return CalendarBackupStore(prefs)
}
