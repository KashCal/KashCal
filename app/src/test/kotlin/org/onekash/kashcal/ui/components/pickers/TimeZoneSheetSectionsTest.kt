package org.onekash.kashcal.ui.components.pickers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.util.zone.ZoneCatalog
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

/**
 * Tests [timeZoneSheetRows]: which sections the time zone sheet shows, which row is selected,
 * what a tap on each row does, and when the "From the calendar" subtitle shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class TimeZoneSheetSectionsTest {

    private lateinit var savedLocale: Locale
    private val at = Instant.parse("2026-01-15T15:00:00Z")
    private val entries by lazy { ZoneCatalog.entries(Locale.US, at) }

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    private fun rows(
        eventZone: String? = null,
        sourceZone: String? = null,
        deviceZone: String = "America/Chicago",
        recents: List<String> = emptyList(),
        entries: List<ZoneCatalog.Entry>? = this.entries,
        query: String = "",
    ) = timeZoneSheetRows(eventZone, sourceZone, deviceZone, recents, entries, query, at, Locale.US)

    private fun List<ZoneSheetRow>.headers() = filterIsInstance<ZoneSheetRow.Header>().map { it.section }
    private fun List<ZoneSheetRow>.zones(section: ZoneSection) =
        filterIsInstance<ZoneSheetRow.Zone>().filter { it.section == section }

    @Test
    fun `an event zone shows This event, Device, and All`() {
        val r = rows(eventZone = "Europe/Berlin")
        assertEquals(listOf(ZoneSection.THIS_EVENT, ZoneSection.DEVICE, ZoneSection.ALL), r.headers())
        val thisEvent = r.zones(ZoneSection.THIS_EVENT).single()
        assertEquals("Berlin", thisEvent.entry.city)
        assertTrue(thisEvent.selected)
        assertEquals(ZonePick.Keep, thisEvent.pick)
        val device = r.zones(ZoneSection.DEVICE).single()
        assertFalse(device.selected)
        assertEquals(ZonePick.Device, device.pick)
    }

    @Test
    fun `no event zone and no source selects the Device row`() {
        val r = rows()
        assertFalse(ZoneSection.THIS_EVENT in r.headers())
        val device = r.zones(ZoneSection.DEVICE).single()
        assertTrue(device.selected)
        assertEquals(ZonePick.Keep, device.pick)
    }

    @Test
    fun `an unresolved source zone is This event, selected, shown in device time`() {
        val r = rows(sourceZone = "Foo/Bar")
        assertEquals(ZoneSection.THIS_EVENT, r.headers().first())
        val unresolved = r.filterIsInstance<ZoneSheetRow.Unresolved>().single()
        assertEquals("Foo/Bar", unresolved.sourceId)
        assertEquals(ZonePick.Keep, unresolved.pick)
        val device = r.zones(ZoneSection.DEVICE).single()
        assertFalse(device.selected)
        assertEquals(ZonePick.Device, device.pick)
    }

    @Test
    fun `the selected zone in the list keeps the form as it is`() {
        val r = rows(eventZone = "Asia/Kolkata")
        val kolkata = r.zones(ZoneSection.ALL).single { it.entry.city == "Kolkata" }
        assertTrue(kolkata.selected)
        assertEquals(ZonePick.Keep, kolkata.pick)
        val tokyo = r.zones(ZoneSection.ALL).single { it.entry.city == "Tokyo" }
        assertFalse(tokyo.selected)
        assertEquals(ZonePick.Set(tokyo.entry.id), tokyo.pick)
    }

    @Test
    fun `own Asia Kolkata event has no From the calendar line`() {
        assertFalse(ZoneSection.THIS_EVENT in rows(eventZone = "Asia/Kolkata", deviceZone = "Asia/Kolkata").headers())
        assertNull(rows(eventZone = "Asia/Kolkata").zones(ZoneSection.THIS_EVENT).single().fromCalendar)
    }

    @Test
    fun `an alias spelling from a calendar shows From the calendar`() {
        val row = rows(eventZone = "US/Pacific").zones(ZoneSection.THIS_EVENT).single()
        assertEquals("Los Angeles", row.entry.city)
        assertEquals("US/Pacific", row.fromCalendar)
    }

    @Test
    fun `recents leave out zones already shown above and keep their order`() {
        val r = rows(
            eventZone = "Europe/Berlin",
            recents = listOf("Asia/Tokyo", "Europe/Berlin", "America/Chicago", "Asia/Kolkata"),
        )
        assertEquals(listOf("Tokyo", "Kolkata"), r.zones(ZoneSection.RECENT).map { it.entry.city })
        assertTrue(r.zones(ZoneSection.RECENT).all { it.pick is ZonePick.Set })
    }

    @Test
    fun `no recents shows no Recent header`() {
        assertFalse(ZoneSection.RECENT in rows(recents = listOf("America/Chicago")).headers())
    }

    @Test
    fun `All lists every entry including UTC`() {
        val all = rows().zones(ZoneSection.ALL)
        assertEquals(entries.size, all.size)
        assertEquals(1, all.count { it.entry.id == ZoneCatalog.UTC_ID })
    }

    @Test
    fun `entries still loading show a loading row, not the empty state`() {
        val r = rows(entries = null)
        assertTrue(r.any { it is ZoneSheetRow.Loading })
        assertFalse(r.any { it is ZoneSheetRow.Empty })
        assertEquals(ZoneSection.DEVICE, r.zones(ZoneSection.DEVICE).single().section)
        assertTrue(rows(entries = null, query = "ber").single() is ZoneSheetRow.Loading)
    }

    @Test
    fun `a query shows ranked results without headers`() {
        val r = rows(eventZone = "Europe/Berlin", query = "ber")
        assertTrue(r.headers().isEmpty())
        val results = r.zones(ZoneSection.RESULTS)
        assertEquals("Berlin", results.first().entry.city)
        assertTrue(results.first().selected)
    }

    @Test
    fun `a query with no match shows the empty state`() {
        assertEquals(listOf(ZoneSheetRow.Empty), rows(query = "qqqqzz"))
    }

    @Test
    fun `an alias of the device zone shows as the Device row`() {
        val r = rows(eventZone = "US/Pacific", deviceZone = "America/Los_Angeles")
        assertFalse(ZoneSection.THIS_EVENT in r.headers())
        assertTrue(r.zones(ZoneSection.DEVICE).single().selected)
    }

    @Test
    fun `an event in the device zone shows only the selected Device row`() {
        val r = rows(eventZone = "America/Chicago")
        assertFalse(ZoneSection.THIS_EVENT in r.headers())
        val device = r.zones(ZoneSection.DEVICE).single()
        assertTrue(device.selected)
        assertEquals(ZonePick.Keep, device.pick)
    }
}
