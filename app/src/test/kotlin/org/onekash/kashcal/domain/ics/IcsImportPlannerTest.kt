package org.onekash.kashcal.domain.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.ImportGroup
import org.onekash.kashcal.data.ics.ImportMethod
import org.onekash.kashcal.data.ics.ImportedAttendee
import org.onekash.kashcal.data.ics.ImportedComponent

/** The import rules, one per RFC 5546 behaviour, for each target kind. */
class IcsImportPlannerTest {

    private val me = "me@example.com"
    private val boss = "boss@example.com"
    private val uid = "meeting@example.com"
    private val slot = 1_800_000_000_000L

    private fun component(
        sequence: Int = 0,
        itipDtstamp: Long? = 1_000L,
        status: String = "CONFIRMED",
        organizer: String? = boss,
        attendees: List<String> = listOf(me),
        instanceTime: Long? = null,
        thisAndFuture: Boolean = false,
    ) = ImportedComponent(
        event = Event(
            uid = uid, calendarId = 0, title = "Meeting", startTs = slot, endTs = slot + 3_600_000,
            dtstamp = 0, sequence = sequence, status = status, organizerEmail = organizer,
            originalInstanceTime = instanceTime, itipDtstamp = itipDtstamp,
        ),
        attendees = attendees.map { ImportedAttendee("mailto:$it", null, null, "NEEDS-ACTION", null, true) },
        thisAndFuture = thisAndFuture,
    )

    private fun stored(
        eventId: Long = 1,
        instanceTime: Long? = null,
        sequence: Int = 0,
        itipDtstamp: Long? = 1_000L,
        status: String = "CONFIRMED",
        organizer: String? = boss,
        pendingDelete: Boolean = false,
        placeholder: Boolean = false,
    ) = StoredComponent(eventId, instanceTime, organizer, sequence, itipDtstamp, status, pendingDelete, placeholder)

    private fun plan(
        method: ImportMethod?,
        components: List<ImportedComponent>,
        stored: StoredUid? = null,
        target: ImportTarget = ImportTarget.CALDAV,
        user: Set<String> = setOf(me),
        elsewhere: Boolean = false,
        changeWaiting: Boolean = false,
    ): GroupPlan = IcsImportPlanner.plan(
        ImportPlanInput(
            group = ImportGroup(uid, components.filter { !it.isInstance }, components.filter { it.isInstance }),
            method = method,
            stored = stored,
            target = target,
            userAddresses = user,
            elsewhereInAccount = elsewhere,
            changeWaiting = changeWaiting,
        )
    )

    private fun GroupPlan.single(): ImportAction = actions.single()

    // ---- new events ----

    @Test
    fun `a new invitation is created with its UID`() {
        val action = plan(ImportMethod.REQUEST, listOf(component())).single()
        assertTrue(action is ImportAction.Create)
        assertFalse((action as ImportAction.Create).freshUid)
    }

    @Test
    fun `a plain event whose UID is elsewhere in the account is created under a fresh UID`() {
        val action = plan(null, listOf(component(organizer = null, attendees = emptyList())), elsewhere = true).single()
        assertTrue((action as ImportAction.Create).freshUid)
    }

