package org.onekash.kashcal.calendarbackup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

class CalendarBackupNamesTest {

    private val defaultZone: TimeZone = TimeZone.getDefault()
    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun restoreDefaults() {
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    private fun builtIn(id: Long = 1) = BackupCalendarName(id, "Local", isBuiltIn = true)
    private fun user(id: Long, name: String) = BackupCalendarName(id, name, isBuiltIn = false)

    private fun plan(vararg calendars: BackupCalendarName) =
        CalendarBackupNames.planBaseNames(calendars.toList())

    // ---------- base names ----------

    @Test
    fun `built-in calendar is always Local whatever its stored name`() {
        assertEquals(mapOf(1L to "Local"), plan(BackupCalendarName(1, "Lokal", isBuiltIn = true)))
    }

    @Test
    fun `a calendar with a unique cleaned name keeps the plain name`() {
        assertEquals(
            mapOf(1L to "Local", 2L to "Trips", 3L to "Kids-2026"),
            plan(builtIn(), user(2, "Trips"), user(3, "Kids 2026")),
        )
    }

    @Test
    fun `names that clean to the same file name both get their id`() {
        assertEquals(mapOf(17L to "Work-17", 18L to "Work-18"), plan(user(17, "Work"), user(18, "Work!")))
        assertEquals(mapOf(4L to "Team-1-4", 5L to "Team-1-5"), plan(user(4, "Team 1"), user(5, "Team-1")))
        assertEquals(
            mapOf(6L to "Kids-2026-6", 7L to "Kids-2026-7"),
            plan(user(6, "Kids (2026)"), user(7, "Kids 2026")),
        )
    }

    @Test
    fun `the clash check ignores case`() {
        assertEquals(mapOf(2L to "Work-2", 3L to "work-3"), plan(user(2, "Work"), user(3, "work!")))
    }

    @Test
    fun `names that clean to nothing use calendar and their id`() {
        assertEquals(mapOf(8L to "calendar-8", 9L to "calendar-9"), plan(user(8, "🎂"), user(9, "🎉")))
    }

    @Test
    fun `names equal in their first 50 characters clash`() {
        val prefix = "a".repeat(50)
        val names = plan(user(2, prefix + "one"), user(3, prefix + "two"))
        assertEquals("$prefix-2", names[2])
        assertEquals("$prefix-3", names[3])
    }

    @Test
    fun `a user calendar cleaning to Local gets its id and the built-in stays Local`() {
        // A German user's "Local" is allowed (the built-in shows as "Lokal" there).
        assertEquals(mapOf(1L to "Local", 12L to "Local-12"), plan(builtIn(), user(12, "Local")))
    }

    @Test
    fun `a plain name equal to a suffixed name is suffixed too`() {
        val names = plan(user(17, "Work"), user(18, "Work!"), user(5, "Work 17"))
        assertEquals(mapOf(17L to "Work-17", 18L to "Work-18", 5L to "Work-17-5"), names)
        assertUnique(names.values)
    }

    @Test
    fun `an emoji calendar's fallback can't take another calendar's plain name`() {
        val names = plan(user(5, "🎂"), user(6, "Calendar 5"))
        assertUnique(names.values)
        assertEquals("calendar-5", names[5])
        assertEquals("Calendar-5-6", names[6])
    }

    @Test
    fun `planned names are unique ignoring case for random calendars`() {
        val pieces = listOf("Work", "work", "WORK", "Work!", "Team 1", "Team-1", "17", "-17", "Local",
            "local", "🎉", "Calendar", "calendar 5", "Kids (2026)", "a".repeat(55), "İstanbul", "", " ")
        val random = Random(99)
        repeat(2_000) {
            val count = random.nextInt(1, 7)
            val calendars = (1..count).map { index ->
                val name = (1..random.nextInt(1, 3)).joinToString(" ") { pieces.random(random) }
                user(index + 1L, name)
            } + builtIn(id = 1)
            assertUnique(CalendarBackupNames.planBaseNames(calendars).values)
        }
    }

    @Test
    fun `names equal under full case folding clash`() {
        val names = plan(user(2, "Straße"), user(3, "Strasse"))
        assertEquals(mapOf(2L to "Straße-2", 3L to "Strasse-3"), names)
    }

    @Test
    fun `keys are lower-cased with the root locale`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))
        // Under a Turkish default, "I".lowercase() would be a dotless ı and miss "i".
        val names = plan(user(2, "IDEAS"), user(3, "ideas!"))
        assertEquals(mapOf(2L to "IDEAS-2", 3L to "ideas-3"), names)
    }

    // ---------- file names ----------

    @Test
    fun `latest file name is the base with ics`() {
        assertEquals("Work.ics", CalendarBackupNames.latestFileName("Work"))
    }

    @Test
    fun `dated copy name is UTC with seconds whatever the default zone`() {
        // 2026-03-08 is the US DST change day.
        val instant = Instant.parse("2026-03-08T07:30:05Z")
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val inNewYork = CalendarBackupNames.datedCopyName("Work", instant)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        val inKolkata = CalendarBackupNames.datedCopyName("Work", instant)
        assertEquals("Work_20260308T073005Z.ics", inNewYork)
        assertEquals(inNewYork, inKolkata)
    }

    @Test
    fun `dated copies are recognised only by the exact pattern for their base`() {
        assertTrue(CalendarBackupNames.isDatedCopyOf("Work_20261025T031005Z.ics", "Work"))
        listOf(
            "Work.ics",
            "Work_x.ics",
            "Work (1).ics",
            "Work_20261025T031005Z (1).ics",
            "Work_20261025T0310Z.ics",
            "Work-17_20261025T031005Z.ics",
            "Trips_20261025T031005Z.ics",
            "XWork_20261025T031005Z.ics",
            "Work_20261025T031005Z.ics.tmp",
        ).forEach { assertFalse(it, CalendarBackupNames.isDatedCopyOf(it, "Work")) }
    }

    @Test
    fun `a base with regex characters is matched literally`() {
        assertTrue(CalendarBackupNames.isDatedCopyOf("a.b_20261025T031005Z.ics", "a.b"))
        assertFalse(CalendarBackupNames.isDatedCopyOf("aXb_20261025T031005Z.ics", "a.b"))
    }

    // ---------- retention ----------

    private val copies = listOf(
        "Work_20261020T030000Z.ics",
        "Work_20261022T030000Z.ics",
        "Work_20261024T030000Z.ics",
        "Work_20261021T030000Z.ics",
    )

    @Test
    fun `keep counts the latest file so keep minus one dated copies stay`() {
        assertEquals(
            listOf("Work_20261021T030000Z.ics", "Work_20261020T030000Z.ics"),
            CalendarBackupNames.datedCopiesToDelete(copies, keep = 3, justAdded = null),
        )
    }

    @Test
    fun `copies are ordered by their time whatever the case of their names`() {
        val names = listOf("work_20261002T000000Z.ics", "Work_20261027T000000Z.ics", "Work_20261020T000000Z.ics")
        assertEquals(
            listOf("work_20261002T000000Z.ics"),
            CalendarBackupNames.datedCopiesToDelete(names, keep = 3, justAdded = null),
        )
    }

    @Test
    fun `keep one removes every dated copy`() {
        assertEquals(copies.sortedDescending(), CalendarBackupNames.datedCopiesToDelete(copies, 1, null))
    }

    @Test
    fun `the copy just written stays even when named by a clock that ran behind`() {
        val justAdded = "Work_20200101T000000Z.ics"
        val toDelete = CalendarBackupNames.datedCopiesToDelete(copies + justAdded, keep = 2, justAdded)
        assertFalse(toDelete.contains(justAdded))
        assertEquals(copies.size, toDelete.size)
    }

    @Test
    fun `keep is limited to 1 to 30`() {
        assertEquals(copies.size, CalendarBackupNames.datedCopiesToDelete(copies, keep = 0, null).size)
        assertEquals(0, CalendarBackupNames.datedCopiesToDelete(copies, keep = 99, null).size)
    }

    private fun assertUnique(names: Collection<String>) {
        val keys = names.map { it.lowercase(Locale.ROOT) }
        assertEquals("names not unique: $names", keys.size, keys.toSet().size)
    }
}
