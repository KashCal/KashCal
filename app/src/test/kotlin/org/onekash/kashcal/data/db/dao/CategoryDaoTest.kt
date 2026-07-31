package org.onekash.kashcal.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Category
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [CategoryDao] — the tag metadata sidecar table. A row records a
 * tag's optional user-chosen color and the last time it was used (for
 * recency-ranked suggestions). The table is loosely coupled to events by name,
 * so a missing row is a valid state (the chip falls back to a hash color).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CategoryDaoTest {

    private lateinit var database: KashCalDatabase
    private lateinit var dao: CategoryDao

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.categoryDao()
    }

    @After
    fun teardown() = database.close()

    @Test
    fun `insertIgnore and getByName round-trip`() = runTest {
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))

        val row = dao.getByName("Work")
        assertNotNull(row)
        assertEquals("Work", row!!.name)
        assertEquals(0xFF4457C9.toInt(), row.color)
        assertEquals(100L, row.lastUsedAt)
    }

    @Test
    fun `NOCASE primary key collapses cased duplicates via insertIgnore`() = runTest {
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))
        dao.insertIgnore(Category(name = "work", color = 0xFFE04A8E.toInt(), lastUsedAt = 200L))

        val all = dao.observeAll().first()
        assertEquals("Work and work are one tag under the NOCASE PK", 1, all.size)
        // First-seen row wins under IGNORE — casing and color preserved.
        assertEquals("Work", all[0].name)
        assertEquals(0xFF4457C9.toInt(), all[0].color)
    }

    @Test
    fun `getByName is case-insensitive`() = runTest {
        dao.insertIgnore(Category(name = "Family", color = null, lastUsedAt = 100L))

        assertNotNull(dao.getByName("family"))
        assertNotNull(dao.getByName("FAMILY"))
    }

    @Test
    fun `suggestions order by last_used_at desc then name asc`() = runTest {
        dao.insertIgnore(Category(name = "Personal", color = null, lastUsedAt = 300L))
        // Same recency for Alpha and Beta -> name-ASC tiebreak.
        dao.insertIgnore(Category(name = "Beta", color = null, lastUsedAt = 200L))
        dao.insertIgnore(Category(name = "Alpha", color = null, lastUsedAt = 200L))

        val names = dao.suggestions(limit = 20)
        assertEquals(listOf("Personal", "Alpha", "Beta"), names)
    }

    @Test
    fun `suggestions respects the limit`() = runTest {
        for (i in 1..25) {
            dao.insertIgnore(Category(name = "tag$i", color = null, lastUsedAt = i.toLong()))
        }
        assertEquals(20, dao.suggestions(limit = 20).size)
    }

    @Test
    fun `deleteByName removes the row`() = runTest {
        dao.insertIgnore(Category(name = "Temp", color = null, lastUsedAt = 100L))
        dao.deleteByName("Temp")

        assertNull(dao.getByName("Temp"))
        assertEquals(0, dao.observeAll().first().size)
    }

    @Test
    fun `touch creates an absent tag with null color and given time`() = runTest {
        dao.touch("Gym", 500L)

        val row = dao.getByName("Gym")
        assertNotNull(row)
        assertNull("auto-created tags carry no custom color", row!!.color)
        assertEquals(500L, row.lastUsedAt)
    }

    @Test
    fun `touch preserves an existing custom color and updates recency`() = runTest {
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))

        dao.touch("Work", 900L)

        val row = dao.getByName("Work")
        assertEquals("touch must never clobber a user's color", 0xFF4457C9.toInt(), row!!.color)
        assertEquals("touch advances recency", 900L, row.lastUsedAt)
    }

    @Test
    fun `touch collapses cased names to one row`() = runTest {
        dao.touch("Work", 100L)
        dao.touch("work", 200L)

        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("recency advances on the existing row", 200L, all[0].lastUsedAt)
    }

    @Test
    fun `seedFromPull creates an absent tag with null color at the given recency`() = runTest {
        dao.seedFromPull("Conference", 500L)

        val row = dao.getByName("Conference")
        assertNotNull(row)
        assertNull("pulled tags carry no custom color", row!!.color)
        assertEquals(500L, row.lastUsedAt)
    }

    @Test
    fun `seedFromPull only raises recency, never lowers it`() = runTest {
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 900L))

        dao.seedFromPull("Work", 100L)

        val row = dao.getByName("Work")
        assertEquals("an older pull must not roll back recency", 900L, row!!.lastUsedAt)
        assertEquals("and must never clobber a color", 0xFF4457C9.toInt(), row.color)
    }

    @Test
    fun `setColor upserts an absent tag then recolors it`() = runTest {
        dao.setColor("Study", 0xFF2E9F63.toInt(), now = 700L)
        assertEquals(0xFF2E9F63.toInt(), dao.getByName("Study")!!.color)

        dao.setColor("Study", 0xFFE47F1B.toInt(), now = 800L)
        val row = dao.getByName("Study")
        assertEquals(0xFFE47F1B.toInt(), row!!.color)
        assertEquals("recoloring keeps the original lastUsedAt", 700L, row.lastUsedAt)
    }

    // ---- rename / merge / delete cascades over event category strings ----

    /** Insert a bare account+calendar once so events satisfy the FK. */
    private fun seedCalendar() {
        val raw = database.openHelper.writableDatabase
        raw.execSQL("INSERT INTO accounts (id, provider, email, created_at) VALUES (1, 'CALDAV', 'a@test.com', 0)")
        raw.execSQL("INSERT INTO calendars (id, account_id, caldav_url, display_name, color) VALUES (1, 1, 'https://x/', 'C', 0)")
    }

    /** Insert an event whose `categories` column holds the given JSON blob verbatim. */
    private fun seedEvent(id: Long, uid: String, categoriesJson: String?) {
        val raw = database.openHelper.writableDatabase
        val value = if (categoriesJson == null) "NULL" else "'${categoriesJson.replace("'", "''")}'"
        raw.execSQL(
            "INSERT INTO events (id, uid, calendar_id, title, start_ts, end_ts, timezone, dtstamp, created_at, updated_at, categories) " +
                "VALUES ($id, '$uid', 1, 'E', 0, 0, 'UTC', 0, 0, 0, $value)"
        )
    }

    /** Read the raw `categories` JSON string for an event (null if the column is null). */
    private fun categoriesOf(id: Long): String? {
        val raw = database.openHelper.writableDatabase
        raw.query("SELECT categories FROM events WHERE id = $id").use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getString(0)
        }
    }

    @Test
    fun `renameTag rewrites the tag on every carrying event`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"Work\"]")
        seedEvent(2, "e2", "[\"Work\",\"Gym\"]")
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))

        dao.renameTag("Work", "Job")

        assertEquals("[\"Job\"]", categoriesOf(1))
        assertEquals("[\"Job\",\"Gym\"]", categoriesOf(2))
    }

    @Test
    fun `renameTag returns the ids of exactly the events whose list changed`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"Work\"]") // carries -> changes
        seedEvent(2, "e2", "[\"Work\",\"Gym\"]") // carries -> changes
        seedEvent(3, "e3", "[\"Gym\"]") // does not carry -> untouched
        seedEvent(4, "e4", null) // untagged -> not even scanned

        val changed = dao.renameTag("Work", "Job")

        assertEquals(
            "only the two carrying events changed",
            listOf(1L, 2L),
            changed.sorted()
        )
    }

    @Test
    fun `renameTag omits an event whose rebuilt list is byte-identical`() = runTest {
        seedCalendar()
        // Renaming "Work" onto itself with identical casing rewrites the same
        // bytes: the event carries the tag but its stored list does not change,
        // so it must not be reported as changed (nothing to re-upload).
        seedEvent(1, "e1", "[\"Work\"]")

        val changed = dao.renameTag("Work", "Work")

        assertEquals("a byte-identical rewrite is not a change", emptyList<Long>(), changed)
    }

    @Test
    fun `renameTag moves the metadata row preserving color and recency`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"Work\"]")
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))

        dao.renameTag("Work", "Job")

        assertNull("the old name is gone", dao.getByName("Work"))
        val moved = dao.getByName("Job")
        assertNotNull(moved)
        assertEquals("the custom color follows the rename", 0xFF4457C9.toInt(), moved!!.color)
        assertEquals("recency follows the rename", 100L, moved.lastUsedAt)
    }

    @Test
    fun `renameTag leaves a similarly-named tag untouched`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"Work\"]")
        seedEvent(2, "e2", "[\"Teamwork\"]")

        dao.renameTag("Work", "Job")

        assertEquals("[\"Job\"]", categoriesOf(1))
        assertEquals("Teamwork must not be touched by renaming Work", "[\"Teamwork\"]", categoriesOf(2))
    }

    @Test
    fun `renameTag into a name an event already carries dedups the result`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"Work\",\"Job\"]")

        dao.renameTag("Work", "Job")

        assertEquals("must not produce [Job, Job]", "[\"Job\"]", categoriesOf(1))
    }

    @Test
    fun `renameTag into an existing tag keeps the target row and drops the source`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"Work\"]")
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))
        dao.insertIgnore(Category(name = "Job", color = 0xFF2E9F63.toInt(), lastUsedAt = 900L))

        dao.renameTag("Work", "Job")

        assertNull(dao.getByName("Work"))
        val target = dao.getByName("Job")
        assertEquals("the target row's color wins", 0xFF2E9F63.toInt(), target!!.color)
        assertEquals("the target row's recency wins", 900L, target.lastUsedAt)
    }

    @Test
    fun `case-only renameTag keeps the custom color and updates the stored casing`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"work\"]")
        dao.insertIgnore(Category(name = "work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))

        dao.renameTag("work", "Work")

        val row = dao.getByName("Work")
        assertNotNull("the row survives a case-only rename", row)
        assertEquals("the recased name is stored", "Work", row!!.name)
        assertEquals("a case-only rename must not drop the color", 0xFF4457C9.toInt(), row.color)
        assertEquals("recency is preserved", 100L, row.lastUsedAt)
        assertEquals("the only metadata row remains", 1, dao.observeAll().first().size)
        assertEquals("the event label is recased too", "[\"Work\"]", categoriesOf(1))
    }

    @Test
    fun `renameTag rewrites events carrying the tag in a different non-ASCII casing`() = runTest {
        seedCalendar()
        // Cyrillic lower/upper differ outside ASCII, so a `LIKE '%"Работа"%'`
        // prefilter (ASCII-only case folding on Android's SQLite) would miss the
        // lowercased event. Both carrying events must still be renamed.
        seedEvent(1, "e1", "[\"Работа\"]")
        seedEvent(2, "e2", "[\"работа\"]")

        dao.renameTag("Работа", "Проект")

        assertEquals("[\"Проект\"]", categoriesOf(1))
        assertEquals("the lowercased-casing event must not be skipped", "[\"Проект\"]", categoriesOf(2))
    }

    @Test
    fun `renameTag rewrites a tag whose name contains a double quote`() = runTest {
        seedCalendar()
        // A quote is legal in a tag name; stored JSON escapes it as \". The rename
        // prefilter must search for the escaped form or the event is silently missed.
        seedEvent(1, "e1", "[\"My \\\"Q\\\" tag\"]")

        dao.renameTag("My \"Q\" tag", "Quarterly")

        assertEquals("[\"Quarterly\"]", categoriesOf(1))
    }

    @Test
    fun `deleteByName leaves event category strings intact`() = runTest {
        seedCalendar()
        seedEvent(1, "e1", "[\"Work\",\"Gym\"]")
        dao.insertIgnore(Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L))

        dao.deleteByName("Work")

        assertNull("the metadata row is gone", dao.getByName("Work"))
        assertEquals(
            "the event keeps its label so the chip can fall back to a hash color",
            "[\"Work\",\"Gym\"]",
            categoriesOf(1)
        )
    }
}
