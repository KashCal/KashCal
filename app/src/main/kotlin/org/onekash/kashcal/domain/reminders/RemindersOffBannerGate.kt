package org.onekash.kashcal.domain.reminders

/** API level at which POST_NOTIFICATIONS became a runtime permission (Android 13). */
private const val NOTIFICATIONS_PERMISSION_MIN_SDK = 33

/**
 * Returns whether the "Reminders are off" banner shows.
 *
 * It is for a user the app never asked for notification permission who has reminders due soon:
 * Android 13 or later, permission not granted, no recorded ask ([askDone]), no recorded refusal
 * ([deniedCount]), no rationale due (Android shows one only after a refusal), and at least one
 * event with a reminder in the window. A running sync holds back only the first show, so the
 * count is settled; a banner already on screen stays while a sync runs.
 */
fun shouldShowRemindersOffBanner(
    sdkInt: Int,
    permissionGranted: Boolean,
    askDone: Boolean,
    deniedCount: Int,
    rationaleDue: Boolean,
    syncRunning: Boolean,
    alreadyShown: Boolean,
    eventsWithReminders: Int,
): Boolean = sdkInt >= NOTIFICATIONS_PERMISSION_MIN_SDK &&
    !permissionGranted &&
    !askDone &&
    deniedCount == 0 &&
    !rationaleDue &&
    eventsWithReminders > 0 &&
    (alreadyShown || !syncRunning)
