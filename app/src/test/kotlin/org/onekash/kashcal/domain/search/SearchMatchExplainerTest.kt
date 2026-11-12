package org.onekash.kashcal.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.domain.search.SearchMatchExplainer.Guest

class SearchMatchExplainerTest {

    private fun explain(
        query: String,
        title: String,
        location: String? = null,
        notes: String? = null,
        guests: List<Guest> = emptyList(),
    ) = SearchMatchExplainer.explain(SearchQuery.of(query).words, title, location, notes, guests)

    @Test
    fun `a title holding every word needs no reason`() {
        assertNull(explain("dent", "Dentist", location = "Dental clinic"))
        assertNull(explain("ВРАЧ", "Врач"))
        assertNull(explain("team lunch", "Lunch with the team"))
    }

    @Test
    fun `a word found only in the location names the location`() {
        assertEquals(
            MatchReason(MatchField.LOCATION, "Riverside Clinic"),
            explain("checkup riverside", "Checkup", location = "Riverside Clinic", notes = "riverside entrance"),
        )
    }

    @Test
    fun `a word found only in the notes shows the notes around it`() {
        val notes = "Bring the insurance card and arrive ten minutes early. Ask about the dermatology referral, " +
            "then pick up the prescription at the pharmacy on the way home."
        val reason = explain("dermatology", "Checkup", notes = notes)!!

        assertEquals(MatchField.NOTES, reason.field)
        assertEquals(true, reason.text.contains("dermatology referral"))
        assertEquals(true, reason.text.length < notes.length)
        assertEquals(true, reason.text.startsWith("…") && reason.text.endsWith("…"))
    }

    @Test
    fun `notes on several lines read as one line`() {
        val reason = explain("parking", "Checkup", notes = "Room 4\nParking behind\nthe building")!!

        assertEquals(MatchReason(MatchField.NOTES, "Room 4 Parking behind the building"), reason)
    }

    @Test
    fun `a guest's name is shown when it matched`() {
        assertEquals(
            MatchReason(MatchField.GUEST, "Dr. Amara Lee"),
            explain("lee", "Checkup", guests = listOf(Guest("Ana Lima", "mailto:ana@example.test"), Guest("Dr. Amara Lee", "mailto:amara@example.test"))),
        )
    }

    @Test
    fun `a guest's email is shown without mailto when only it matched`() {
        assertEquals(
            MatchReason(MatchField.GUEST, "amara@example.test"),
            explain("amara@", "Checkup", guests = listOf(Guest("Dr. Lee", "mailto:amara@example.test"))),
        )
    }

    @Test
    fun `an address that is not an email never explains a match`() {
        assertNull(explain("uuid", "Checkup", guests = listOf(Guest(null, "urn:uuid:1234"))))
        assertNull(explain("mailto", "Checkup", guests = listOf(Guest(null, "mailto:amara@example.test"))))
    }

    @Test
    fun `location is named before notes and notes before guests`() {
        val guests = listOf(Guest("Lee Park", null))
        assertEquals(MatchField.LOCATION, explain("park", "Walk", location = "Park", notes = "park", guests = guests)!!.field)
        assertEquals(MatchField.NOTES, explain("park", "Walk", notes = "the park", guests = guests)!!.field)
        assertEquals(MatchField.GUEST, explain("park", "Walk", guests = guests)!!.field)
    }

    @Test
    fun `case is folded per letter but accents and dotless i are not`() {
        assertEquals(MatchField.LOCATION, explain("ärzte", "Termin", location = "ÄRZTEHAUS")!!.field)
        assertNull(explain("cafe", "Meet", location = "Café"))
        assertNull(explain("kirmizi", "Meet", location = "Kırmızı"))
    }

    @Test
    fun `the fold keeps every index in place`() {
        assertEquals(3, SearchFold.indexOf("Größe İstanbul", "ße", 0))
        assertEquals(6, SearchFold.indexOf("Größe İstanbul", "İst", 0))
        assertEquals(-1, SearchFold.indexOf("Größe", "sse", 0))
    }
}
