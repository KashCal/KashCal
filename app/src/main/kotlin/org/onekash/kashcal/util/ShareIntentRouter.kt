package org.onekash.kashcal.util

import android.content.Intent
import org.onekash.kashcal.ui.viewmodels.PendingAction

/**
 * Maps an `Intent.ACTION_SEND` `text/plain` intent to a [PendingAction]:
 *
 *  - Short input → [PendingAction.QuickAddFromText], seeding the Quick Add dialog.
 *  - Long input → [PendingAction.CreateEventFromCalendarIntent], the full event form with the
 *    whole text in the description.
 *  - Anything else (wrong action or type, blank, unreadable extras) → null.
 *
 * The Quick Add action carries `nowMs` as its reference time, so "tomorrow" in the shared text
 * resolves against the share's arrival, not the date the user was browsing.
 */
object ShareIntentRouter {

    fun route(intent: Intent?, nowMs: Long): PendingAction? {
        return when (val result = ShareTextIntentParser.parse(intent, nowMs)) {
            null -> null
            is ShareTextResult.Short -> PendingAction.QuickAddFromText(
                text = result.text,
                location = result.location,
                referenceMs = result.referenceMs
            )
            is ShareTextResult.Long -> PendingAction.CreateEventFromCalendarIntent(
                data = CalendarIntentData(
                    title = result.title,
                    description = result.description,
                    location = result.location
                ),
                // Shares carry no invitees.
                invitees = emptyList()
            )
        }
    }
}
