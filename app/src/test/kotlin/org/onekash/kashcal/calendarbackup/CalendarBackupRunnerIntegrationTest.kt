package org.onekash.kashcal.calendarbackup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.coordinator.OfflineCalendarFixture
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs automatic backup over the real export path: offline calendars read through the real
 * [org.onekash.kashcal.domain.coordinator.EventCoordinator], text built by [IcsExporter], into
 * [FakeBackupFolder].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarBackupRunnerIntegrationTest {

    private lateinit var context: Context
    private lateinit var fixture: OfflineCalendarFixture
    private lateinit var folder: FakeBackupFolder
    private lateinit var store: CalendarBackupStore
    private lateinit var source: EventCoordinatorOfflineCalendarSource
    private lateinit var runner: CalendarBackupRunner

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fixture = OfflineCalendarFixture(context)
        store = freshCalendarBackupStore()
        store.updateSettings { it.copy(enabled = true, folderUri = FakeBackupFolder.TREE) }
        folder = FakeBackupFolder()
        source = EventCoordinatorOfflineCalendarSource(fixture.coordinator, IcsExporter(), context)
        runner = CalendarBackupRunner(store, folder, source, FakeCalendarBackupAlerts())
    }

    @After
    fun tearDown() = fixture.close()

    private suspend fun addEvent(calendarId: Long, uid: String, title: String, startTs: Long): Long =
        fixture.db.eventsDao().insert(
            Event(
                calendarId = calendarId,
                uid = uid,
                title = title,
                startTs = startTs,
                endTs = startTs + 3_600_000L,
                isAllDay = false,
                timezone = "UTC",
                dtstamp = startTs,
                createdAt = startTs,
                updatedAt = startTs,
                syncStatus = SyncStatus.SYNCED,
            )
        )

    @Test
    fun `the built-in calendar goes to Local_ics under its shown name`() = runBlocking {
        val local = fixture.coordinator.ensureLocalCalendarExists()
        addEvent(local, "a@example.test", "Lunch", 1_800_000_000_000L)

        runner.run(RunKind.ONE_TIME)

        val text = folder.text("Local.ics")!!
        assertTrue(text.contains("SUMMARY:Lunch"))
        assertTrue(text.contains("X-WR-CALNAME:${context.getString(org.onekash.kashcal.R.string.calendar_local)}"))
    }

    @Test
    @Config(qualifiers = "de")
    fun `the built-in keeps Local_ics but its calendar name follows the language`() = runBlocking {
        val local = fixture.coordinator.ensureLocalCalendarExists()
        addEvent(local, "a@example.test", "Mittag", 1_800_000_000_000L)

        runner.run(RunKind.ONE_TIME)

        val shown = context.getString(org.onekash.kashcal.R.string.calendar_local)
        assertTrue("German label expected, got $shown", shown != "Local")
        val text = folder.text("Local.ics")!!
        assertTrue(text.contains("X-WR-CALNAME:$shown"))
    }

    @Test
    fun `an edit makes one dated copy on the next run`() = runBlocking {
        val trips = fixture.coordinator.createLocalCalendar("Trips", 0, "Local").getOrThrow()
        val id = addEvent(trips, "a@example.test", "Flight", 1_800_000_000_000L)
        runner.run(RunKind.ONE_TIME)

        val event = fixture.db.eventsDao().getById(id)!!
        fixture.db.eventsDao().update(event.copy(title = "Flight home"))
        runner.run(RunKind.ONE_TIME)

        assertTrue(folder.text("Trips.ics")!!.contains("SUMMARY:Flight home"))
        val copies = folder.files.keys.filter { CalendarBackupNames.isDatedCopyOf(it, "Trips") }
        assertEquals(1, copies.size)
        assertTrue(folder.text(copies.single())!!.contains("SUMMARY:Flight\r\n"))
    }

    @Test
    fun `the backup text is the text Export calendars writes`() = runBlocking {
        val trips = fixture.coordinator.createLocalCalendar("Trips", 0, "Local").getOrThrow()
        addEvent(trips, "a@example.test", "Flight", 1_800_000_000_000L)
        addEvent(trips, "b@example.test", "Hotel", 1_800_000_000_000L)

        val backupText = source.calendarText(source.calendars().single { it.id == trips })!!
        val shared = File(context.cacheDir, "shared").apply { deleteRecursively() }
        IcsExporter().exportCalendar(context, fixture.coordinator.getCalendarEventsForExport(trips), "Trips")
        val exported = shared.listFiles()!!.single().readText()

        // DTSTAMP is the time each text was written, so it alone may differ.
        val withoutStamp = { text: String -> text.lines().filterNot { it.startsWith("DTSTAMP:") } }
        assertEquals(withoutStamp(exported), withoutStamp(backupText))
    }

    @Test
    fun `a calendar with no events writes no file`() = runBlocking {
        val trips = fixture.coordinator.createLocalCalendar("Trips", 0, "Local").getOrThrow()

        runner.run(RunKind.ONE_TIME)

        assertEquals(CalendarBackupResult.Kind.EMPTY, store.status.value.results[trips]?.kind)
        assertTrue(folder.files.keys.none { it.startsWith("Trips") })
    }
}
