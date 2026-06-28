package org.onekash.kashcal.ui.components.attendees

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
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
     * because the ORGANIZER isn't represented on the ATTENDEE list. Two
     * flavors: the user organized the event without listing themselves
     * (isYou=true), or the user was invited and the host was stripped from
     * the ATTENDEE list (isYou=false). Used as part of the Compose slot key
     * so synthesized chips can never collide with real attendee rows.
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
         * @param organizerName the event's ORGANIZER CN (raw form). Used as
         *   the synthesized chip's display name when ORGANIZER isn't already
         *   represented as an ATTENDEE row; falls back to [Account.displayName]
         *   when the user IS the organizer, then to local-part of the canonical
         *   address. Pre-A2 events without this field still render correctly
         *   via the displayName / local-part fallback chain.
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

            // RFC 5545: ORGANIZER and ATTENDEE are separate properties. When
            // ORGANIZER isn't represented on the ATTENDEE list, synthesize a
            // chip so the host is visible. Two flavors fall out of one rule:
            //  - User organized the event, no self ATTENDEE row → "You" + 👑.
            //  - User was invited, host stripped from ATTENDEE list by an
            //    iTIP-style server → host chip with 👑, isYou = false.
            //
            // "Already represented" includes the multi-alias case: if the user
            // is the organizer and the user already appears on the attendee
            // list via a different alias, the organizer is represented through
            // the existing "You" chip. Without this guard a multi-alias
            // organizer would render twice.
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
         * Build the UI model list from CalendarProvider `Attendees` rows.
         *
         * Unlike [fromRoom], the device provider stores ORGANIZER as an
         * ordinary attendee row flagged `RELATIONSHIP_ORGANIZER`, so there is
         * no separate ORGANIZER property to reconcile and no synthesized chip:
         * one provider row maps to exactly one model. Status comes from the
         * provider int (not a PARTSTAT string), and "you" is decided by
         * canonical-matching the calendar's owner email (`OWNER_ACCOUNT`)
         * rather than a CalDAV account's mailto addresses.
         *
         * @param attendees rows in provider order (index becomes [sortOrder])
         * @param ownerEmail the device calendar's `OWNER_ACCOUNT`; null when
         *   unknown, in which case every model has [isYou] = false.
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
         * Canonicalize a device-provider email (`ATTENDEE_EMAIL` /
         * `OWNER_ACCOUNT`) for compare-time equality. Delegates to the
         * data-layer
         * [org.onekash.kashcal.data.calendar_provider.canonicalAttendeeEmail]
         * so the read-side identity match and the write-side guest diff share
         * one canonicalization rule and can't drift.
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
