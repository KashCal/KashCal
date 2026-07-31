package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.notification.InviteNotifier
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the sync pull path seeds the tag metadata table: when an event
 * arrives from the server carrying a category the app has never seen, a
 * `categories` row appears so the tag shows up in suggestions and the
 * management screen. Uses a real in-memory Room DB (so the seed actually
 * persists) with a mocked CalDAV client and peripheral collaborators.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyCategorySeedTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private val client: CalDavClient = mockk()
    private val calendarRepository: CalendarRepository = mockk(relaxed = true)
    private val dataStore: KashCalDataStore = TestDataStoreFactory.createDefault()
    private val inviteNotifier: InviteNotifier = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val reminderScheduler: ReminderScheduler = mockk(relaxed = true)

    private val account = Account(
        id = 1L,
        provider = AccountProvider.ICLOUD,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // The events table foreign-keys account and calendar rows; seed both so
        // the pulled event's upsert isn't silently rejected by the constraint.
        database.accountsDao().insert(account)
        database.calendarsDao().insert(calendar())
        val occurrenceGenerator = OccurrenceGenerator(
            database, database.occurrencesDao(), database.eventsDao(), dataStore
        )
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = ICloudQuirks(),
            dataStore = dataStore,
            inviteNotifier = inviteNotifier,
            accountRepository = accountRepository,
            reminderScheduler = reminderScheduler
        )
        coEvery { accountRepository.getAccountById(account.id) } returns account
    }

    @After
    fun tearDown() = database.close()

    private fun calendar() = Calendar(
        id = 9L,
        accountId = account.id,
        caldavUrl = "https://caldav.example.test/cal9/",
        displayName = "Work",
        color = 0xFF0000,
        ctag = null,
        syncToken = null
    )

    private fun icalWithCategories(uid: String, categories: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260501T120000Z
        DTSTART:20350601T100000Z
        DTEND:20350601T110000Z
        SUMMARY:Quarterly review
        CATEGORIES:$categories
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    /** Prime the client mock to deliver [ical] as a single fresh event on full sync. */
    private fun primeFullSync(calendar: Calendar, ical: String) {
        val href = "evt.ics"
        val url = "${calendar.caldavUrl}$href"
        val serverEvents = listOf(CalDavEvent(href, url, "etag-1", ical))
        coEvery { client.getCtag(calendar.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchEtagsInRange(calendar.caldavUrl, any(), any()) } returns
            CalDavResult.success(serverEvents.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(calendar.caldavUrl, any()) } returns
            CalDavResult.success(serverEvents)
        coEvery { client.getSyncToken(calendar.caldavUrl) } returns CalDavResult.success("new-token")
    }

    @Test
    fun `pulling an event with an unknown tag seeds a colorless category row`() = runTest {
        val cal = calendar()
        primeFullSync(cal, icalWithCategories("uid-new", "Conference"))

        pullStrategy.pull(cal, client = client)

        val seeded = database.categoryDao().getByName("Conference")
        assertNotNull("an unknown pulled tag becomes a suggestion", seeded)
        assertNull("seeded tags render via the hash color until recolored", seeded!!.color)
    }

    @Test
    fun `pulling an event dates the tag's recency to the event, not wall-clock now`() = runTest {
        val cal = calendar()
        // The fixture's DTSTART is 2035 — far from any plausible test wall-clock.
        primeFullSync(cal, icalWithCategories("uid-new", "Conference"))

        pullStrategy.pull(cal, client = client)

        // 2035-06-01T10:00:00Z in epoch millis. A wall-clock `now` seed would be
        // ~2025-2026 instead, so this pins recency to the event itself.
        val expected = 2064304800000L
        assertEquals(
            "recency reflects the event's own time, not the moment of pull",
            expected,
            database.categoryDao().getByName("Conference")!!.lastUsedAt
        )
    }

    @Test
    fun `pulling an older event never rolls back a newer local recency`() = runTest {
        // A locally very-recent use of the tag.
        database.categoryDao().touch("Work", 9_000_000_000_000L)
        val cal = calendar()
        // Server event's DTSTART (2035) is older than the local recency above.
        primeFullSync(cal, icalWithCategories("uid-known", "Work"))

        pullStrategy.pull(cal, client = client)

        assertEquals(
            "a raise-only seed keeps the newer local recency",
            9_000_000_000_000L,
            database.categoryDao().getByName("Work")!!.lastUsedAt
        )
    }

    @Test
    fun `pulling an event does not clobber an existing tag's custom color`() = runTest {
        database.categoryDao().setColor("Work", 0xFF4457C9.toInt(), now = 1L)
        val cal = calendar()
        primeFullSync(cal, icalWithCategories("uid-known", "Work"))

        pullStrategy.pull(cal, client = client)

        assertEquals(
            "a recolored tag survives being seen again on the server",
            0xFF4457C9.toInt(),
            database.categoryDao().getByName("Work")!!.color
        )
    }
}
