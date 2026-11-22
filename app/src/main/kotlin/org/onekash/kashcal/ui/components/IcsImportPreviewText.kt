package org.onekash.kashcal.ui.components

import android.content.res.Resources
import org.onekash.kashcal.R
import org.onekash.kashcal.data.ics.ImportedComponent
import org.onekash.kashcal.domain.ics.GroupPlan
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.domain.ics.IgnoreReason
import org.onekash.kashcal.domain.ics.ImportAction
import org.onekash.kashcal.domain.ics.writesStoredEvent

/** An inline notice the import sheet shows before the tap; each can be dismissed. */
enum class ImportNotice {
    /** The file holds an invitation, whose sender nothing can verify. */
    SENDER_UNVERIFIED,

    /** The user attends an invitation in the file, and answering it may reach the organizer. */
    ANSWER_MAY_NOTIFY,

    /** The user organizes a meeting the import writes, and its guests may be invited again. */
    GUESTS_MAY_RECEIVE_AGAIN,
}

/** One event the import changes, as the sheet names it. */
data class PlannedChange(val title: String, val organizer: String?)

/** What importing a file into one calendar does, counted from the planner's actions. */
data class IcsImportPreview(
    val cancels: List<PlannedChange>,
    val updates: List<PlannedChange>,
    val upToDate: Int,
    val skipped: Map<IgnoreReason, Int>,
    val notices: List<ImportNotice>,
)

/**
 * Builds the sheet's preview from the import plan for the chosen calendar.
 *
 * @param targetCanSend false for a calendar that sends nothing (an offline calendar, or a
 *   device calendar no account syncs); it gets no notices, since nothing leaves the phone
 */
fun buildImportPreview(plans: List<GroupPlan>, targetCanSend: Boolean): IcsImportPreview {
    val actions = plans.flatMap { it.actions }
    fun change(component: ImportedComponent) = PlannedChange(component.event.title, component.event.organizerEmail)
    val cancels = actions.mapNotNull { a ->
        when (a) {
            is ImportAction.CancelSeries -> change(a.component)
            is ImportAction.CancelInstance -> change(a.component)
            else -> null
        }
    }
    val updates = actions.mapNotNull { a ->
        when (a) {
            is ImportAction.UpdateSeries -> change(a.component)
            is ImportAction.UpdateInstance -> change(a.component)
            is ImportAction.CreateInstance -> change(a.component)
            else -> null
        }
    }
    val notices = if (!targetCanSend) {
        emptyList()
    } else {
        buildList {
            if (plans.any { it.isInvitation }) add(ImportNotice.SENDER_UNVERIFIED)
            if (plans.any { it.isInvitation && it.userIsAttendee && !it.userIsOrganizer }) {
                add(ImportNotice.ANSWER_MAY_NOTIFY)
            }
            if (plans.any { p -> p.userIsOrganizer && p.actions.any { it is ImportAction.Create || it.writesStoredEvent } }) {
                add(ImportNotice.GUESTS_MAY_RECEIVE_AGAIN)
            }
        }
    }
    return IcsImportPreview(
        cancels = cancels,
        updates = updates,
        upToDate = actions.count { it is ImportAction.UpToDate },
        skipped = actions.filterIsInstance<ImportAction.Ignored>().groupingBy { it.reason }.eachCount(),
        notices = notices,
    )
}

/** Returns the preview's lines in display order: cancels, updates, up to date, then skips. */
fun previewLines(preview: IcsImportPreview, resources: Resources): List<String> = buildList {
    preview.cancels.forEach { c ->
        add(
            if (c.organizer.isNullOrBlank()) resources.getString(R.string.ics_import_will_cancel_no_organizer, c.title)
            else resources.getString(R.string.ics_import_will_cancel, c.title, c.organizer)
        )
    }
    preview.updates.forEach { add(resources.getString(R.string.ics_import_will_update, it.title)) }
    if (preview.upToDate > 0) {
        add(resources.getQuantityString(R.plurals.ics_import_preview_up_to_date, preview.upToDate, preview.upToDate))
    }
    preview.skipped.forEach { (reason, count) ->
        add(resources.getQuantityString(R.plurals.ics_import_preview_skipped, count, count, resources.getString(reason.textRes())))
    }
}

/** Returns the notice's text resource. */
fun ImportNotice.textRes(): Int = when (this) {
    ImportNotice.SENDER_UNVERIFIED -> R.string.ics_import_notice_unverified
    ImportNotice.ANSWER_MAY_NOTIFY -> R.string.ics_import_notice_answer_notifies
    ImportNotice.GUESTS_MAY_RECEIVE_AGAIN -> R.string.ics_import_notice_guests_again
}

private fun IgnoreReason.textRes(): Int = when (this) {
    IgnoreReason.REPLY_FILE -> R.string.ics_import_reason_reply
    IgnoreReason.UNSUPPORTED_METHOD -> R.string.ics_import_reason_unsupported
    IgnoreReason.ORGANIZER_MISMATCH -> R.string.ics_import_reason_organizer_mismatch
    IgnoreReason.CANCEL_NO_MATCH -> R.string.ics_import_reason_cancel_no_match
    IgnoreReason.CANCEL_NOT_FOR_USER -> R.string.ics_import_reason_cancel_not_for_you
    IgnoreReason.DELETION_PENDING -> R.string.ics_import_reason_deletion_pending
    IgnoreReason.CANCELLED_IN_FILE -> R.string.ics_import_reason_cancelled_in_file
    IgnoreReason.THIS_AND_FUTURE -> R.string.ics_import_reason_this_and_future
    IgnoreReason.OWN_CANCEL -> R.string.ics_import_reason_own_cancel
    IgnoreReason.ON_ANOTHER_CALENDAR -> R.string.ics_import_reason_on_another_calendar
    IgnoreReason.CALENDAR_KEEPS_COPY -> R.string.ics_import_reason_calendar_keeps_copy
    IgnoreReason.OWN_UPDATE -> R.string.ics_import_reason_own_update
    IgnoreReason.CHANGE_WAITING -> R.string.ics_import_reason_change_waiting
}

/** Returns the snackbar text after an import, naming each non-zero count. */
fun importSummary(result: IcsImportResult, resources: Resources): String {
    val parts = listOf(
        R.plurals.ics_import_summary_created to result.created,
        R.plurals.ics_import_summary_updated to result.updated,
        R.plurals.ics_import_summary_cancelled to result.cancelled,
        R.plurals.ics_import_summary_up_to_date to result.upToDate,
        R.plurals.ics_import_summary_skipped to result.ignored.size,
        R.plurals.ics_import_summary_failed to result.failed,
    ).filter { it.second > 0 }.map { (res, n) -> resources.getQuantityString(res, n, n) }
    return if (parts.isEmpty()) resources.getString(R.string.ics_import_summary_nothing)
    else parts.joinToString(resources.getString(R.string.ics_import_summary_separator))
}
