package org.onekash.kashcal.domain.initializer

/** Why an offline calendar create, rename, recolor or delete was refused. */
enum class LocalCalendarRefusal {
    /** The name is empty after trimming. */
    BLANK_NAME,

    /**
     * The trimmed name matches another offline calendar's name, ignoring case. The built-in
     * calendar is matched by its translated label, not its stored English name.
     */
    DUPLICATE_NAME,

    /**
     * The trimmed name differs from another offline calendar's, but both would give the same
     * backup file name ([org.onekash.kashcal.util.exportNameKey]), such as "Work!" next to
     * "Work". [LocalCalendarException.conflictingName] holds the other calendar's shown name.
     */
    SIMILAR_NAME,

    /** No calendar has that id. */
    NOT_FOUND,

    /** The calendar is not under an offline account ([LocalCalendarInitializer.isLocalAccount]). */
    NOT_LOCAL,

    /** The built-in calendar can be recolored but not renamed or deleted. */
    BUILT_IN,
}

/**
 * Carries a [LocalCalendarRefusal] in a failed [Result]. [conflictingName] is the other
 * calendar's shown name for [LocalCalendarRefusal.SIMILAR_NAME], otherwise null.
 */
class LocalCalendarException(
    val refusal: LocalCalendarRefusal,
    val conflictingName: String? = null,
) : Exception(refusal.name)
