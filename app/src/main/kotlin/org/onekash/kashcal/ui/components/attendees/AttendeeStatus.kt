package org.onekash.kashcal.ui.components.attendees

import androidx.annotation.StringRes
import org.onekash.kashcal.R

/**
 * Projects an attendee's participation status (RFC 5545 §3.2.12 PARTSTAT, or the device
 * provider's status int) for the UI, so composables never see raw stored values.
 *
 * Unrecognized values, servers' X-extensions included, map to [NeedsAction], the RFC default.
 * `COMPLETED` and `IN-PROCESS` are VTODO-only, so the VEVENT surfaces never meet them.
 */
enum class AttendeeStatus(@StringRes val labelResId: Int) {
    Accepted(R.string.attendee_status_accepted),
    Declined(R.string.attendee_status_declined),
    Tentative(R.string.attendee_status_tentative),
    Delegated(R.string.attendee_status_delegated),
    NeedsAction(R.string.attendee_status_pending);

    /**
     * Returns the RFC 5545 §3.2.12 PARTSTAT token, the inverse of [fromPartstat], or null when
     * the status isn't an RSVP choice: NeedsAction is the absence of a response, and the
     * Respond UI doesn't offer Delegated.
     */
    fun toPartstat(): String? = when (this) {
        Accepted -> "ACCEPTED"
        Declined -> "DECLINED"
        Tentative -> "TENTATIVE"
        Delegated, NeedsAction -> null
    }

    /**
     * Returns the `CalendarContract.Attendees.ATTENDEE_STATUS_*` int, the inverse of
     * [fromDeviceStatus], for writing an RSVP to the provider. The provider has no DELEGATED
     * state, so [Delegated], not an RSVP choice, maps to NONE with [NeedsAction].
     */
    fun toDeviceStatus(): Int = when (this) {
        Accepted -> android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        Declined -> android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        Tentative -> android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
        Delegated, NeedsAction -> android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_NONE
    }

    companion object {
        fun fromPartstat(partstat: String?): AttendeeStatus = when (partstat?.uppercase()) {
            "ACCEPTED" -> Accepted
            "DECLINED" -> Declined
            "TENTATIVE" -> Tentative
            "DELEGATED" -> Delegated
            else -> NeedsAction
        }

        /**
         * Maps a `CalendarContract.Attendees.ATTENDEE_STATUS_*` int to a status. The provider
         * has no DELEGATED state; INVITED, NONE and any unknown value mean no response yet,
         * [NeedsAction].
         */
        fun fromDeviceStatus(status: Int): AttendeeStatus = when (status) {
            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> Accepted
            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> Declined
            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> Tentative
            else -> NeedsAction
        }
    }
}
