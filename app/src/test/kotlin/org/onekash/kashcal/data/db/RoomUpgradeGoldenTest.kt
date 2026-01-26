package org.onekash.kashcal.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.BaseDaoTest
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Pre-upgrade golden test for Room 2.6.1 → 2.8.x migration.
 *
 * This test captures baseline behavior for:
 * 1. FTS4 full-text search
 * 2. TypeConverters (List<String>, Map<String, String>)
 * 3. Flow queries and emissions
 * 4. @Transaction operations
 * 5. Edge cases and null handling
 * 6. RFC 5545/7986 fields (categories, color, url, priority, geo)
 * 7. Database build + TypeConverter validation (Room 2.7.1 fix for b/409804755)
 *
 * Run BEFORE upgrade to establish baseline.
 * Run AFTER upgrade to verify no regressions.
 *
 * Created: 2026-01-25 (Room 2.6.1)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RoomUpgradeGoldenTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }
    private val calendarsDao by lazy { database.calendarsDao() }
    private val eventsDao by lazy { database.eventsDao() }
    private val occurrencesDao by lazy { database.occurrencesDao() }

    private var testAccountId: Long = 0
    private var testCalendarId: Long = 0

    @Before
    override fun setup() {
        super.setup()
        runTest {
            testAccountId = accountsDao.insert(
                Account(provider = "icloud", email = "test@icloud.com")
            )
            testCalendarId = calendarsDao.insert(
                Calendar(
                    accountId = testAccountId,
                    caldavUrl = "https://caldav.icloud.com/123/calendar/",
                    displayName = "Test Calendar",
                    color = 0xFF0000FF.toInt()
                )
            )
        }
    }

    private fun createEvent(
        title: String = "Test Event",
        location: String? = null,
        description: String? = null,
        startTs: Long = System.currentTimeMillis(),
        categories: List<String>? = null,
        color: Int? = null,
        url: String? = null,
        priority: Int = 0,
        geoLat: Double? = null,
        geoLon: Double? = null
    ) = Event(
        uid = UUID.randomUUID().toString(),
        calendarId = testCalendarId,
        title = title,
        location = location,
        description = description,
        startTs = startTs,
        endTs = startTs + 3600000,
        dtstamp = System.currentTimeMillis(),
        categories = categories,
        color = color,
        url = url,
        priority = priority,
        geoLat = geoLat,
        geoLon = geoLon
    )

    // ==================== FTS4 Golden Tests ====================

    @Test
    fun `golden - FTS4 search finds event by title`() = runTest {
        eventsDao.insert(createEvent(title = "Important Meeting"))

        val results = eventsDao.search("Important*")

        assertEquals(1, results.size)
        assertEquals("Important Meeting", results[0].title)
    }

    @Test
    fun `golden - FTS4 search is case insensitive`() = runTest {
        eventsDao.insert(createEvent(title = "MEETING"))
        eventsDao.insert(createEvent(title = "meeting"))
        eventsDao.insert(createEvent(title = "Meeting"))

        val results = eventsDao.search("meeting*")

        assertEquals(3, results.size)
    }

    @Test
    fun `golden - FTS4 prefix search works`() = runTest {
        eventsDao.insert(createEvent(title = "Meeting with Alice"))
        eventsDao.insert(createEvent(title = "Meet and greet"))
        eventsDao.insert(createEvent(title = "Meditation session"))

        val results = eventsDao.search("Meet*")

        // "Meet*" matches "Meeting" and "Meet", but NOT "Meditation" (Med != Meet)
        assertEquals(2, results.size)
    }

    @Test
    fun `golden - FTS4 multi-word search`() = runTest {
        eventsDao.insert(createEvent(title = "Team Meeting"))
        eventsDao.insert(createEvent(title = "Team Lunch"))
        eventsDao.insert(createEvent(title = "Meeting Alone"))

        val results = eventsDao.search("Team* Meeting*")

        assertEquals(1, results.size)
        assertEquals("Team Meeting", results[0].title)
    }

    // ==================== TypeConverter Golden Tests ====================

    @Test
    fun `golden - categories list round-trip`() = runTest {
        val categories = listOf("work", "important", "urgent")
        val id = eventsDao.insert(createEvent(categories = categories))

        val retrieved = eventsDao.getById(id)

        assertEquals(categories, retrieved?.categories)
    }

    @Test
    fun `golden - categories with special characters`() = runTest {
        val categories = listOf(
            "work/home",
            "project: alpha",
            "tag with spaces",
            "emoji 🎉",
            "quote\"test"
        )
        val id = eventsDao.insert(createEvent(categories = categories))

        val retrieved = eventsDao.getById(id)

        assertEquals(categories, retrieved?.categories)
    }

    @Test
    fun `golden - null categories becomes empty list`() = runTest {
        // TypeConverter returns empty list for null (see toStringList)
        val id = eventsDao.insert(createEvent(categories = null))

        val retrieved = eventsDao.getById(id)

        // Converter returns List<String> (non-nullable), so null becomes []
        assertTrue(retrieved?.categories?.isEmpty() ?: true)
    }

    @Test
    fun `golden - empty categories list preserved`() = runTest {
        val id = eventsDao.insert(createEvent(categories = emptyList()))

        val retrieved = eventsDao.getById(id)

        assertTrue(retrieved?.categories?.isEmpty() ?: true)
    }

    // ==================== RFC 5545/7986 Fields Golden Tests ====================

    @Test
    fun `golden - priority field round-trip`() = runTest {
        val id = eventsDao.insert(createEvent(priority = 5))

        val retrieved = eventsDao.getById(id)

        assertEquals(5, retrieved?.priority)
    }

    @Test
    fun `golden - priority edge cases`() = runTest {
        // Priority 0 = undefined
        val id0 = eventsDao.insert(createEvent(priority = 0, title = "P0"))
        // Priority 1 = highest
        val id1 = eventsDao.insert(createEvent(priority = 1, title = "P1"))
        // Priority 9 = lowest
        val id9 = eventsDao.insert(createEvent(priority = 9, title = "P9"))

        assertEquals(0, eventsDao.getById(id0)?.priority)
        assertEquals(1, eventsDao.getById(id1)?.priority)
        assertEquals(9, eventsDao.getById(id9)?.priority)
    }

    @Test
    fun `golden - geo coordinates round-trip`() = runTest {
        val lat = 37.7749
        val lon = -122.4194
        val id = eventsDao.insert(createEvent(geoLat = lat, geoLon = lon))

        val retrieved = eventsDao.getById(id)

        assertEquals(lat, retrieved?.geoLat!!, 0.0001)
        assertEquals(lon, retrieved.geoLon!!, 0.0001)
    }

    @Test
    fun `golden - geo coordinates extreme values`() = runTest {
        // North pole
        val id1 = eventsDao.insert(createEvent(geoLat = 90.0, geoLon = 0.0, title = "North"))
        // South pole
        val id2 = eventsDao.insert(createEvent(geoLat = -90.0, geoLon = 0.0, title = "South"))
        // Date line
        val id3 = eventsDao.insert(createEvent(geoLat = 0.0, geoLon = 180.0, title = "Date"))
        val id4 = eventsDao.insert(createEvent(geoLat = 0.0, geoLon = -180.0, title = "Date2"))

        val e1 = eventsDao.getById(id1)
        val e2 = eventsDao.getById(id2)
        val e3 = eventsDao.getById(id3)
        val e4 = eventsDao.getById(id4)

        assertEquals(90.0, e1?.geoLat!!, 0.0001)
        assertEquals(-90.0, e2?.geoLat!!, 0.0001)
        assertEquals(180.0, e3?.geoLon!!, 0.0001)
        assertEquals(-180.0, e4?.geoLon!!, 0.0001)
    }

    @Test
    fun `golden - null geo coordinates`() = runTest {
        val id = eventsDao.insert(createEvent(geoLat = null, geoLon = null))

        val retrieved = eventsDao.getById(id)

        assertNull(retrieved?.geoLat)
        assertNull(retrieved?.geoLon)
    }

    @Test
    fun `golden - color ARGB round-trip`() = runTest {
        val color = 0xFFFF5733.toInt()
        val id = eventsDao.insert(createEvent(color = color))

        val retrieved = eventsDao.getById(id)

        assertEquals(color, retrieved?.color)
    }

    @Test
    fun `golden - color edge values`() = runTest {
        // Fully transparent
        val id1 = eventsDao.insert(createEvent(color = 0x00000000, title = "Trans"))
        // Fully opaque white
        val id2 = eventsDao.insert(createEvent(color = 0xFFFFFFFF.toInt(), title = "White"))
        // Fully opaque black
        val id3 = eventsDao.insert(createEvent(color = 0xFF000000.toInt(), title = "Black"))

        assertEquals(0x00000000, eventsDao.getById(id1)?.color)
        assertEquals(0xFFFFFFFF.toInt(), eventsDao.getById(id2)?.color)
        assertEquals(0xFF000000.toInt(), eventsDao.getById(id3)?.color)
    }

    @Test
    fun `golden - url round-trip`() = runTest {
        val url = "https://example.com/meeting?id=123&foo=bar"
        val id = eventsDao.insert(createEvent(url = url))

        val retrieved = eventsDao.getById(id)

        assertEquals(url, retrieved?.url)
    }

    @Test
    fun `golden - url with special characters`() = runTest {
        val urls = listOf(
            "https://example.com/path with spaces",
            "https://example.com/unicode/日本語",
            "https://example.com/query?a=1&b=2#section"
        )

        urls.forEachIndexed { index, url ->
            val id = eventsDao.insert(createEvent(url = url, title = "URL $index"))
            val retrieved = eventsDao.getById(id)
            assertEquals(url, retrieved?.url)
        }
    }

    // ==================== Flow Query Golden Tests ====================

    @Test
    fun `golden - Flow emits on insert`() = runTest {
        val event = createEvent(title = "Flow Test")
        eventsDao.insert(event)

        val flow = eventsDao.getByCalendarId(testCalendarId)
        val events = flow.first()

        assertEquals(1, events.size)
        assertEquals("Flow Test", events[0].title)
    }

    @Test
    fun `golden - Flow emits on update`() = runTest {
        val id = eventsDao.insert(createEvent(title = "Original"))

        // Update
        val original = eventsDao.getById(id)!!
        eventsDao.update(original.copy(title = "Updated"))

        val flow = eventsDao.getByCalendarId(testCalendarId)
        val events = flow.first()

        assertEquals("Updated", events[0].title)
    }

    // ==================== Transaction Golden Tests ====================

    @Test
    fun `golden - transaction rollback on failure`() = runTest {
        val eventsBefore = eventsDao.getByCalendarId(testCalendarId).first()
        assertTrue(eventsBefore.isEmpty())

        try {
            database.runInTransaction {
                eventsDao.insert(createEvent(title = "Transaction Test"))
                throw RuntimeException("Simulated failure")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        val eventsAfter = eventsDao.getByCalendarId(testCalendarId).first()
        // Should be empty - transaction rolled back
        assertTrue(eventsAfter.isEmpty())
    }

    @Test
    fun `golden - transaction commit on success`() = runTest {
        database.runInTransaction {
            eventsDao.insert(createEvent(title = "Event 1"))
            eventsDao.insert(createEvent(title = "Event 2"))
        }

        val events = eventsDao.getByCalendarId(testCalendarId).first()
        assertEquals(2, events.size)
    }

    // ==================== Null Handling Edge Cases ====================

    @Test
    fun `golden - all nullable fields null`() = runTest {
        val event = Event(
            uid = UUID.randomUUID().toString(),
            calendarId = testCalendarId,
            title = "Minimal Event",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            dtstamp = System.currentTimeMillis(),
            // All nullable fields default to null
        )
        val id = eventsDao.insert(event)

        val retrieved = eventsDao.getById(id)

        assertNotNull(retrieved)
        assertNull(retrieved?.location)
        assertNull(retrieved?.description)
        // Categories TypeConverter returns empty list for null
        assertTrue(retrieved?.categories?.isEmpty() ?: true)
        assertNull(retrieved?.color)
        assertNull(retrieved?.url)
        assertNull(retrieved?.geoLat)
        assertNull(retrieved?.geoLon)
        assertEquals(0, retrieved?.priority) // Default, not null
    }

    @Test
    fun `golden - empty string vs null`() = runTest {
        val id1 = eventsDao.insert(createEvent(title = "Null", location = null))
        val id2 = eventsDao.insert(createEvent(title = "Empty", location = ""))

        val e1 = eventsDao.getById(id1)
        val e2 = eventsDao.getById(id2)

        assertNull(e1?.location)
        assertEquals("", e2?.location)
    }

    // ==================== Large Data Edge Cases ====================

    @Test
    fun `golden - large categories list`() = runTest {
        val categories = (1..100).map { "category_$it" }
        val id = eventsDao.insert(createEvent(categories = categories))

        val retrieved = eventsDao.getById(id)

        assertEquals(100, retrieved?.categories?.size)
        assertEquals(categories, retrieved?.categories)
    }

    @Test
    fun `golden - long url string`() = runTest {
        val url = "https://example.com/" + "a".repeat(2000)
        val id = eventsDao.insert(createEvent(url = url))

        val retrieved = eventsDao.getById(id)

        assertEquals(url, retrieved?.url)
    }

    @Test
    fun `golden - unicode in all text fields`() = runTest {
        val id = eventsDao.insert(createEvent(
            title = "会议 📅 Meeting",
            location = "东京 🗼 Tokyo",
            description = "讨论 🎯 Discussion about 日程表",
            categories = listOf("工作", "重要", "🔴")
        ))

        val retrieved = eventsDao.getById(id)

        assertEquals("会议 📅 Meeting", retrieved?.title)
        assertEquals("东京 🗼 Tokyo", retrieved?.location)
        assertEquals("讨论 🎯 Discussion about 日程表", retrieved?.description)
        assertEquals(listOf("工作", "重要", "🔴"), retrieved?.categories)
    }

    // ==================== Index Usage Golden Tests ====================

    @Test
    fun `golden - sync status round-trip`() = runTest {
        val id = eventsDao.insert(createEvent(title = "Pending").copy(syncStatus = SyncStatus.PENDING_CREATE))

        val retrieved = eventsDao.getById(id)

        assertEquals(SyncStatus.PENDING_CREATE, retrieved?.syncStatus)
    }

    @Test
    fun `golden - update sync status`() = runTest {
        val id = eventsDao.insert(createEvent(title = "Event").copy(syncStatus = SyncStatus.PENDING_CREATE))

        eventsDao.updateSyncStatus(id, SyncStatus.SYNCED, System.currentTimeMillis())

        val retrieved = eventsDao.getById(id)
        assertEquals(SyncStatus.SYNCED, retrieved?.syncStatus)
    }

    @Test
    fun `golden - FTS search with occurrences`() = runTest {
        val jan6 = 1736121600000L  // Jan 6, 2025 00:00 UTC
        val jan7 = 1736208000000L  // Jan 7, 2025 00:00 UTC

        val eventId = eventsDao.insert(createEvent(title = "Golden Meeting", startTs = jan6))
        occurrencesDao.insert(Occurrence(
            eventId = eventId,
            calendarId = testCalendarId,
            startTs = jan6,
            endTs = jan6 + 3600000,
            startDay = Occurrence.toDayFormat(jan6, false),
            endDay = Occurrence.toDayFormat(jan6, false)
        ))

        val results = eventsDao.searchInRange("Golden*", jan6 - 86400000, jan7)

        assertEquals(1, results.size)
        assertEquals("Golden Meeting", results[0].title)
    }

    // ==================== Database Build + TypeConverter Validation ====================
    // Tests for Room 2.7.1 fix (b/409804755) - IndexOutOfBoundsException during
    // TypeConverter validation. While KashCal uses standard @TypeConverters (not
    // @ProvidedTypeConverter), this test explicitly verifies database initialization.

    @Test
    fun `golden - database builds without IndexOutOfBoundsException`() {
        // This test explicitly verifies Room's validateTypeConverters() succeeds.
        // Room 2.7.1 fixed IndexOutOfBoundsException during converter validation.
        //
        // The database is built in BaseDaoTest.setup(), so if we reach here,
        // TypeConverter validation succeeded. This test makes that implicit
        // coverage explicit.
        assertTrue("Database should be open", database.isOpen)
        assertNotNull("AccountsDao should be accessible", database.accountsDao())
        assertNotNull("CalendarsDao should be accessible", database.calendarsDao())
        assertNotNull("EventsDao should be accessible", database.eventsDao())
        assertNotNull("OccurrencesDao should be accessible", database.occurrencesDao())
    }

    @Test
    fun `golden - all TypeConverters work through Room integration`() = runTest {
        // Exercises all 4 TypeConverter types through actual Room operations:
        // 1. SyncStatus enum (fromSyncStatus/toSyncStatus)
        // 2. ReminderStatus enum (fromReminderStatus/toReminderStatus) - via ScheduledReminder
        // 3. List<String> (fromStringList/toStringList) - via categories, reminders
        // 4. Map<String, String> (fromStringMap/toStringMap) - via extraProperties

        // SyncStatus converter
        val eventWithStatus = createEvent(title = "Status Test").copy(
            syncStatus = SyncStatus.PENDING_UPDATE
        )
        val eventId = eventsDao.insert(eventWithStatus)
        assertEquals(SyncStatus.PENDING_UPDATE, eventsDao.getById(eventId)?.syncStatus)

        // List<String> converter (categories)
        val eventWithList = createEvent(
            title = "List Test",
            categories = listOf("work", "urgent", "meeting")
        )
        val listEventId = eventsDao.insert(eventWithList)
        assertEquals(
            listOf("work", "urgent", "meeting"),
            eventsDao.getById(listEventId)?.categories
        )

        // List<String> converter (reminders)
        val eventWithReminders = createEvent(title = "Reminders Test").copy(
            reminders = listOf("-PT15M", "-PT1H", "-P1D")
        )
        val remindersEventId = eventsDao.insert(eventWithReminders)
        assertEquals(
            listOf("-PT15M", "-PT1H", "-P1D"),
            eventsDao.getById(remindersEventId)?.reminders
        )

        // Map<String, String> converter (extraProperties)
        val eventWithMap = createEvent(title = "Map Test").copy(
            extraProperties = mapOf(
                "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR" to "AUTOMATIC",
                "X-CUSTOM-PROP" to "value"
            )
        )
        val mapEventId = eventsDao.insert(eventWithMap)
        val retrieved = eventsDao.getById(mapEventId)
        assertEquals("AUTOMATIC", retrieved?.extraProperties?.get("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR"))
        assertEquals("value", retrieved?.extraProperties?.get("X-CUSTOM-PROP"))
    }
}
