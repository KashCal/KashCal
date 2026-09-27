package org.onekash.kashcal.data.contacts

import android.content.res.Resources
import android.provider.ContactsContract
import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.screens.settings.SubscriptionColors

/**
 * Holds every per-type difference between the birthday and anniversary calendars, so
 * [BaseContactEventRepository] implements the sync once.
 *
 * A new type needs an entry here and a repository subclass. Code outside this enum names each
 * type explicitly and needs one too, for example [ContactEventManager],
 * [ContactEventSyncWorker], [org.onekash.kashcal.domain.coordinator.EventCoordinator],
 * [org.onekash.kashcal.data.preferences.KashCalDataStore] and the settings screen.
 */
enum class ContactEventType(
    val accountEmail: String,
    @StringRes val calendarDisplayNameRes: Int,
    val sourcePrefix: String,
    val localCalendarUrl: String,
    val defaultColor: Int,
    val uidSuffix: String,
    val contactEventTypeId: Int,
    val logTag: String,
    val formatTitle: (name: String, year: Int?, occurrenceTs: Long) -> String,
    val formatTitleI18n: (name: String, year: Int?, occurrenceTs: Long, resources: Resources) -> String,
) {
    BIRTHDAY(
        accountEmail = "contact_birthdays",
        calendarDisplayNameRes = R.string.settings_contact_birthdays,
        sourcePrefix = "contact_birthday",
        localCalendarUrl = "local://contact_birthdays",
        defaultColor = SubscriptionColors.Purple,
        uidSuffix = "@kashcal.birthday",
        contactEventTypeId = ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY,
        logTag = "ContactBirthdayRepo",
        formatTitle = ContactEventUtils::formatBirthdayTitle,
        formatTitleI18n = ContactEventUtils::formatBirthdayTitle,
    ),
    ANNIVERSARY(
        accountEmail = "contact_anniversaries",
        calendarDisplayNameRes = R.string.settings_contact_anniversaries,
        sourcePrefix = "contact_anniversary",
        localCalendarUrl = "local://contact_anniversaries",
        defaultColor = SubscriptionColors.Pink,
        uidSuffix = "@kashcal.anniversary",
        contactEventTypeId = ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY,
        logTag = "ContactAnniversaryRepo",
        formatTitle = ContactEventUtils::formatAnniversaryTitle,
        formatTitleI18n = ContactEventUtils::formatAnniversaryTitle,
    );

    /** Localized display name for the contact event calendar. */
    fun calendarDisplayName(resources: Resources): String =
        resources.getString(calendarDisplayNameRes)

    /**
     * Returns the synthetic key stored in `caldavUrl` that matches an event to its contact and
     * date; [fromCaldavUrl] reads the type back from its prefix.
     */
    fun getCaldavUrl(lookupKey: String, month: Int, day: Int): String =
        "$sourcePrefix:$lookupKey:$month-$day"

    companion object {
        fun fromCaldavUrl(caldavUrl: String?): ContactEventType? =
            entries.find { caldavUrl?.startsWith("${it.sourcePrefix}:") == true }
    }
}
