package org.onekash.kashcal.sync.strategy

import android.content.Context
import org.onekash.kashcal.data.db.EventIcsStore
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.PendingOperation
import org.onekash.kashcal.sync.notification.InviteNotifier
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.model.CalDavEvent
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.client.model.CalendarMetadataProbe
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What a pull does with an imported invite (#364): it posts no "invited" notification, the user
 * imported it themselves, and while the user's answer is still queued it leaves the reply
 * receipt empty, since the stored one describes the previous answer. An ordinary invite keeps
 * today's behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PullStrategyImportedInviteTest {

    private lateinit var database: KashCalDatabase
    private lateinit var pullStrategy: PullStrategy
    private val client: CalDavClient = mockk()
    private val accountRepository: AccountRepository = mockk()
    private val inviteNotifier: InviteNotifier = mockk(relaxed = true)

    private val account = Account(id = 1L, provider = AccountProvider.CALDAV, email = "self@example.test")
    private val calendarId = 9L
    private val calendarUrl = "https://caldav.example.test/cal9/"
    private var pullCount = 0

    private val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val hour = 3_600_000L
    private val dayMs = 24 * hour
    private val seriesStart = ((System.currentTimeMillis() / dayMs) + 21) * dayMs + 15 * hour
    private val slot = seriesStart + 7 * dayMs

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        database.accountsDao().insert(account)
        database.calendarsDao().insert(
            Calendar(id = calendarId, accountId = account.id, caldavUrl = calendarUrl, displayName = "Work", color = 0)
        )
        val dataStore = TestDataStoreFactory.createDefault()
        pullStrategy = PullStrategy(
            database = database,
            calendarRepository = CalendarRepositoryImpl(database.calendarsDao()),
            eventsDao = database.eventsDao(),
            attendeesDao = database.attendeesDao(),
            occurrenceGenerator = OccurrenceGenerator(
                database, database.occurrencesDao(), database.eventsDao(), dataStore
            ),
            defaultQuirks = ICloudQuirks(),
            dataStore = dataStore,
            inviteNotifier = inviteNotifier,
            accountRepository = accountRepository,
            reminderScheduler = mockk(relaxed = true),
            icsSource = EventIcsStore(database),
        )
        coEvery { accountRepository.getAccountById(account.id) } returns account
    }

    @After
    fun tearDown() = database.close()

    private fun utc(ms: Long) = utcFormat.format(Instant.ofEpochMilli(ms))

    private fun body(title: String, receipt: String? = null) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:series-uid\r\nDTSTAMP:2026010${pullCount + 1}T000000Z\r\n" +
            "DTSTART:${utc(seriesStart)}\r\nDTEND:${utc(seriesStart + hour)}\r\nSUMMARY:$title\r\n" +
            "ORGANIZER${receipt?.let { ";SCHEDULE-STATUS=$it" } ?: ""}:mailto:boss@example.test\r\n" +
            "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:self@example.test\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"

    private suspend fun pullServing(body: String) {
        pullCount++
        val href = "/cal9/series.ics"
        val events = listOf(CalDavEvent(href, "https://caldav.example.test$href", "etag-$pullCount", body))
        coEvery { client.getCtag(calendarUrl) } returns CalDavResult.success(
            CalendarMetadataProbe(ctag = "ctag-$pullCount", displayName = null, color = null, isReadOnly = null)
        )
        coEvery { client.getSyncToken(calendarUrl) } returns CalDavResult.error(501, "Not supported")
        coEvery { client.fetchAllEtags(calendarUrl) } returns CalDavResult.success(events.map { it.href to it.etag })
        coEvery { client.fetchEtagsInRange(calendarUrl, any(), any()) } returns
            CalDavResult.success(events.map { it.href to it.etag })
        coEvery { client.fetchEventsByHref(calendarUrl, any()) } returns CalDavResult.success(events)
        val calendar = database.calendarsDao().getById(calendarId)!!
        val result = pullStrategy.pull(calendar, client = client)
        assertTrue("pull $pullCount must succeed, got $result", result is PullResult.Success)
    }

    private fun markImported() {
        TestDatabases.raw(database).execSQL(
            "UPDATE events SET import_source = '${Event.IMPORT_SOURCE_ICS_FILE}' WHERE uid = 'series-uid'"
        )
    }

    private suspend fun row() = database.eventsDao().findAllByUid("series-uid", calendarId).single()

    private suspend fun queueAnswer(eventId: Long) {
        database.pendingOperationsDao().insert(
            PendingOperation(
                eventId = eventId,
                operation = PendingOperation.OPERATION_UPDATE,
                partstatOnly = true,
                partstatTarget = "ACCEPTED"
            )
        )
    }

    @Test
    fun `an imported invite's pull leaves the receipt empty while an answer is queued`() = runTest {
        pullServing(body("Sync"))
        markImported()
        queueAnswer(row().id)

        pullServing(body("Sync renamed", receipt = "1.1"))

        assertEquals("Sync renamed", row().title)
        assertEquals(null, row().organizerScheduleStatus)
    }

    @Test
    fun `an imported invite's pull stores the receipt when no answer is queued`() = runTest {
        pullServing(body("Sync"))
        markImported()

        pullServing(body("Sync renamed", receipt = "1.1"))

        assertEquals("1.1", row().organizerScheduleStatus)
    }

    @Test
    fun `an ordinary invite keeps today's receipt even with an answer queued`() = runTest {
        pullServing(body("Sync"))
        queueAnswer(row().id)

        pullServing(body("Sync renamed", receipt = "1.1"))

        assertEquals("1.1", row().organizerScheduleStatus)
    }

    @Test
    fun `a pull posts no invite notification for an imported invite`() = runTest {
        pullServing(body("Sync"))
        markImported()

        pullServing(body("Sync renamed"))

        coVerify(exactly = 1) { inviteNotifier.notifyNew(any(), any()) }
        assertTrue(row().importSource == Event.IMPORT_SOURCE_ICS_FILE)
    }

    @Test
    fun `a pending attendee-side cancel holds the series against the pull`() = runTest {
        pullServing(body("Sync"))
        database.pendingOperationsDao().insert(
            PendingOperation(eventId = row().id, operation = PendingOperation.OPERATION_ATTENDEE_CANCEL)
        )

        pullServing(body("Sync renamed"))
        assertEquals("held while the cancel is pending", "Sync", row().title)

        database.pendingOperationsDao().deleteForEvent(row().id)
        pullServing(body("Sync renamed again"))
        assertEquals("released once it is gone", "Sync renamed again", row().title)
    }
}
