package org.onekash.kashcal.widget

import androidx.datastore.preferences.core.intPreferencesKey

/**
 * Preference keys for MonthWidget Glance state.
 *
 * Used with [PreferencesGlanceStateDefinition] to persist
 * the month navigation offset across widget updates.
 */
object MonthWidgetStateKeys {
    /** Month offset from current month (0 = current, +1 = next, -1 = previous) */
    val MONTH_OFFSET = intPreferencesKey("month_offset")
}
