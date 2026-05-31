package org.onekash.kashcal.util

import android.content.Intent
import org.onekash.kashcal.ui.viewmodels.PendingAction

/**
 * Maps an `Intent.ACTION_SEND` `text/plain` intent to the right `PendingAction`:
 *
 *  - Short input → [PendingAction.QuickAddFromText] (Quick Add dialog seed)
 *  - Long input  → [PendingAction.CreateEventFromCalendarIntent] (full event form,
 *                  whole text in description)
 *  - Anything else (wrong action/type, blank, unreadable extras) → null
 *
 * Reference time is stamped at routing time so "tomorrow" in the shared text
 * resolves relative to share-arrival, not whatever date the user was browsing.
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
                // Shares carry no invitees — empty by contract.
                invitees = emptyList()
            )
        }
    }
}
