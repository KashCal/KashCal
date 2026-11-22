package org.onekash.kashcal.util

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.util.Log
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.calendar_provider.DeviceUidRow
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.IcsImportFile
import org.onekash.kashcal.data.ics.ImportGroup
import org.onekash.kashcal.data.ics.ImportedAttendee
import org.onekash.kashcal.data.ics.ImportedComponent
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.ics.GroupPlan
import org.onekash.kashcal.domain.ics.IcsImportPlanner
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.domain.ics.ImportAction
import org.onekash.kashcal.domain.ics.ImportPlanInput
import org.onekash.kashcal.domain.ics.ImportTarget
import org.onekash.kashcal.domain.ics.StoredComponent
import org.onekash.kashcal.domain.ics.StoredUid
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "DeviceCalendarImporter"

/**
 * Returns what [importIcsFileToDeviceCalendar] would do with each group of [file] in device
 * calendar [calendarId], without writing anything.
 */
suspend fun planDeviceImport(
    file: IcsImportFile,
    calendarId: Long,
    repo: CalendarProviderRepository,
): List<GroupPlan> {
    val context = deviceImportContext(calendarId, repo)
    return file.groups.map { IcsImportPlanner.plan(context.planInput(file, it, repo).first) }
}

/**
 * Imports a parsed ICS file into device calendar [calendarId], applying its iTIP METHOD the way
 * [IcsImportPlanner] decides for [ImportTarget.DEVICE]: the owning sync adapter holds the truth,
 * so a UID the calendar has is left alone unless the file cancels it.
 *
 * A new UID is written with the file's UID in `UID_2445`; an invitation (REQUEST) also keeps
 * its organizer and guests, with the user's own row under the calendar's owner address. A plain
 * event whose UID another calendar of the same account holds gets no `UID_2445`, so the adapter
 * makes one instead of uploading a duplicate. Only a master carries the UID: its changed
 * occurrences import as standalone events, and cancelled ones are left out. A group whose
 * shape isn't one master (plus exceptions of a series) imports event by event.
 *
 * Each event is written in the zone Room stores for it
 * ([org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper.storedZoneOf]), the phone's zone
 * when that is null (an all-day event is stored in UTC by the repository), so a file shows the
 * same times in a device and a KashCal calendar.
 * A recurring event is written with a DURATION and no end time. An invitation gets the user's
 * default reminders instead of the organizer's VALARMs; a plain event keeps its VALARMs and
 * takes the default only when it has none. A failed write is counted and skipped; cancellation
 * still propagates.
 *
 * @param defaultTimedReminderMinutes the user's default for timed events
 *   (`KashCalDataStore.defaultReminderMinutes`); [KashCalDataStore.REMINDER_OFF] applies none
 * @param defaultAllDayReminderMinutes the same for all-day events
 */
suspend fun importIcsFileToDeviceCalendar(
    file: IcsImportFile,
    calendarId: Long,
    repo: CalendarProviderRepository,
    defaultTimedReminderMinutes: Int = KashCalDataStore.REMINDER_OFF,
    defaultAllDayReminderMinutes: Int = KashCalDataStore.REMINDER_OFF,
): IcsImportResult {
    val context = deviceImportContext(calendarId, repo)
    val defaults = ReminderDefaults(defaultTimedReminderMinutes, defaultAllDayReminderMinutes)
    var result = IcsImportResult()
    for (group in file.groups) {
        result += try {
            val (input, rows) = context.planInput(file, group, repo)
            IcsImportPlanner.plan(input).actions.fold(IcsImportResult()) { sum, action ->
                sum + applyDeviceAction(context, file, action, rows, repo, defaults)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Import of one UID failed", e)
            IcsImportResult(failed = group.components.size)
        }
    }
    return result
}

private class ReminderDefaults(val timed: Int, val allDay: Int)

private class DeviceImportContext(
    val calendarId: Long,
    val calendar: DeviceCalendar?,
    val userAddresses: Set<String>,
    val siblingCalendarIds: List<Long>,
) {
    /** Returns the planner input for [group] and the target calendar's rows by event id. */
    suspend fun planInput(
        file: IcsImportFile,
        group: ImportGroup,
        repo: CalendarProviderRepository,
    ): Pair<ImportPlanInput, Map<Long, DeviceUidRow>> {
        val rows = repo.findEventsByUid(group.uid, listOf(calendarId) + siblingCalendarIds).getOrThrow()
        val here = rows.filter { it.calendarId == calendarId }
        val stored = StoredUid(
            master = here.firstOrNull { it.originalId == null }?.toStored(),
            exceptions = here.filter { it.originalId != null }.map { it.toStored() },
        ).takeUnless { it.isEmpty }
        val input = ImportPlanInput(
            group = group,
            method = file.method,
            stored = stored,
            target = ImportTarget.DEVICE,
            userAddresses = userAddresses,
            elsewhereInAccount = rows.any { it.calendarId != calendarId },
        )
        return input to here.associateBy { it.eventId }
    }
}

private suspend fun deviceImportContext(calendarId: Long, repo: CalendarProviderRepository): DeviceImportContext {
    // An unreadable calendar list leaves the import without an identity or sibling calendars;
    // the writes that follow report their own failures.
    val calendar = readOrNull { repo.getDeviceCalendar(calendarId) }
    val siblings = if (calendar == null) emptyList() else readOrNull { repo.getDeviceCalendars() }.orEmpty()
        .filter { it.id != calendarId && it.accountName == calendar.accountName && it.accountType == calendar.accountType }
        .map { it.id }
    val owner = calendar?.ownerAccount?.takeIf { it.isNotBlank() }
    return DeviceImportContext(
        calendarId = calendarId,
        calendar = calendar,
        userAddresses = setOfNotNull(owner?.let(AddressNormalizer::canonical)),
        siblingCalendarIds = siblings,
    )
}

private suspend fun <T> readOrNull(read: suspend () -> T): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Device calendar read failed", e)
    null
}

