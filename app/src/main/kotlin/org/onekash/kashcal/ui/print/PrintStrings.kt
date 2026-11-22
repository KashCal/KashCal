package org.onekash.kashcal.ui.print

/**
 * The printout's words, already resolved from string resources, so [renderPrintHtml] stays a
 * pure function.
 *
 * @property languageTag the document language (BCP 47), for `lang` and font choice.
 * @property rtl whether the language is written right to left.
 * @property documentTitle the print job name, also the saved PDF's file name.
 * @property calendarsSummary how many of the listed calendars print ("2 of 7 calendars").
 * @property printedOn the date line in the page footer ("Printed Oct 26, 2026").
 * @property pageOf the page counter with `%1$s` for the page and `%2$s` for the page count, in
 *   the translation's order.
 */
data class PrintStrings(
    val languageTag: String,
    val rtl: Boolean,
    val documentTitle: String,
    val weekRange: String,
    val weekLabel: String,
    val detailsLabel: String,
    val calendarsSummary: String,
    val allDay: String,
    val repeats: String,
    val declined: String,
    val cancelled: String,
    val freeDay: String,
    val numbersNote: String,
    val timesShownIn: String,
    val printedOn: String,
    val pageOf: String,
)
