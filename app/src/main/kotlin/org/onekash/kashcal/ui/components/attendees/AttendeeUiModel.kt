package org.onekash.kashcal.ui.components.attendees

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Read-only UI projection of an [Attendee] row. Built once at the
 * read boundary; composables receive [AttendeeUiModel] not [Attendee],
 * so DB strings (raw partstat, raw mailto address) never leak into the
 * UI layer.
 */
@Immutable
data class AttendeeUiModel(
    /** Display label — CN if present, else local-part of address, else raw address. */
    val displayName: String,
    /** Address with `mailto:` prefix stripped + lowercased per RFC 5545 §3.3.3 canonical form. */
    val bareAddress: String,
    val status: AttendeeStatus,
    /** True when this attendee is the authenticated user on the event's account. */
    val isYou: Boolean,
    /** True when this attendee's address matches the event's ORGANIZER. */
    val isOrganizer: Boolean,
    /** Wire-order preservation; index in the source attendees list. */
    val sortOrder: Int,
    /**
     * True when this chip was synthesized from `event.organizer_email`
     * because the user is the organizer but isn't listed as an ATTENDEE.
     * Used as part of the Compose slot key so synthesized chips can never
     * collide with real attendee rows.
     */
    val isSynthesized: Boolean = false
) {
    companion object {
        /**
         * Build the UI model list from Room rows.
         *
         * @param attendees rows in their original sortOrder
         * @param currentAccount the event's account; null when there's no
         *   resolvable account (orphan event, deleted account). When null,
         *   every model has [isYou] = false.
         * @param organizerAddress the event's ORGANIZER property (raw form);
         *   each attendee's [isOrganizer] is computed by canonical match.
         */
        fun fromRoom(
            attendees: List<Attendee>,
            currentAccount: Account?,
            organizerAddress: String?
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

            // RFC 5545: ORGANIZER and ATTENDEE are separate properties. When the
            // user organizes an event without listing themselves as an ATTENDEE
            // (the common case for invites the user sent), we synthesize a chip
            // so "You" + 👑 still surface on the user's own events.
            if (currentAccount != null &&
                canonicalOrganizer != null &&
                currentAccount.matchesAttendee(organizerAddress!!) &&
                mapped.none { it.isYou }
            ) {
                return listOf(
                    synthesizedOrganizerChip(currentAccount, canonicalOrganizer)
                ) + mapped
            }
            return mapped
        }

        private fun synthesizedOrganizerChip(
            account: Account,
            canonicalOrganizer: String
        ): AttendeeUiModel = AttendeeUiModel(
            displayName = displayNameFor(account.displayName, canonicalOrganizer),
            bareAddress = canonicalOrganizer,
            status = AttendeeStatus.Accepted,
            isYou = true,
            isOrganizer = true,
            sortOrder = -1,
            isSynthesized = true
        )

        /**
         * "Humanize an address" — used by both real-attendee mapping and
         * the synthesized organizer chip. CN wins if present and non-blank;
         * else the local-part of the canonical address; else the canonical
         * itself for non-mailto forms.
         */
        private fun displayNameFor(cn: String?, canonicalAddress: String): String {
            val trimmedCn = cn?.trim()
            if (!trimmedCn.isNullOrBlank()) return trimmedCn
            val atIndex = canonicalAddress.indexOf('@')
            return if (atIndex > 0) canonicalAddress.substring(0, atIndex) else canonicalAddress
        }

        /**
         * True when [currentAccount] matches any attendee OR matches
         * [organizerAddress]. Drives whether the chip row uses the inline
         * render (with the user surfaced as a chip) or falls back to the
         * lavender count chip. Organizer-only events (user organizes,
         * no attendee row for the user) still render Inline.
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
         * Sort for the chip row.
         *
         * - When [expanded] = true: all models, with You (if any) at index 0.
         * - When collapsed and total ≤ 3: same — You at 0, all visible.
         * - When collapsed and total ≥ 4 and You exists: 4 chips total —
         *   You at index 0 plus the next 3 by sortOrder excluding You.
         *   Pinning You without hiding two wire-first slots matches Google
         *   Calendar parity.
         * - When collapsed and total ≥ 4 and no You: first 3 by sortOrder.
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
                // No You — first 3 by sortOrder when total ≥ 4, else all.
                return if (models.size >= 4) others.take(3) else others
            }
            // You exists — 4 chips when total ≥ 4 (You + 3 others), else all.
            return if (models.size >= 4) {
                listOf(you) + others.take(3)
            } else {
                withYouFirst
            }
        }

    }
}
