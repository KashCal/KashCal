package org.onekash.kashcal.data.contacts

import android.content.res.Resources
import android.provider.ContactsContract
import androidx.annotation.StringRes
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.screens.settings.SubscriptionColors

/**
 * Configuration enum for contact event types (birthday, anniversary).
 *
 * Carries all per-type differences so that [BaseContactEventRepository] can implement
 * the shared sync algorithm once. Adding a new contact event type (e.g., custom dates)
 * requires only a new enum entry + thin repository subclass for Hilt DI.
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

    fun getCaldavUrl(lookupKey: String, month: Int, day: Int): String =
        "$sourcePrefix:$lookupKey:$month-$day"

    companion object {
        fun fromCaldavUrl(caldavUrl: String?): ContactEventType? =
            entries.find { caldavUrl?.startsWith("${it.sourcePrefix}:") == true }
    }
}
