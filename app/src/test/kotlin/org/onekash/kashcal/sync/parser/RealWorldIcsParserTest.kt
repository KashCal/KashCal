package org.onekash.kashcal.sync.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests ICS parser with real-world holiday calendars from Thunderbird.
 *
 * Source: https://www.thunderbird.net/en-US/calendar/holidays/
 *
 * These calendars test:
 * - CJK characters (Japanese: 元日, 成人の日)
 * - German umlauts (ä, ü, ö, ß)
 * - Portuguese diacritics (ã, é, ç)
 * - RFC 5545 line folding in DESCRIPTION
 * - VALUE=DATE all-day events
 * - CATEGORIES, CLASS, TRANSP properties
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RealWorldIcsParserTest {

    private lateinit var parser: Ical4jParser

    @Before
    fun setup() {
        parser = Ical4jParser()
    }

    // ==================== Japan Holidays ====================

    @Test
    fun `parse Japan holidays - CJK characters preserved`() {
        val ics = loadResource("ics/JapanHolidays.ics")
        val result = parser.parse(ics)

        assertTrue("Should parse multiple events", result.events.size >= 10)

        // Find New Year's Day (元日)
        val newYear = result.events.find { it.summary?.contains("元日") == true }
        assertNotNull("Should find 元日 (New Year's Day)", newYear)
    }

    @Test
    fun `parse Japan holidays - all events are all-day`() {
        val ics = loadResource("ics/JapanHolidays.ics")
        val result = parser.parse(ics)

        result.events.forEach { event ->
            assertTrue(
                "Japanese holiday '${event.summary}' should be all-day",
                event.isAllDay
            )
        }
    }

    @Test
    fun `parse Japan holidays - descriptions with CJK line folding`() {
        val ics = loadResource("ics/JapanHolidays.ics")
        val result = parser.parse(ics)

        // Descriptions should be unfolded correctly
        val eventWithDesc = result.events.find { it.description?.isNotBlank() == true }
        assertNotNull("Should have event with description", eventWithDesc)

        // Description should not have line folding artifacts
        assertFalse(
            "Description should not have CRLF+SPACE artifacts",
            eventWithDesc!!.description?.contains("\r\n ") == true
        )
    }

    @Test
    fun `parse Japan holidays - Coming of Age Day kanji`() {
        val ics = loadResource("ics/JapanHolidays.ics")
        val result = parser.parse(ics)

        val comingOfAge = result.events.find { it.summary?.contains("成人の日") == true }
        assertNotNull("Should find 成人の日 (Coming of Age Day)", comingOfAge)
    }

    // ==================== German Holidays ====================

    @Test
    fun `parse German holidays - umlauts preserved`() {
        val ics = loadResource("ics/GermanHolidays.ics")
        val result = parser.parse(ics)

        assertTrue("Should parse multiple events", result.events.size >= 10)

        // Find events with umlauts
        val withUmlaut = result.events.find {
            it.summary?.contains("ä") == true ||
            it.summary?.contains("ü") == true ||
            it.summary?.contains("ö") == true
        }
        assertNotNull("Should have event with umlaut", withUmlaut)
    }

    @Test
    fun `parse German holidays - Neujahrstag`() {
        val ics = loadResource("ics/GermanHolidays.ics")
        val result = parser.parse(ics)

        val newYear = result.events.find { it.summary?.contains("Neujahrstag") == true }
        assertNotNull("Should find Neujahrstag", newYear)
        assertTrue("Should be all-day", newYear!!.isAllDay)
    }

    @Test
    fun `parse German holidays - description line folding with umlauts`() {
        val ics = loadResource("ics/GermanHolidays.ics")
        val result = parser.parse(ics)

        // German descriptions have long text with umlauts that gets folded
        val eventWithDesc = result.events.find {
            it.description?.contains("Deutschland") == true
        }
        assertNotNull("Should have event about Deutschland", eventWithDesc)

        // Check umlauts in description are preserved
        val descWithUmlaut = result.events.find {
            it.description?.contains("ä") == true ||
            it.description?.contains("ü") == true ||
            it.description?.contains("ö") == true
        }
        assertNotNull("Description should preserve umlauts", descWithUmlaut)
    }

    @Test
    fun `parse German holidays - escaped commas in description`() {
        val ics = loadResource("ics/GermanHolidays.ics")
        val result = parser.parse(ics)

        // German ICS uses escaped commas (\,)
        val eventWithComma = result.events.find {
            it.description?.contains(",") == true
        }
        assertNotNull("Should have unescaped comma in description", eventWithComma)

        // Should NOT have literal backslash-comma
        result.events.forEach { event ->
            assertFalse(
                "Description should unescape \\,",
                event.description?.contains("\\,") == true
            )
        }
    }

    // ==================== Brazil Holidays ====================

    @Test
    fun `parse Brazil holidays - Portuguese diacritics`() {
        val ics = loadResource("ics/BrazilHolidays.ics")
        val result = parser.parse(ics)

        assertTrue("Should parse multiple events", result.events.size >= 10)

        // Find events with Portuguese characters
        val withDiacritic = result.events.find {
            it.summary?.contains("ã") == true ||
            it.summary?.contains("é") == true ||
            it.summary?.contains("ç") == true ||
            it.summary?.contains("í") == true
        }
        assertNotNull("Should have event with Portuguese diacritics", withDiacritic)
    }

    @Test
    fun `parse Brazil holidays - Carnaval`() {
        val ics = loadResource("ics/BrazilHolidays.ics")
        val result = parser.parse(ics)

        val carnaval = result.events.find {
            it.summary?.lowercase()?.contains("carnaval") == true
        }
        assertNotNull("Should find Carnaval", carnaval)
    }

    @Test
    fun `parse Brazil holidays - Tiradentes Day`() {
        val ics = loadResource("ics/BrazilHolidays.ics")
        val result = parser.parse(ics)

        val tiradentes = result.events.find {
            it.summary?.contains("Tiradentes") == true
        }
        assertNotNull("Should find Dia de Tiradentes", tiradentes)
    }

    // ==================== Cross-Calendar Tests ====================

    @Test
    fun `all calendars have TRANSPARENT events`() {
        listOf(
            "ics/JapanHolidays.ics",
            "ics/GermanHolidays.ics",
            "ics/BrazilHolidays.ics"
        ).forEach { file ->
            val ics = loadResource(file)
            val result = parser.parse(ics)

            // All holiday events should be TRANSPARENT (don't block time)
            result.events.forEach { event ->
                assertEquals(
                    "Holiday in $file should be TRANSPARENT",
                    "TRANSPARENT",
                    event.transp
                )
            }
        }
    }

    @Test
    fun `all calendars have UIDs`() {
        listOf(
            "ics/JapanHolidays.ics",
            "ics/GermanHolidays.ics",
            "ics/BrazilHolidays.ics"
        ).forEach { file ->
            val ics = loadResource(file)
            val result = parser.parse(ics)

            result.events.forEach { event ->
                assertNotNull("Event in $file should have UID", event.uid)
                assertTrue("UID should not be empty", event.uid!!.isNotBlank())
            }
        }
    }

    @Test
    fun `all calendars have valid positive timestamps`() {
        listOf(
            "ics/JapanHolidays.ics",
            "ics/GermanHolidays.ics",
            "ics/BrazilHolidays.ics"
        ).forEach { file ->
            val ics = loadResource(file)
            val result = parser.parse(ics)

            result.events.forEach { event ->
                assertTrue(
                    "Event '${event.summary}' in $file should have positive startTs",
                    event.startTs > 0
                )
                assertTrue(
                    "Event '${event.summary}' in $file should have positive endTs",
                    event.endTs > 0
                )
                assertTrue(
                    "Event '${event.summary}' in $file should have endTs >= startTs",
                    event.endTs >= event.startTs
                )
            }
        }
    }

    @Test
    fun `parse total event count across all calendars`() {
        var totalEvents = 0

        listOf(
            "ics/JapanHolidays.ics",
            "ics/GermanHolidays.ics",
            "ics/BrazilHolidays.ics"
        ).forEach { file ->
            val ics = loadResource(file)
            val result = parser.parse(ics)
            totalEvents += result.events.size
        }

        // Should have substantial number of events across 3 calendars for 4 years
        assertTrue("Should have at least 100 events total", totalEvents >= 100)
    }

    // ==================== Helper ====================

    private fun loadResource(path: String): String {
        return this::class.java.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }
}
