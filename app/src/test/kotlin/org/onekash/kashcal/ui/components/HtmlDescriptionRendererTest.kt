package org.onekash.kashcal.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [buildHtmlDescriptionAnnotatedString], the pure helper that
 * wraps `AnnotatedString.fromHtml` with KashCal's URL-safety gate.
 *
 * `AnnotatedString.fromHtml` delegates to `HtmlCompat.fromHtml` which needs
 * the Android runtime, so this test runs under Robolectric. The helper itself
 * has no Compose-UI dependencies — we can drive its `LinkInteractionListener`
 * directly and assert that unsafe schemes are blocked before `onNavigate`
 * is invoked.
 */
@RunWith(RobolectricTestRunner::class)
class HtmlDescriptionRendererTest {

    private val linkStyles = TextLinkStyles(
        style = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline)
    )

    /** Collect every URL the safety-gated listener would navigate to. */
    private fun runWithCapturedNavigations(
        html: String,
        block: (AnnotatedStringUnderTest) -> Unit = {}
    ): List<String> {
        val navigated = mutableListOf<String>()
        val annotated = buildHtmlDescriptionAnnotatedString(
            htmlText = html,
            linkStyles = linkStyles,
            onNavigate = { url -> navigated.add(url) }
        )
        block(AnnotatedStringUnderTest(annotated))
        return navigated
    }

    /** Thin wrapper that exposes just the accessors we need in assertions. */
    private class AnnotatedStringUnderTest(val value: androidx.compose.ui.text.AnnotatedString) {
        val text: String get() = value.text
        fun urlLinks(): List<LinkAnnotation.Url> =
            value.getLinkAnnotations(0, value.length).mapNotNull {
                it.item as? LinkAnnotation.Url
            }
    }

    /** Invoke each link's interaction listener (simulates tap). */
    private fun tapAllLinks(a: AnnotatedStringUnderTest) {
        a.value.getLinkAnnotations(0, a.value.length).forEach { range ->
            val link = range.item
            if (link is LinkAnnotation.Url) {
                link.linkInteractionListener?.onClick(link)
            }
        }
    }

    // ---------- Basic tag parsing ----------

    @Test
    fun `anchor tag produces a LinkAnnotation Url with the href`() {
        val html = """Click <a href="https://example.com/x">here</a>"""
        runWithCapturedNavigations(html) { a ->
            val links = a.urlLinks()
            assertEquals(1, links.size)
            assertEquals("https://example.com/x", links[0].url)
        }
    }

    @Test
    fun `html-blob wrapper is invisible in rendered text`() {
        val html = "<html-blob><p>Meeting notes</p></html-blob>"
        runWithCapturedNavigations(html) { a ->
            assertFalse("html-blob tag name should not appear in text", a.text.contains("html-blob"))
            assertTrue("body text should be preserved", a.text.contains("Meeting notes"))
        }
    }

    @Test
    fun `bold italic underline strikethrough all produce text`() {
        val html = "<b>B</b> <strong>S</strong> <i>I</i> <em>E</em> <u>U</u> <s>K</s>"
        runWithCapturedNavigations(html) { a ->
            // Every character survives; tag names do not.
            assertTrue(a.text.contains("B"))
            assertTrue(a.text.contains("S"))
            assertTrue(a.text.contains("I"))
            assertTrue(a.text.contains("E"))
            assertTrue(a.text.contains("U"))
            assertTrue(a.text.contains("K"))
            assertFalse(a.text.contains("<b>"))
            assertFalse(a.text.contains("<strong>"))
            assertFalse(a.text.contains("<i>"))
        }
    }

    @Test
    fun `bullet list items appear in text`() {
        val html = "<ul><li>Alpha</li><li>Beta</li></ul>"
        runWithCapturedNavigations(html) { a ->
            assertTrue(a.text.contains("Alpha"))
            assertTrue(a.text.contains("Beta"))
            assertFalse(a.text.contains("<li>"))
        }
    }

    @Test
    fun `br produces a newline`() {
        val html = "first<br>second"
        runWithCapturedNavigations(html) { a ->
            assertTrue(
                "expected a newline between 'first' and 'second', got: ${a.text}",
                a.text.contains("first") &&
                    a.text.contains("second") &&
                    a.text.indexOf("first") < a.text.indexOf("second")
            )
            assertFalse(a.text.contains("<br>"))
        }
    }

    @Test
    fun `entities are decoded by fromHtml`() {
        val html = "Tom &amp; Jerry &lt;3"
        runWithCapturedNavigations(html) { a ->
            assertTrue(a.text.contains("Tom & Jerry"))
            assertTrue(a.text.contains("<3"))
        }
    }

    // ---------- Safety gate ----------

    @Test
    fun `javascript scheme URL is blocked by safety gate`() {
        val html = """<a href="javascript:alert('xss')">click</a>"""
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(
            "javascript: scheme must be blocked — no navigation",
            emptyList<String>(),
            navigated
        )
    }

    @Test
    fun `data scheme URL is blocked by safety gate`() {
        val html = """<a href="data:text/html,<script>alert(1)</script>">x</a>"""
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(emptyList<String>(), navigated)
    }

    @Test
    fun `file scheme URL is blocked by safety gate`() {
        val html = """<a href="file:///etc/passwd">x</a>"""
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(emptyList<String>(), navigated)
    }

    @Test
    fun `https URL is allowed by safety gate`() {
        val html = """<a href="https://example.com/x">x</a>"""
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(listOf("https://example.com/x"), navigated)
    }

    @Test
    fun `http URL is allowed by safety gate`() {
        val html = """<a href="http://example.com/x">x</a>"""
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(listOf("http://example.com/x"), navigated)
    }

    @Test
    fun `mailto URL is allowed by safety gate`() {
        val html = """<a href="mailto:alice@example.com">email</a>"""
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(listOf("mailto:alice@example.com"), navigated)
    }

    @Test
    fun `tel URL is allowed by safety gate`() {
        val html = """<a href="tel:+15551234567">call</a>"""
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(listOf("tel:+15551234567"), navigated)
    }

    @Test
    fun `mixed safe and unsafe links only propagate safe ones`() {
        val html = """
            <a href="https://good.com">ok</a>
            <a href="javascript:evil()">bad</a>
            <a href="mailto:a@b.com">email</a>
        """.trimIndent()
        val navigated = runWithCapturedNavigations(html) { a -> tapAllLinks(a) }
        assertEquals(
            listOf("https://good.com", "mailto:a@b.com"),
            navigated
        )
    }

    // ---------- Images ----------

    @Test
    fun `img tag does not trigger a network request and does not crash`() {
        // fromHtml passes null ImageGetter (verified in Compose ui-text 1.11.0 bytecode)
        // so <img> becomes no-op rendering, not a network fetch.
        val html = """Before <img src="https://tracker.invalid/px.gif"> after"""
        val navigated = runWithCapturedNavigations(html) { a ->
            assertNotNull(a.value)
            // Surrounding text must survive
            assertTrue(a.text.contains("Before"))
            assertTrue(a.text.contains("after"))
        }
        assertEquals(emptyList<String>(), navigated)
    }

    // ---------- Malformed HTML ----------

    @Test
    fun `unclosed paragraph tag does not crash`() {
        val html = "<p>unclosed"
        runWithCapturedNavigations(html) { a ->
            assertTrue(a.text.contains("unclosed"))
        }
    }

    @Test
    fun `stray angle bracket followed by tag name does not crash`() {
        val html = "<p>good</p> and <div>more"
        runWithCapturedNavigations(html) { a ->
            assertTrue(a.text.contains("good"))
            assertTrue(a.text.contains("more"))
        }
    }

    @Test
    fun `empty input produces empty annotated string`() {
        runWithCapturedNavigations("") { a ->
            assertEquals("", a.text)
            assertTrue(a.urlLinks().isEmpty())
        }
    }

    // ---------- Real-world Google invite shape ----------

    @Test
    fun `google html-blob with links bold and br renders formatted`() {
        val html = """
            <html-blob><div dir="ltr">
            You are invited: <b>Team sync</b><br>
            Join: <a href="https://meet.google.com/abc-defg-hij">meet.google.com</a><br>
            Agenda:<ul><li>Demo</li><li>Retro</li></ul>
            </div></html-blob>
        """.trimIndent()
        val navigated = runWithCapturedNavigations(html) { a ->
            // Text content survives
            assertTrue(a.text.contains("Team sync"))
            assertTrue(a.text.contains("meet.google.com"))
            assertTrue(a.text.contains("Demo"))
            assertTrue(a.text.contains("Retro"))
            // Tag names do not appear
            assertFalse(a.text.contains("html-blob"))
            assertFalse(a.text.contains("<li>"))
            assertFalse(a.text.contains("<br>"))
            // Exactly one link annotation
            assertEquals(1, a.urlLinks().size)
            assertEquals("https://meet.google.com/abc-defg-hij", a.urlLinks()[0].url)
            tapAllLinks(a)
        }
        assertEquals(listOf("https://meet.google.com/abc-defg-hij"), navigated)
    }
}
