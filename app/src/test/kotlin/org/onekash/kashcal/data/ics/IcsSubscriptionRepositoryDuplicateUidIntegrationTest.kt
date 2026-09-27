package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the master-uniqueness trigger against the duplicate-UID and synthetic-master handling of
 * #227, in an in-memory Room database.
 *
 * - [KashCalDatabase.testCallback] installs the trigger.
 * - Two masters renamed to distinct `#dup=` UIDs both persist.
 * - Two masters with the same UID in the same calendar, both with `original_event_id IS NULL`,
 *   still abort, so the trigger isn't relaxed.
 * - A synthetic master and its linked exceptions persist.
 *
 * Mocked unit tests can't run the SQL trigger. The setup follows `ConstraintDiagnosticTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsSubscriptionRepositoryDuplicateUidIntegrationTest {

    private lateinit var db: KashCalDatabase
    private var calendarId: Long = 0L
    private val nowMs = System.currentTimeMillis()

    @Before
    fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(KashCalDatabase.testCallback())
            .build()

        val accountId = db.accountsDao().insert(
            Account(
                provider = AccountProvider.ICS,
                email = "subscriptions@local",
                displayName = "ICS",
                isEnabled = true
            )
        )
        calendarId = db.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://example.com/test.ics",
                displayName = "Test ICS",
                color = 0xFF0000FF.toInt(),
                isReadOnly = true,
                isVisible = true,
                isDefault = false
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `master uniqueness trigger is installed by testCallback`() {
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='trigger' AND name='trigger_master_event_unique_insert'"
        )
        cursor.use {
            assertEquals("trigger must be present in the test DB", 1, it.count)
        }
    }

    @Test
    fun `two masters with disambiguated UIDs both persist without trigger abort`() = runBlocking {
        val originalUid = "xxx@google.com"
        val firstStartTs = 1744203600000L  // 2026-04-09T13:00Z
        val secondStartTs = 1803386700000L // 2027-02-26T11:45Z

        val firstId = db.eventsDao().insert(
            buildMaster(
                uid = "$originalUid#dup=$firstStartTs",
                startTs = firstStartTs
            )
        )
        val secondId = db.eventsDao().insert(
            buildMaster(
                uid = "$originalUid#dup=$secondStartTs",
                startTs = secondStartTs
            )
        )

        assertNotEquals("two distinct row ids", firstId, secondId)
        val rowsByUid = db.eventsDao().getAllMasterEventsForCalendar(calendarId)
        assertEquals("both disambiguated rows persisted", 2, rowsByUid.size)
        assertEquals(
            "stored uids reflect the disambiguation",
            setOf("$originalUid#dup=$firstStartTs", "$originalUid#dup=$secondStartTs"),
            rowsByUid.map { it.uid }.toSet()
        )
    }

    @Test
    fun `two masters with truly identical UIDs still abort via trigger (sanity)`() = runBlocking {
        val sharedUid = "regression-guard@example.com"
        db.eventsDao().insert(
            buildMaster(uid = sharedUid, startTs = nowMs)
        )

        // Same UID, same calendar, both original_event_id IS NULL: what the trigger guards
        // against. If this stops throwing, the trigger was relaxed.
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking {
                db.eventsDao().insert(
                    buildMaster(uid = sharedUid, startTs = nowMs + 1000L)
                )
            }
        }

        // Only the first row persisted.
        val rows = db.eventsDao().getAllMasterEventsForCalendar(calendarId).filter { it.uid == sharedUid }
        assertEquals(1, rows.size)
    }

    @Test
    fun `disambiguation helper produces UIDs that pass through the trigger`() = runBlocking {
        // Renames two duplicate-UID masters and inserts them. The renaming is a local copy of
        // [IcsSubscriptionRepository.disambiguateDuplicateUidMasters] (uid, importId and the
        // original-UID key; not caldavUrl), so this checks that the UID scheme passes the
        // trigger, not the production function itself.
        val originalUid = "shared@google.com"
        val raw = listOf(
            buildIncomingMaster(uid = originalUid, startTs = nowMs),
            buildIncomingMaster(uid = originalUid, startTs = nowMs + 60_000L)
        )

        val disambiguated = run {
            val masterCounts = raw.filter { it.originalInstanceTime == null }
                .groupingBy { it.uid }.eachCount()
            raw.map { event ->
                if (event.originalInstanceTime == null && (masterCounts[event.uid] ?: 0) > 1) {
                    event.copy(
                        uid = "${event.uid}#dup=${event.startTs}",
                        importId = "${event.uid}#dup=${event.startTs}",
                        extraProperties = (event.extraProperties ?: emptyMap()) +
                            (ORIGINAL_UID_EXTRA_KEY to event.uid)
                    )
                } else {
                    event
                }
            }
        }

        disambiguated.forEach { db.eventsDao().insert(it) }

        val rows = db.eventsDao().getAllMasterEventsForCalendar(calendarId)
            .filter { it.uid.startsWith("$originalUid#dup=") }
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertNotNull("X-KASHCAL-ORIGINAL-UID preserved", row.extraProperties)
            assertEquals(
                originalUid,
                row.extraProperties?.get(ORIGINAL_UID_EXTRA_KEY)
            )
        }
    }

    /**
     * Inserts a synthetic master (CANCELLED, originalEventId null) and then two exceptions
     * linked to it, the order the sync writes them in, without tripping the trigger (#227).
     *
     * The trigger fires on a (uid, calendar_id, original_event_id IS NULL) collision; the linked
     * exceptions have an original_event_id, so the UID has one master row. The rows are built by
     * hand, and no legacy standalone row is present.
     */
    @Test
    fun `synthetic master plus linked orphan exceptions persist without trigger abort`() = runBlocking {
        val uid = "orphan-uid@example.com"
        val recId1 = nowMs
        val recId2 = nowMs + 86_400_000L

        // The synthetic master for the exception-only UID.
        val syntheticId = db.eventsDao().insert(
            Event(
                uid = uid,
                importId = uid,
                calendarId = calendarId,
                title = "Orphan Series",
                startTs = recId1,
                endTs = recId1, // zero-duration synthetic
                dtstamp = nowMs,
                status = "CANCELLED",
                rrule = null,
                caldavUrl = "ics_subscription:1:$uid",
                syncStatus = SyncStatus.SYNCED,
                extraProperties = mapOf(SYNTHETIC_MASTER_EXTRA_KEY to "true")
            )
        )
        assertNotEquals("Synthetic must be persisted", 0L, syntheticId)

        // Two exceptions linked to it.
        val exceptionId1 = db.eventsDao().insert(
            Event(
                uid = uid,
                importId = "$uid:RECID:20260101T000000Z",
                calendarId = calendarId,
                title = "Orphan Exception 1",
                startTs = recId1,
                endTs = recId1 + 3_600_000L,
                dtstamp = nowMs,
                originalEventId = syntheticId,
                originalInstanceTime = recId1,
                caldavUrl = "ics_subscription:1:$uid:RECID:20260101T000000Z",
                syncStatus = SyncStatus.SYNCED
            )
        )
        val exceptionId2 = db.eventsDao().insert(
            Event(
                uid = uid,
                importId = "$uid:RECID:20260102T000000Z",
                calendarId = calendarId,
                title = "Orphan Exception 2",
                startTs = recId2,
                endTs = recId2 + 3_600_000L,
                dtstamp = nowMs,
                originalEventId = syntheticId,
                originalInstanceTime = recId2,
                caldavUrl = "ics_subscription:1:$uid:RECID:20260102T000000Z",
                syncStatus = SyncStatus.SYNCED
            )
        )

        assertNotEquals(
            "Both exception inserts succeeded — trigger did not fire",
            0L,
            exceptionId1
        )
        assertNotEquals(
            "Both exception inserts succeeded — trigger did not fire",
            0L,
            exceptionId2
        )
        assertNotEquals(
            "Distinct exception row ids",
            exceptionId1,
            exceptionId2
        )

        // One master row for the UID, and it is the synthetic.
        val masters = db.eventsDao().getAllMasterEventsForCalendar(calendarId)
            .filter { it.uid == uid }
        assertEquals("Exactly one master row for the orphan UID", 1, masters.size)
        assertEquals(
            "The master row is the synthetic",
            syntheticId,
            masters.single().id
        )
    }

    private fun buildMaster(
        uid: String,
        startTs: Long,
        endTs: Long = startTs + 3_600_000L
    ): Event = Event(
        uid = uid,
        importId = uid,
        calendarId = calendarId,
        title = "Test Event",
        startTs = startTs,
        endTs = endTs,
        dtstamp = nowMs,
        syncStatus = SyncStatus.SYNCED,
        caldavUrl = "ics_subscription:1:$uid"
    )

    private fun buildIncomingMaster(uid: String, startTs: Long): Event = buildMaster(uid, startTs)
}
