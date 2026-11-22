package org.onekash.kashcal.data.search

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holds app-calendar and device-calendar search to one matching rule: the same words find the
 * same events in both. Each subclass seeds events into one implementation and searches it;
 * [DeviceSearchMatchingContractTest] runs the cases against the platform model, which is where
 * the expected results come from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
abstract class SearchMatchingContractTest {

    protected data class Guest(val name: String?, val email: String)

    /** Seeds one event at the fixed test time. */
    protected abstract fun seed(
        title: String,
        location: String? = null,
        notes: String? = null,
        guests: List<Guest> = emptyList(),
        tags: List<String> = emptyList(),
    )

    /** Returns the titles [text] finds, sorted. */
    protected abstract fun search(text: String): List<String>

    private fun seedCorpus() {
        seed("Dentist")
        seed("Checkup", guests = listOf(Guest("Dr. Amara Lee", "amara@example.test")))
        seed("Ärzte-Termin")
        seed("Врач")
        seed("定期歯科検診")
        seed("Team lunch")
        seed("Lunch team")
        seed("Café Nero")
    }

    @Test
    fun `a whole word finds the event`() {
        seedCorpus()
        assertEquals(listOf("Dentist"), search("dentist"))
    }

    @Test
    fun `a word part finds the event`() {
        seedCorpus()
        assertEquals(listOf("Dentist"), search("tist"))
    }

    @Test
    fun `a guest's name finds the event`() {
        seedCorpus()
        assertEquals(listOf("Checkup"), search("lee"))
    }

    @Test
    fun `German case is folded`() {
        seedCorpus()
        assertEquals(listOf("Ärzte-Termin"), search("ärzte"))
    }

    @Test
    fun `Cyrillic case is folded`() {
        seedCorpus()
        assertEquals(listOf("Врач"), search("врач"))
        assertEquals(listOf("Врач"), search("ВРАЧ"))
    }

    @Test
    fun `one Japanese character finds the event`() {
        seedCorpus()
        assertEquals(listOf("定期歯科検診"), search("歯"))
    }

    @Test
    fun `a quoted phrase finds only that phrase`() {
        seedCorpus()
        assertEquals(listOf("Team lunch"), search("\"team lunch\""))
        assertEquals(listOf("Lunch team", "Team lunch"), search("team lunch"))
    }

    @Test
    fun `accents are not folded`() {
        seedCorpus()
        assertEquals(emptyList<String>(), search("cafe"))
        assertEquals(listOf("Café Nero"), search("café"))
    }

    @Test
    fun `each word may match a different field`() {
        seed(
            "Review",
            location = "Room 4",
            notes = "bring budget",
            guests = listOf(Guest("Ana Lima", "ana.lima@example.test")),
        )
        seed("Review only")

        assertEquals(listOf("Review"), search("review room budget lima"))
        assertEquals(listOf("Review"), search("review ana.lima@example"))
        assertEquals(emptyList<String>(), search("review room nowhere"))
    }

    @Test
    fun `percent underscore and hash match literally`() {
        seed("50% off")
        seed("500 off")
        seed("ab_c")
        seed("abxc")
        seed("Room x#4")
        seed("Room x4")

        assertEquals(listOf("50% off"), search("50%"))
        assertEquals(listOf("ab_c"), search("ab_c"))
        assertEquals(listOf("Room x#4"), search("x#4"))
    }

    @Test
    fun `an apostrophe splits words`() {
        seed("Call O'Brien")
        seed("Brien Kim")

        assertEquals(listOf("Call O'Brien"), search("O'Brien"))
    }

    @Test
    fun `a tag finds the events carrying it`() {
        seed("Checkup", tags = listOf("Doctor"))
        seed("Dentist", tags = listOf("Family", "doctor"))
        seed("Lunch", tags = listOf("Family"))
        seed("Doctor Who night")

        assertEquals(listOf("Checkup", "Dentist"), search("#doctor"))
    }

    @Test
    fun `a tag matches the whole name only`() {
        seed("Team", tags = listOf("Teamwork"))
        seed("Gym", tags = listOf("Workout"))
        seed("Office", tags = listOf("Work"))

        assertEquals(listOf("Office"), search("#work"))
    }

    @Test
    fun `a tag folds case beyond ASCII and keeps accents`() {
        seed("Russian", tags = listOf("Работа"))
        seed("Cafe", tags = listOf("Café"))

        assertEquals(listOf("Russian"), search("#работа"))
        assertEquals(emptyList<String>(), search("#cafe"))
    }

    @Test
    fun `a quoted tag name may hold spaces`() {
        seed("Planning", tags = listOf("Team Meeting"))
        seed("Lunch", tags = listOf("Team"))

        assertEquals(listOf("Planning"), search("#\"team meeting\""))
    }

    @Test
    fun `tags and words must all match`() {
        seed("Kids checkup", tags = listOf("Doctor"))
        seed("Adult checkup", tags = listOf("Doctor"))
        seed("Kids party", tags = listOf("Fun"))
        seed("Kids flu shot", tags = listOf("Doctor", "Kids"))

        assertEquals(listOf("Kids checkup", "Kids flu shot"), search("#doctor kids"))
        assertEquals(listOf("Kids flu shot"), search("#doctor #kids"))
        assertEquals(listOf("Kids flu shot"), search("kids #KIDS"))
    }

    @Test
    fun `a tag no event carries finds nothing`() {
        seed("Checkup", tags = listOf("Doctor"))

        assertEquals(emptyList<String>(), search("#dentist"))
        assertEquals(emptyList<String>(), search("#dentist checkup"))
    }
}
