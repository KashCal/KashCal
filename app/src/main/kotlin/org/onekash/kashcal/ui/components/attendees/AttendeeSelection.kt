package org.onekash.kashcal.ui.components.attendees

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.util.AddressNormalizer

/**
 * The attendee picker's selection model.
 *
 * Holds Room [Attendee] **entities**, not the lossy [AttendeeUiModel]. An
 * event pulled from a CalDAV server carries wire fields the UI projection
 * drops — `role`, `cutype`, `rsvp`, `delegatedFrom`/`To`, `member`, `sentBy`,
 * and the `schedule*` parameters. If the picker rebuilt its set from
 * [AttendeeUiModel] on save, those fields would be silently stripped on the
 * next push. So the model seeds from the real entities, mutates them by
 * add/remove only, and hands the merged entity list back to the save path.
 *
 * [isChanged] tells the save path whether the set actually changed: an
 * unedited open-and-save must pass the existing rows through untouched (the
 * domain layer treats a `null` attendee list as "leave the table alone"), so
 * the picker only commits a non-null list when the user added or removed
 * someone.
 *
 * Dedup is by canonical address ([AddressNormalizer.canonical]) so a person
 * already on the list — whether stored `mailto:`-prefixed, bare, or in a
 * different case — is never added twice.
 *
 * [seedCanonicals] snapshots the canonical addresses present at seed time (the
 * originally-invited guests), captured unconditionally. [removedFromSeed]
 * diffs it against the current set to report which originals the organizer
 * dropped — the recipients owed an iTIP CANCEL. A guest added and removed
 * within the same session was never in [seedCanonicals], so it nets out and is
 * not cancelled (it was never on the wire).
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
     * Whether [attendee] can be removed from the picker. Always true — removing
     * an invited guest is allowed; the dropped guest is sent an iTIP CANCEL on
     * save. (Retained as a method so the picker chip's remove-affordance check
     * has a single home, and to leave room for future per-row restrictions.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun isRemovable(attendee: Attendee): Boolean = true

    /**
     * Canonical addresses present at seed time but absent from the current set
     * — the originally-invited guests the organizer removed this session. These
     * are the recipients owed a CANCEL. A session-only add that was then
     * removed is not here (it was never in [seedCanonicals]).
     */
    fun removedFromSeed(): Set<String> = seedCanonicals - canonicalAddresses()

    /**
     * Add a newly picked/typed attendee. Email-shaped addresses are stored
     * `mailto:`-prefixed to match the pull-side storage convention; any other
     * CAL-ADDRESS form is stored verbatim (never `mailto:urn:uuid:…`). A
     * freshly invited person has no response yet, so PARTSTAT is NEEDS-ACTION
     * and role/cutype/rsvp/delegation take their entity defaults (null/empty).
     *
     * A canonical duplicate is a no-op (the existing entity, with its wire
     * fields, is kept) and does not flip [isChanged].
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
     * Remove the attendee whose canonical address matches [address] (any
     * CAL-ADDRESS form — the chip displays the canonical form, this
     * canonicalizes its argument so the two always meet). Removing an absent
     * address is a no-op and does not flip [isChanged].
     */
    fun remove(address: String): AttendeeSelection {
        val target = AddressNormalizer.canonical(address)
        val filtered = attendees.filterNot { AddressNormalizer.canonical(it.address) == target }
        if (filtered.size == attendees.size) return this
        return copy(attendees = filtered, isChanged = true)
    }

    companion object {
        /**
         * Seed the model from the event's existing attendee entities. A pure
         * seed is unchanged. The seed's canonical addresses are snapshotted
         * into [seedCanonicals] so a later removal can be reported via
         * [removedFromSeed] for cancellation.
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
