package org.onekash.kashcal.domain.onboarding

/**
 * Returns whether the first-launch "Where are your calendars?" sheet shows.
 *
 * It is for a user with nothing set up yet: it shows only while the sheet was never completed, no
 * iCloud or CalDAV account is signed in, no calendar feed or birthdays account exists, device
 * calendars are off and the app holds no events (an offline or imported event counts). The facts
 * are read in that order and reading stops at the first one that hides the sheet, so a completed
 * sheet costs one read on every launch.
 */
suspend fun shouldShowOnboarding(
    isCompleted: suspend () -> Boolean,
    hasSignedInSyncAccount: suspend () -> Boolean,
    hasFeedOrBirthdaysAccount: suspend () -> Boolean,
    deviceCalendarsEnabled: suspend () -> Boolean,
    eventCount: suspend () -> Int,
): Boolean = !isCompleted() &&
    !hasSignedInSyncAccount() &&
    !hasFeedOrBirthdaysAccount() &&
    !deviceCalendarsEnabled() &&
    eventCount() == 0
