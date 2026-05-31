package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextNormalizerTest {

    @Test
    fun `short input with no URL returns Short with null location`() {
        val result = SharedTextNormalizer.normalize("Lunch tomorrow at 1pm")
        assertEquals(NormalizedShareText.Short("Lunch tomorrow at 1pm", null), result)
    }

    @Test
    fun `short input with https URL strips URL into location`() {
        val result = SharedTextNormalizer.normalize("Lunch https://meet.zoom.us/123 tomorrow")
        assertEquals(
            NormalizedShareText.Short("Lunch tomorrow", "https://meet.zoom.us/123"),
            result
        )
    }

    @Test
    fun `short input with http URL strips URL into location`() {
        val result = SharedTextNormalizer.normalize("Standup http://example.com/x at 10am")
        assertEquals(
            NormalizedShareText.Short("Standup at 10am", "http://example.com/x"),
            result
        )
    }

    @Test
    fun `bare filename like notes_txt is not detected as URL`() {
        val result = SharedTextNormalizer.normalize("Review notes.txt at 3pm")
        assertEquals(NormalizedShareText.Short("Review notes.txt at 3pm", null), result)
    }

    @Test
    fun `bare domain without scheme is not detected as URL`() {
        val result = SharedTextNormalizer.normalize("Visit www.example.com")
        assertEquals(NormalizedShareText.Short("Visit www.example.com", null), result)
    }

    @Test
    fun `URL only input keeps URL in location and leaves text empty`() {
        val result = SharedTextNormalizer.normalize("https://only-url.com/path")
        assertEquals(NormalizedShareText.Short("", "https://only-url.com/path"), result)
    }

    @Test
    fun `multi-line short input joins newlines to spaces`() {
        val result = SharedTextNormalizer.normalize("Standup\nTomorrow 10am\nDaily")
        assertEquals(
            NormalizedShareText.Short("Standup Tomorrow 10am Daily", null),
            result
        )
    }

    @Test
    fun `multi-line short input joins newlines and strips URL`() {
        val result = SharedTextNormalizer.normalize("Standup\nTomorrow 10am\nhttps://zoom.us/x")
        assertEquals(
            NormalizedShareText.Short("Standup Tomorrow 10am", "https://zoom.us/x"),
            result
        )
    }

    @Test
    fun `long input over 500 chars returns Long`() {
        val firstLine = "Project status review with the cross-team product committee"
        val body = "Body line ".repeat(60) // ~600 chars body
        val input = "$firstLine\n$body"

        val result = SharedTextNormalizer.normalize(input)

        assertTrue(result is NormalizedShareText.Long)
        result as NormalizedShareText.Long
        // Title is first non-blank line truncated at 80 chars
        assertTrue(
            "title should start with first line, was: ${result.title}",
            firstLine.startsWith(result.title) || result.title == firstLine
        )
        assertTrue("title <= 80 chars, was ${result.title.length}", result.title.length <= 80)
        // Description is the entire original text with newlines preserved
        assertEquals(input, result.description)
    }

    @Test
    fun `long input with no newline truncates title at 80 chars`() {
        val blob = "x".repeat(600)
        val result = SharedTextNormalizer.normalize(blob)
        assertTrue(result is NormalizedShareText.Long)
        result as NormalizedShareText.Long
        assertEquals(80, result.title.length)
        assertEquals(blob, result.description)
    }

    @Test
    fun `long input strips URL into location but preserves it in description`() {
        val firstLine = "Quarterly review meeting"
        val urlLine = "Join here https://meet.example.com/quarterly-2026"
        val padding = "Detail line ".repeat(50) // pushes total > 500
        val input = "$firstLine\n$urlLine\n$padding"

        val result = SharedTextNormalizer.normalize(input)
        assertTrue(result is NormalizedShareText.Long)
        result as NormalizedShareText.Long
        assertEquals("https://meet.example.com/quarterly-2026", result.location)
        assertTrue(
            "description preserves the original including URL",
            result.description.contains("https://meet.example.com/quarterly-2026")
        )
    }

    @Test
    fun `length triage uses post-newline-strip length`() {
        // 250-char line repeated twice with newline = 501 raw chars but 500 once joined.
        // This codifies the rule: the cap is on logical length the parser will see.
        val line = "a".repeat(250)
        val input = "$line\n$line"
        val result = SharedTextNormalizer.normalize(input)
        // After newline-strip the joined string is 501 chars (250 + space + 250). Long.
        assertTrue(result is NormalizedShareText.Long)
    }

    @Test
    fun `boundary at exactly 500 chars stays Short`() {
        val input = "a".repeat(500)
        val result = SharedTextNormalizer.normalize(input)
        assertTrue(result is NormalizedShareText.Short)
    }

    @Test
    fun `boundary at 501 chars goes Long`() {
        val input = "a".repeat(501)
        val result = SharedTextNormalizer.normalize(input)
        assertTrue(result is NormalizedShareText.Long)
    }

    @Test
    fun `URL embedded mid-sentence is extracted`() {
        val result = SharedTextNormalizer.normalize("Visit https://x.com tomorrow")
        assertEquals(
            NormalizedShareText.Short("Visit tomorrow", "https://x.com"),
            result
        )
    }

    @Test
    fun `trailing period is stripped from URL`() {
        val result = SharedTextNormalizer.normalize("Notes https://x.com.")
        assertTrue(result is NormalizedShareText.Short)
        result as NormalizedShareText.Short
        assertEquals("https://x.com", result.location)
    }

    @Test
    fun `trailing comma is stripped from URL`() {
        val result = SharedTextNormalizer.normalize("Standup https://x.com, see you")
        assertTrue(result is NormalizedShareText.Short)
        result as NormalizedShareText.Short
        assertEquals("https://x.com", result.location)
    }

    @Test
    fun `trailing closing paren is stripped from URL`() {
        val result = SharedTextNormalizer.normalize("(see https://x.com)")
        assertTrue(result is NormalizedShareText.Short)
        result as NormalizedShareText.Short
        assertEquals("https://x.com", result.location)
    }

    @Test
    fun `trailing semicolon and quote are stripped from URL`() {
        val result = SharedTextNormalizer.normalize("Open https://x.com\"; later")
        assertTrue(result is NormalizedShareText.Short)
        result as NormalizedShareText.Short
        assertEquals("https://x.com", result.location)
    }

    @Test
    fun `URL with query parameters preserves the query`() {
        val result = SharedTextNormalizer.normalize("https://maps.example.com/?q=Cafe")
        assertTrue(result is NormalizedShareText.Short)
        result as NormalizedShareText.Short
        assertEquals("https://maps.example.com/?q=Cafe", result.location)
    }

    @Test
    fun `only first URL is extracted when multiple present`() {
        val result = SharedTextNormalizer.normalize(
            "https://first.com and https://second.com"
        )
        assertTrue(result is NormalizedShareText.Short)
        result as NormalizedShareText.Short
        assertEquals("https://first.com", result.location)
        // Second URL stays in text — keeps the contract simple (only one location field).
        assertTrue(result.text.contains("https://second.com"))
    }

    @Test
    fun `empty input returns Short with empty text and null location`() {
        val result = SharedTextNormalizer.normalize("")
        assertEquals(NormalizedShareText.Short("", null), result)
    }

    @Test
    fun `whitespace only input returns Short with empty text after trim`() {
        val result = SharedTextNormalizer.normalize("   \n\t  ")
        assertTrue(result is NormalizedShareText.Short)
        result as NormalizedShareText.Short
        // Caller (ShareTextIntentParser) is responsible for dropping blank shares.
        // Here we just ensure no crash and that the text is whitespace or empty.
        assertTrue(result.text.isBlank())
        assertNull(result.location)
    }
}
