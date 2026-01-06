package org.onekash.kashcal.data.db.converter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.SyncStatus

/**
 * Unit tests for Room TypeConverters.
 *
 * Tests round-trip conversion for all custom types stored in SQLite.
 */
class ConvertersTest {

    private lateinit var converters: Converters

    @Before
    fun setup() {
        converters = Converters()
    }

    // ========== SyncStatus Enum Tests ==========

    @Test
    fun `fromSyncStatus converts SYNCED to string`() {
        val result = converters.fromSyncStatus(SyncStatus.SYNCED)
        assertEquals("SYNCED", result)
    }

    @Test
    fun `fromSyncStatus converts all enum values correctly`() {
        SyncStatus.entries.forEach { status ->
            val result = converters.fromSyncStatus(status)
            assertEquals(status.name, result)
        }
    }

    @Test
    fun `toSyncStatus converts valid string to enum`() {
        val result = converters.toSyncStatus("PENDING_CREATE")
        assertEquals(SyncStatus.PENDING_CREATE, result)
    }

    @Test
    fun `toSyncStatus returns SYNCED for invalid string`() {
        val result = converters.toSyncStatus("INVALID_STATUS")
        assertEquals(SyncStatus.SYNCED, result)
    }

    @Test
    fun `toSyncStatus handles empty string`() {
        val result = converters.toSyncStatus("")
        assertEquals(SyncStatus.SYNCED, result)
    }

    @Test
    fun `SyncStatus round-trip preserves all values`() {
        SyncStatus.entries.forEach { original ->
            val stored = converters.fromSyncStatus(original)
            val restored = converters.toSyncStatus(stored)
            assertEquals(original, restored)
        }
    }

    // ========== List<String> Tests ==========

    @Test
    fun `fromStringList converts list to JSON`() {
        val list = listOf("item1", "item2", "item3")
        val result = converters.fromStringList(list)
        assertEquals("[\"item1\",\"item2\",\"item3\"]", result)
    }

    @Test
    fun `fromStringList handles empty list`() {
        val result = converters.fromStringList(emptyList())
        assertEquals("[]", result)
    }

    @Test
    fun `fromStringList handles null`() {
        val result = converters.fromStringList(null)
        assertNull(result)
    }

    @Test
    fun `toStringList converts JSON to list`() {
        val json = "[\"a\",\"b\",\"c\"]"
        val result = converters.toStringList(json)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `toStringList handles empty JSON array`() {
        val result = converters.toStringList("[]")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toStringList handles null`() {
        val result = converters.toStringList(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toStringList handles blank string`() {
        val result = converters.toStringList("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toStringList handles malformed JSON gracefully`() {
        val result = converters.toStringList("not valid json")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `List round-trip preserves data`() {
        val original = listOf("-PT15M", "-PT1H", "-P1D")
        val stored = converters.fromStringList(original)
        val restored = converters.toStringList(stored)
        assertEquals(original, restored)
    }

    @Test
    fun `List with special characters round-trip`() {
        val original = listOf("test with spaces", "test\"with\"quotes", "test\\backslash")
        val stored = converters.fromStringList(original)
        val restored = converters.toStringList(stored)
        assertEquals(original, restored)
    }

    // ========== Map<String, String> Tests ==========

    @Test
    fun `fromStringMap converts map to JSON`() {
        val map = mapOf("key1" to "value1", "key2" to "value2")
        val result = converters.fromStringMap(map)
        assertTrue(result!!.contains("\"key1\":\"value1\""))
        assertTrue(result.contains("\"key2\":\"value2\""))
    }

    @Test
    fun `fromStringMap handles empty map`() {
        val result = converters.fromStringMap(emptyMap())
        assertEquals("{}", result)
    }

    @Test
    fun `fromStringMap handles null`() {
        val result = converters.fromStringMap(null)
        assertNull(result)
    }

    @Test
    fun `toStringMap converts JSON to map`() {
        val json = "{\"key1\":\"value1\",\"key2\":\"value2\"}"
        val result = converters.toStringMap(json)
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), result)
    }

    @Test
    fun `toStringMap handles empty JSON object`() {
        val result = converters.toStringMap("{}")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toStringMap handles null`() {
        val result = converters.toStringMap(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toStringMap handles blank string`() {
        val result = converters.toStringMap("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toStringMap handles malformed JSON gracefully`() {
        val result = converters.toStringMap("not valid json")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `Map round-trip preserves data`() {
        val original = mapOf(
            "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR" to "AUTOMATIC",
            "X-APPLE-STRUCTURED-LOCATION" to "geo:37.7749,-122.4194"
        )
        val stored = converters.fromStringMap(original)
        val restored = converters.toStringMap(stored)
        assertEquals(original, restored)
    }

    @Test
    fun `Map with special characters round-trip`() {
        val original = mapOf(
            "key with spaces" to "value with spaces",
            "key\"quotes" to "value\"quotes"
        )
        val stored = converters.fromStringMap(original)
        val restored = converters.toStringMap(stored)
        assertEquals(original, restored)
    }
}
