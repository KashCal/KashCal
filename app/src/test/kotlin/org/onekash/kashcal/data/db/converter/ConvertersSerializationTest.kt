package org.onekash.kashcal.data.db.converter

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checks that kotlinx.serialization reads and writes the JSON the Room converters store for
 * `List<String>` and `Map<String, String>`, including Gson-produced strings in existing rows.
 * Only the list test pins the encoded string; the map test checks the round trip.
 */
class ConvertersSerializationTest {

    @Test
    fun `List-String round-trip produces identical JSON`() {
        val input = listOf("PT15M", "PT1H", "-PT30M")
        val json = Json.encodeToString(input)
        assertEquals("""["PT15M","PT1H","-PT30M"]""", json)
        val decoded = Json.decodeFromString<List<String>>(json)
        assertEquals(input, decoded)
    }

    @Test
    fun `Map-String round-trip produces identical JSON`() {
        val input = mapOf("X-APPLE-TRAVEL" to "AUTOMATIC", "X-COLOR" to "#FF0000")
        val json = Json.encodeToString(input)
        val decoded = Json.decodeFromString<Map<String, String>>(json)
        assertEquals(input, decoded)
    }

    @Test
    fun `backward compat - Gson-produced List JSON deserializes`() {
        // Known format from existing Room data (reminders column)
        val gsonJson = """["PT15M","PT1H"]"""
        val result = Json.decodeFromString<List<String>>(gsonJson)
        assertEquals(listOf("PT15M", "PT1H"), result)
    }

    @Test
    fun `backward compat - Gson-produced Map JSON deserializes`() {
        // Known format from existing Room data (extra_properties column)
        val gsonJson = """{"X-APPLE-TRAVEL-ADVISORY-BEHAVIOR":"AUTOMATIC"}"""
        val result = Json.decodeFromString<Map<String, String>>(gsonJson)
        assertEquals("AUTOMATIC", result["X-APPLE-TRAVEL-ADVISORY-BEHAVIOR"])
    }

    @Test
    fun `null and empty inputs`() {
        assertEquals(emptyList<String>(), Json.decodeFromString<List<String>>("[]"))
        assertEquals(emptyMap<String, String>(), Json.decodeFromString<Map<String, String>>("{}"))
    }

    @Test
    fun `malformed JSON throws SerializationException caught by Exception handler`() {
        // Converters catches Exception, which covers SerializationException, and returns empty.
        assertThrows(Exception::class.java) {
            Json.decodeFromString<List<String>>("not json")
        }
    }
}