// Rows keep no SEQUENCE or DTSTAMP, so the planner compares only what a CANCEL needs.
private fun DeviceUidRow.toStored() = StoredComponent(
    eventId = eventId,
    instanceTime = if (originalId != null) originalInstanceTime else null,
    organizerEmail = organizer,
    sequence = 0,
    itipDtstamp = null,
    status = when (status) {
        Events.STATUS_CANCELED -> ImportedComponent.STATUS_CANCELLED
        Events.STATUS_TENTATIVE -> "TENTATIVE"
        else -> "CONFIRMED"
    },
    pendingDelete = false,
)

private suspend fun applyDeviceAction(
    context: DeviceImportContext,
    file: IcsImportFile,
    action: ImportAction,
    rows: Map<Long, DeviceUidRow>,
    repo: CalendarProviderRepository,
    defaults: ReminderDefaults,
): IcsImportResult = when (action) {
    is ImportAction.Create -> createOnDevice(context, file, action, repo, defaults)
    is ImportAction.CancelSeries -> {
        val ids = listOf(action.master.eventId) + action.exceptions.filterNot { it.isCancelled }.map { it.eventId }
        val failures = ids.count { repo.setEventStatus(it, Events.STATUS_CANCELED).isFailure }
        if (failures == 0) IcsImportResult(cancelled = 1, firstStartTs = action.component.event.startTs)
        else IcsImportResult(failed = 1)
    }
    is ImportAction.CancelInstance -> {
        val instanceTime = requireNotNull(action.component.event.originalInstanceTime)
        val allDay = rows[action.master.eventId]?.isAllDay ?: action.component.event.isAllDay
        if (repo.deleteSingleOccurrence(action.master.eventId, instanceTime, allDay).isSuccess) {
            IcsImportResult(cancelled = 1, firstStartTs = action.component.event.startTs)
        } else {
            IcsImportResult(failed = 1)
        }
    }
    is ImportAction.UpToDate -> IcsImportResult(upToDate = 1)
    is ImportAction.Ignored -> IcsImportResult(ignored = listOf(action.reason))
    // The planner keeps a device calendar's own copy, so it never plans these for one.
    is ImportAction.UpdateSeries, is ImportAction.UpdateInstance, is ImportAction.CreateInstance -> {
        Log.w(TAG, "Unexpected ${action::class.simpleName} for a device calendar")
        IcsImportResult(failed = 1)
    }
}

private suspend fun createOnDevice(
    context: DeviceImportContext,
    file: IcsImportFile,
    action: ImportAction.Create,
    repo: CalendarProviderRepository,
    defaults: ReminderDefaults,
): IcsImportResult {
    val group = action.group
    val master = group.masters.singleOrNull()
    val linked = master != null && (group.exceptions.isEmpty() || master.event.rrule != null)
    var result = IcsImportResult()
    val standalone = if (linked && master != null) {
        val uid = if (action.freshUid) null else group.uid
        val created = if (file.isInvitation && uid != null && master.event.organizerEmail != null) {
            createInvitation(context, master, uid, repo, defaults)
        } else {
            createPlain(context.calendarId, master, uid, repo, defaults, file.isInvitation)
        }
        result += created
        group.exceptions
    } else {
        group.components
    }
    for (component in standalone.filterNot { it.isCancelled }) {
        result += createPlain(context.calendarId, component, null, repo, defaults, file.isInvitation)
    }
    return result
}