    @Test
    fun `an invitation whose UID is elsewhere in the account is ignored`() {
        val action = plan(ImportMethod.REQUEST, listOf(component()), elsewhere = true).single()
        assertEquals(IgnoreReason.ON_ANOTHER_CALENDAR, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `a cancelled component with nothing to attach to is ignored, not created`() {
        val action = plan(null, listOf(component(status = "CANCELLED"))).single()
        assertEquals(IgnoreReason.CANCELLED_IN_FILE, (action as ImportAction.Ignored).reason)
    }

    // ---- methods that never write ----

    @Test
    fun `a REPLY creates nothing`() {
        assertEquals(IgnoreReason.REPLY_FILE, (plan(ImportMethod.REPLY, listOf(component())).single() as ImportAction.Ignored).reason)
    }

    @Test
    fun `COUNTER, REFRESH, ADD and DECLINECOUNTER are not applied`() {
        for (m in listOf(ImportMethod.COUNTER, ImportMethod.REFRESH, ImportMethod.ADD, ImportMethod.DECLINECOUNTER)) {
            val action = plan(m, listOf(component())).single() as ImportAction.Ignored
            assertEquals(m.name, IgnoreReason.UNSUPPORTED_METHOD, action.reason)
        }
    }

    // ---- REQUEST against a stored event ----

    @Test
    fun `on CalDAV a newer REQUEST from the stored organizer updates the series`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 1)), StoredUid(stored(), emptyList())).single()
        assertTrue(action is ImportAction.UpdateSeries)
    }

    @Test
    fun `on CalDAV the same version is up to date and an older one is older`() {
        val same = plan(ImportMethod.REQUEST, listOf(component(sequence = 1)), StoredUid(stored(sequence = 1), emptyList())).single()
        assertEquals(UpToDateReason.SAME_VERSION, (same as ImportAction.UpToDate).reason)
        val older = plan(ImportMethod.REQUEST, listOf(component(sequence = 0)), StoredUid(stored(sequence = 1), emptyList())).single()
        assertEquals(UpToDateReason.OLDER_VERSION, (older as ImportAction.UpToDate).reason)
    }

    @Test
    fun `on CalDAV the same SEQUENCE with a later DTSTAMP updates`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 1, itipDtstamp = 2_000L)), StoredUid(stored(sequence = 1, itipDtstamp = 1_000L), emptyList())).single()
        assertTrue(action is ImportAction.UpdateSeries)
    }

    @Test
    fun `on CalDAV a newer REQUEST from another organizer is ignored`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 1, organizer = "other@example.com")), StoredUid(stored(), emptyList())).single()
        assertEquals(IgnoreReason.ORGANIZER_MISMATCH, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `on CalDAV a newer cancelled REQUEST cancels the series`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 1, status = "CANCELLED")), StoredUid(stored(), emptyList())).single()
        assertTrue(action is ImportAction.CancelSeries)
    }

    @Test
    fun `on CalDAV a REQUEST for the user's own meeting writes nothing, cancelled or not`() {
        for (status in listOf("CONFIRMED", "CANCELLED")) {
            val action = plan(
                ImportMethod.REQUEST, listOf(component(sequence = 1, status = status, organizer = me)),
                StoredUid(stored(organizer = me), emptyList())
            ).single()
            assertEquals(status, IgnoreReason.OWN_UPDATE, (action as ImportAction.Ignored).reason)
        }
    }

    @Test
    fun `on the local calendar a REQUEST for the user's own meeting still updates`() {
        val action = plan(
            ImportMethod.REQUEST, listOf(component(sequence = 1, organizer = me)),
            StoredUid(stored(organizer = me), emptyList()), ImportTarget.LOCAL, setOf(me)
        ).single()
        assertTrue(action is ImportAction.UpdateSeries)
    }

    @Test
    fun `on CalDAV a change waiting to upload blocks every write but not an up-to-date read`() {
        val newer = plan(ImportMethod.REQUEST, listOf(component(sequence = 1)), StoredUid(stored(), emptyList()), changeWaiting = true).single()
        assertEquals(IgnoreReason.CHANGE_WAITING, (newer as ImportAction.Ignored).reason)
        val same = plan(ImportMethod.REQUEST, listOf(component(sequence = 0)), StoredUid(stored(), emptyList()), changeWaiting = true).single()
        assertEquals(UpToDateReason.SAME_VERSION, (same as ImportAction.UpToDate).reason)
    }

    @Test
    fun `a CANCEL still cancels while a change waits to upload`() {
        val action = plan(ImportMethod.CANCEL, listOf(component(sequence = 1, status = "CANCELLED")), StoredUid(stored(), emptyList()), changeWaiting = true).single()
        assertTrue(action is ImportAction.CancelSeries)
    }

    @Test
    fun `on the local calendar a waiting change is not read`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 1)), StoredUid(stored(), emptyList()), ImportTarget.LOCAL, emptySet(), changeWaiting = true).single()
        assertTrue(action is ImportAction.UpdateSeries)
    }

    @Test
    fun `on CalDAV the pull's placeholder series keeps the calendar's copy for every component`() {
        val actions = plan(
            ImportMethod.REQUEST, listOf(component(sequence = 1), component(sequence = 1, instanceTime = slot)),
            StoredUid(stored(placeholder = true, status = "CANCELLED", organizer = null), listOf(stored(eventId = 2, instanceTime = slot)))
        ).actions
        assertEquals(2, actions.size)
        actions.forEach { assertEquals(IgnoreReason.CALENDAR_KEEPS_COPY, (it as ImportAction.Ignored).reason) }
    }

    @Test
    fun `a CANCEL for an occurrence of a placeholder series still cancels it`() {
        val action = plan(
            ImportMethod.CANCEL, listOf(component(sequence = 1, status = "CANCELLED", instanceTime = slot)),
            StoredUid(stored(placeholder = true, status = "CANCELLED"), listOf(stored(eventId = 2, instanceTime = slot)))
        ).single()
        assertTrue(action is ImportAction.CancelInstance)
    }

    @Test
    fun `with no series row stored, the local calendar is up to date and CalDAV and device keep their copy`() {
        val onlyException = StoredUid(null, listOf(stored(eventId = 2, instanceTime = slot)))
        val local = plan(ImportMethod.REQUEST, listOf(component(sequence = 1)), onlyException, ImportTarget.LOCAL, emptySet()).single()
        assertEquals(UpToDateReason.SAME_VERSION, (local as ImportAction.UpToDate).reason)
        for (target in listOf(ImportTarget.CALDAV, ImportTarget.DEVICE)) {
            val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 1)), onlyException, target).single()
            assertEquals(target.name, IgnoreReason.CALENDAR_KEEPS_COPY, (action as ImportAction.Ignored).reason)
        }
    }

    @Test
    fun `on a device calendar a matched REQUEST keeps the calendar's copy, even when newer`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 3)), StoredUid(stored(), emptyList()), ImportTarget.DEVICE).single()
        assertEquals(IgnoreReason.CALENDAR_KEEPS_COPY, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `a matched newer PUBLISH or plain file on CalDAV keeps the calendar's copy`() {
        for (method in listOf(ImportMethod.PUBLISH, null)) {
            val action = plan(method, listOf(component(sequence = 3)), StoredUid(stored(), emptyList())).single()
            assertEquals("$method", IgnoreReason.CALENDAR_KEEPS_COPY, (action as ImportAction.Ignored).reason)
        }
    }

    @Test
    fun `a PUBLISH over an event pending deletion reads as pending deletion`() {
        val action = plan(ImportMethod.PUBLISH, listOf(component(sequence = 3)), StoredUid(stored(pendingDelete = true), emptyList())).single()
        assertEquals(IgnoreReason.DELETION_PENDING, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `on the local calendar a higher SEQUENCE updates`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 2)), StoredUid(stored(sequence = 1), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        assertTrue(action is ImportAction.UpdateSeries)
    }

    @Test
    fun `on the local calendar the same SEQUENCE with a later DTSTAMP updates, the same DTSTAMP is up to date`() {
        val newer = plan(ImportMethod.REQUEST, listOf(component(itipDtstamp = 2_000)), StoredUid(stored(itipDtstamp = 1_000), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        val same = plan(ImportMethod.REQUEST, listOf(component(itipDtstamp = 1_000)), StoredUid(stored(itipDtstamp = 1_000), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        assertTrue(newer is ImportAction.UpdateSeries)
        assertEquals(UpToDateReason.SAME_VERSION, (same as ImportAction.UpToDate).reason)
    }

    @Test
    fun `on the local calendar an older file is up to date`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 1)), StoredUid(stored(sequence = 2), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        assertEquals(UpToDateReason.OLDER_VERSION, (action as ImportAction.UpToDate).reason)
    }

    @Test
    fun `an unknown stored DTSTAMP decides on SEQUENCE alone`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(itipDtstamp = 9_000)), StoredUid(stored(itipDtstamp = null), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        assertEquals(UpToDateReason.SAME_VERSION, (action as ImportAction.UpToDate).reason)
    }

    @Test
    fun `a newer file from another organizer is ignored`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 5, organizer = "mallory@example.com")), StoredUid(stored(), emptyList()), ImportTarget.LOCAL, emptySet()).single() as ImportAction.Ignored
        assertEquals(IgnoreReason.ORGANIZER_MISMATCH, action.reason)
        assertEquals("mallory@example.com", action.fileOrganizer)
        assertEquals(boss, action.storedOrganizer)
    }

    @Test
    fun `an event pending deletion is ignored`() {
        val action = plan(ImportMethod.REQUEST, listOf(component()), StoredUid(stored(pendingDelete = true), emptyList())).single()
        assertEquals(IgnoreReason.DELETION_PENDING, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `a REQUEST older than an applied CANCEL does not revive it`() {
        val action = plan(ImportMethod.REQUEST, listOf(component(sequence = 0)), StoredUid(stored(sequence = 1, status = "CANCELLED"), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        assertEquals(UpToDateReason.OLDER_VERSION, (action as ImportAction.UpToDate).reason)
    }

    // ---- instances ----

    @Test
    fun `an instance REQUEST on the local calendar creates or updates that exception`() {
        val create = plan(ImportMethod.REQUEST, listOf(component(sequence = 1, instanceTime = slot)), StoredUid(stored(), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        val update = plan(ImportMethod.REQUEST, listOf(component(sequence = 2, instanceTime = slot)), StoredUid(stored(), listOf(stored(eventId = 2, instanceTime = slot, sequence = 1))), ImportTarget.LOCAL, emptySet()).single()
        assertTrue(create is ImportAction.CreateInstance)
        assertTrue(update is ImportAction.UpdateInstance)
    }

    @Test
    fun `an instance REQUEST on CalDAV creates or updates that exception`() {
        val create = plan(ImportMethod.REQUEST, listOf(component(sequence = 1, instanceTime = slot)), StoredUid(stored(), emptyList())).single()
        assertTrue(create is ImportAction.CreateInstance)
        val update = plan(ImportMethod.REQUEST, listOf(component(sequence = 1, instanceTime = slot)), StoredUid(stored(), listOf(stored(eventId = 2, instanceTime = slot)))).single()
        assertTrue(update is ImportAction.UpdateInstance)
    }

    @Test
    fun `an instance message whose series isn't stored is created standalone`() {
        assertTrue(plan(ImportMethod.REQUEST, listOf(component(instanceTime = slot))).single() is ImportAction.Create)
    }

    @Test
    fun `RANGE=THISANDFUTURE is not applied`() {
        val action = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", instanceTime = slot, thisAndFuture = true)), StoredUid(stored(), emptyList())).single()
        assertEquals(IgnoreReason.THIS_AND_FUTURE, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `a new series keeps its RANGE=THISANDFUTURE change out`() {
        val master = component()
        val later = component(instanceTime = slot, thisAndFuture = true)
        val actions = plan(ImportMethod.REQUEST, listOf(master, later)).actions

        val create = actions.filterIsInstance<ImportAction.Create>().single()
        assertTrue("the change isn't linked as one occurrence", create.group.exceptions.isEmpty())
        assertEquals(listOf(IgnoreReason.THIS_AND_FUTURE), actions.filterIsInstance<ImportAction.Ignored>().map { it.reason })
    }

    @Test
    fun `a lone RANGE=THISANDFUTURE change creates nothing`() {
        val actions = plan(ImportMethod.REQUEST, listOf(component(instanceTime = slot, thisAndFuture = true))).actions

        assertEquals(listOf(IgnoreReason.THIS_AND_FUTURE), actions.map { (it as ImportAction.Ignored).reason })
    }

    // ---- CANCEL ----

    @Test
    fun `a CANCEL never creates`() {
        val action = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 1))).single()
        assertEquals(IgnoreReason.CANCEL_NO_MATCH, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `STATUS CANCELLED cancels the series with its exceptions`() {
        val exc = stored(eventId = 2, instanceTime = slot)
        val action = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 1)), StoredUid(stored(), listOf(exc))).single() as ImportAction.CancelSeries
        assertEquals(listOf(exc), action.exceptions)
    }

    @Test
    fun `a CANCEL without STATUS cancels when it lists the user, and is an uninvite of someone else otherwise`() {
        val forMe = plan(ImportMethod.CANCEL, listOf(component(sequence = 1, attendees = listOf(me))), StoredUid(stored(), emptyList())).single()
        val forOther = plan(ImportMethod.CANCEL, listOf(component(sequence = 1, attendees = listOf("other@example.com"))), StoredUid(stored(), emptyList())).single()
        assertTrue(forMe is ImportAction.CancelSeries)
        assertEquals(IgnoreReason.CANCEL_NOT_FOR_USER, (forOther as ImportAction.Ignored).reason)
    }

    @Test
    fun `on the local calendar a CANCEL without STATUS from the stored organizer cancels`() {
        val action = plan(ImportMethod.CANCEL, listOf(component(sequence = 1, attendees = listOf("other@example.com"))), StoredUid(stored(), emptyList()), ImportTarget.LOCAL, emptySet()).single()
        assertTrue(action is ImportAction.CancelSeries)
    }

    @Test
    fun `a CANCEL from another organizer is ignored`() {
        val action = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 1, organizer = "mallory@example.com")), StoredUid(stored(), emptyList())).single()
        assertEquals(IgnoreReason.ORGANIZER_MISMATCH, (action as ImportAction.Ignored).reason)
    }

    @Test
    fun `an older CANCEL is up to date, the same SEQUENCE still cancels`() {
        val older = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 0)), StoredUid(stored(sequence = 2), emptyList())).single()
        val same = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 2)), StoredUid(stored(sequence = 2), emptyList())).single()
        assertEquals(UpToDateReason.OLDER_VERSION, (older as ImportAction.UpToDate).reason)
        assertTrue(same is ImportAction.CancelSeries)
    }

    @Test
    fun `an instance CANCEL cancels that occurrence, with or without its exception`() {
        val exc = stored(eventId = 2, instanceTime = slot)
        val withExc = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 1, instanceTime = slot)), StoredUid(stored(), listOf(exc))).single() as ImportAction.CancelInstance
        val without = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 1, instanceTime = slot)), StoredUid(stored(), emptyList())).single() as ImportAction.CancelInstance
        assertEquals(exc, withExc.stored)
        assertEquals(null, without.stored)
    }

    @Test
    fun `the user's own CANCEL on a CalDAV calendar is ignored`() {
        val action = plan(ImportMethod.CANCEL, listOf(component(status = "CANCELLED", sequence = 1, organizer = me)), StoredUid(stored(organizer = me), emptyList())).single()
        assertEquals(IgnoreReason.OWN_CANCEL, (action as ImportAction.Ignored).reason)
    }

    // ---- flags ----

    @Test
    fun `the plan flags invitations, the user as organizer and as attendee`() {
        val invite = plan(ImportMethod.REQUEST, listOf(component()))
        val own = plan(null, listOf(component(organizer = me, attendees = listOf("guest@example.com"))))
        assertTrue(invite.isInvitation); assertTrue(invite.userIsAttendee); assertFalse(invite.userIsOrganizer)
        assertFalse(own.isInvitation); assertTrue(own.userIsOrganizer); assertFalse(own.userIsAttendee)
    }
}
