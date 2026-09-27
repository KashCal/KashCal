package org.onekash.kashcal.widget

import androidx.datastore.preferences.core.intPreferencesKey

/**
 * Holds [MonthWidget]'s per-instance Glance state keys, which persist the month navigation offset
 * across widget updates.
 */
object MonthWidgetStateKeys {
    /** Months from the current month: 0 is the current month, +1 next, -1 previous. */
    val MONTH_OFFSET = intPreferencesKey("month_offset")
}