private suspend fun createPlain(
    calendarId: Long,
    component: ImportedComponent,
    uid: String?,
    repo: CalendarProviderRepository,
    defaults: ReminderDefaults,
    isInvitation: Boolean,
): IcsImportResult {
    val event = component.event
    val isRecurring = !event.rrule.isNullOrBlank()
    val created = repo.createEvent(
        calendarId = calendarId,
        title = event.title,
        description = event.description?.takeIf { it.isNotBlank() },
        location = event.location?.takeIf { it.isNotBlank() },
        startTs = event.startTs,
        endTs = if (isRecurring) null else event.endTs,
        isAllDay = event.isAllDay,
        rrule = event.rrule,
        duration = if (isRecurring) event.duration ?: computeDurationString(event.startTs, event.endTs, event.isAllDay) else null,
        timezone = component.event.timezone ?: java.util.TimeZone.getDefault().id,
        reminders = remindersFor(event, defaults, isInvitation),
        uid = uid,
    )
    return if (created.isSuccess) IcsImportResult(created = 1, firstStartTs = event.startTs) else IcsImportResult(failed = 1)
}

private suspend fun createInvitation(
    context: DeviceImportContext,
    master: ImportedComponent,
    uid: String,
    repo: CalendarProviderRepository,
    defaults: ReminderDefaults,
): IcsImportResult {
    val event = master.event
    val organizer = requireNotNull(event.organizerEmail)
    val isRecurring = !event.rrule.isNullOrBlank()
    val created = repo.createInvitation(
        calendarId = context.calendarId,
        title = event.title,
        description = event.description?.takeIf { it.isNotBlank() },
        location = event.location?.takeIf { it.isNotBlank() },
        startTs = event.startTs,
        endTs = if (isRecurring) null else event.endTs,
        isAllDay = event.isAllDay,
        rrule = event.rrule,
        duration = if (isRecurring) event.duration ?: computeDurationString(event.startTs, event.endTs, event.isAllDay) else null,
        timezone = master.event.timezone ?: java.util.TimeZone.getDefault().id,
        reminders = remindersFor(event, defaults, isInvitation = true),
        uid = uid,
        organizer = organizer,
        status = if (event.status == "TENTATIVE") Events.STATUS_TENTATIVE else Events.STATUS_CONFIRMED,
        attendees = master.attendees.map { it.toDeviceAttendee(organizer, context.calendar?.ownerAccount) },
    )
    return if (created.isSuccess) IcsImportResult(created = 1, firstStartTs = event.startTs) else IcsImportResult(failed = 1)
}

/**
 * Maps a file attendee to a provider row. The user's row takes the calendar's owner string
 * exactly, since the provider matches SELF_ATTENDEE_STATUS by exact text; the organizer is a
 * row only when the file lists it as an attendee. NEEDS-ACTION (or no PARTSTAT, its default)
 * is INVITED, so the user can answer.
 */
private fun ImportedAttendee.toDeviceAttendee(organizer: String, ownerAccount: String?): DeviceAttendee {
    val email = address.removePrefix("mailto:").removePrefix("MAILTO:")
    val canonical = AddressNormalizer.canonical(email)
    val isOwner = !ownerAccount.isNullOrBlank() && AddressNormalizer.canonical(ownerAccount) == canonical
    return DeviceAttendee(
        id = 0,
        name = displayName,
        email = if (isOwner) ownerAccount else email,
        relationship = if (canonical == AddressNormalizer.canonical(organizer)) {
            Attendees.RELATIONSHIP_ORGANIZER
        } else {
            Attendees.RELATIONSHIP_ATTENDEE
        },
        status = when (partstat?.uppercase()) {
            "ACCEPTED" -> Attendees.ATTENDEE_STATUS_ACCEPTED
            "DECLINED" -> Attendees.ATTENDEE_STATUS_DECLINED
            "TENTATIVE" -> Attendees.ATTENDEE_STATUS_TENTATIVE
            else -> Attendees.ATTENDEE_STATUS_INVITED
        },
    )
}

private fun remindersFor(event: Event, defaults: ReminderDefaults, isInvitation: Boolean): List<Int> {
    if (!isInvitation && event.reminders != null) return isoRemindersToMinutes(event.reminders)
    val minutes = if (event.isAllDay) defaults.allDay else defaults.timed
    return if (minutes == KashCalDataStore.REMINDER_OFF) emptyList() else listOf(minutes)
}
