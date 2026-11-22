package org.onekash.kashcal.data.db.migration

import org.onekash.kashcal.testutil.KotlinSourceText
import java.io.File

/**
 * Reads `Migrations.kt` for the source-checking migration tests: the file text, and the string
 * literals it passes to SQL or keeps in declarations, each filed under the migration whose body
 * holds it.
 */
internal object MigrationSource {

    /** Returns the text of `Migrations.kt`. */
    fun text(): String {
        val relative = "src/main/kotlin/org/onekash/kashcal/data/db/migration/Migrations.kt"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }

    /**
     * Maps each call whose arguments carry SQL text, values bound into it or names checked with it
     * to the group a text is filed under. The statement calls share one group, so the same SQL
     * moving between them (a cursor query becoming `count`) is not a change; a helper keeps its
     * own name, so a shipped migration swapping one helper for another is.
     */
    val SQL_CALLS: Map<String, String> =
        listOf("execSQL", "query", "compileStatement", "prepare", "count", "changedRows").associateWith { SQL } +
            listOf("bindText", "bindString").associateWith { "bind" } +
            listOf("listOf", "setOf", "mapOf", "linkedMapOf").associateWith { "list" } +
            listOf(
                "addColumnIfNotExists", "dropIndexIfExists", "indexExists", "tableExists",
                "columnExists", "columnTypeOf", "tableColumns", "primaryKeyIsNoCase",
            ).associateWith { it }
    val CALL = Regex("""\b(${SQL_CALLS.keys.joinToString("|")})\s*\(""")

    /** A `val` declaration other than a migration itself: SQL fragments and SQL held in locals. */
    val DECLARATION = Regex("""\bval\s+(?!MIGRATION_\d)\w+\s*(:[^=]+)?=(?!\s*object\b)""")

    /** Files the SQL statement calls' texts. */
    const val SQL = "sql"

    /** Owns the texts outside every migration body: helpers and constants. */
    const val SHARED = "shared"

    /** Opens a manual migration's object body: `val MIGRATION_6_7 = object : Migration(6, 7) {`. */
    val MIGRATION_BODY = Regex("""\bval\s+(MIGRATION_\d+_\d+)\s*=\s*object\s*:\s*Migration\([^)]*\)\s*\{""")

    /** Holds one literal: its owning migration, its [SQL_CALLS] group or `val`, and its text. */
    data class SqlText(val owner: String, val call: String, val text: String)

    /**
     * Returns every literal in a [SQL_CALLS] call or a [DECLARATION] in [source], sorted. A
     * literal inside both is filed under the call; a call inside another call's arguments under
     * the outer one.
     */
    fun sqlTexts(source: String): List<SqlText> {
        val code = KotlinSourceText.codeLines(source).joinToString("\n")
        val owner = owners(code)
        val byPosition = linkedMapOf<Int, SqlText>()
        var outerEnd = -1
        for (match in CALL.findAll(code)) {
            val open = match.range.last
            if (open < outerEnd) continue
            outerEnd = matching(code, open, '(', ')')
            val call = SQL_CALLS.getValue(match.groupValues[1])
            literals(source, code, open, outerEnd).forEach { (at, text) -> byPosition[at] = SqlText(owner(open), call, text) }
        }
        for (match in DECLARATION.findAll(code)) {
            val at = match.range.last
            literals(source, code, at, declarationEnd(code, at + 1)).forEach { (pos, text) ->
                byPosition.putIfAbsent(pos, SqlText(owner(at), "val", text))
            }
        }
        return byPosition.values.sortedWith(compareBy({ it.owner }, { it.call }, { it.text }))
    }

    /**
     * Returns each SQL statement call's argument text in [source], its literals joined with a
     * space, so a statement written as concatenated literals reads as one.
     */
    fun sqlArguments(source: String): List<String> {
        val code = KotlinSourceText.codeLines(source).joinToString("\n")
        val arguments = mutableListOf<String>()
        var outerEnd = -1
        for (match in CALL.findAll(code)) {
            val open = match.range.last
            if (open < outerEnd) continue
            outerEnd = matching(code, open, '(', ')')
            if (SQL_CALLS[match.groupValues[1]] != SQL) continue
            val texts = literals(source, code, open, outerEnd).map { it.second }
            if (texts.isNotEmpty()) arguments += texts.joinToString(" ")
        }
        return arguments
    }

    /** Returns which migration body holds each position of [code], or [SHARED]. */
    private fun owners(code: String): (Int) -> String {
        val bodies = MIGRATION_BODY.findAll(code).map { match ->
            match.groupValues[1] to (match.range.last..matching(code, match.range.last, '{', '}'))
        }.toList()
        return { at -> bodies.firstOrNull { at in it.second }?.first ?: SHARED }
    }

    /**
     * Returns where the declaration whose value starts at [from] ends: the first newline outside
     * parentheses and strings, once something was read, that no `+` or `.` continues.
     */
    private fun declarationEnd(code: String, from: Int): Int {
        var depth = 0
        var raw = false
        var plain = false
        var consumed = false
        var k = from
        while (k < code.length) {
            val c = code[k]
            when {
                code.startsWith("\"\"\"", k) && !plain -> { raw = !raw; consumed = true; k += 3; continue }
                c == '"' && !raw -> { plain = !plain; consumed = true }
                raw || plain -> {}
                c == '(' || c == '{' -> depth++
                c == ')' || c == '}' -> depth--
                c == '\n' && depth == 0 && consumed -> {
                    val next = code.substring(k + 1).trimStart()
                    val last = code.substring(from, k).trimEnd().lastOrNull()
                    if (!next.startsWith("+") && !next.startsWith(".") && last != '+') return k
                }
                !c.isWhitespace() -> consumed = true
            }
            k++
        }
        return code.length
    }

    private fun matching(code: String, open: Int, opener: Char, closer: Char): Int {
        var depth = 0
        for (k in open until code.length) {
            when (code[k]) {
                opener -> depth++
                closer -> if (--depth == 0) return k
            }
        }
        error("unbalanced $opener at $open")
    }

    /** Reads the literals between [from] and [to] with their start; [code] marks where they are. */
    private fun literals(source: String, code: String, from: Int, to: Int): List<Pair<Int, String>> {
        val found = mutableListOf<Pair<Int, String>>()
        var k = from
        while (k < to) {
            if (code[k] != '"') { k++; continue }
            val raw = source.startsWith("\"\"\"", k)
            val quote = if (raw) "\"\"\"" else "\""
            val start = k + quote.length
            var end = start
            while (true) {
                end = source.indexOf(quote, end)
                if (raw || source[end - 1] != '\\') break
                end++
            }
            found += k to source.substring(start, end).replace(Regex("""\s+"""), " ").trim()
            k = end + quote.length
        }
        return found
    }
}
