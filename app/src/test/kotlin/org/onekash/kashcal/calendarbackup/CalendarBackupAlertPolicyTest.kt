package org.onekash.kashcal.calendarbackup

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.calendarbackup.CalendarBackupAlertPolicy.Action

class CalendarBackupAlertPolicyTest {

    private fun done(problem: FolderFailure? = null, failed: List<String> = emptyList(), kind: RunKind = RunKind.PERIODIC) =
        BackupRunOutcome.Done(kind, problem, failed)

    private fun decide(outcome: BackupRunOutcome.Done, failedRuns: Int, notified: String? = null) =
        CalendarBackupAlertPolicy.decide(outcome, failedRuns, notified, "Backups")

    @Test
    fun `a clean run removes the notification`() {
        assertEquals(Action.Cancel, decide(done(), failedRuns = 0, notified = "FolderUnreachable"))
        assertEquals(Action.Cancel, decide(done(kind = RunKind.ONE_TIME), failedRuns = 0))
    }

    @Test
    fun `lost access and an unsupported folder alert on the first failed run`() {
        assertEquals(
            Action.Notify(BackupAlert.FolderUnreachable("Backups")),
            decide(done(FolderFailure.NO_ACCESS), failedRuns = 1),
        )
        assertEquals(
            Action.Notify(BackupAlert.FolderUnsupported("Backups")),
            decide(done(FolderFailure.UNSUPPORTED), failedRuns = 1),
        )
    }

    @Test
    fun `problems that can heal alert after two failed runs in a row`() {
        assertEquals(Action.None, decide(done(FolderFailure.MISSING), failedRuns = 1))
        assertEquals(
            Action.Notify(BackupAlert.FolderUnreachable("Backups")),
            decide(done(FolderFailure.MISSING), failedRuns = 2),
        )
        assertEquals(Action.None, decide(done(failed = listOf("Trips.ics")), failedRuns = 1))
        assertEquals(
            Action.Notify(BackupAlert.CalendarsFailed(listOf("Trips.ics"))),
            decide(done(failed = listOf("Trips.ics")), failedRuns = 2),
        )
        assertEquals(
            Action.Notify(BackupAlert.FolderUnreachable("Backups")),
            decide(done(FolderFailure.FAILED), failedRuns = 3),
        )
    }

    @Test
    fun `one notification per problem`() {
        assertEquals(Action.None, decide(done(FolderFailure.NO_ACCESS), failedRuns = 4, notified = "FolderUnreachable"))
    }

    @Test
    fun `a different problem replaces the notification`() {
        assertEquals(
            Action.Notify(BackupAlert.CalendarsFailed(listOf("Trips.ics"))),
            decide(done(failed = listOf("Trips.ics")), failedRuns = 3, notified = "FolderUnreachable"),
        )
    }

    @Test
    fun `a run started from the sheet never posts`() {
        assertEquals(Action.None, decide(done(FolderFailure.NO_ACCESS, kind = RunKind.ONE_TIME), failedRuns = 5))
    }
}
