package org.onekash.kashcal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Keeps the ICS export messages in string resources, so they show in the user's language: the
 * snackbars and share-sheet titles of the quick-view export (each `onExportIcs = {` handler in
 * MainActivity) and of the calendar export events in SettingsActivity. A failure must name no
 * exception text either.
 *
 * Only those handlers are read; other messages in the activities are outside this check.
 */
class ExportMessagesLocalizedTest {

    private val source = File(resolveProjectRoot(), "app/src/main/kotlin/org/onekash/kashcal")

    @Test
    fun `quick-view export handlers use string resources`() {
        val bodies = blocksAfter(File(source, "MainActivity.kt").readText(), "onExportIcs = {")
        assertEquals("the Room and the device quick-view export", 2, bodies.size)
        bodies.forEach(::assertNoLiteralMessages)
    }

    @Test
    fun `calendar export handler uses string resources`() {
        val bodies = blocksAfter(File(source, "SettingsActivity.kt").readText(), SETTINGS_ANCHOR)
        assertEquals("the calendar export handler", 1, bodies.size)
        bodies.forEach(::assertNoLiteralMessages)
    }

    private fun assertNoLiteralMessages(body: String) {
        val literals = LITERAL_MESSAGE.findAll(body).map { it.value }.toList()
        assertTrue("hard-coded export text: $literals", literals.isEmpty())
        assertTrue("an export message shows exception text", !body.contains("e.message"))
    }

    /** Returns the brace-matched body that follows each [anchor] in [text]. */
    private fun blocksAfter(text: String, anchor: String): List<String> {
        val bodies = mutableListOf<String>()
        var from = text.indexOf(anchor)
        while (from >= 0) {
            val open = text.indexOf('{', from + anchor.length - 1)
            var depth = 0
            var end = open
            while (end < text.length) {
                when (text[end]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                }
                end++
            }
            bodies += text.substring(open, end + 1)
            from = text.indexOf(anchor, end)
        }
        return bodies
    }

    private companion object {
        const val SETTINGS_ANCHOR = "private fun showCalendarExportEvent(event: CalendarExportEvent) {"

        // A string literal as a snackbar's message or as a chooser's title.
        val LITERAL_MESSAGE = Regex("""showSnackbar\(\s*"|Chooser\([^()]*(\([^()]*\))?[^()]*,\s*"[^"]*"\s*\)""")
    }
}
