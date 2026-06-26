package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AttendeesDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var attendeesDao: AttendeesDao
    private var eventId1: Long = 0
    private var eventId2: Long = 0
    private var eventId3: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        attendeesDao = database.attendeesDao()

        val accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        val calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF0000FF.toInt()
            )
        )
        eventId1 = database.eventsDao().insert(makeEvent(calendarId, "evt1"))
        eventId2 = database.eventsDao().insert(makeEvent(calendarId, "evt2"))
        eventId3 = database.eventsDao().insert(makeEvent(calendarId, "evt3"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun makeEvent(calendarId: Long, uid: String): Event = Event(
        uid = uid,
        calendarId = calendarId,
        title = "Test",
        startTs = 1_000L,
        endTs = 2_000L,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED
    )

    private fun makeAttendee(
        eventId: Long,
        address: String,
        sortOrder: Int = 0,
        partstat: String? = null
    ): Attendee = Attendee(
        eventId = eventId,
        address = address,
        partstat = partstat,
        sortOrder = sortOrder
    )

    @Test
    fun `replaceForEvent inserts attendees on empty event`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:alice@x.com", 0),
                makeAttendee(eventId1, "mailto:bob@x.com", 1)
            )
        )
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(2, rows.size)
        assertEquals("mailto:alice@x.com", rows[0].address)
        assertEquals("mailto:bob@x.com", rows[1].address)
    }

    @Test
    fun `replaceForEvent overwrites existing attendees`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com", 0),
                makeAttendee(eventId1, "mailto:b@x.com", 1)
            )
        )
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:c@x.com", 0),
                makeAttendee(eventId1, "mailto:d@x.com", 1),
                makeAttendee(eventId1, "mailto:e@x.com", 2)
            )
        )
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(listOf("mailto:c@x.com", "mailto:d@x.com", "mailto:e@x.com"), rows.map { it.address })
    }

    @Test
    fun `replaceForEvent with empty list deletes all attendees for that event`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com"))
        )
        attendeesDao.replaceForEvent(eventId1, emptyList())
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(0, rows.size)
    }

    @Test
    fun `replaceForEvent does not affect other events' attendees`() = runTest {
        attendeesDao.replaceForEvent(eventId1, listOf(makeAttendee(eventId1, "mailto:a1@x.com")))
        attendeesDao.replaceForEvent(eventId2, listOf(makeAttendee(eventId2, "mailto:a2@x.com")))
        attendeesDao.replaceForEvent(eventId1, emptyList())

        assertEquals(0, attendeesDao.getForEvent(eventId1).first().size)
        assertEquals(1, attendeesDao.getForEvent(eventId2).first().size)
    }

    @Test
    fun `getForEvents bulk query returns all matching rows ordered`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com", 0),
                makeAttendee(eventId1, "mailto:b@x.com", 1)
            )
        )
        attendeesDao.replaceForEvent(eventId2, emptyList())
        attendeesDao.replaceForEvent(
            eventId3,
            listOf(
                makeAttendee(eventId3, "mailto:c@x.com", 0),
                makeAttendee(eventId3, "mailto:d@x.com", 1),
                makeAttendee(eventId3, "mailto:e@x.com", 2),
                makeAttendee(eventId3, "mailto:f@x.com", 3)
            )
        )

        val rows = attendeesDao.getForEvents(listOf(eventId1, eventId2, eventId3)).first()
        assertEquals(6, rows.size)
        // Ordered by event_id, then sort_order
        assertEquals(eventId1, rows[0].eventId)
        assertEquals(eventId1, rows[1].eventId)
        assertEquals(eventId3, rows[2].eventId)
        assertEquals(eventId3, rows[5].eventId)
        assertEquals("mailto:c@x.com", rows[2].address)
    }

    @Test
    fun `event delete cascades to attendees (FK CASCADE)`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com"),
                makeAttendee(eventId1, "mailto:b@x.com")
            )
        )
        // Delete the parent event — FK CASCADE should remove the attendees.
        database.eventsDao().deleteById(eventId1)

        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(0, rows.size)
    }

    @Test
    fun `replaceForEvent preserves wire order via sort_order`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:zeta@x.com", 0),
                makeAttendee(eventId1, "mailto:alpha@x.com", 1),
                makeAttendee(eventId1, "mailto:mid@x.com", 2)
            )
        )
        val rows = attendeesDao.getForEvent(eventId1).first()
        assertEquals(
            listOf("mailto:zeta@x.com", "mailto:alpha@x.com", "mailto:mid@x.com"),
            rows.map { it.address }
        )
        assertTrue(rows.zipWithNext().all { (a, b) -> a.sortOrder < b.sortOrder })
    }

    // ========== notified_at merge-preserve (race fix) ==========

    @Test
    fun `replaceForEvent preserves notified_at when prior row was non-NEEDS-ACTION`() = runTest {
        // Initial state: self responded ACCEPTED, notification fired earlier.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:self@x.com", 0, partstat = "ACCEPTED")
                    .copy(notifiedAt = 1_000_000L),
                makeAttendee(eventId1, "mailto:alice@x.com", 1, partstat = "ACCEPTED")
            )
        )

        // Server replays the event (its REPLY queue hasn't fired yet) →
        // returns NEEDS-ACTION for self. The new row's notifiedAt is null.
        // Without merge-preserve, this would re-fire the notification.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:self@x.com", 0, partstat = "NEEDS-ACTION"),
                makeAttendee(eventId1, "mailto:alice@x.com", 1, partstat = "ACCEPTED")
            )
        )

        val rows = attendeesDao.getForEvent(eventId1).first()
        val self = rows.first { it.address == "mailto:self@x.com" }
        assertEquals(
            "notified_at must survive when prior PARTSTAT was non-NEEDS-ACTION",
            1_000_000L,
            self.notifiedAt
        )
        // PARTSTAT itself reflects the new server state.
        assertEquals("NEEDS-ACTION", self.partstat)
    }

    @Test
    fun `replaceForEvent always preserves notified_at by canonical address`() = runTest {
        // The merge semantic: any prior row with the same canonical address
        // donates its notified_at, regardless of PARTSTAT. This is broader
        // than the original race-fix spec but safer — the notification
        // only re-fires when a prior row didn't exist (truly new attendee
        // on the event). A re-invite of the same address won't re-notify;
        // that's an acceptable edge-case tradeoff.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:self@x.com", 0, partstat = "NEEDS-ACTION")
                    .copy(notifiedAt = 1_000_000L)
            )
        )

        // Routine pull, still NEEDS-ACTION → preserve.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:self@x.com", 0, partstat = "NEEDS-ACTION")
            )
        )

        val rows = attendeesDao.getForEvent(eventId1).first()
        val self = rows.first { it.address == "mailto:self@x.com" }
        assertEquals(1_000_000L, self.notifiedAt)
    }

    @Test
    fun `replaceForEvent does not carry notified_at across address change`() = runTest {
        // notifiedAt belongs to (eventId, canonical address). A different
        // attendee replacing the row should NOT inherit the prior's notified_at.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:alice@x.com", 0)
                    .copy(notifiedAt = 1_000_000L)
            )
        )
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:bob@x.com", 0)
            )
        )
        val rows = attendeesDao.getForEvent(eventId1).first()
        val bob = rows.first { it.address == "mailto:bob@x.com" }
        assertEquals(null, bob.notifiedAt)
    }

    @Test
    fun `markNotified sets notified_at on a single row`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:self@x.com", 0, partstat = "NEEDS-ACTION"))
        )
        val before = attendeesDao.getForEventOnce(eventId1).first()
        attendeesDao.markNotified(before.id, 1_234_000L)
        val after = attendeesDao.getForEventOnce(eventId1).first()
        assertEquals(1_234_000L, after.notifiedAt)
    }

    // ========== schedule_status / schedule_agent merge-preserve ==========
    // A server stamps SCHEDULE-STATUS on the stored ATTENDEE (RFC 6638 §7.3),
    // but the client never echoes it on a subsequent PUT. A cosmetic re-push
    // whose read-back races an async-stamping server can return the attendee
    // with no status; without merge-preserve, replaceForEvent would wipe the
    // captured receipt. RFC 6638 §7.3: a client SHOULD NOT remove a
    // server-provided parameter — null incoming preserves; non-null overwrites.

    @Test
    fun `replaceForEvent preserves prior schedule_status when incoming is null`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(scheduleStatus = "5.0"))
        )
        // Re-push read-back: same attendee, server returned no SCHEDULE-STATUS.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(scheduleStatus = null))
        )
        val row = attendeesDao.getForEventOnce(eventId1).first()
        assertEquals("5.0", row.scheduleStatus)
    }

    @Test
    fun `replaceForEvent preserves prior schedule_agent when incoming is null`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(scheduleAgent = "CLIENT"))
        )
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(scheduleAgent = null))
        )
        val row = attendeesDao.getForEventOnce(eventId1).first()
        assertEquals("CLIENT", row.scheduleAgent)
    }

    @Test
    fun `replaceForEvent lets a non-null incoming schedule_status overwrite the prior`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(scheduleStatus = "1.0"))
        )
        // Server later reports successful delivery — authoritative, overwrites.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(scheduleStatus = "2.0"))
        )
        val row = attendeesDao.getForEventOnce(eventId1).first()
        assertEquals("2.0", row.scheduleStatus)
    }

    @Test
    fun `schedule_status preserve is keyed by canonical address`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(scheduleStatus = "5.0"))
        )
        // A different attendee with no status must not inherit a@x.com's receipt.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com", 0).copy(scheduleStatus = null),
                makeAttendee(eventId1, "mailto:b@x.com", 1).copy(scheduleStatus = null)
            )
        )
        val rows = attendeesDao.getForEventOnce(eventId1).associateBy { it.address }
        assertEquals("5.0", rows["mailto:a@x.com"]?.scheduleStatus)
        assertEquals(null, rows["mailto:b@x.com"]?.scheduleStatus)
    }

    // ========== itip_request_sequence / itip_request_status merge-preserve ==========
    // The client-outbox idempotency marker (itip_request_sequence) records the
    // SEQUENCE at which a METHOD:REQUEST was sent to an attendee. The read-back
    // calls replaceForEvent with server-parsed rows that carry no iTIP marker
    // (the server never echoes it), so without merge-preserve every read-back
    // cycle would wipe the marker -> the attendee re-classifies ClientMustDeliver
    // -> a duplicate invite is POSTed every sync. The marker MUST survive the
    // server-authoritative replace, exactly like notified_at.

    @Test
    fun `replaceForEvent preserves prior itip_request_sequence when incoming is null`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(itipRequestSequence = 2))
        )
        // Read-back after a later (cosmetic) push: server-parsed row has no marker.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(itipRequestSequence = null))
        )
        val row = attendeesDao.getForEventOnce(eventId1).first()
        assertEquals(2, row.itipRequestSequence)
    }

    @Test
    fun `replaceForEvent preserves prior itip_request_status when incoming is null`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(itipRequestStatus = "2.0;Success"))
        )
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(itipRequestStatus = null))
        )
        val row = attendeesDao.getForEventOnce(eventId1).first()
        assertEquals("2.0;Success", row.itipRequestStatus)
    }

    @Test
    fun `replaceForEvent lets a non-null incoming itip marker overwrite the prior`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com")
                    .copy(itipRequestSequence = 1, itipRequestStatus = "2.0;Success")
            )
        )
        // A genuinely new send at a higher SEQUENCE writes the marker again.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com")
                    .copy(itipRequestSequence = 3, itipRequestStatus = "3.7;Invalid calendar user")
            )
        )
        val row = attendeesDao.getForEventOnce(eventId1).first()
        assertEquals(3, row.itipRequestSequence)
        assertEquals("3.7;Invalid calendar user", row.itipRequestStatus)
    }

    @Test
    fun `itip marker preserve is keyed by canonical address`() = runTest {
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(makeAttendee(eventId1, "mailto:a@x.com").copy(itipRequestSequence = 2))
        )
        // A different (late-added) attendee must NOT inherit a@x.com's marker —
        // it must stay null so it gets its own first invite.
        attendeesDao.replaceForEvent(
            eventId1,
            listOf(
                makeAttendee(eventId1, "mailto:a@x.com", 0).copy(itipRequestSequence = null),
                makeAttendee(eventId1, "mailto:b@x.com", 1).copy(itipRequestSequence = null)
            )
        )
        val rows = attendeesDao.getForEventOnce(eventId1).associateBy { it.address }
        assertEquals(2, rows["mailto:a@x.com"]?.itipRequestSequence)
        assertEquals(null, rows["mailto:b@x.com"]?.itipRequestSequence)
    }
}
