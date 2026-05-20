package org.onekash.kashcal.ui.components.attendees

/**
 * Pure logic extracted from the EventQuickViewSheet "Respond" section so
 * the visibility + filled-vs-outlined predicates are unit-testable without
 * Compose semantics.
 *
 * The composable consumes these predicates; this file is the single home
 * for the rules.
 */

/**
 * Should the Respond section render at all?
 *
 * Visible only when the user is on the event's attendee list (i.e., the
 * attendee chip row would emit a chip with `isYou = true`). For events
 * the user organized but isn't listed as an attendee on, the synthesized
 * organizer chip's PARTSTAT is `Accepted` — we still don't show RSVP
 * buttons there, because organizers respond by editing the event, not
 * by RSVPing to themselves.
 *
 * @param currentUserPartstat The current user's PARTSTAT, or null when
 *   the user isn't on the attendee list.
 * @param isOrganizer True when the current user is the event's ORGANIZER.
 *   Drives the organizer-suppression rule above.
 */
fun shouldShowRespondSection(
    currentUserPartstat: AttendeeStatus?,
    isOrganizer: Boolean
): Boolean {
    if (currentUserPartstat == null) return false
    if (isOrganizer) return false
    return true
}

/**
 * Is the button for [buttonStatus] currently selected (i.e., should it
 * render in the filled rather than outlined variant)?
 *
 * @param buttonStatus The PARTSTAT this button represents (Accepted /
 *   Tentative / Declined).
 * @param currentUserPartstat The user's current PARTSTAT, or null when the
 *   user is not on the attendee list. NEEDS-ACTION means no button is
 *   highlighted yet.
 */
fun isRespondButtonSelected(
    buttonStatus: AttendeeStatus,
    currentUserPartstat: AttendeeStatus?
): Boolean {
    if (currentUserPartstat == null) return false
    if (currentUserPartstat == AttendeeStatus.NeedsAction) return false
    return buttonStatus == currentUserPartstat
}

/**
 * Semantic tint to apply to a Respond button's glyph. The Compose layer
 * maps each role to a `MaterialTheme.colorScheme` token. Splitting the
 * mapping out of the composable lets the per-status-tint logic be unit-
 * tested without Compose semantics, and lets the Compose layer pick the
 * exact token (so the same role can resolve differently in light vs. dark
 * theme).
 */
enum class GlyphTintRole {
    /** Selected pill — composable uses the primary container/content tokens. */
    Selected,

    /** Accept (✓) — composable uses a success-green token. */
    Success,

    /** Tentative (?) — composable uses an amber/secondary token. */
    Tentative,

    /** Decline (✕) — composable uses an error-red token. */
    Error
}

/**
 * Tint role for the [buttonStatus]'s glyph given the user's
 * [currentUserPartstat]. When the button is the user's selection, returns
 * [GlyphTintRole.Selected] (regardless of which status it is). Otherwise
 * returns the per-status tint so the user can read the response semantics
 * at a glance even before they pick.
 */
fun respondGlyphTintRole(
    buttonStatus: AttendeeStatus,
    currentUserPartstat: AttendeeStatus?
): GlyphTintRole {
    if (isRespondButtonSelected(buttonStatus, currentUserPartstat)) {
        return GlyphTintRole.Selected
    }
    return when (buttonStatus) {
        AttendeeStatus.Accepted -> GlyphTintRole.Success
        AttendeeStatus.Tentative -> GlyphTintRole.Tentative
        AttendeeStatus.Declined -> GlyphTintRole.Error
        // Decline buttons aren't surfaced for these states — fall back to
        // a neutral-ish tint so a future caller doesn't crash.
        AttendeeStatus.Delegated, AttendeeStatus.NeedsAction -> GlyphTintRole.Tentative
    }
}

/**
 * Should the "applies to the whole series" disclosure caption render
 * directly below the Respond pills?
 *
 * Series-level RSVP is a T2 lock — tapping Decline on the Friday
 * occurrence of a weekly meeting declines every Friday, not just this
 * one. The caption gives the user that context before they tap so the
 * series-wide effect doesn't surprise them after sync.
 *
 * Visible only when (a) the Respond section itself is visible, AND
 * (b) the event has a recurrence rule (master series or one of its
 * exceptions — both apply at series level).
 *
 * @param currentUserPartstat See [shouldShowRespondSection].
 * @param isOrganizer See [shouldShowRespondSection].
 * @param isRecurring True when the event is part of a recurring series
 *   (RRULE-driven master, or an exception to one).
 */
fun shouldShowSeriesRsvpDisclosure(
    currentUserPartstat: AttendeeStatus?,
    isOrganizer: Boolean,
    isRecurring: Boolean
): Boolean {
    if (!shouldShowRespondSection(currentUserPartstat, isOrganizer)) return false
    return isRecurring
}

