package org.onekash.kashcal.data.db.migration

import androidx.sqlite.SQLiteConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import java.io.File

/**
 * Checks that a migrated database holds exactly the tables Room's schema export of its version
 * declares. `MigrationTestHelper.runMigrationsAndValidate` doesn't reject a table the export
 * lacks, so a migration that leaves a table behind would pass it; the tests that use it call
 * [assertExactly] after it.
 */
internal object ExportedSchemaTables {

    private const val SCHEMA_DIR = "schemas/org.onekash.kashcal.data.db.KashCalDatabase"

    /** Room's bookkeeping table and the framework's locale table, which no export lists. */
    private val BOOKKEEPING = setOf("room_master_table", "android_metadata")

    /** The shadow tables SQLite can keep for an FTS4 table; which exist depends on its options. */
    private val FTS4_SHADOWS = listOf("content", "segments", "segdir", "docsize", "stat")

    /** Returns the tables the export of [version] declares. */
    fun declared(version: Int): Set<String> = entities(version).mapTo(mutableSetOf()) { it.first }

    /** Returns the shadow tables SQLite may keep for the FTS tables in the export of [version]. */
    fun ftsShadows(version: Int): Set<String> =
        entities(version).filter { it.second }.flatMapTo(mutableSetOf()) { (name, _) -> FTS4_SHADOWS.map { "${name}_$it" } }

    private fun entities(version: Int): List<Pair<String, Boolean>> {
        val file = listOf(File("$SCHEMA_DIR/$version.json"), File("app/$SCHEMA_DIR/$version.json")).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database")
            .jsonObject.getValue("entities").jsonArray.map { it.jsonObject }
            .map { it.getValue("tableName").jsonPrimitive.content to ("ftsVersion" in it) }
    }

    /**
     * Asserts [connection]'s tables, besides SQLite's and Room's own and the FTS shadow tables
     * SQLite keeps (which depend on the FTS options), are exactly the ones the export declares.
     */
    fun assertExactly(connection: SQLiteConnection, version: Int) {
        val actual = connection.prepare(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'"
        ).use { statement -> buildSet { while (statement.step()) add(statement.getText(0)) } } - BOOKKEEPING
        assertEquals("tables after migrating to v$version", declared(version), actual - ftsShadows(version))
    }
}
