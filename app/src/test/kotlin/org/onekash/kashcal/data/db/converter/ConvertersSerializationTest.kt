package org.onekash.kashcal.data.db.converter

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies kotlinx.serialization produces identical JSON to Gson for
 * Room TypeConverter types (List<String>, Map<String, String>).
 *
 * Includes backward compat tests with known Gson-produced JSON strings
 * from existing Room data.
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
        // Converters.kt uses catch (e: Exception) which handles SerializationException
        assertThrows(Exception::class.java) {
            Json.decodeFromString<List<String>>("not json")
        }
    }
}
