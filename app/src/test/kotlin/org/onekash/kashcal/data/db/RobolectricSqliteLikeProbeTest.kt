package org.onekash.kashcal.data.db

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.db.dao.BaseDaoTest

/**
 * Pins how the SQLite under the unit tests folds case in LIKE.
 *
 * Search matches with `LIKE '%word%'`, and only an SQLite built with ICU folds
 * letters outside ASCII. The phone's SQLite does (an API 37 emulator finds
 * "Врач" from "врач"); plain host SQLite does not. This probe records what the
 * Robolectric SQLite behind the Room tests does: it folds Cyrillic and German
 * case and keeps accents, as the phone does, so the Room search tests prove the
 * non-ASCII cases here. If a Robolectric bump changes that, these fail first;
 * `RoomSearchCaseFoldingTest` proves the same cases on a device.
 */
class RobolectricSqliteLikeProbeTest : BaseDaoTest() {

    private fun like(value: String, pattern: String): Int =
        database.openHelper.readableDatabase
            .query("SELECT ? LIKE ?", arrayOf(value, pattern))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun `LIKE folds ASCII case`() {
        assertEquals(1, like("Dentist", "%DENT%"))
    }

    @Test
    fun `LIKE folds Cyrillic case`() {
        assertEquals(1, like("Врач", "%врач%"))
    }

    @Test
    fun `LIKE folds German umlaut case`() {
        assertEquals(1, like("Ärzte", "%ärzte%"))
    }

    @Test
    fun `LIKE does not fold accents`() {
        assertEquals(0, like("Café", "%cafe%"))
        assertEquals(1, like("Café", "%CAFÉ%"))
    }
}
