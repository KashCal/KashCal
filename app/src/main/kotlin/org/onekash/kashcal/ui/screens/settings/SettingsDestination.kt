package org.onekash.kashcal.ui.screens.settings

/**
 * Lists the screens reachable within Settings. The root swaps to a detail screen and back as the
 * user drills in; [depth] and [isForwardTo] pick the slide direction, so drilling in slides the
 * incoming screen in from the trailing edge and backing out reverses it.
 */
enum class SettingsDestination(val depth: Int) {
    Root(depth = 0),
    Accounts(depth = 1),
    BirthdaysAnniversaries(depth = 1),
    Subscriptions(depth = 1),
    Tags(depth = 1),
    DeviceCalendars(depth = 1);

    /** True when navigating from this destination to [target] moves deeper (root -> detail). */
    fun isForwardTo(target: SettingsDestination): Boolean = target.depth > depth

    companion object {
        /**
         * Resolves the active destination from the individual navigation flags. A detail flag
         * wins over the root; if more than one is set, the first in parameter order wins.
         */
        fun from(
            accounts: Boolean,
            birthdaysAnniversaries: Boolean,
            subscriptions: Boolean,
            tags: Boolean,
            deviceCalendars: Boolean,
        ): SettingsDestination = when {
            accounts -> Accounts
            birthdaysAnniversaries -> BirthdaysAnniversaries
            subscriptions -> Subscriptions
            tags -> Tags
            deviceCalendars -> DeviceCalendars
            else -> Root
        }
    }
}
