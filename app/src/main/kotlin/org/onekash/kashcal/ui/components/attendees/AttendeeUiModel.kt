package org.onekash.kashcal.ui.components.attendees

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Projects a Room [Attendee] row or a device [DeviceAttendee] row for display. Built once at the
 * read boundary, so raw stored strings (PARTSTAT, `mailto:` addresses) never reach composables.
 */
@Immutable
data class AttendeeUiModel(
    /** Label: the CN when present, else the address's local part, else the whole [bareAddress]. */
    val displayName: String,
    /**
     * The address for display and matching: [AddressNormalizer.canonical] for a Room row, the
     * email without `mailto:` and lowercased for a device row, empty for a device row with no
     * email.
     */
    val bareAddress: String,
    val status: AttendeeStatus,
    /** True when this attendee is the authenticated user on the event's account. */
    val isYou: Boolean,
    /** True when this attendee's address matches the event's ORGANIZER. */
    val isOrganizer: Boolean,
    /**
     * Order among the attendees: the Room row's own sort order, or the device row's index. The
     * synthesized organizer is -1.
     */
    val sortOrder: Int,
    /**
     * True when [fromRoom] built this entry from the event's ORGANIZER because no ATTENDEE row
     * represents it: the user organized without listing themselves ([isYou] true), or the user
     * was invited and the host isn't on the ATTENDEE list ([isYou] false).
     */
    val isSynthesized: Boolean = false
) {
    companion object {
        /**
         * Builds the models for an event's Room attendee rows, adding a synthesized organizer
         * first when no row represents the ORGANIZER.
         *
         * @param currentAccount the event's account, or null when none resolves (an orphan
         *   event, a deleted account); then every model has [isYou] false.
         * @param organizerAddress the event's ORGANIZER, raw; [isOrganizer] is a canonical match.
         * @param organizerName the ORGANIZER CN, raw: the synthesized entry's name. It falls
         *   back to [Account.displayName] when the user is the organizer, then to the address's
         *   local part, which covers events stored without a CN.
         */
        fun fromRoom(
            attendees: List<Attendee>,
            currentAccount: Account?,
            organizerAddress: String?,
            organizerName: String?
        ): List<AttendeeUiModel> {
            val canonicalOrganizer = organizerAddress?.let { AddressNormalizer.canonical(it) }
            val mapped = attendees.map { attendee ->
                val canonical = AddressNormalizer.canonical(attendee.address)
                AttendeeUiModel(
                    displayName = displayNameFor(attendee.displayName, canonical),
                    bareAddress = canonical,
                    status = AttendeeStatus.fromPartstat(attendee.partstat),
                    isYou = currentAccount?.matchesAttendee(attendee.address) == true,
                    isOrganizer = canonicalOrganizer != null && canonical == canonicalOrganizer,
                    sortOrder = attendee.sortOrder
                )
            }

            // ORGANIZER and ATTENDEE are separate properties (RFC 5545), so when no ATTENDEE row
            // is the organizer, synthesize one so the host is visible:
            //  - the user organized with no self ATTENDEE row: "You" as host;
            //  - the user was invited and an iTIP-style server stripped the host from the
            //    ATTENDEE list: the host, isYou false.
            //
            // A user-organizer already on the list under another alias counts as represented
            // through the existing "You" row; otherwise that organizer would show twice.
            if (!canonicalOrganizer.isNullOrBlank()) {
                val userIsOrganizer = currentAccount?.matchesAttendee(organizerAddress!!) == true
                val organizerOnList = mapped.any { it.bareAddress == canonicalOrganizer } ||
                    (userIsOrganizer && mapped.any { it.isYou })
                if (!organizerOnList) {
                    val selfDisplayName = if (userIsOrganizer) currentAccount?.displayName else null
                    return listOf(
                        synthesizedOrganizerChip(
                            canonicalOrganizer,
                            userIsOrganizer,
                            organizerName,
                            selfDisplayName
                        )
                    ) + mapped
                }
            }
            return mapped
        }

        /**
         * Builds the models for CalendarProvider `Attendees` rows, one model per row.
         *
         * The provider stores the organizer as an ordinary attendee row flagged
         * `RELATIONSHIP_ORGANIZER`, so unlike [fromRoom] nothing is synthesized. Status comes
         * from the provider's int, and "you" is a canonical match on the calendar's
         * `OWNER_ACCOUNT` instead of a CalDAV account's addresses.
         *
         * @param attendees rows in provider order; the index becomes [sortOrder].
         * @param ownerEmail the calendar's `OWNER_ACCOUNT`, or null or blank when unknown; then
         *   every model has [isYou] false.
         */
        fun fromDevice(
            attendees: List<DeviceAttendee>,
            ownerEmail: String?,
        ): List<AttendeeUiModel> {
            val canonicalOwner = ownerEmail?.takeUnless { it.isBlank() }
                ?.let { canonicalDeviceEmail(it) }
            return attendees.mapIndexed { index, attendee ->
                val email = attendee.email.orEmpty()
                val canonical = if (email.isBlank()) "" else canonicalDeviceEmail(email)
                AttendeeUiModel(
                    displayName = displayNameFor(attendee.name, canonical),
                    bareAddress = canonical,
                    status = AttendeeStatus.fromDeviceStatus(attendee.status),
                    isYou = canonicalOwner != null && canonical == canonicalOwner,
                    isOrganizer = attendee.relationship ==
                        android.provider.CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
                    sortOrder = index,
                )
            }
        }

        /**
         * Canonicalizes a device `ATTENDEE_EMAIL` or `OWNER_ACCOUNT` through
         * [org.onekash.kashcal.data.calendar_provider.canonicalAttendeeEmail], so the read-side
         * identity match and the write-side guest diff share one rule.
         */
        private fun canonicalDeviceEmail(raw: String): String =
            org.onekash.kashcal.data.calendar_provider.canonicalAttendeeEmail(raw)

        private fun synthesizedOrganizerChip(
            canonicalOrganizer: String,
            isYou: Boolean,
            organizerName: String?,
            selfDisplayName: String?
        ): AttendeeUiModel = AttendeeUiModel(
            displayName = displayNameFor(
                organizerName.takeUnless { it.isNullOrBlank() } ?: selfDisplayName,
                canonicalOrganizer
            ),
            bareAddress = canonicalOrganizer,
            status = AttendeeStatus.Accepted,
            isYou = isYou,
            isOrganizer = true,
            sortOrder = -1,
            isSynthesized = true
        )

        /**
         * Returns the trimmed [cn] when not blank, else the local part of [canonicalAddress],
         * else the whole address when it has no local part.
         */
        private fun displayNameFor(cn: String?, canonicalAddress: String): String {
            val trimmedCn = cn?.trim()
            if (!trimmedCn.isNullOrBlank()) return trimmedCn
            val atIndex = canonicalAddress.indexOf('@')
            return if (atIndex > 0) canonicalAddress.substring(0, atIndex) else canonicalAddress
        }

        /**
         * Returns true when [currentAccount] matches an attendee or [organizerAddress], so an
         * organizer with no attendee row of their own counts as on the list. When false,
         * [InviteesBlock] shows its off-list summary.
         */
        fun isCurrentUserOnList(
            attendees: List<Attendee>,
            currentAccount: Account?,
            organizerAddress: String? = null
        ): Boolean {
            if (currentAccount == null) return false
            if (attendees.any { currentAccount.matchesAttendee(it.address) }) return true
            if (organizerAddress != null && currentAccount.matchesAttendee(organizerAddress)) return true
            return false
        }

        /**
         * Orders models for a chip row: the first You at index 0, then the models that aren't You
         * by [sortOrder]; any further You is left out.
         *
         * - [expanded], or collapsed with at most 3 models: all of them.
         * - Collapsed with 4 or more and a You: You plus the next 3, so pinning You hides no
         *   other chip of the first three.
         * - Collapsed with 4 or more and no You: the first 3.
         */
        fun sortForCollapsedView(
            models: List<AttendeeUiModel>,
            expanded: Boolean
        ): List<AttendeeUiModel> {
            if (models.isEmpty()) return models

            val you = models.firstOrNull { it.isYou }
            val others = models.filter { !it.isYou }.sortedBy { it.sortOrder }
            val withYouFirst = listOfNotNull(you) + others

            if (expanded) return withYouFirst
            if (you == null) {
                return if (models.size >= 4) others.take(3) else others
            }
            return if (models.size >= 4) {
                listOf(you) + others.take(3)
            } else {
                withYouFirst
            }
        }

    }
}
