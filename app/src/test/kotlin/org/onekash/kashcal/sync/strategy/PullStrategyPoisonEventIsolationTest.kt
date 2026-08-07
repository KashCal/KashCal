package org.onekash.kashcal.sync.strategy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
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
import org.onekash.kashcal.sync.client.model.SyncItem
import org.onekash.kashcal.sync.client.model.SyncItemStatus
import org.onekash.kashcal.sync.client.model.SyncReport
import org.onekash.kashcal.sync.notification.InviteNotifier
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fault-isolation contract for the pull processing loop: one event whose
 * post-map write path throws (occurrence generation blows up on a shape no
 * fixture anticipated) must NOT abort the whole calendar's sync. The good
 * events in the same batch must still land, and the failure must route through
 * the same parse-error accounting that holds the sync token for a retry —
 * exactly as a malformed-ICS parse failure already does upstream.
 *
 * The throw is injected via a spy on OccurrenceGenerator rather than a crafted
 * ICS: the committed property/corpus tests already push toEntity + the real
 * generator hard to lower the *probability* of a throw; this test bounds the
 * *blast radius* when one slips through anyway, so it must simulate the throw
 * deterministically regardless of which internal line would produce it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyPoisonEventIsolationTest {

    private lateinit var database: KashCalDatabase
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private lateinit var eventsDao: org.onekash.kashcal.data.db.dao.EventsDao
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

    private companion object {
        const val POISON_UID = "uid-poison"
        const val GOOD_UID = "uid-good"
        const val GOOD_ORPHAN_UID = "uid-good-orphan"
    }

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.accountsDao().insert(account)
        database.calendarsDao().insert(calendar())

        // Spy the real generator so the good event persists normally, but the
        // poison event's occurrence generation throws — simulating a mapper/
        // generator failure on one event mid-batch.
        occurrenceGenerator = spyk(
            OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), dataStore)
        )
        coEvery {
            occurrenceGenerator.regenerateOccurrences(match { it.uid == POISON_UID })
        } throws RuntimeException("simulated occurrence-generation failure")

        // Spy the DAO so individual tests can make a single write throw. The
        // default is callOriginal(), so tests that don't stub it behave exactly
        // like the real in-memory DAO.
        eventsDao = spyk(database.eventsDao())

        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
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
    fun tearDown() {
        unmockkObject(ICalEventMapper)
        database.close()
    }

    private fun calendar(ctag: String? = null, syncToken: String? = null) = Calendar(
        id = 9L,
        accountId = account.id,
        caldavUrl = "https://caldav.example.test/cal9/",
        displayName = "Work",
        color = 0xFF0000,
        ctag = ctag,
        syncToken = syncToken
    )

    private fun ical(uid: String, summary: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260501T120000Z
        DTSTART:20350601T100000Z
        DTEND:20350601T110000Z
        SUMMARY:$summary
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    /**
     * An orphan exception: a VEVENT carrying RECURRENCE-ID whose master is
     * absent from this batch and from Room. The pull path synthesizes a
     * placeholder master so the exception's FK has a target.
     */
    private fun orphanException(uid: String): String = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//Test//EN
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260501T120000Z
        RECURRENCE-ID:20350601T100000Z
        DTSTART:20350601T140000Z
        DTEND:20350601T150000Z
        SUMMARY:Orphan override
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    /**
     * Two events in one resource-fetch batch, poison FIRST so that under the
     * pre-fix code its throw aborts the batch before the good event is reached.
     */
    private fun poisonThenGood(): List<CalDavEvent> = listOf(
        CalDavEvent("poison.ics", "${calendar().caldavUrl}poison.ics", "etag-poison", ical(POISON_UID, "Poison")),
        CalDavEvent("good.ics", "${calendar().caldavUrl}good.ics", "etag-good", ical(GOOD_UID, "Good")),
    )

    @Test
    fun `full sync survives one event whose occurrence generation throws`() = runTest {
        val cal = calendar(ctag = null, syncToken = null)
        val events = poisonThenGood()
        coEvery { client.getCtag(cal.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchEtagsInRange(cal.caldavUrl, any(), any()) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(cal.caldavUrl, any()) } returns CalDavResult.success(events)
        coEvery { client.getSyncToken(cal.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(cal, client = client)

        // The whole pull must NOT collapse to an error because of one bad event.
        assertTrue("pull must succeed despite one poison event, was $result", result is PullResult.Success)
        assertEquals("only the good event should count as added", 1, (result as PullResult.Success).eventsAdded)

        // The good event must have landed and generated its occurrence.
        val good = database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, cal.id)
        assertNotNull("good event must survive the poison event in the same batch", good)
        assertTrue("good event's occurrence must be generated",
            database.occurrencesDao().getForEvent(good!!.id).isNotEmpty())

        // The poison event must NOT be left half-written (transaction rolled back).
        assertNull("poison event must not be persisted", database.eventsDao().getMasterByUidAndCalendar(POISON_UID, cal.id))
    }

    @Test
    fun `full sync survives one event whose map step throws before the transaction`() = runTest {
        // The map step (ICalEventMapper.toEntity) runs BEFORE the upsert
        // transaction. A parseable-but-hostile event can make it throw a shape
        // no fixture anticipated; that throw must be isolated to the one event,
        // not abort the whole calendar's pull. This is the pre-transaction
        // sibling of the occurrence-generation poison test above.
        mockkObject(ICalEventMapper)
        every {
            ICalEventMapper.toEntity(match { it.uid == POISON_UID }, any(), any(), any(), any(), any())
        } throws RuntimeException("simulated map-step failure")
        every {
            ICalEventMapper.toEntity(match { it.uid != POISON_UID }, any(), any(), any(), any(), any())
        } answers { callOriginal() }

        val cal = calendar(ctag = null, syncToken = null)
        val events = poisonThenGood()
        coEvery { client.getCtag(cal.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchEtagsInRange(cal.caldavUrl, any(), any()) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(cal.caldavUrl, any()) } returns CalDavResult.success(events)
        coEvery { client.getSyncToken(cal.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(cal, client = client)

        assertTrue("pull must succeed despite one map-step failure, was $result", result is PullResult.Success)
        assertEquals("only the good event should count as added", 1, (result as PullResult.Success).eventsAdded)

        val good = database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, cal.id)
        assertNotNull("good event must survive the map-step failure in the same batch", good)
        assertTrue("good event's occurrence must be generated",
            database.occurrencesDao().getForEvent(good!!.id).isNotEmpty())

        assertNull("poison event must not be persisted", database.eventsDao().getMasterByUidAndCalendar(POISON_UID, cal.id))
    }

    @Test
    fun `incremental sync holds the sync token when an event fails to process`() = runTest {
        val cal = calendar(ctag = "old-ctag", syncToken = "token-A")
        val events = poisonThenGood()
        coEvery { client.getCtag(cal.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.syncCollection(cal.caldavUrl, "token-A") } returns CalDavResult.success(
            SyncReport(
                syncToken = "token-B",
                changed = events.map { SyncItem(it.href, it.etag, SyncItemStatus.OK) },
                deleted = emptyList()
            )
        )
        coEvery { client.fetchEventsByHref(cal.caldavUrl, any()) } returns CalDavResult.success(events)

        val sessionBuilder = SyncSessionBuilder(
            calendarId = cal.id,
            calendarName = cal.displayName,
            syncType = SyncType.INCREMENTAL,
            triggerSource = SyncTrigger.FOREGROUND_MANUAL
        )
        val result = pullStrategy.pull(cal, client = client, sessionBuilder = sessionBuilder)

        assertTrue("pull must succeed despite one poison event, was $result", result is PullResult.Success)
        val success = result as PullResult.Success
        // Token is HELD (not advanced to token-B) so the failed event is re-fetched
        // next cycle — the same recovery a malformed-ICS parse failure already gets.
        assertEquals("sync token must be held for retry, not advanced", "token-A", success.newSyncToken)
        assertTrue("the processing failure must be counted as a parse/skip error",
            sessionBuilder.getSkippedParseError() > 0)

        // Good event still lands even though the token is held.
        assertNotNull("good event must survive", database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, cal.id))
    }

    /**
     * Fault isolation must extend to the orphan-exception synthetic-master
     * write, not just the map/upsert transaction. When an orphan exception has
     * no master in the batch or Room, the exception pass synthesizes a
     * placeholder master and inserts it. If that insert throws (a DB-layer
     * failure on one row), the pull must still isolate the failure to that one
     * event — the good event in the same batch must land and the whole pull
     * must NOT collapse to an error.
     */
    @Test
    fun `full sync survives an orphan exception whose synthetic-master insert throws`() = runTest {
        val cal = calendar(ctag = null, syncToken = null)
        // processEvents partitions by RECURRENCE-ID, not list order: masters
        // (GOOD_UID) commit in pass 2 before the exception pass (pass 3) runs at
        // all, so a master survives the pass-3 throw regardless of ordering. The
        // discriminating case is a SECOND orphan exception (GOOD_ORPHAN_UID)
        // queued AFTER the poison one in the same exception pass: if per-exception
        // isolation regresses, the poison's throw would abort the remaining
        // exceptions and this second orphan would silently vanish.
        val events = listOf(
            CalDavEvent(
                "orphan-poison.ics", "${cal.caldavUrl}orphan-poison.ics", "etag-orphan-poison",
                orphanException(POISON_UID)
            ),
            CalDavEvent(
                "orphan-good.ics", "${cal.caldavUrl}orphan-good.ics", "etag-orphan-good",
                orphanException(GOOD_ORPHAN_UID)
            ),
            CalDavEvent(
                "good.ics", "${cal.caldavUrl}good.ics", "etag-good",
                ical(GOOD_UID, "Good")
            ),
        )

        // Make ONLY the poison orphan's synthetic-master insert throw. The match
        // is scoped to POISON_UID so the second orphan's synthetic insert runs
        // the real DAO and must land — proving the throw was isolated to the one
        // failing exception, not the whole exception pass.
        coEvery {
            eventsDao.insert(match {
                it.uid == POISON_UID &&
                    it.extraProperties?.get(PULL_SYNTHETIC_MASTER_EXTRA_KEY) == "true"
            })
        } throws RuntimeException("simulated synthetic-master insert failure")

        coEvery { client.getCtag(cal.caldavUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "new-ctag", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.fetchAllEtags(any()) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchEtagsInRange(cal.caldavUrl, any(), any()) } returns
            CalDavResult.success(events.map { Pair(it.href, it.etag) })
        coEvery { client.fetchEventsByHref(cal.caldavUrl, any()) } returns CalDavResult.success(events)
        coEvery { client.getSyncToken(cal.caldavUrl) } returns CalDavResult.success("new-token")

        val result = pullStrategy.pull(cal, client = client)

        assertTrue(
            "pull must succeed despite one orphan-exception synthetic-master insert failure, was $result",
            result is PullResult.Success
        )

        // The good master must have landed (it commits in pass 2, before the
        // pass-3 throw — a baseline sanity check, not the discriminating one).
        val good = database.eventsDao().getMasterByUidAndCalendar(GOOD_UID, cal.id)
        assertNotNull("good event must survive the synthetic-master insert failure", good)
        assertTrue(
            "good event's occurrence must be generated",
            database.occurrencesDao().getForEvent(good!!.id).isNotEmpty()
        )

        // The discriminating assertion: the SECOND orphan exception — queued
        // after the poison one in the same exception pass — must still be
        // promoted via its own synthetic master. If per-exception isolation
        // regresses, the poison throw aborts the rest of the pass and this row
        // never appears.
        val secondOrphanMaster = database.eventsDao().getMasterByUidAndCalendar(GOOD_ORPHAN_UID, cal.id)
        assertNotNull(
            "second orphan exception must survive the poison orphan's failure in the same pass",
            secondOrphanMaster
        )

        // The poison orphan itself must NOT have been persisted.
        assertNull(
            "poison orphan's master must not be persisted",
            database.eventsDao().getMasterByUidAndCalendar(POISON_UID, cal.id)
        )
    }
}
