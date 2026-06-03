package org.onekash.kashcal.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure holiday-catalog logic: JSON parsing, search
 * filtering, and already-added marking. The Android resource loader
 * (loadHolidayCatalog) is verified by build + manual; this exercises the
 * total/pure functions it delegates to.
 */
class HolidayCatalogTest {

    private val sampleJson = """
        {
          "source": "https://www.thunderbird.net/en-US/calendar/holidays/",
          "license": "CC BY-SA 3.0 (or any later version)",
          "license_url": "https://creativecommons.org/licenses/by-sa/3.0/",
          "license_note": "ignored by parser",
          "entries": [
            { "name": "United States", "url": "https://www.thunderbird.net/media/caldata/autogen/USHolidays.ics" },
            { "name": "albania", "url": "https://www.thunderbird.net/media/caldata/autogen/AlbaniaHolidays.ics" },
            { "name": "Germany", "url": "https://www.thunderbird.net/media/caldata/autogen/GermanHolidays.ics" },
            { "name": "Algeria (French)", "url": "https://www.thunderbird.net/media/caldata/autogen/AlgeriaHolidays.ics" }
          ]
        }
    """.trimIndent()

    // ---- parsing ----

    @Test
    fun `parses all entries with non-blank name and https ics url`() {
        val entries = parseHolidayCatalog(sampleJson)
        assertEquals(4, entries.size)
        entries.forEach {
            assertTrue("name should be non-blank", it.name.isNotBlank())
            assertTrue("url should be https", it.url.startsWith("https://"))
            assertTrue("url should be .ics", it.url.endsWith(".ics"))
        }
    }

    @Test
    fun `entries are sorted case-insensitively by name`() {
        val names = parseHolidayCatalog(sampleJson).map { it.name }
        assertEquals(
            listOf("albania", "Algeria (French)", "Germany", "United States"),
            names
        )
    }

    @Test
    fun `unknown top-level keys are ignored`() {
        // source/license/license_url/license_note are all unknown to the model
        val entries = parseHolidayCatalog(sampleJson)
        assertEquals(4, entries.size)
    }

    @Test
    fun `malformed json returns empty list and does not throw`() {
        assertTrue(parseHolidayCatalog("{ this is not json").isEmpty())
    }

    @Test
    fun `empty string returns empty list`() {
        assertTrue(parseHolidayCatalog("").isEmpty())
    }

    @Test
    fun `json without entries array returns empty list`() {
        assertTrue(parseHolidayCatalog("""{ "source": "x" }""").isEmpty())
    }

    // ---- filtering ----

    @Test
    fun `filter matches case-insensitive substring on name`() {
        val entries = parseHolidayCatalog(sampleJson)
        val result = filterCatalog(entries, "germ").map { it.name }
        assertEquals(listOf("Germany"), result)
    }

    @Test
    fun `filter matches substring anywhere in the name, not just prefix`() {
        // "ger" appears mid-word in both "Algeria" and "Germany"
        val entries = parseHolidayCatalog(sampleJson)
        assertEquals(
            listOf("Algeria (French)", "Germany"),
            filterCatalog(entries, "ger").map { it.name }
        )
    }

    @Test
    fun `filter is case-insensitive both directions`() {
        val entries = parseHolidayCatalog(sampleJson)
        assertEquals(listOf("albania"), filterCatalog(entries, "ALB").map { it.name })
    }

    @Test
    fun `blank query returns all entries`() {
        val entries = parseHolidayCatalog(sampleJson)
        assertEquals(entries.size, filterCatalog(entries, "").size)
        assertEquals(entries.size, filterCatalog(entries, "   ").size)
    }

    @Test
    fun `no match returns empty list`() {
        val entries = parseHolidayCatalog(sampleJson)
        assertTrue(filterCatalog(entries, "atlantis").isEmpty())
    }

    // ---- already-added marking ----

    @Test
    fun `entry is marked added when its url is an existing subscription`() {
        val entries = parseHolidayCatalog(sampleJson)
        val subscribed = setOf("https://www.thunderbird.net/media/caldata/autogen/GermanHolidays.ics")
        val marked = markAlreadyAdded(entries, subscribed)
        assertTrue(marked.first { it.entry.name == "Germany" }.alreadyAdded)
        assertFalse(marked.first { it.entry.name == "albania" }.alreadyAdded)
    }

    @Test
    fun `already-added matching tolerates surrounding whitespace in stored url`() {
        val entries = parseHolidayCatalog(sampleJson)
        val subscribed = setOf("  https://www.thunderbird.net/media/caldata/autogen/USHolidays.ics  ")
        val marked = markAlreadyAdded(entries, subscribed)
        assertTrue(marked.first { it.entry.name == "United States" }.alreadyAdded)
    }

    @Test
    fun `no subscriptions means nothing is marked added`() {
        val entries = parseHolidayCatalog(sampleJson)
        val marked = markAlreadyAdded(entries, emptySet())
        assertTrue(marked.none { it.alreadyAdded })
        assertEquals(entries.size, marked.size)
    }
}
