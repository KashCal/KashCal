package org.onekash.kashcal.domain.share

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ShareCardStylePickerTest {

    @Test
    fun `regular event title returns Regular`() {
        assertEquals(ShareCardStyle.Standard, ShareCardStylePicker.autoPickFor("Brunch at Sam's"))
    }

    @Test
    fun `null title returns Regular`() {
        assertEquals(ShareCardStyle.Standard, ShareCardStylePicker.autoPickFor(null))
    }

    @Test
    fun `empty title returns Regular`() {
        assertEquals(ShareCardStyle.Standard, ShareCardStylePicker.autoPickFor(""))
    }

    @Test
    fun `whitespace-only title returns Regular`() {
        assertEquals(ShareCardStyle.Standard, ShareCardStylePicker.autoPickFor("   "))
    }

    // ============== Emoji triggers ==============

    @Test
    fun `birthday cake emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("🎂 Maya turns 5"))
    }

    @Test
    fun `party popper emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("🎉 New Year"))
    }

    @Test
    fun `confetti ball emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("🎊 Anniversary"))
    }

    @Test
    fun `clinking glasses emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("🥂 toast night"))
    }

    @Test
    fun `balloon emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("🎈 surprise"))
    }

    @Test
    fun `bottle with popping cork emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("🍾 celebration time"))
    }

    @Test
    fun `ring emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("💍 engagement"))
    }

    @Test
    fun `graduation cap emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("🎓 commencement"))
    }

    @Test
    fun `baby emoji triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("👶 shower"))
    }

    // ============== Keyword triggers ==============

    @Test
    fun `lowercase birthday keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("birthday"))
    }

    @Test
    fun `uppercase BIRTHDAY keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("BIRTHDAY PARTY"))
    }

    @Test
    fun `mixed case Party keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("Party at the Smiths"))
    }

    @Test
    fun `wedding keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("My wedding day"))
    }

    @Test
    fun `anniversary keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("10th anniversary"))
    }

    @Test
    fun `baby shower keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("Sarah's baby shower"))
    }

    @Test
    fun `graduation keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("Graduation ceremony"))
    }

    @Test
    fun `new year keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("New Year's eve"))
    }

    @Test
    fun `housewarming keyword triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("our housewarming"))
    }

    // ============== Word-boundary guards ==============

    @Test
    fun `antiparty does not trigger Celebration`() {
        // "antiparty" contains "party" but isn't a celebration.
        assertEquals(ShareCardStyle.Standard, ShareCardStylePicker.autoPickFor("antiparty meeting"))
    }

    @Test
    fun `partygoers as standalone word does trigger Celebration`() {
        // "partygoers" begins with "party" on a word boundary — picker
        // matches stem prefixes for the "party" keyword. This documents
        // current intent: prefix-of-word matches trigger.
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("partygoers welcome"))
    }

    @Test
    fun `unbirthday does not trigger Celebration`() {
        assertEquals(ShareCardStyle.Standard, ShareCardStylePicker.autoPickFor("unbirthday surprise"))
    }

    @Test
    fun `keyword surrounded by punctuation triggers Celebration`() {
        assertEquals(ShareCardStyle.Celebration, ShareCardStylePicker.autoPickFor("Sarah's-birthday"))
    }

    // ============== Locale safety ==============

    @Test
    fun `Turkish locale uppercase does not break keyword matching`() {
        // Turkish has the famous "i / İ" and "ı / I" mappings. The picker
        // must lowercase using a locale-independent rule (e.g., Locale.ROOT).
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // The string "BIRTHDAY" upper→lower in tr-TR yields "bırthday"
            // (dotless ı), which would NOT match "birthday" if we used
            // locale-aware case folding. Verify Locale.ROOT is used.
            assertEquals(
                ShareCardStyle.Celebration,
                ShareCardStylePicker.autoPickFor("BIRTHDAY")
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
