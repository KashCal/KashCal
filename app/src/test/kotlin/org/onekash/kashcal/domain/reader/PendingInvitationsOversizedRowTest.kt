package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Holds a text length past the 2 MB CursorWindow under Robolectric: the window holds a text
 * column's UTF-8 bytes, and 1.5M ASCII chars still read back while 2.2M don't.
 */
internal const val OVERSIZED_ROW_CHARS = 2_500_000

/**
 * Tests the pending-invitations inbox over a stored event row too large for Android's 2 MB
 * CursorWindow. The inbox is read at launch, so a full read of such a row crash-loops the app.
 * The rows are real Room rows: Room writes a row of any size, and only reading it back fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PendingInvitationsOversizedRowTest {

    private lateinit var database: KashCalDatabase

    private val account = Account(
        id = 1L,
        provider = AccountProvider.CALDAV,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test"),
    )
    private val calendar = Calendar(
        id = 9L,
        accountId = account.id,
        caldavUrl = "https://caldav.example.test/cal9/",
        displayName = "Work",
        color = 0xFF00AA00.toInt(),
    )

    private companion object {
        val NOW_MS: Long = Instant.parse("2035-05-01T00:00:00Z").toEpochMilli()
        val BIG_START_MS: Long = Instant.parse("2035-06-02T10:00:00Z").toEpochMilli()
        val GOOD_START_MS: Long = Instant.parse("2035-06-01T10:00:00Z").toEpochMilli()
        const val HOUR_MS = 3_600_000L
    }

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context)
            .addCallback(PendingOperationTriggers.callback())
            .allowMainThreadQueries()
            .build()
        database.accountsDao().insert(account)
        database.calendarsDao().insert(calendar)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertInvitation(
        uid: String,
        title: String,
        startTs: Long,
        description: String? = null,
        extraProperties: Map<String, String>? = null,
    ): Long {
        val eventId = database.eventsDao().insert(
            Event(
                uid = uid,
                calendarId = calendar.id,
                title = title,
                location = "Room $uid",
                startTs = startTs,
                endTs = startTs + HOUR_MS,
                dtstamp = 0L,
                syncStatus = SyncStatus.SYNCED,
                organizerEmail = "mailto:organizer@example.test",
                organizerName = "Org",
                caldavUrl = "${calendar.caldavUrl}$uid.ics",
                description = description,
                extraProperties = extraProperties,
            )
        )
        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendar.id,
                startTs = startTs,
                endTs = startTs + HOUR_MS,
                startDay = 20350601,
                endDay = 20350601,
                isCancelled = false,
            )
        )
        database.attendeesDao().insertAll(
            listOf(
                Attendee(
                    eventId = eventId,
                    address = "mailto:self@example.test",
                    partstat = "NEEDS-ACTION",
                )
            )
        )
        return eventId
    }

    private suspend fun assertInboxReads(bigId: Long, goodId: Long) {
        val pending = EventReader(database).getPendingInvitations(now = NOW_MS).first()

        assertEquals(listOf(goodId, bigId), pending.map { it.eventId })
        val big = pending.single { it.eventId == bigId }
        assertEquals("Big invite", big.title)
        assertEquals("Room big", big.location)
        assertEquals(false, big.isAllDay)
        assertEquals(BIG_START_MS, big.occurrenceStartTs)
        assertEquals(BIG_START_MS + HOUR_MS, big.occurrenceEndTs)
        assertEquals(calendar.color, big.calendarColor)
        assertEquals("Org", big.organizerLabel)
        assertEquals(account.id, big.accountId)
    }

    @Test
    fun `inbox reads an invitation whose description is over the cursor window`() = runTest {
        val bigId = insertInvitation(
            "big", "Big invite", BIG_START_MS, description = "D".repeat(OVERSIZED_ROW_CHARS)
        )
        val goodId = insertInvitation("good", "Good invite", GOOD_START_MS)

        assertInboxReads(bigId, goodId)
    }

    @Test
    fun `inbox reads an invitation whose unknown properties are over the cursor window`() =
        runTest {
            val bigId = insertInvitation(
                "big", "Big invite", BIG_START_MS,
                extraProperties = mapOf("X-ALT-DESC" to "X".repeat(OVERSIZED_ROW_CHARS))
            )
            val goodId = insertInvitation("good", "Good invite", GOOD_START_MS)

            assertInboxReads(bigId, goodId)
        }
}
