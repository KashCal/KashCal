package org.onekash.kashcal.domain.whatsnew

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.onekash.kashcal.R

/**
 * Every release note the What's New sheet can show.
 *
 * To announce a release, add a [ReleaseNote] for its versionCode, add its strings (and any
 * string array), and generate their translations. A release without an entry shows nothing.
 * [WhatsNewGate] sorts by versionCode; entries sharing one show as pages in the order listed here.
 */
val ALL_RELEASE_NOTES: ImmutableList<ReleaseNote> = persistentListOf(
    ReleaseNote(
        versionCode = 598,
        titleRes = R.string.whats_new_v598_title,
        bodyRes = R.string.whats_new_v598_body,
        actionLabelRes = R.string.whats_new_v598_action_label,
        actionUrlRes = R.string.whats_new_v598_action_url,
    ),
    ReleaseNote(
        versionCode = 732,
        titleRes = R.string.whats_new_v732_title,
        bodyRes = R.string.whats_new_v732_body,
        captionRes = R.string.whats_new_v732_caption,
        actionLabelRes = R.string.whats_new_v732_action_label,
        actionUrlRes = R.string.whats_new_v732_action_url,
    ),
    ReleaseNote(
        versionCode = 772,
        titleRes = R.string.whats_new_v772_icon_title,
        bodyRes = R.string.whats_new_v772_icon_body,
        findInRes = listOf(R.string.hub_section_make_it_yours, R.string.settings_app_icon),
        hero = WhatsNewHero.DATE_ICON,
    ),
    ReleaseNote(
        versionCode = 772,
        titleRes = R.string.whats_new_v772_changes_title,
        bodyRes = R.string.whats_new_v772_changes_body,
        findInRes = listOf(R.string.hub_section_own_your_calendar, R.string.recent_changes_title),
        hero = WhatsNewHero.RECENT_CHANGES,
    ),
    ReleaseNote(
        versionCode = 772,
        titleRes = R.string.settings_change_notifications,
        bodyRes = R.string.whats_new_v772_notifications_body,
        findInRes = listOf(
            R.string.hub_accounts_and_settings,
            R.string.settings_section_sync,
            R.string.settings_change_notifications,
        ),
        hero = WhatsNewHero.CHANGE_NOTIFICATIONS,
    ),
    ReleaseNote(
        versionCode = 803,
        titleRes = R.string.whats_new_v803_offline_title,
        bodyRes = R.string.whats_new_v803_offline_body,
        findInRes = listOf(
            R.string.hub_accounts_and_settings,
            R.string.settings_section_calendars,
            R.string.offline_calendars_row_label,
        ),
        hero = WhatsNewHero.OFFLINE_CALENDARS,
    ),
    ReleaseNote(
        versionCode = 803,
        titleRes = R.string.whats_new_v803_backup_title,
        bodyRes = R.string.whats_new_v803_backup_body,
        findInRes = listOf(
            R.string.hub_accounts_and_settings,
            R.string.settings_section_data,
            R.string.calendar_backup_title,
        ),
        hero = WhatsNewHero.CALENDAR_BACKUP,
    ),
    ReleaseNote(
        versionCode = 803,
        titleRes = R.string.whats_new_v803_print_title,
        bodyRes = R.string.whats_new_v803_print_body,
        findInRes = listOf(R.string.hub_section_own_your_calendar, R.string.print_your_week),
        hero = WhatsNewHero.PRINT_WEEK,
    ),
)
