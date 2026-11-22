package org.onekash.kashcal.calendarbackup

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CalendarBackupStoreTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(CalendarBackupStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun store() = CalendarBackupStore(prefs)

    @Test
    fun `defaults are off, no folder, every 12 hours, 3 copies`() {
        val settings = store().settings.value
        assertFalse(settings.enabled)
        assertNull(settings.folderUri)
        assertNull(settings.folderName)
        assertEquals(12, settings.intervalHours)
        assertEquals(3, settings.keepCount)
        val status = store().status.value
        assertNull(status.lastCheckedAt)
        assertTrue(status.results.isEmpty())
        assertNull(status.folderProblem)
        assertEquals(0, status.failedRuns)
        assertNull(status.notifiedAlert)
    }

    @Test
    fun `settings and status survive a new store over the same file`() {
        val first = store()
        first.updateSettings {
            it.copy(enabled = true, folderUri = "content://t/tree/a", folderName = "Backups", intervalHours = 54, keepCount = 7)
        }
        first.updateStatus {
            it.copy(
                lastCheckedAt = 10L,
                results = mapOf(
                    3L to CalendarBackupResult(CalendarBackupResult.Kind.UPDATED, 5L, "Work.ics"),
                    4L to CalendarBackupResult(CalendarBackupResult.Kind.FAILED, 10L, "Trips.ics"),
                ),
                folderProblem = FolderFailure.MISSING,
                failedRuns = 2,
                notifiedAlert = "FolderUnreachable",
            )
        }
        val second = store()
        assertEquals(first.settings.value, second.settings.value)
        assertEquals(first.status.value, second.status.value)
    }

    @Test
    fun `a write is seen at once on the flow`() {
        val store = store()
        store.updateSettings { it.copy(keepCount = 9) }
        assertEquals(9, store.settings.value.keepCount)
    }

    @Test
    fun `out of range values read as defaults`() {
        prefs.edit().putInt("interval_hours", 0).putInt("keep_count", 31).commit()
        assertEquals(12, store().settings.value.intervalHours)
        assertEquals(3, store().settings.value.keepCount)
        prefs.edit().putInt("interval_hours", 721).putInt("keep_count", 0).commit()
        assertEquals(12, store().settings.value.intervalHours)
        assertEquals(3, store().settings.value.keepCount)
    }

    @Test
    fun `values written out of range are limited`() {
        val store = store()
        store.updateSettings { it.copy(keepCount = 99, intervalHours = 9999) }
        assertEquals(30, store.settings.value.keepCount)
        assertEquals(12, store.settings.value.intervalHours)
    }

    @Test
    fun `unreadable per-calendar results read as none`() {
        prefs.edit().putString("results", "{not json").putString("folder_problem", "NOPE").commit()
        assertTrue(store().status.value.results.isEmpty())
        assertNull(store().status.value.folderProblem)
    }

    @Test
    fun `the file is excluded from backup and device transfer`() {
        val expected = """path="${CalendarBackupStore.PREFS_NAME}.xml""""
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { rules ->
            val file = File(resolveProjectRoot(), "app/src/main/res/xml/$rules")
            assertTrue("$rules not found at ${file.absolutePath}", file.exists())
            val excludes = Regex("""<exclude domain="sharedpref" [^>]*>""")
                .findAll(file.readText())
                .count { it.value.contains(expected) }
            val sections = if (rules == "backup_rules.xml") 1 else 2
            assertEquals("$rules must exclude ${CalendarBackupStore.PREFS_NAME}.xml in every section", sections, excludes)
        }
    }

    @Test
    fun `a dismissed notifications hint survives a new store and clears`() {
        assertNull(store().dismissedHint.value)
        store().setDismissedHint(BackupNotificationState.CHANNEL_OFF)
        assertEquals(BackupNotificationState.CHANNEL_OFF, store().dismissedHint.value)
        store().setDismissedHint(null)
        assertNull(store().dismissedHint.value)
    }
}
