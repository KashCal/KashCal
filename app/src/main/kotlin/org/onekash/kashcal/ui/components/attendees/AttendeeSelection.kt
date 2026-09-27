package org.onekash.kashcal.ui.components.attendees

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.util.AddressNormalizer

/**
 * Holds the attendee picker's selection as Room [Attendee] rows, not the lossy [AttendeeUiModel].
 *
 * A pulled event carries wire fields the UI projection drops: `role`, `cutype`, `rsvp`,
 * `delegatedFrom`/`To`, `member`, `sentBy` and the `schedule*` parameters. Rebuilding the set
 * from [AttendeeUiModel] would silently strip them on the next push, so the model seeds from
 * the real rows, changes them only by add and remove, and hands the merged list back.
 *
 * [isChanged] is true once the user added or removed someone. The event form keeps its own
 * `attendeesEdited` flag, set on each change the picker reports, so an unedited open-and-save
 * leaves the attendee table alone.
 *
 * Dedup is by [AddressNormalizer.canonical], so a person already on the list, stored with or
 * without `mailto:` or in another case, is never added twice.
 *
 * [seedCanonicals] snapshots the canonical addresses at seed time, the originally invited
 * guests, and [removedFromSeed] reports which of them are gone. A guest added and removed in
 * the same session was never in [seedCanonicals], so it doesn't appear.
 */
@Immutable
data class AttendeeSelection(
    val attendees: List<Attendee>,
    val isChanged: Boolean,
    val seedCanonicals: Set<String> = emptySet(),
) {
    private fun canonicalAddresses(): Set<String> =
        attendees.mapTo(mutableSetOf()) { AddressNormalizer.canonical(it.address) }

    /**
     * Returns whether [attendee] can be removed from the picker; always true. A method so the
     * picker chip's remove check has one home if a per-row restriction is ever needed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isRemovable(attendee: Attendee): Boolean = true

    /** Returns the canonical addresses present at seed time but absent from the current set. */
    fun removedFromSeed(): Set<String> = seedCanonicals - canonicalAddresses()

    /**
     * Adds a picked or typed attendee at the end of the sort order. An email-shaped address is
     * stored `mailto:`-prefixed to match the pull side; any other CAL-ADDRESS form is stored
     * trimmed but otherwise as given, never as `mailto:urn:uuid:...`. A new invitee has no
     * response yet, so PARTSTAT is NEEDS-ACTION and role, cutype, rsvp and delegation take the
     * entity defaults (null or empty).
     *
     * A canonical duplicate is a no-op that returns this instance: the existing row and its wire
     * fields stay, and [isChanged] doesn't flip.
     */
    fun addNew(displayName: String?, bareAddress: String): AttendeeSelection {
        val canonical = AddressNormalizer.canonical(bareAddress)
        if (canonical in canonicalAddresses()) return this
        val address = if (AddressNormalizer.isEmailShaped(bareAddress)) {
            "mailto:${AddressNormalizer.stripMailto(bareAddress)}"
        } else {
            bareAddress.trim()
        }
        val nextSortOrder = (attendees.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val row = Attendee(
            eventId = 0,
            address = address,
            displayName = displayName?.trim()?.ifBlank { null },
            partstat = "NEEDS-ACTION",
            sortOrder = nextSortOrder,
        )
        return copy(attendees = attendees + row, isChanged = true)
    }

    /**
     * Removes the attendee whose canonical address matches [address], given in any CAL-ADDRESS
     * form; the argument is canonicalized, so the picker's canonical form always matches.
     * Removing an absent address is a no-op that returns this instance and doesn't flip
     * [isChanged].
     */
    fun remove(address: String): AttendeeSelection {
        val target = AddressNormalizer.canonical(address)
        val filtered = attendees.filterNot { AddressNormalizer.canonical(it.address) == target }
        if (filtered.size == attendees.size) return this
        return copy(attendees = filtered, isChanged = true)
    }

    companion object {
        /**
         * Seeds the model from the event's attendee rows, unchanged, snapshotting their
         * canonical addresses into [seedCanonicals] for [removedFromSeed].
         */
        fun seed(existing: List<Attendee>): AttendeeSelection =
            AttendeeSelection(
                attendees = existing,
                isChanged = false,
                seedCanonicals = existing.mapTo(mutableSetOf()) {
                    AddressNormalizer.canonical(it.address)
                },
            )
    }
}
