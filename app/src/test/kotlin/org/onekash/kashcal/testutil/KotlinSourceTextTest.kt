package org.onekash.kashcal.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [KotlinSourceText], the reader behind the source-scanning guard tests:
 * comments and the contents of string and character literals must disappear,
 * code must not, and line numbers must survive so violations point at the right
 * line. Also covers the end-in-code flag, and [KotlinSourceText.references]
 * matching across line breaks and refusing a source the reader lost track of.
 */
class KotlinSourceTextTest {

    private fun code(source: String): List<String> = KotlinSourceText.codeLines(source)

    private fun oneLine(source: String): String = code(source).single()

    @Test
    fun `line comments are blanked, code before them is kept`() {
        assertEquals("", oneLine("// Secret").trim())
        assertEquals("val a = 1", oneLine("val a = 1 // Secret").trim())
    }

    @Test
    fun `block comments are blanked and code on either side is kept`() {
        assertEquals("val a = b", oneLine("/* note */ val a = /* x */ b").replace(Regex("\\s+"), " ").trim())
        assertEquals("val a = 1", oneLine("/**/val a = 1").trim())
    }

    @Test
    fun `code after a multi-line comment closes is kept`() {
        val lines = code("/*\n Secret\n */ val a = Code\nval b = 2")
        assertEquals(4, lines.size)
        assertFalse(lines[1].contains("Secret"))
        assertEquals("val a = Code", lines[2].trim())
        assertEquals("val b = 2", lines[3].trim())
    }

    @Test
    fun `nested block comments only close at the outer end`() {
        val lines = code("/* outer /* inner */ still Secret */ val a = Code")
        assertFalse(lines[0].contains("Secret"))
        assertEquals("val a = Code", lines[0].trim())
    }

    @Test
    fun `kdoc is blanked`() {
        val lines = code("/**\n * [Secret]\n */\nclass Code")
        assertFalse(lines.any { it.contains("Secret") })
        assertEquals("class Code", lines[3].trim())
    }

    @Test
    fun `string contents are blanked, including escaped quotes`() {
        val line = oneLine("val s = \"Secret \\\" still Secret\"; val t = Code")
        assertFalse(line.contains("Secret"))
        assertTrue(line.contains("val t = Code"))
    }

    @Test
    fun `a URL in a string does not hide the code after it`() {
        val line = oneLine("val u = \"https://example.test\"; val r = Code")
        assertFalse(line.contains("example"))
        assertTrue(line.contains("val r = Code"))
    }

    @Test
    fun `raw strings over several lines are blanked`() {
        val lines = code("val s = \"\"\"\nSecret \" quote\n\"\"\"\nval t = Code")
        assertFalse(lines[1].contains("Secret"))
        assertEquals("val t = Code", lines[3].trim())
    }

    @Test
    fun `a raw string closed by extra quotes ends at the last three`() {
        val line = oneLine("val s = \"\"\"\"Secret\"\"\"\"; val t = Code")
        assertFalse(line.contains("Secret"))
        assertTrue(line.contains("val t = Code"))
    }

    @Test
    fun `templates keep their code, even nested`() {
        assertTrue(oneLine("val s = \"a ${'$'}{a.Code} b\"").contains("a.Code"))
        assertTrue(oneLine("val s = \"a ${'$'}Code b\"").contains("Code"))
        val nested = oneLine("val s = \"${'$'}{xs.map { \"${'$'}{it.Code} Secret\" }} Secret\"; val t = After")
        assertTrue(nested.contains("xs.map"))
        assertTrue(nested.contains("it.Code"))
        assertFalse(nested.contains("Secret"))
        assertTrue(nested.contains("val t = After"))
    }

    @Test
    fun `a dollar template inside a raw string does not end it`() {
        val line = oneLine("val s = \"\"\"${'$'}{'${'$'}'}Secret\"\"\"; val t = Code")
        assertFalse(line.contains("Secret"))
        assertTrue(line.contains("val t = Code"))
    }

    @Test
    fun `character literals are blanked without opening a string`() {
        val line = oneLine("val q = '\"'; val b = '\\\\'; val e = '\\''; val t = Code")
        assertTrue(line.contains("val t = Code"))
        assertFalse(line.contains("\"'"))
    }

    @Test
    fun `quotes inside a backtick identifier are not a string`() {
        val line = oneLine("fun `a \" quote`() = Code")
        assertTrue(line.contains("= Code"))
    }

    @Test
    fun `line count is preserved`() {
        val source = "a\n/* b\nc */\n\"\"\"\nd\n\"\"\"\ne\n"
        assertEquals(source.split("\n").size, code(source).size)
    }

    @Test
    fun `end state says whether the scan finished back in code`() {
        assertTrue(KotlinSourceText.scan("val a = \"x\" /* y */").endsInCode)
        assertFalse(KotlinSourceText.scan("val a = \"unterminated").endsInCode)
        assertFalse(KotlinSourceText.scan("val a = \"\"\"unterminated").endsInCode)
        assertFalse(KotlinSourceText.scan("/* unterminated").endsInCode)
        assertFalse(KotlinSourceText.scan("val a = \"${'$'}{b").endsInCode)
    }

    @Test
    fun `multi-dollar strings need as many dollars to open a template`() {
        // In a $$ string a single-dollar brace is plain text, so it must not
        // swallow the code that follows.
        val lines = code("val a = ${'$'}${'$'}\"${'$'}{ Secret\"\nval r = Code\nval b = \"}\"")
        assertFalse(lines[0].contains("Secret"))
        assertEquals("val r = Code", lines[1].trim())
        assertTrue(KotlinSourceText.scan("val a = ${'$'}${'$'}\"${'$'}{ x\"").endsInCode)
        // Two dollars do open a template in a $$ string, and its code is kept.
        assertTrue(oneLine("val a = ${'$'}${'$'}\"x ${'$'}${'$'}{a.Code} y\"").contains("a.Code"))
        assertFalse(oneLine("val a = ${'$'}${'$'}\"${'$'}Secret\"").contains("Secret"))
        assertFalse(oneLine("val a = ${'$'}${'$'}\"\"\"${'$'}{ Secret\"\"\"; val t = 1").contains("Secret"))
    }

    @Test
    fun `references are found across line breaks and statements, with their line`() {
        val name = Regex("""\borg(\s*\.\s*\w+)+""")
        val found = KotlinSourceText.references(
            "import a.B; import org.x.Y\n// org.not.This\nval z = org\n    .y.Z",
            name,
        )
        assertEquals(listOf(1 to "org.x.Y", 3 to "org.y.Z"), found)
    }

    @Test(expected = IllegalStateException::class)
    fun `references refuse a source the reader lost track of`() {
        KotlinSourceText.references("val a = \"unterminated org.x.Y", Regex("org"))
    }
}
