package org.onekash.kashcal.domain.whatsnew

/** The illustration a What's New page draws above its title; each value names one drawing. */
enum class WhatsNewHero {
    /** The launcher icon turning over from yesterday's date to today's. */
    DATE_ICON,

    /** A Recent changes card filling with sample rows. */
    RECENT_CHANGES,

    /** A change notification arriving on a lock screen. */
    CHANGE_NOTIFICATIONS,

    /** A list of offline calendars filling in, then one of them changing color. */
    OFFLINE_CALENDARS,

    /** A backup folder getting a new dated copy while the oldest is deleted. */
    CALENDAR_BACKUP,

    /** A week calendar page and its details page sliding out of a printer. */
    PRINT_WEEK,
}
