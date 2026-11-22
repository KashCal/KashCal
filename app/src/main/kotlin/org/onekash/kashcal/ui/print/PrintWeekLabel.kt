package org.onekash.kashcal.ui.print

import android.content.Context
import android.text.format.DateUtils
import java.time.LocalDate
import java.time.ZoneId

/**
 * Returns the localized range of the 7 days from [start] ("Oct 25 – 31, 2026"), the label the
 * print sheet's week row and the printout's heading show.
 */
internal fun formatPrintWeekLabel(context: Context, start: LocalDate): String {
    val zone = ZoneId.systemDefault()
    val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
    // Noon of the last day, so the range can't spill into the next day.
    val to = start.plusDays(6).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateRange(context, from, to, flags)
}
