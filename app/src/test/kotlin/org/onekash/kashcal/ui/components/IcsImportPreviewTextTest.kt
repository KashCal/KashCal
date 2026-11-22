package org.onekash.kashcal.ui.components

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.ImportGroup
import org.onekash.kashcal.data.ics.ImportedComponent
import org.onekash.kashcal.domain.ics.GroupPlan
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.domain.ics.IgnoreReason
import org.onekash.kashcal.domain.ics.ImportAction
import org.onekash.kashcal.domain.ics.StoredComponent
import org.onekash.kashcal.domain.ics.UpToDateReason
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [buildImportPreview], [previewLines] and [importSummary]: the import sheet's words. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IcsImportPreviewTextTest {

    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun component(title: String, organizer: String? = "boss@example.test") = ImportedComponent(
        Event(uid = "u", calendarId = 0, title = title, startTs = 0, endTs = 1, dtstamp = 0, organizerEmail = organizer),
        emptyList(),
    )

    private val stored = StoredComponent(1L, null, "boss@example.test", 0, null, "CONFIRMED", false)

    private fun plan(
        vararg actions: ImportAction,
        isInvitation: Boolean = true,
        organizer: Boolean = false,
        attendee: Boolean = true,
    ) = GroupPlan("u", actions.toList(), isInvitation, organizer, attendee)

    @Test
    fun `cancels, updates, up to date and skips read in that order`() {
        val preview = buildImportPreview(
            listOf(
                plan(
                    ImportAction.Ignored(IgnoreReason.ORGANIZER_MISMATCH, component("A")),
                    ImportAction.UpToDate(UpToDateReason.SAME_VERSION, component("B")),
                    ImportAction.UpdateSeries(stored, component("Review")),
                    ImportAction.CancelSeries(stored, emptyList(), component("Planning")),
                    ImportAction.Ignored(IgnoreReason.ORGANIZER_MISMATCH, component("C")),
                ),
            ),
            targetCanSend = true,
        )

        assertEquals(
            listOf(
                "This cancels Planning, organized by boss@example.test",
                "This updates Review",
                "1 event is already up to date",
                "2 skipped: it comes from someone other than the meeting's organizer",
            ),
            previewLines(preview, resources),
        )
    }

    @Test
    fun `a cancel without an organizer names only the event`() {
        val preview = buildImportPreview(
            listOf(plan(ImportAction.CancelInstance(stored, null, component("Planning", organizer = null)))),
            targetCanSend = false,
        )

        assertEquals(listOf("This cancels Planning"), previewLines(preview, resources))
    }

    @Test
    fun `notices follow the file and the calendar`() {
        val create = ImportAction.Create(ImportGroup("u", listOf(component("M")), emptyList()), freshUid = false)
        fun notices(plan: GroupPlan, canSend: Boolean = true) = buildImportPreview(listOf(plan), canSend).notices

        assertEquals(
            listOf(ImportNotice.SENDER_UNVERIFIED, ImportNotice.ANSWER_MAY_NOTIFY),
            notices(plan(create)),
        )
        assertEquals(
            listOf(ImportNotice.SENDER_UNVERIFIED, ImportNotice.GUESTS_MAY_RECEIVE_AGAIN),
            notices(plan(create, organizer = true, attendee = false)),
        )
        assertEquals("a plain file", emptyList<ImportNotice>(), notices(plan(create, isInvitation = false, attendee = false)))
        assertEquals("a calendar that sends nothing", emptyList<ImportNotice>(), notices(plan(create), canSend = false))
    }

    @Test
    fun `the calendar-copy, own-meeting and change-waiting skips name their reason`() {
        val preview = buildImportPreview(
            listOf(
                plan(
                    ImportAction.Ignored(IgnoreReason.CALENDAR_KEEPS_COPY, component("A")),
                    ImportAction.Ignored(IgnoreReason.OWN_UPDATE, component("B")),
                    ImportAction.Ignored(IgnoreReason.CHANGE_WAITING, component("C")),
                ),
            ),
            targetCanSend = true,
        )

        assertEquals(
            listOf(
                "1 skipped: this calendar keeps its own copy of the event",
                "1 skipped: you organize this meeting, so change it from the event",
                "1 skipped: the event has a change waiting to sync; import again after it syncs",
            ),
            previewLines(preview, resources),
        )
    }

    @Test
    fun `an own meeting the import skips doesn't warn that guests may be invited again`() {
        val skipped = plan(ImportAction.Ignored(IgnoreReason.OWN_UPDATE, component("M")), organizer = true, attendee = false)
        assertEquals(listOf(ImportNotice.SENDER_UNVERIFIED), buildImportPreview(listOf(skipped), true).notices)
    }

    @Test
    fun `the summary names each count it has`() {
        assertEquals(
            "2 added, 1 cancelled, 1 skipped",
            importSummary(IcsImportResult(created = 2, cancelled = 1, ignored = listOf(IgnoreReason.REPLY_FILE)), resources),
        )
        assertEquals("Nothing to import", importSummary(IcsImportResult(), resources))
    }
}
