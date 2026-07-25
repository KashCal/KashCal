package org.onekash.kashcal.ui.screens.settings

/**
 * The screens reachable within the Settings surface. The root screen swaps to one
 * of the detail screens (and back) as the user drills in; [depth] and [isForwardTo]
 * drive the directional slide animation between them so drilling into a detail
 * slides the incoming screen in from the trailing edge and backing out reverses it.
 */
enum class SettingsDestination(val depth: Int) {
    Root(depth = 0),
    Accounts(depth = 1),
    BirthdaysAnniversaries(depth = 1),
    Subscriptions(depth = 1),
    DeviceCalendars(depth = 1);

    /** True when navigating from this destination to [target] moves deeper (root -> detail). */
    fun isForwardTo(target: SettingsDestination): Boolean = target.depth > depth

    companion object {
        /**
         * Resolves the active destination from the individual navigation flags. A
         * detail flag wins over the root; if several were somehow set the first in
         * declaration order is chosen, matching the `when` ordering in the caller.
         */
        fun from(
            accounts: Boolean,
            birthdaysAnniversaries: Boolean,
            subscriptions: Boolean,
            deviceCalendars: Boolean,
        ): SettingsDestination = when {
            accounts -> Accounts
            birthdaysAnniversaries -> BirthdaysAnniversaries
            subscriptions -> Subscriptions
            deviceCalendars -> DeviceCalendars
            else -> Root
        }
    }
}
