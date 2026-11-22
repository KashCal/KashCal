package org.onekash.kashcal.testutil

import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import java.util.UUID

/**
 * Inserts a second offline calendar at a fresh `local://calendar/<uuid>` URL under the local
 * account and returns its id. Creates the local account and the built-in calendar first through
 * [LocalCalendarInitializer.ensureLocalCalendarExists] when either is missing, so it also works
 * after a test seeded them itself. Mirrors [LocalCalendarInitializer.createCalendar] without its
 * name rule, so tests can seed without going through the code they test.
 */
suspend fun KashCalDatabase.insertOfflineCalendar(displayName: String = "Work", color: Int = 0): Long {
    LocalCalendarInitializer(this).ensureLocalCalendarExists()
    val account = requireNotNull(
        accountsDao().getByProviderAndEmail(AccountProvider.LOCAL, LocalCalendarInitializer.LOCAL_EMAIL)
    )
    return calendarsDao().insert(
        Calendar(
            accountId = account.id,
            caldavUrl = "${LocalCalendarInitializer.NEW_CALENDAR_URL_PREFIX}${UUID.randomUUID()}",
            displayName = displayName,
            color = color
        )
    )
}
