package org.onekash.kashcal.util.zone

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.util.resolveWindowsZone
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Tests [ZoneCatalog] under the ICU that Robolectric's sdk 34 carries (72.1): zone comparison,
 * the list, labels at a date, and ranked search. Outputs that differ between ICU 72, 77 and 78
 * (the exemplar accent of Sao Paulo, the list size, the canonical id of EST) are not pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ZoneCatalogTest {

    private lateinit var savedLocale: Locale
    private val us = Locale.US
    private val january = Instant.parse("2026-01-15T12:00:00Z")
    private val july = Instant.parse("2026-07-15T12:00:00Z")

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(us)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    private fun label(id: String, at: Instant = january, locale: Locale = us): ZoneCatalog.Entry =
        ZoneCatalog.label(id, at, locale) ?: throw AssertionError("no label for $id")

    // ---- Windows ids ----

    @Test
    fun `windowsZone resolves a Windows id through ICU`() {
        assertEquals(ZoneId.of("America/Chicago"), ZoneCatalog.windowsZone("Central Standard Time"))
        assertNull(ZoneCatalog.windowsZone("Nowhere Standard Time"))
    }

    @Test
    fun `parse resolver uses only the Windows step`() {
        assertEquals(ZoneId.of("America/New_York"), resolveWindowsZone("Eastern Standard Time"))
        assertNull(resolveWindowsZone("EST"))
    }

    // ---- sameZone ----

    @Test
    fun `sameZone groups ICU aliases of one zone`() {
        assertTrue(ZoneCatalog.sameZone("Asia/Calcutta", "Asia/Kolkata"))
        assertTrue(ZoneCatalog.sameZone("US/Pacific", "America/Los_Angeles"))
    }

    @Test
    fun `sameZone treats the UTC family as one zone`() {
        val family = listOf("Etc/UCT", "UTC", "Etc/UTC", "GMT", "Etc/GMT", "Z", "+00:00")
        for (a in family) for (b in family) assertTrue("$a vs $b", ZoneCatalog.sameZone(a, b))
    }

    @Test
    fun `sameZone keeps distinct zones apart`() {
        assertFalse(ZoneCatalog.sameZone("Europe/Amsterdam", "Europe/Brussels"))
        assertFalse(ZoneCatalog.sameZone("Europe/London", "UTC"))
        assertTrue(ZoneCatalog.sameZone("Foo/Bar", "Foo/Bar"))
        assertFalse(ZoneCatalog.sameZone("Foo/Bar", "Foo/Baz"))
        assertFalse(ZoneCatalog.sameZone("Foo/Bar", null))
    }

    // ---- entries ----

    @Test
    fun `entries list each place once plus one UTC row`() {
        val entries = ZoneCatalog.entries(us, january)
        val ids = entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(1, entries.count { it.id == ZoneCatalog.UTC_ID })
        assertTrue("Asia/Calcutta" in ids || "Asia/Kolkata" in ids)
        assertFalse("US/Pacific" in ids)
        assertFalse("Etc/UCT" in ids)
        assertTrue(ids.all { it == ZoneCatalog.UTC_ID || runCatching { ZoneId.of(it) }.isSuccess })
        assertTrue(entries.size > 300)
    }

    @Test
    fun `entries are sorted by offset at the date, then city`() {
        val entries = ZoneCatalog.entries(us, january)
        val offsets = entries.map { it.offsetSeconds }
        assertEquals(offsets.sorted(), offsets)
        val berlinIndex = entries.indexOfFirst { it.city == "Berlin" }
        val londonIndex = entries.indexOfFirst { it.city == "London" }
        assertTrue(londonIndex < berlinIndex)
    }

    // ---- labels ----

    @Test
    fun `New York reads EST in January`() {
        val l = label("America/New_York")
        assertEquals("New York", l.city)
        assertEquals("EST", l.abbreviation)
        assertEquals("GMT-05:00", l.offset)
    }

    @Test
    fun `Berlin reads CET from the zone's country, CEST in July`() {
        assertEquals("Berlin", label("Europe/Berlin").city)
        assertEquals("CET", label("Europe/Berlin").abbreviation)
        assertEquals("GMT+01:00", label("Europe/Berlin").offset)
        assertEquals("CEST", label("Europe/Berlin", july).abbreviation)
        assertEquals("GMT+02:00", label("Europe/Berlin", july).offset)
    }

    @Test
    fun `Kolkata reads IST under either spelling`() {
        for (id in listOf("Asia/Kolkata", "Asia/Calcutta")) {
            val l = label(id)
            assertEquals("Kolkata", l.city)
            assertEquals("IST", l.abbreviation)
            assertEquals("GMT+05:30", l.offset)
        }
    }

    @Test
    fun `Tokyo has no abbreviation in English`() {
        val l = label("Asia/Tokyo")
        assertEquals("Tokyo", l.city)
        assertNull(l.abbreviation)
        assertEquals("GMT+09:00", l.offset)
    }

    @Test
    fun `the UTC family reads as UTC with a zero offset`() {
        for (id in listOf("Etc/UCT", "UTC", "Etc/UTC", "GMT", "Z")) {
            val l = label(id)
            assertEquals(id, ZoneCatalog.UTC_ID, l.id)
            assertEquals(id, "UTC", l.city)
            assertNull(id, l.abbreviation)
            assertEquals(id, "GMT+00:00", l.offset)
        }
    }

    @Test
    fun `London in January drops GMT and shows the zero offset`() {
        val l = label("Europe/London")
        assertEquals("London", l.city)
        assertNull(l.abbreviation)
        assertEquals("GMT+00:00", l.offset)
    }

    @Test
    fun `zero offset matches the locale's own pattern and digits`() {
        assertEquals("UTC+00.00", label("UTC", locale = Locale("fi", "FI")).offset)
        val arabic = label("UTC", locale = Locale("ar", "EG")).offset
        assertTrue(arabic, arabic.contains("٠٠"))
        assertFalse(arabic, arabic.contains("00"))
    }

    @Test
    fun `offset ids are labelled by their offset alone`() {
        for ((id, offset) in listOf(
            "Etc/GMT+5" to "GMT-05:00",
            "-05:00" to "GMT-05:00",
            "UTC+05:00" to "GMT+05:00",
            "EST" to "GMT-05:00",
            "HST" to "GMT-10:00",
        )) {
            val l = label(id)
            assertEquals(id, "", l.city)
            assertNull(id, l.zoneName)
            assertNull(id, l.abbreviation)
            assertEquals(id, offset, l.offset)
        }
    }

    @Test
    fun `an unreadable id has no label`() {
        assertNull(ZoneCatalog.label("Somewhere Standard Time", january, us))
        assertNull(ZoneCatalog.label(null, january, us))
    }

    @Test
    fun `zone names prefer the generic name, then the season's name`() {
        assertEquals("Central European Time", label("Europe/Berlin").zoneName)
        assertEquals("British Summer Time", label("Europe/London", july).zoneName)
        assertEquals("Germany", label("Europe/Berlin").country)
    }

    // ---- search ----

    private val entries by lazy { ZoneCatalog.entries(us, january) }
    private fun search(q: String) = ZoneCatalog.search(entries, q, us).map { it.city }

    @Test
    fun `utc terms put the UTC row first`() {
        for (q in listOf("utc", "UTC", "gmt", "z")) assertEquals(q, "UTC", search(q).first())
    }

    @Test
    fun `city prefix ranks by city`() {
        val results = search("ber")
        assertEquals("Berlin", results[0])
        assertTrue(results.indexOf("Bermuda") > 0)
    }

    @Test
    fun `exact abbreviation finds the zones that use it`() {
        assertTrue(search("cet").contains("Berlin"))
    }

    @Test
    fun `zone name and country match`() {
        assertTrue(search("central european").contains("Berlin"))
        assertTrue(search("germany").contains("Berlin"))
    }

    @Test
    fun `Windows id lists the zone ICU returns for it first`() {
        assertEquals("Chicago", search("Central Standard Time").first())
        assertEquals("Chicago", search("central standard time").first())
    }

    @Test
    fun `IANA and CLDR ids match`() {
        assertTrue(search("asia/kol").contains("Kolkata"))
        assertTrue(search("asia/calc").contains("Kolkata"))
    }

    @Test
    fun `offsets match in several forms`() {
        for (q in listOf("+5:30", "gmt+5:30", "+05:30", "UTC+5:30")) {
            assertTrue(q, search(q).contains("Kolkata"))
        }
    }

    @Test
    fun `aliases find their zone`() {
        assertEquals("Chicago", search("dallas").first())
        assertEquals("Kolkata", search("mumbai").first())
        assertEquals("Edmonton", search("calgary").first())
        assertEquals("Qatar", search("doha").first())
        assertEquals("Kuwait", search("kuwait city").first())
    }

    @Test
    fun `cities with their own zone never list a zone with another offset`() {
        for ((q, wrong) in listOf(
            "riyadh" to "Dubai", "edmonton" to "Vancouver", "brisbane" to "Sydney",
            "adelaide" to "Melbourne", "accra" to "Lagos",
        )) {
            assertFalse(q, search(q).contains(wrong))
        }
    }

    @Test
    fun `search ignores accents and case`() {
        assertTrue(ZoneCatalog.search(entries, "sao paulo", us).any { it.id == "America/Sao_Paulo" })
        assertTrue(ZoneCatalog.search(entries, "SÃO PAULO", us).any { it.id == "America/Sao_Paulo" })
    }

    @Test
    fun `blank query returns nothing and no match returns nothing`() {
        assertTrue(search("  ").isEmpty())
        assertTrue(search("qqqqzz").isEmpty())
    }

    @Test
    fun `entryFor finds the list row for any spelling`() {
        assertEquals("Kolkata", ZoneCatalog.entryFor(entries, "Asia/Kolkata")?.city)
        assertEquals(ZoneCatalog.UTC_ID, ZoneCatalog.entryFor(entries, "Etc/UCT")?.id)
        assertNull(ZoneCatalog.entryFor(entries, "Foo/Bar"))
    }

    @Test
    fun `entries on a clock-change day use the instant asked for`() {
        val beforeChange = Instant.parse("2026-03-08T05:00:00Z")
        val afterChange = Instant.parse("2026-03-08T18:00:00Z")
        val ny = { at: Instant -> ZoneCatalog.entries(us, at).single { it.id == "America/New_York" } }
        assertEquals("GMT-05:00", ny(beforeChange).offset)
        assertEquals("GMT-04:00", ny(afterChange).offset)
        assertEquals("EDT", ny(afterChange).abbreviation)
    }

    @Test
    fun `same-offset cities sort by city`() {
        val cities = entries.filter { it.offsetSeconds == 3600 }.map { it.city }
        assertTrue(cities.indexOf("Amsterdam") < cities.indexOf("Berlin"))
        assertTrue(cities.indexOf("Berlin") < cities.indexOf("Zurich"))
    }

    @Test
    fun `any word of an alias matches`() {
        assertEquals("Los Angeles", search("vegas").first())
        assertEquals("Los Angeles", search("francisco").first())
        assertEquals("America/Sao_Paulo", ZoneCatalog.search(entries, "janeiro", us).first().id)
    }

    @Test
    fun `common country names find their zones`() {
        for (q in listOf("england", "scotland", "wales", "northern ireland", "britain", "great britain", "british", "uk")) {
            assertEquals(q, "London", search(q).first())
        }
        assertEquals("United States", ZoneCatalog.search(entries, "usa", us).first().country)
        assertEquals("United States", ZoneCatalog.search(entries, "us", us).first().country)
        assertEquals("Dubai", search("uae").first())
        assertEquals("Amsterdam", search("holland").first())
        assertEquals("Prague", search("czech republic").first())
        assertEquals("Abidjan", search("ivory coast").first())
        assertTrue(search("burma").first() in setOf("Yangon", "Rangoon"))
    }

    @Test
    fun `an exact country name ranks above a city prefix`() {
        assertEquals("Kolkata", search("india").first())
        assertEquals("Shanghai", search("china").first())
    }

    @Test
    fun `aliases point at the city's own zone`() {
        assertEquals("Rome", search("milan").first())
        assertEquals("Madrid", search("barcelona").first())
        assertEquals("Ho Chi Minh City", search("hanoi").first())
        assertEquals(listOf("Monterrey"), search("monterrey"))
    }

    @Test
    fun `more major cities find their zone`() {
        for ((q, city) in listOf(
            "bombay" to "Kolkata", "madras" to "Kolkata", "peking" to "Shanghai", "nashville" to "Chicago",
            "kansas city" to "Chicago", "orlando" to "New York", "glasgow" to "London", "cardiff" to "London",
            "lyon" to "Paris", "naples" to "Rome", "cologne" to "Berlin", "stuttgart" to "Berlin",
            "san jose" to "Los Angeles", "sacramento" to "Los Angeles",
            "nuku'alofa" to "Tongatapu", "nukualofa" to "Tongatapu",
            "british columbia" to "Vancouver",
        )) {
            assertEquals(q, city, search(q).first())
        }
    }

    @Test
    fun `every alias names a listed zone`() {
        val listed = entries.map { it.id }.toSet()
        for (id in ZoneCatalog.aliasZoneIds()) assertTrue(id, id in listed)
    }

    @Test
    fun `gmt finds the zones on Greenwich time`() {
        val results = search("gmt")
        assertEquals("UTC", results.first())
        for (city in listOf("London", "Dublin", "Accra", "Abidjan")) assertTrue(city, city in results)
    }
}
