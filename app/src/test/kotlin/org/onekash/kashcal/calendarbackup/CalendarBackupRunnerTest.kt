package org.onekash.kashcal.calendarbackup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarBackupRunnerTest {

    private var clock = Instant.parse("2026-10-26T03:00:00Z").toEpochMilli()
    private lateinit var store: CalendarBackupStore
    private lateinit var folder: FakeBackupFolder
    private lateinit var source: FakeOfflineCalendarSource
    private lateinit var runner: CalendarBackupRunner
    private lateinit var alerts: FakeCalendarBackupAlerts

    private val tree = FakeBackupFolder.TREE

    @Before
    fun setup() {
        store = freshCalendarBackupStore()
        store.updateSettings { it.copy(enabled = true, folderUri = tree, folderName = "KashCal backups") }
        folder = FakeBackupFolder(now = { clock })
        source = FakeOfflineCalendarSource()
        alerts = FakeCalendarBackupAlerts()
        runner = CalendarBackupRunner(store, folder, source, alerts, now = { clock })
    }

    private fun ics(summary: String, stamp: String = "20261026T030000Z") =
        "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:1\r\nDTSTAMP:$stamp\r\nSUMMARY:$summary\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

    private fun run(kind: RunKind = RunKind.PERIODIC) = runBlocking { runner.run(kind) }
    private fun result(id: Long) = store.status.value.results[id]
    private fun writes() = folder.operations.count { it == "write" || it == "copy" || it == "create" }

    // ---------- when nothing runs ----------

    @Test
    fun `off or without a folder nothing runs`() {
        source.add(1, "Local", ics("A"), builtIn = true)
        store.updateSettings { it.copy(enabled = false) }
        assertEquals(BackupRunOutcome.Skipped, run())
        store.updateSettings { it.copy(enabled = true, folderUri = null) }
        assertEquals(BackupRunOutcome.Skipped, run())
        assertTrue(folder.operations.isEmpty())
        assertNull(store.status.value.lastCheckedAt)
    }

    @Test
    fun `without a write grant the run records lost access and writes nothing`() {
        source.add(1, "Local", ics("A"), builtIn = true)
        folder.hasGrant = false
        val outcome = run() as BackupRunOutcome.Done
        assertEquals(FolderFailure.NO_ACCESS, outcome.folderProblem)
        assertEquals(FolderFailure.NO_ACCESS, store.status.value.folderProblem)
        assertEquals(0, writes())
        assertEquals(1, store.status.value.failedRuns)
    }

    @Test
    fun `a folder that is gone or can't hold files is recorded`() {
        source.add(1, "Local", ics("A"), builtIn = true)
        folder.missing = true
        assertEquals(FolderFailure.MISSING, (run() as BackupRunOutcome.Done).folderProblem)
        folder.missing = false
        folder.canCreate = false
        assertEquals(FolderFailure.UNSUPPORTED, (run() as BackupRunOutcome.Done).folderProblem)
        assertEquals(2, store.status.value.failedRuns)
    }

    // ---------- writing ----------

    @Test
    fun `each calendar is written to its latest file`() {
        source.add(1, "Lokal", ics("A"), builtIn = true)
        source.add(2, "Trips", ics("B"))
        val outcome = run() as BackupRunOutcome.Done
        assertNull(outcome.folderProblem)
        assertEquals(ics("A"), folder.text("Local.ics"))
        assertEquals(ics("B"), folder.text("Trips.ics"))
        assertEquals(CalendarBackupResult.Kind.UPDATED, result(1)?.kind)
        assertEquals("Local.ics", result(1)?.fileName)
        assertEquals(clock, store.status.value.lastCheckedAt)
        assertEquals(0, store.status.value.failedRuns)
    }

    @Test
    fun `a calendar with no events is skipped and its file left as it was`() {
        folder.put("Trips.ics", ics("old"))
        source.add(2, "Trips", text = null)
        run()
        assertEquals(ics("old"), folder.text("Trips.ics"))
        assertEquals(CalendarBackupResult.Kind.EMPTY, result(2)?.kind)
    }

    @Test
    fun `an unchanged calendar still keeps the previous file as a copy`() {
        val oldTime = Instant.parse("2026-10-25T03:10:05Z").toEpochMilli()
        folder.put("Trips.ics", ics("B"), lastModified = oldTime)
        source.add(2, "Trips", ics("B"))
        run()
        assertEquals(CalendarBackupResult.Kind.UPDATED, result(2)?.kind)
        assertEquals(ics("B"), folder.text("Trips_20261025T031005Z.ics"))
        assertEquals(ics("B"), folder.text("Trips.ics"))
    }

    @Test
    fun `a change keeps the old content as a dated copy named by its time`() {
        val oldTime = Instant.parse("2026-10-25T03:10:05Z").toEpochMilli()
        folder.put("Trips.ics", ics("old"), lastModified = oldTime)
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(ics("new"), folder.text("Trips.ics"))
        assertEquals(ics("old"), folder.text("Trips_20261025T031005Z.ics"))
    }

    @Test
    fun `a dated copy without a file time is named by now`() {
        folder.put("Trips.ics", ics("old"), lastModified = null)
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(ics("old"), folder.text("Trips_20261026T030000Z.ics"))
    }

    @Test
    fun `a taken dated name moves to the next free second`() {
        val oldTime = Instant.parse("2026-10-25T03:10:05Z").toEpochMilli()
        folder.put("Trips.ics", ics("old"), lastModified = oldTime)
        folder.put("Trips_20261025T031005Z.ics", ics("older"))
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(ics("older"), folder.text("Trips_20261025T031005Z.ics"))
        assertEquals(ics("old"), folder.text("Trips_20261025T031006Z.ics"))
    }

    @Test
    fun `keep one overwrites without a dated copy and removes old copies`() {
        store.updateSettings { it.copy(keepCount = 1) }
        folder.put("Trips.ics", ics("old"))
        folder.put("Trips_20261020T000000Z.ics", ics("older"))
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(listOf("Trips.ics"), folder.files.keys.toList())
        assertEquals(ics("new"), folder.text("Trips.ics"))
    }

    @Test
    fun `an empty latest file gets no dated copy`() {
        folder.put("Trips.ics", "")
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(listOf("Trips.ics"), folder.files.keys.toList())
    }

    @Test
    fun `a failed dated copy is removed and the latest is left as it was`() {
        folder.put("Trips.ics", ics("old"))
        folder.failures["copy"] = FolderFailure.FAILED
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(listOf("Trips.ics"), folder.files.keys.toList())
        assertEquals(ics("old"), folder.text("Trips.ics"))
        assertEquals(CalendarBackupResult.Kind.FAILED, result(2)?.kind)
        assertEquals(1, store.status.value.failedRuns)
    }

    @Test
    fun `a provider that keeps the old tail fails the calendar`() {
        store.updateSettings { it.copy(keepCount = 1) }
        folder.put("Trips.ics", ics("a much longer old summary than the new one"))
        folder.ignoreTruncation = true
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(CalendarBackupResult.Kind.FAILED, result(2)?.kind)
    }

    @Test
    fun `a latest file the provider renames on create fails the calendar`() {
        folder.subfolders += "Trips.ics"
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(CalendarBackupResult.Kind.FAILED, result(2)?.kind)
        assertFalse(folder.files.keys.any { it.startsWith("Trips (") })
    }

    @Test
    fun `one calendar failing doesn't stop the others`() {
        source.add(1, "Local", ics("A"), builtIn = true)
        source.add(2, "Trips", ics("B"))
        source.add(3, "Ideas", ics("C"))
        source.failing += 2
        folder.put("Ideas.ics", "x")
        folder.failWritesTo["Ideas.ics"] = FolderFailure.FAILED
        val outcome = run() as BackupRunOutcome.Done
        assertEquals(ics("A"), folder.text("Local.ics"))
        assertEquals(CalendarBackupResult.Kind.FAILED, result(2)?.kind)
        assertEquals(CalendarBackupResult.Kind.FAILED, result(3)?.kind)
        assertEquals(listOf("Trips.ics", "Ideas.ics"), outcome.failedFiles)
        assertEquals(1, store.status.value.failedRuns)
    }

    @Test
    fun `failed runs count up and a clean run resets them`() {
        source.add(2, "Trips", ics("B"))
        folder.hasGrant = false
        run()
        run()
        assertEquals(2, store.status.value.failedRuns)
        folder.hasGrant = true
        run()
        assertEquals(0, store.status.value.failedRuns)
        assertNull(store.status.value.folderProblem)
    }

    @Test
    fun `a case-only rename keeps writing into the existing file`() {
        folder.caseInsensitive = true
        folder.put("work.ics", ics("old"))
        source.add(2, "Work", ics("new"))
        run()
        assertEquals(CalendarBackupResult.Kind.UPDATED, result(2)?.kind)
        assertEquals(ics("new"), folder.text("work.ics"))
        assertFalse("Work.ics" in folder.files)
        assertEquals(ics("old"), folder.text("work_20261026T030000Z.ics") ?: folder.text("Work_20261026T030000Z.ics"))
    }

    // ---------- trimming ----------

    @Test
    fun `every run trims dated copies beyond the count, keeping the new one`() {
        store.updateSettings { it.copy(keepCount = 2) }
        val wrongClock = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()
        folder.put("Trips.ics", ics("old"), lastModified = wrongClock)
        folder.put("Trips_20261020T000000Z.ics", ics("x"))
        folder.put("Trips_20261021T000000Z.ics", ics("y"))
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(setOf("Trips.ics", "Trips_20200101T000000Z.ics"), folder.files.keys)
    }

    @Test
    fun `an empty calendar is trimmed too`() {
        store.updateSettings { it.copy(keepCount = 2) }
        folder.put("Trips.ics", ics("B"))
        folder.put("Trips_20261020T000000Z.ics", ics("x"))
        folder.put("Trips_20261021T000000Z.ics", ics("y"))
        source.add(2, "Trips", text = null)
        run()
        assertEquals(setOf("Trips.ics", "Trips_20261021T000000Z.ics"), folder.files.keys)
    }

    @Test
    fun `without delete support the trim is skipped and the backup still counts`() {
        store.updateSettings { it.copy(keepCount = 1) }
        folder.deleteSupported = false
        folder.put("Trips.ics", ics("old"))
        folder.put("Trips_20261020T000000Z.ics", ics("x"))
        source.add(2, "Trips", ics("new"))
        run()
        assertEquals(CalendarBackupResult.Kind.UPDATED, result(2)?.kind)
        assertTrue("Trips_20261020T000000Z.ics" in folder.files)
        assertEquals(0, store.status.value.failedRuns)
    }

    @Test
    fun `files the backup didn't name are never deleted`() {
        store.updateSettings { it.copy(keepCount = 1) }
        val others = listOf("Trips (1).ics", "notes.txt", "Ideas_20261020T000000Z.ics", "Trips_x.ics", "trips-old.ics")
        others.forEach { folder.put(it, "keep") }
        folder.put("Trips.ics", ics("old"))
        source.add(2, "Trips", ics("new"))
        run()
        others.forEach { assertTrue(it, it in folder.files) }
    }

    @Test
    fun `clashing names get their id and never share a file`() {
        source.add(17, "Work", ics("W"))
        source.add(18, "Work!", ics("X"))
        run()
        assertEquals(ics("W"), folder.text("Work-17.ics"))
        assertEquals(ics("X"), folder.text("Work-18.ics"))
        assertFalse("Work.ics" in folder.files)
    }

    // ---------- run bookkeeping ----------

    @Test
    fun `results of a run whose folder changed meanwhile are dropped`() {
        source.add(2, "Trips", ics("B"))
        source.onCalendarText = {
            store.updateSettings { it.copy(folderUri = "content://other/tree/x") }
        }
        folder.hasGrant = true
        assertEquals(BackupRunOutcome.Skipped, run())
        assertNull(store.status.value.lastCheckedAt)
        assertTrue(store.status.value.results.isEmpty())
    }

    @Test
    fun `results of a run turned off meanwhile are dropped`() {
        source.add(2, "Trips", ics("B"))
        source.onCalendarText = { store.updateSettings { it.copy(enabled = false) } }
        assertEquals(BackupRunOutcome.Skipped, run())
        assertEquals(0, store.status.value.failedRuns)
    }

    @Test
    fun `results of deleted calendars are dropped`() {
        source.add(2, "Trips", ics("B"))
        run()
        source.calendars.clear()
        source.add(3, "Ideas", ics("C"))
        run()
        assertEquals(setOf(3L), store.status.value.results.keys)
    }

    @Test
    fun `runs never overlap`() = runBlocking {
        source.add(2, "Trips", ics("B"))
        val inside = AtomicInteger()
        var most = 0
        source.onCalendarText = {
            most = maxOf(most, inside.incrementAndGet())
            delay(20)
            inside.decrementAndGet()
        }
        (1..4).map { async(Dispatchers.Default) { runner.run(RunKind.ONE_TIME) } }.awaitAll()
        assertEquals(1, most)
    }

    // ---------- alerts ----------

    @Test
    fun `a periodic run that lost access posts once`() {
        source.add(2, "Trips", ics("B"))
        folder.hasGrant = false
        run()
        run()
        assertEquals(listOf<BackupAlert>(BackupAlert.FolderUnreachable("KashCal backups")), alerts.posted)
        assertEquals("FolderUnreachable", store.status.value.notifiedAlert)
    }

    @Test
    fun `when the folder comes back but a calendar keeps failing the notification changes`() {
        source.add(2, "Trips", ics("B"))
        folder.hasGrant = false
        run()
        folder.hasGrant = true
        folder.put("Trips.ics", "x")
        folder.failWritesTo["Trips.ics"] = FolderFailure.FAILED
        run()
        assertEquals(
            listOf(BackupAlert.FolderUnreachable("KashCal backups"), BackupAlert.CalendarsFailed(listOf("Trips.ics"))),
            alerts.posted,
        )
    }

    @Test
    fun `a run started from the sheet posts nothing but counts toward the alert`() {
        source.add(2, "Trips", ics("B"))
        folder.put("Trips.ics", "x")
        folder.failWritesTo["Trips.ics"] = FolderFailure.FAILED
        run(RunKind.ONE_TIME)
        assertTrue(alerts.posted.isEmpty())
        run(RunKind.PERIODIC)
        assertEquals(listOf<BackupAlert>(BackupAlert.CalendarsFailed(listOf("Trips.ics"))), alerts.posted)
    }

    @Test
    fun `a clean run removes the notification and clears the flag`() {
        source.add(2, "Trips", ics("B"))
        folder.hasGrant = false
        run()
        folder.hasGrant = true
        run()
        assertEquals(1, alerts.cancels)
        assertNull(store.status.value.notifiedAlert)
    }

    @Test
    fun `nothing is posted when notifications can't show`() {
        source.add(2, "Trips", ics("B"))
        folder.hasGrant = false
        alerts.postable = false
        run()
        assertTrue(alerts.posted.isEmpty())
        assertNull(store.status.value.notifiedAlert)
    }

    @Test
    fun `a run whose folder changed meanwhile raises no alert`() {
        source.add(2, "Trips", ics("B"))
        folder.failWritesTo["Trips.ics"] = FolderFailure.FAILED
        source.onCalendarText = { store.updateSettings { it.copy(folderUri = "content://other/tree/x") } }
        run()
        assertTrue(alerts.posted.isEmpty())
        assertEquals(0, alerts.cancels)
    }
}
