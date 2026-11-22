package org.onekash.kashcal.calendarbackup

import org.onekash.kashcal.util.foldFileNameCase
import org.onekash.kashcal.util.sanitizeExportBaseName
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An offline calendar as automatic backup names its files. */
data class BackupCalendarName(
    val calendarId: Long,
    val displayName: String,
    val isBuiltIn: Boolean,
)

/**
 * Names the files automatic backup writes and decides which dated copies go.
 *
 * Each offline calendar has a latest file, `<base>.ics`, whose name never changes so file-sync
 * apps keep following it, and older copies `<base>_<UTC time>.ics`. Only names matching
 * [isDatedCopyOf] for a calendar's base are ever deleted.
 */
object CalendarBackupNames {

    /** The built-in calendar's base name in every language. */
    const val BUILT_IN_BASE = "Local"

    private const val EXTENSION = ".ics"
    private const val EMPTY_NAME_BASE = "calendar"

    // The tail every dated copy name ends with: "yyyyMMdd'T'HHmmss'Z'.ics".
    private const val STAMP_AND_EXTENSION_LENGTH = 20

    private val DATED_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * Returns each calendar's base name by id, unique after case folding ([foldFileNameCase]).
     *
     * A calendar keeps its cleaned name ([sanitizeExportBaseName]) unless that is empty or shared,
     * case folded ([foldFileNameCase]), with another calendar's (the built-in's [BUILT_IN_BASE]
     * included); then it gets `-<calendarId>` appended. A plain name equal to a suffixed one is
     * suffixed as well, repeated until none is ("Work", "Work!" and "Work 17" become Work-17,
     * Work-18, Work-17-5). Two suffixed names can't match: ids are distinct and contain no `-`. The
     * built-in is never suffixed.
     */
    fun planBaseNames(calendars: List<BackupCalendarName>): Map<Long, String> {
        val cleaned = calendars.associate { calendar ->
            calendar.calendarId to if (calendar.isBuiltIn) {
                BUILT_IN_BASE
            } else {
                sanitizeExportBaseName(calendar.displayName, fallback = "")
            }
        }
        val keyCounts = cleaned.values.groupingBy { foldFileNameCase(it) }.eachCount()
        val suffixed = calendars
            .filter { !it.isBuiltIn }
            .map { it.calendarId }
            .filter { id ->
                val clean = cleaned.getValue(id)
                clean.isEmpty() || keyCounts.getValue(foldFileNameCase(clean)) > 1
            }
            .toMutableSet()

        fun nameOf(id: Long): String {
            val clean = cleaned.getValue(id)
            return if (id in suffixed) "${clean.ifEmpty { EMPTY_NAME_BASE }}-$id" else clean
        }

        while (true) {
            val suffixedKeys = suffixed.map { foldFileNameCase(nameOf(it)) }.toSet()
            val clashing = calendars
                .filter { !it.isBuiltIn && it.calendarId !in suffixed }
                .map { it.calendarId }
                .filter { foldFileNameCase(nameOf(it)) in suffixedKeys }
            if (clashing.isEmpty()) break
            suffixed += clashing
        }
        return calendars.associate { it.calendarId to nameOf(it.calendarId) }
    }

    /** Returns the latest file's name for [base]. */
    fun latestFileName(base: String): String = base + EXTENSION

    /** Returns the dated copy name for [base] at [time], in UTC to the second. */
    fun datedCopyName(base: String, time: Instant): String =
        "${base}_${DATED_STAMP.format(time)}$EXTENSION"

    /** Returns true when [fileName] is a dated copy this feature names for [base]. */
    fun isDatedCopyOf(fileName: String, base: String): Boolean = datedCopyPattern(base).matches(fileName)

    // Case is ignored: on shared storage "work_…" and "Work_…" are the same calendar's copies.
    private fun datedCopyPattern(base: String) =
        Regex("^${Regex.escape(base)}_\\d{8}T\\d{6}Z\\.ics$", RegexOption.IGNORE_CASE)

    /**
     * Returns the dated copies in [names] to delete so that, with the latest file, [keep] files
     * remain: the newest `keep - 1` by the time in their names stay. [justAdded] counts as the
     * newest whatever its name, so a copy named by a clock that ran behind can't push itself out.
     * [keep] is limited to 1..30.
     */
    fun datedCopiesToDelete(names: List<String>, keep: Int, justAdded: String?): List<String> {
        val newestFirst = names.sortedByDescending { it.takeLast(STAMP_AND_EXTENSION_LENGTH) }
        val ordered = newestFirst.filter { it == justAdded } + newestFirst.filter { it != justAdded }
        return ordered.drop(keep.coerceIn(CalendarBackupSettings.MIN_KEEP, CalendarBackupSettings.MAX_KEEP) - 1)
    }

    /**
     * Returns the files among [names] that a count of [keep] removes for [base]: its dated copies
     * ([isDatedCopyOf]) beyond the count ([datedCopiesToDelete]).
     */
    fun copiesBeyondCount(names: List<String>, base: String, keep: Int, justAdded: String?): List<String> {
        val pattern = datedCopyPattern(base)
        return datedCopiesToDelete(names.filter { pattern.matches(it) }, keep, justAdded)
    }
}
