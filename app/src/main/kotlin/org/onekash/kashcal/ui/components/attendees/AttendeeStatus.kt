package org.onekash.kashcal.ui.components.attendees

import androidx.annotation.StringRes
import org.onekash.kashcal.R

/**
 * UI-layer projection of RFC 5545 §3.2.12 PARTSTAT values used by the
 * attendee chip surfaces. Translates Room TEXT (lenient — servers emit
 * X-extensions) at the read boundary so composables never see raw DB
 * strings.
 *
 * Mapping is TEXT-lenient: anything we don't recognize maps to
 * [NeedsAction]. RFC 5545 PARTSTATs not currently surfaced
 * (`COMPLETED`, `IN-PROCESS`) are VTODO-only and don't reach the
 * VEVENT-only chip surfaces.
 */
enum class AttendeeStatus(@StringRes val labelResId: Int) {
    Accepted(R.string.attendee_status_accepted),
    Declined(R.string.attendee_status_declined),
    Tentative(R.string.attendee_status_tentative),
    Delegated(R.string.attendee_status_delegated),
    NeedsAction(R.string.attendee_status_pending);

    /**
     * Inverse of [fromPartstat]: returns the canonical RFC 5545 §3.2.12
     * PARTSTAT token, or null when this status isn't a user-RSVPable
     * target (NeedsAction is the absence of a response; Delegated is
     * out-of-scope for T2's Respond UI).
     */
    fun toPartstat(): String? = when (this) {
        Accepted -> "ACCEPTED"
        Declined -> "DECLINED"
        Tentative -> "TENTATIVE"
        Delegated, NeedsAction -> null
    }

    companion object {
        fun fromPartstat(partstat: String?): AttendeeStatus = when (partstat?.uppercase()) {
            "ACCEPTED" -> Accepted
            "DECLINED" -> Declined
            "TENTATIVE" -> Tentative
            "DELEGATED" -> Delegated
            else -> NeedsAction
        }
    }
}
