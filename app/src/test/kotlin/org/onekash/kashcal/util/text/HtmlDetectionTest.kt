package org.onekash.kashcal.util.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [looksLikeHtml], the gate that decides whether a description
 * is routed through the HTML rendering path or the plain-text path.
 *
 * Design intent (from issue #207): plain text containing `<` must NOT
 * be treated as HTML, because `HtmlCompat.fromHtml` would drop those
 * characters. Only structural tags (`<a href`, `<br>`, `<p>`, …) should
 * trigger the HTML branch.
 */
class HtmlDetectionTest {

    // ---------- Plain text with `<` that must NOT be treated as HTML ----------

    @Test
    fun `angle bracket followed by digit is not HTML`() {
        assertFalse(looksLikeHtml("see you <3 tomorrow"))
    }

    @Test
    fun `math expression with less-than is not HTML`() {
        assertFalse(looksLikeHtml("if a < b then return"))
    }

    @Test
    fun `arrow-like sequence is not HTML`() {
        assertFalse(looksLikeHtml("<- go back"))
        assertFalse(looksLikeHtml("meet at 3 < make sure you're on time"))
    }

    @Test
    fun `empty and blank strings are not HTML`() {
        assertFalse(looksLikeHtml(""))
        assertFalse(looksLikeHtml("   "))
    }

    @Test
    fun `normal description without any angle brackets is not HTML`() {
        assertFalse(looksLikeHtml("Team sync at 3pm. Bring notes."))
    }

    @Test
    fun `unrelated tag-looking word is not HTML`() {
        // `<foo>` is not a known structural tag
        assertFalse(looksLikeHtml("version <foo> not released"))
    }

    // ---------- Real HTML that MUST be treated as HTML ----------

    @Test
    fun `anchor tag is HTML`() {
        assertTrue(looksLikeHtml("""Click <a href="https://x.com">here</a>"""))
    }

    @Test
    fun `anchor tag with uppercase is HTML`() {
        assertTrue(looksLikeHtml("""<A HREF="https://x.com">X</A>"""))
    }

    @Test
    fun `google html-blob wrapper is HTML`() {
        assertTrue(looksLikeHtml("<html-blob><div>body</div></html-blob>"))
    }

    @Test
    fun `br tag with and without slash is HTML`() {
        assertTrue(looksLikeHtml("line one<br>line two"))
        assertTrue(looksLikeHtml("line one<br/>line two"))
        assertTrue(looksLikeHtml("line one<br />line two"))
    }

    @Test
    fun `paragraph div span are HTML`() {
        assertTrue(looksLikeHtml("<p>paragraph</p>"))
        assertTrue(looksLikeHtml("<div>block</div>"))
        assertTrue(looksLikeHtml("""<span style="x">styled</span>"""))
    }

    @Test
    fun `bold italic underline strike are HTML`() {
        assertTrue(looksLikeHtml("<b>bold</b>"))
        assertTrue(looksLikeHtml("<strong>bold</strong>"))
        assertTrue(looksLikeHtml("<i>italic</i>"))
        assertTrue(looksLikeHtml("<em>italic</em>"))
        assertTrue(looksLikeHtml("<u>under</u>"))
        assertTrue(looksLikeHtml("<s>strike</s>"))
    }

    @Test
    fun `headings are HTML`() {
        assertTrue(looksLikeHtml("<h1>Title</h1>"))
        assertTrue(looksLikeHtml("<h6>Smallest</h6>"))
    }

    @Test
    fun `lists are HTML`() {
        assertTrue(looksLikeHtml("<ul><li>one</li></ul>"))
        assertTrue(looksLikeHtml("<ol><li>one</li></ol>"))
    }

    @Test
    fun `img and table and blockquote and pre and code are HTML`() {
        assertTrue(looksLikeHtml("""<img src="http://x/1.png">"""))
        assertTrue(looksLikeHtml("<table><tr><td>x</td></tr></table>"))
        assertTrue(looksLikeHtml("<blockquote>q</blockquote>"))
        assertTrue(looksLikeHtml("<pre>code</pre>"))
        assertTrue(looksLikeHtml("<code>inline</code>"))
    }

    @Test
    fun `font tag is HTML`() {
        assertTrue(looksLikeHtml("""<font color="red">red</font>"""))
    }

    @Test
    fun `HTML comment is HTML`() {
        assertTrue(looksLikeHtml("<!-- a comment --> text"))
    }

    // ---------- Malformed but still HTML ----------

    @Test
    fun `unclosed known tag is still HTML`() {
        assertTrue(looksLikeHtml("<p>unclosed"))
    }

    @Test
    fun `known tag in middle of text is HTML`() {
        assertTrue(looksLikeHtml("Description:\n<br>See link below"))
    }

    @Test
    fun `closing tag alone is HTML`() {
        // A stray `</p>` still marks this as HTML - user probably intends a tag
        assertTrue(looksLikeHtml("</p>"))
    }

    // ---------- Similar-looking false negatives ----------

    @Test
    fun `text ending with less-than is not HTML`() {
        assertFalse(looksLikeHtml("something went wrong <"))
    }

    @Test
    fun `angle bracket with whitespace after is not HTML`() {
        // `< br>` (with leading space inside tag) is not standard; treat as non-HTML
        assertFalse(looksLikeHtml("a < b and c > d"))
    }

    // ---------- Single-letter tag false positives (critical) ----------
    // `a`, `b`, `i`, `u`, `s`, `p` are real HTML tags AND real English words.
    // Without tightening the regex, sentences like "I use <a lot" would be
    // sent through HtmlCompat.fromHtml, which would silently drop everything
    // from `<a` onward.

    @Test
    fun `short-tag letter followed by space and word is not HTML`() {
        assertFalse(looksLikeHtml("I use <a lot of options"))
        assertFalse(looksLikeHtml("<i am confused"))
        assertFalse(looksLikeHtml("send <b if confirmed"))
        assertFalse(looksLikeHtml("I need <u to help"))
        assertFalse(looksLikeHtml("skip <p if not ready"))
    }

    @Test
    fun `tag name must be immediately after opening angle bracket`() {
        // `< b>` (with a leading space inside the tag) is invalid HTML, treat as plain
        assertFalse(looksLikeHtml("a < b > c"))
        assertFalse(looksLikeHtml("range: < br"))
    }
}
