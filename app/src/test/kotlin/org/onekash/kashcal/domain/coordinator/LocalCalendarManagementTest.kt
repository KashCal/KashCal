package org.onekash.kashcal.domain.coordinator

import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.insertOfflineCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creating, renaming and recoloring offline calendars through [EventCoordinator], over real Room:
 * the name rule (trimmed, not blank, no case-insensitive duplicate among offline calendars with
 * the built-in matched by its translated label), the built-in's recolor-only rule, and refusal of
 * every calendar outside the offline account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LocalCalendarManagementTest {

    private lateinit var fixture: OfflineCalendarFixture
    private val coordinator get() = fixture.coordinator

    @Before
    fun setUp() {
        fixture = OfflineCalendarFixture(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = fixture.close()

    private fun refusalOf(result: Result<*>) = fixture.refusalOf(result)

    private suspend fun localAccountCount(): Int =
        fixture.db.accountsDao().getAllOnce().count { it.provider == AccountProvider.LOCAL }

    private suspend fun calendarNames(): List<String> =
        fixture.db.calendarsDao().getAllOnce().map { it.displayName }

    // ---- create ----

    @Test
    fun `create adds a visible writable calendar under the one local account`() = runBlocking {
        val builtInId = coordinator.ensureLocalCalendarExists()
        val builtIn = fixture.db.calendarsDao().getById(builtInId)!!

        val id = coordinator.createLocalCalendar("Work", 0xFF336699.toInt(), builtInName = "Local").getOrThrow()

        val row = fixture.db.calendarsDao().getById(id)!!
        assertTrue(row.caldavUrl, Regex("^local://calendar/[0-9a-f-]{36}$").matches(row.caldavUrl))
        assertEquals("Work", row.displayName)
        assertEquals(0xFF336699.toInt(), row.color)
        assertTrue(row.isVisible)
        assertFalse(row.isReadOnly)
        assertFalse(row.isDefault)
        assertEquals(builtIn.accountId, row.accountId)
        assertEquals(1, localAccountCount())
    }

    @Test
    fun `create on a fresh database makes the local account and the built-in first`() = runBlocking {
        val id = coordinator.createLocalCalendar("Work", 0, builtInName = "Local").getOrThrow()

        val builtIn = fixture.db.calendarsDao().getByCaldavUrl(LocalCalendarInitializer.LOCAL_CALENDAR_URL)!!
        assertEquals(1, localAccountCount())
        assertEquals(builtIn.accountId, fixture.db.calendarsDao().getById(id)!!.accountId)
    }

    @Test
    fun `a created calendar is local and its events stay on the phone`() = runBlocking {
        val id = coordinator.createLocalCalendar("Work", 0, builtInName = "Local").getOrThrow()
        assertTrue(fixture.initializer.isLocalCalendar(fixture.db.calendarsDao().getById(id)!!))

        val start = System.currentTimeMillis() + 2 * 86_400_000L
        val event = coordinator.createEvent(
            Event(
                uid = "",
                calendarId = id,
                title = "Dentist",
                startTs = start,
                endTs = start + 3_600_000L,
                dtstamp = 0L
            ),
            calendarId = id
        )

        assertEquals(SyncStatus.SYNCED, fixture.db.eventsDao().getById(event.id)!!.syncStatus)
        assertTrue(fixture.db.pendingOperationsDao().getAllOnce().isEmpty())
        verify(exactly = 0) { fixture.syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `each create gets its own URL and a rename keeps it`() = runBlocking {
        val a = coordinator.createLocalCalendar("Work", 0, builtInName = "Local").getOrThrow()
        val b = coordinator.createLocalCalendar("Home", 0, builtInName = "Local").getOrThrow()
        val urlA = fixture.db.calendarsDao().getById(a)!!.caldavUrl

        assertNotEquals(urlA, fixture.db.calendarsDao().getById(b)!!.caldavUrl)
        coordinator.renameLocalCalendar(a, "Office", "Local").getOrThrow()
        assertEquals(urlA, fixture.db.calendarsDao().getById(a)!!.caldavUrl)
    }

    @Test
    fun `create stores the trimmed name`() = runBlocking {
        val id = coordinator.createLocalCalendar("  Work  ", 0, builtInName = "Local").getOrThrow()

        assertEquals("Work", fixture.db.calendarsDao().getById(id)!!.displayName)
    }

    @Test
    fun `create refuses a blank name and adds nothing`() = runBlocking {
        coordinator.ensureLocalCalendarExists()
        val before = calendarNames()

        assertEquals(LocalCalendarRefusal.BLANK_NAME, refusalOf(coordinator.createLocalCalendar("", 0, "Local")))
        assertEquals(LocalCalendarRefusal.BLANK_NAME, refusalOf(coordinator.createLocalCalendar("   ", 0, "Local")))
        assertEquals(before, calendarNames())
    }

    @Test
    fun `create refuses another offline calendar's name in any case`() = runBlocking {
        coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()
        val before = calendarNames()

        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(coordinator.createLocalCalendar("work", 0, "Local")))
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(coordinator.createLocalCalendar(" WORK ", 0, "Local")))
        assertEquals(before, calendarNames())
    }

    @Test
    fun `create matches the built-in by its translated label, not its stored name`() = runBlocking {
        coordinator.ensureLocalCalendarExists()

        assertEquals(
            LocalCalendarRefusal.DUPLICATE_NAME,
            refusalOf(coordinator.createLocalCalendar("lokal", 0, builtInName = "Lokal"))
        )
        assertTrue(coordinator.createLocalCalendar("Local", 0, builtInName = "Lokal").isSuccess)
    }

    private fun conflictOf(result: Result<*>) = fixture.conflictOf(result)

    @Test
    fun `create refuses a name whose file would match another calendar's and names it`() = runBlocking {
        coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()
        coordinator.createLocalCalendar("Team 1", 0, "Local").getOrThrow()
        val before = calendarNames()

        val work = coordinator.createLocalCalendar("Work!", 0, "Local")
        assertEquals(LocalCalendarRefusal.SIMILAR_NAME, refusalOf(work))
        assertEquals("Work", conflictOf(work))
        val team = coordinator.createLocalCalendar("team-1", 0, "Local")
        assertEquals(LocalCalendarRefusal.SIMILAR_NAME, refusalOf(team))
        assertEquals("Team 1", conflictOf(team))
        assertEquals(before, calendarNames())
    }

    @Test
    fun `an exact duplicate is still refused as a duplicate`() = runBlocking {
        coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(coordinator.createLocalCalendar("work", 0, "Local")))
    }

    @Test
    fun `the built-in is compared by its translated label`() = runBlocking {
        coordinator.ensureLocalCalendarExists()

        val english = coordinator.createLocalCalendar("Local!", 0, builtInName = "Local")
        assertEquals(LocalCalendarRefusal.SIMILAR_NAME, refusalOf(english))
        assertEquals("Local", conflictOf(english))
        val german = coordinator.createLocalCalendar("Lokal!", 0, builtInName = "Lokal")
        assertEquals(LocalCalendarRefusal.SIMILAR_NAME, refusalOf(german))
        assertEquals("Lokal", conflictOf(german))
        // The built-in's file stays Local.ics; a German user's "Local" gets an id in its file name.
        assertTrue(coordinator.createLocalCalendar("Local", 0, builtInName = "Lokal").isSuccess)
    }

    @Test
    fun `names that clean to nothing are not refused as similar`() = runBlocking {
        assertTrue(coordinator.createLocalCalendar("🎂", 0, "Local").isSuccess)
        assertTrue(coordinator.createLocalCalendar("🎉", 0, "Local").isSuccess)
    }

    @Test
    fun `create allows the name of a synced calendar`() = runBlocking {
        fixture.insertCalendarUnder(AccountProvider.CALDAV, "https://dav.example.test/cal/personal/", "Personal")

        assertTrue(coordinator.createLocalCalendar("Personal", 0, "Local").isSuccess)
    }

    @Test
    fun `two concurrent creates with one name add one calendar`() = runBlocking {
        coordinator.ensureLocalCalendarExists()

        val results = listOf(
            async(Dispatchers.IO) { coordinator.createLocalCalendar("Trip", 0, "Local") },
            async(Dispatchers.IO) { coordinator.createLocalCalendar("Trip", 0, "Local") },
        ).awaitAll()

        assertEquals(1, calendarNames().count { it == "Trip" })
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, results.mapNotNull { refusalOf(it) }.single())
    }

    @Test
    fun `create refreshes widgets once and requests no sync`() = runBlocking {
        coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        coVerify(exactly = 1) { fixture.widgets.updateAllWidgets(any()) }
        verify(exactly = 0) { fixture.syncScheduler.requestExpeditedSync(any()) }
    }

    @Test
    fun `a refused create refreshes no widget`() = runBlocking {
        assertNull(coordinator.createLocalCalendar(" ", 0, "Local").getOrNull())

        coVerify(exactly = 0) { fixture.widgets.updateAllWidgets(any()) }
    }

    // ---- rename and recolor ----

    private suspend fun nameOf(id: Long) = fixture.db.calendarsDao().getById(id)!!.displayName
    private suspend fun colorOf(id: Long) = fixture.db.calendarsDao().getById(id)!!.color

    @Test
    fun `rename stores the trimmed name`() = runBlocking {
        val id = coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        assertTrue(coordinator.renameLocalCalendar(id, "  Trips ", "Local").isSuccess)
        assertEquals("Trips", nameOf(id))
    }

    @Test
    fun `rename to its own name in another case is allowed`() = runBlocking {
        val id = coordinator.createLocalCalendar("work", 0, "Local").getOrThrow()

        assertTrue(coordinator.renameLocalCalendar(id, "Work", "Local").isSuccess)
        assertEquals("Work", nameOf(id))
    }

    @Test
    fun `rename to its exact stored name succeeds even when it now matches the built-in label`() = runBlocking {
        val id = coordinator.createLocalCalendar("Local", 0, builtInName = "Lokal").getOrThrow()

        assertTrue(coordinator.renameLocalCalendar(id, "Local", builtInName = "Local").isSuccess)
        assertEquals("Local", nameOf(id))
    }

    @Test
    fun `rename refuses blank and duplicate names and keeps the stored name`() = runBlocking {
        coordinator.createLocalCalendar("Home", 0, "Local").getOrThrow()
        val id = coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        assertEquals(LocalCalendarRefusal.BLANK_NAME, refusalOf(coordinator.renameLocalCalendar(id, "  ", "Local")))
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(coordinator.renameLocalCalendar(id, "HOME", "Local")))
        assertEquals(LocalCalendarRefusal.DUPLICATE_NAME, refusalOf(coordinator.renameLocalCalendar(id, "lokal", "Lokal")))
        assertEquals("Work", nameOf(id))
    }

    @Test
    fun `rename to a variant of its own name is allowed`() = runBlocking {
        val id = coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        assertTrue(coordinator.renameLocalCalendar(id, "Work!", "Local").isSuccess)
        assertEquals("Work!", nameOf(id))
    }

    @Test
    fun `rename refuses a name whose file would match another calendar's`() = runBlocking {
        coordinator.createLocalCalendar("Home", 0, "Local").getOrThrow()
        val id = coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        val result = coordinator.renameLocalCalendar(id, "Home!", "Local")
        assertEquals(LocalCalendarRefusal.SIMILAR_NAME, refusalOf(result))
        assertEquals("Home", conflictOf(result))
        assertEquals("Work", nameOf(id))
    }

    @Test
    fun `rename allows the name of a synced calendar`() = runBlocking {
        fixture.insertCalendarUnder(AccountProvider.CALDAV, "https://dav.example.test/cal/personal/", "Personal")
        val id = coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        assertTrue(coordinator.renameLocalCalendar(id, "Personal", "Local").isSuccess)
    }

    @Test
    fun `the built-in can't be renamed and keeps its stored name`() = runBlocking {
        val builtInId = coordinator.ensureLocalCalendarExists()

        assertEquals(LocalCalendarRefusal.BUILT_IN, refusalOf(coordinator.renameLocalCalendar(builtInId, "Mine", "Local")))
        assertEquals(LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME, nameOf(builtInId))
    }

    @Test
    fun `the built-in can be recolored`() = runBlocking {
        val builtInId = coordinator.ensureLocalCalendarExists()

        assertTrue(coordinator.recolorLocalCalendar(builtInId, 0xFF112233.toInt()).isSuccess)

        val row = fixture.db.calendarsDao().getById(builtInId)!!
        assertEquals(0xFF112233.toInt(), row.color)
        assertNull(row.localColorOverride)
    }

    @Test
    fun `a user-added calendar can be recolored`() = runBlocking {
        val id = coordinator.createLocalCalendar("Work", 0, "Local").getOrThrow()

        assertTrue(coordinator.recolorLocalCalendar(id, 0xFF445566.toInt()).isSuccess)
        assertEquals(0xFF445566.toInt(), colorOf(id))
    }

    @Test
    fun `rename and recolor refuse synced, feed and contact calendars`() = runBlocking {
        coordinator.ensureLocalCalendarExists()
        val others = listOf(
            fixture.insertCalendarUnder(AccountProvider.ICLOUD, "https://caldav.example.test/123456789/calendars/home/", "Home"),
            fixture.insertCalendarUnder(AccountProvider.CALDAV, "https://dav.example.test/cal/personal/", "Personal"),
            fixture.insertCalendarUnder(AccountProvider.ICS, "https://feeds.example.test/holidays.ics", "Holidays"),
            fixture.insertCalendarUnder(AccountProvider.CONTACTS, "local://contact_birthdays", "Birthdays"),
        )

        for (id in others) {
            val name = nameOf(id)
            assertEquals(LocalCalendarRefusal.NOT_LOCAL, refusalOf(coordinator.renameLocalCalendar(id, "Renamed", "Local")))
            assertEquals(LocalCalendarRefusal.NOT_LOCAL, refusalOf(coordinator.recolorLocalCalendar(id, 0x7F010203)))
            assertEquals(name, nameOf(id))
            assertEquals(0, colorOf(id))
        }
    }

    @Test
    fun `rename and recolor of an unknown id are not found`() = runBlocking {
        assertEquals(LocalCalendarRefusal.NOT_FOUND, refusalOf(coordinator.renameLocalCalendar(9_999L, "X", "Local")))
        assertEquals(LocalCalendarRefusal.NOT_FOUND, refusalOf(coordinator.recolorLocalCalendar(9_999L, 0)))
    }

    @Test
    fun `rename and recolor refresh widgets when they succeed and not when refused`() = runBlocking {
        val builtInId = coordinator.ensureLocalCalendarExists()
        val id = fixture.db.insertOfflineCalendar("Work")

        coordinator.renameLocalCalendar(id, "Trips", "Local").getOrThrow()
        coVerify(exactly = 1) { fixture.widgets.updateAllWidgets(any()) }
        coordinator.recolorLocalCalendar(id, 0x7F010203).getOrThrow()
        coVerify(exactly = 2) { fixture.widgets.updateAllWidgets(any()) }

        coordinator.renameLocalCalendar(builtInId, "Mine", "Local")
        coordinator.recolorLocalCalendar(9_999L, 0)
        coVerify(exactly = 2) { fixture.widgets.updateAllWidgets(any()) }
        verify(exactly = 0) { fixture.syncScheduler.requestExpeditedSync(any()) }
    }
}
