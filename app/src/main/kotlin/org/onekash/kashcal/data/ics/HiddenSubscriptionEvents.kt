package org.onekash.kashcal.data.ics

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore

/**
 * Remembers which events the user hid in a read-only calendar, so they stay hidden. The events
 * belong to someone else: an ICS subscription's rows are rewritten by each refresh, and a device
 * calendar's rows belong to the app that syncs it. So a hide is a rule applied while reading, not
 * an edit of the event.
 *
 * Subscription rules are stored as `subscriptionId|U|uid` (one event or series) or
 * `subscriptionId|T|title`. Device calendar rules are `d|calendarId|E|eventId` (one event or
 * series) or `d|calendarId|T|title`. A title rule matches every event with that title, ignoring
 * case and surrounding space; this is how a holiday that moves each year is hidden for all years.
 * A rule may be followed by [LABEL_SEPARATOR] and the title to show in the restore list. Rules
 * live in [KashCalDataStore] and are never synced.
 *
 * Covered by `HiddenSubscriptionEventsTest`.
 */
@Singleton
class HiddenSubscriptionEvents @Inject constructor(
    private val dataStore: KashCalDataStore,
) {
    /** The rules of one subscription. */
    class Rules internal constructor(
        internal val uids: Set<String>,
        internal val titles: Set<String>,
    ) {
        val isEmpty: Boolean get() = uids.isEmpty() && titles.isEmpty()

        /** True when [event] matches a rule by uid or by title. */
        fun hides(event: Event): Boolean = event.uid in uids || normalizeTitle(event.title) in titles
    }

    /** The rules of every device calendar. */
    class DeviceRules internal constructor(
        internal val eventIds: Set<Pair<Long, Long>>,
        internal val titles: Set<Pair<Long, String>>,
    ) {
        val isEmpty: Boolean get() = eventIds.isEmpty() && titles.isEmpty()

        /** True when the event [eventId] titled [title] in device calendar [calendarId] is hidden. */
        fun hides(calendarId: Long, eventId: Long, title: String): Boolean =
            (calendarId to eventId) in eventIds || (calendarId to normalizeTitle(title)) in titles
    }

    /**
     * One rule as the restore list shows it: the stored [rule], the [label] to show (the title
     * of the event, or of the events it matches) and whether it hides every event with that
     * title ([byTitle]) or just one event or series.
     */
    class Entry internal constructor(
        val rule: String,
        val label: String,
        val byTitle: Boolean,
    )

    private val writeLock = Mutex()

    /** Returns the rules of [subscriptionId]; empty when it has none. */
    suspend fun rulesFor(subscriptionId: Long): Rules = parse(dataStore.hiddenSubscriptionEvents.first(), subscriptionId)

    /**
     * Hides the event or series with [uid] in [subscriptionId]. [label] is the title the
     * restore list shows for it.
     */
    suspend fun hideUid(subscriptionId: Long, uid: String, label: String = "") =
        add("$subscriptionId|$KIND_UID|$uid", label)

    /** Hides every event titled [title] in [subscriptionId]. A blank title adds no rule. */
    suspend fun hideTitle(subscriptionId: Long, title: String) {
        val normalized = normalizeTitle(title)
        if (normalized.isNotEmpty()) add("$subscriptionId|$KIND_TITLE|$normalized", title)
    }

    /**
     * Hides the event or series [eventId] of device calendar [calendarId]. [label] is the title
     * the restore list shows for it.
     */
    suspend fun hideDeviceEvent(calendarId: Long, eventId: Long, label: String = "") =
        add("d|$calendarId|$KIND_EVENT|$eventId", label)

    /** Hides every event titled [title] in device calendar [calendarId]. A blank title adds no rule. */
    suspend fun hideDeviceTitle(calendarId: Long, title: String) {
        val normalized = normalizeTitle(title)
        if (normalized.isNotEmpty()) add("d|$calendarId|$KIND_TITLE|$normalized", title)
    }

    /** Drops the rule [rule], as listed in [Entry.rule]; true when it was there. */
    suspend fun remove(rule: String): Boolean = writeLock.withLock {
        val current = dataStore.hiddenSubscriptionEvents.first()
        if (rule !in current) return@withLock false
        dataStore.setHiddenSubscriptionEvents(current - rule)
        true
    }

    /** Drops every rule of every calendar. */
    suspend fun clearAll() = writeLock.withLock {
        dataStore.setHiddenSubscriptionEvents(emptySet())
    }

    /** Drops every rule of device calendar [calendarId]. */
    suspend fun clearDevice(calendarId: Long) = writeLock.withLock {
        val prefix = "d|$calendarId|"
        dataStore.setHiddenSubscriptionEvents(
            dataStore.hiddenSubscriptionEvents.first().filterNot { it.startsWith(prefix) }.toSet()
        )
    }

    /** Drops every rule of [subscriptionId], for example when the subscription is removed. */
    suspend fun clear(subscriptionId: Long) = writeLock.withLock {
        val prefix = "$subscriptionId|"
        dataStore.setHiddenSubscriptionEvents(
            dataStore.hiddenSubscriptionEvents.first().filterNot { it.startsWith(prefix) }.toSet()
        )
    }

    /**
     * Stores [rule] with [label] after it. A rule already stored for the same target is
     * replaced, so hiding a title twice leaves one entry in the restore list.
     */
    private suspend fun add(rule: String, label: String) = writeLock.withLock {
        val stored = if (label.isBlank()) rule else rule + LABEL_SEPARATOR + label.trim()
        val current = dataStore.hiddenSubscriptionEvents.first()
        dataStore.setHiddenSubscriptionEvents(current.filterNot { ruleKey(it) == rule }.toSet() + stored)
    }

    internal companion object {
        const val KIND_UID = "U"
        const val KIND_TITLE = "T"
        const val KIND_EVENT = "E"

        /** Splits a rule from the label stored after it; never in a uid, title key or id. */
        const val LABEL_SEPARATOR = '\u001F'

        /** Returns [stored] without its label. */
        fun ruleKey(stored: String): String = stored.substringBefore(LABEL_SEPARATOR)

        /**
         * Returns the rules of [raw] as the restore list shows them, sorted by label. A malformed
         * entry is skipped; a rule stored without a label shows its target as the label.
         */
        fun entries(raw: Set<String>): List<Entry> {
            val result = mutableListOf<Entry>()
            for (stored in raw) {
                val key = ruleKey(stored)
                val label = stored.substringAfter(LABEL_SEPARATOR, "")
                val parts = key.split("|", limit = 4)
                val kind: String
                val target: String
                if (parts.firstOrNull() == "d") {
                    if (parts.size != 4 || parts[1].toLongOrNull() == null) continue
                    kind = parts[2]
                    target = parts[3]
                } else {
                    if (parts.size < 3 || parts[0].toLongOrNull() == null) continue
                    kind = parts[1]
                    target = key.split("|", limit = 3)[2]
                }
                when (kind) {
                    KIND_TITLE -> result += Entry(stored, label.ifBlank { target }, byTitle = true)
                    KIND_UID, KIND_EVENT -> result += Entry(stored, label.ifBlank { target }, byTitle = false)
                }
            }
            return result.sortedBy { it.label.lowercase(Locale.ROOT) }
        }

        fun normalizeTitle(title: String): String = title.trim().lowercase(Locale.ROOT)

        /** Parses [raw]; a malformed entry or another subscription's entry is skipped. */
        fun parse(raw: Set<String>, subscriptionId: Long): Rules {
            val uids = mutableSetOf<String>()
            val titles = mutableSetOf<String>()
            for (entry in raw) {
                val parts = ruleKey(entry).split("|", limit = 3)
                if (parts.size != 3 || parts[0].toLongOrNull() != subscriptionId) continue
                when (parts[1]) {
                    KIND_UID -> uids += parts[2]
                    KIND_TITLE -> titles += parts[2]
                }
            }
            return Rules(uids, titles)
        }

        /** Parses the device calendar rules of [raw]; other entries are skipped. */
        fun parseDevice(raw: Set<String>): DeviceRules {
            val eventIds = mutableSetOf<Pair<Long, Long>>()
            val titles = mutableSetOf<Pair<Long, String>>()
            for (entry in raw) {
                val parts = ruleKey(entry).split("|", limit = 4)
                if (parts.size != 4 || parts[0] != "d") continue
                val calendarId = parts[1].toLongOrNull() ?: continue
                when (parts[2]) {
                    KIND_EVENT -> parts[3].toLongOrNull()?.let { eventIds += calendarId to it }
                    KIND_TITLE -> titles += calendarId to parts[3]
                }
            }
            return DeviceRules(eventIds, titles)
        }

        /** Returns [instances] without the ones [rules] hide. */
        fun applyDevice(instances: List<DeviceCalendarInstance>, rules: DeviceRules): List<DeviceCalendarInstance> {
            if (rules.isEmpty) return instances
            return instances.filterNot { rules.hides(it.calendarId, it.eventId, it.title) }
        }

        /**
         * Returns [events] without the ones [rules] hide. Every event that shares a uid with a
         * hidden one goes too, so the exceptions of a hidden series don't survive as orphans.
         */
        fun apply(events: List<Event>, rules: Rules): List<Event> {
            if (rules.isEmpty) return events
            val hiddenUids = events.filter(rules::hides).mapTo(mutableSetOf()) { it.uid }
            return events.filterNot { it.uid in hiddenUids }
        }
    }
}
