package org.onekash.kashcal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.KotlinSourceText
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File

/**
 * Keeps every alarm the app sets on a path that survives the platform refusing it.
 *
 * AlarmManager caps each app at 500 pending alarms and throws IllegalStateException
 * for every further set call. A call that catches only SecurityException, the
 * documented exact-alarm failure, crashes the app on that refusal. So production
 * code sets alarms only through [org.onekash.kashcal.util.AlarmArming], plus the
 * Room reminder scheduler, which catches the refusal itself and is held at its
 * current call count.
 */
class AlarmCallSiteGuardTest {

    companion object {
        private const val HELPER = "util/AlarmArming.kt"
        private const val REMINDER_SCHEDULER = "reminder/scheduler/ReminderScheduler.kt"
        private const val REMINDER_SCHEDULER_CALLS = 2

        /**
         * Calls to set methods only AlarmManager has, with an explicit receiver or
         * an implicit one (inside `with(alarmManager) { ... }`). AlarmManagerCompat's
         * set methods share these names, so they are matched too. A declaration
         * (`fun setExact(`) is not a call.
         */
        private val ALARM_SET_CALL = Regex(
            """(?<!fun\s)\b(setExactAndAllowWhileIdle|setAndAllowWhileIdle|setExact|setWindow|""" +
                """setAlarmClock|setRepeating|setInexactRepeating)\s*\("""
        )

        /** AlarmManager.set(...) itself; only checked in files that use AlarmManager. */
        private val BARE_SET_CALL = Regex("""\.\s*set\s*\(""")

        private val USES_ALARM_MANAGER = Regex("""\bandroid\s*\.\s*app\s*\.\s*AlarmManager\b""")

        private val CATCHES_ALARM_LIMIT =
            Regex("""catch\s*\(\s*\w+\s*:\s*(kotlin\s*\.\s*|java\s*\.\s*lang\s*\.\s*)?IllegalStateException\s*\)""")

        /** Every direct alarm set call in [source], as (line, call). */
        fun alarmSetCalls(source: String): List<Pair<Int, String>> {
            val calls = KotlinSourceText.references(source, ALARM_SET_CALL)
            val bare = if (KotlinSourceText.references(source, USES_ALARM_MANAGER).isNotEmpty()) {
                KotlinSourceText.references(source, BARE_SET_CALL)
            } else {
                emptyList()
            }
            return (calls + bare).sortedBy { it.first }
        }

        fun catchesAlarmLimit(source: String): Boolean =
            KotlinSourceText.references(source, CATCHES_ALARM_LIMIT).isNotEmpty()

        /** Every main source file, keyed by its path relative to org/onekash/kashcal. */
        fun mainFiles(): Map<String, File> {
            val base = File(resolveProjectRoot(), "app/src/main/kotlin/org/onekash/kashcal")
            check(base.isDirectory) { "Main sources not found at ${base.absolutePath}" }
            return base.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associateBy { it.relativeTo(base).invariantSeparatorsPath }
        }
    }

    @Test
    fun `alarms are set only through the refusal-safe helper`() {
        val violations = mainFiles()
            .filterKeys { it != HELPER && it != REMINDER_SCHEDULER }
            .flatMap { (path, file) -> alarmSetCalls(file.readText()).map { (line, call) -> "$path:$line $call" } }

        assertTrue(
            "These set alarms directly. Use AlarmArming.setAllowWhileIdle so a refusal at the " +
                "platform's 500-alarm limit is skipped instead of crashing the app:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `reminder scheduler keeps its existing alarm calls only`() {
        val source = mainFiles().getValue(REMINDER_SCHEDULER).readText()

        assertEquals(
            "A new alarm call in ReminderScheduler must go through AlarmArming",
            REMINDER_SCHEDULER_CALLS,
            alarmSetCalls(source).size
        )
    }

    @Test
    fun `files allowed to set alarms handle a refusal at the alarm limit`() {
        val files = mainFiles()
        for (path in listOf(HELPER, REMINDER_SCHEDULER)) {
            assertTrue("$path must catch IllegalStateException", catchesAlarmLimit(files.getValue(path).readText()))
        }
    }

    // ===== The scanner itself =====

    @Test
    fun `scanner flags a direct alarm call that only catches SecurityException`() {
        val source = """
            fun arm(am: android.app.AlarmManager, pi: android.app.PendingIntent) {
                try {
                    am.setExactAndAllowWhileIdle(0, 1L, pi)
                } catch (e: SecurityException) {
                }
            }
        """.trimIndent()

        assertEquals(listOf(3 to "setExactAndAllowWhileIdle("), alarmSetCalls(source))
        assertFalse(catchesAlarmLimit(source))
    }

    @Test
    fun `scanner flags an alarm call with an implicit receiver`() {
        val source = """
            fun arm(am: android.app.AlarmManager, pi: android.app.PendingIntent) {
                with(am) { setAndAllowWhileIdle(0, 1L, pi) }
            }
        """.trimIndent()

        assertEquals(listOf(2 to "setAndAllowWhileIdle("), alarmSetCalls(source))
    }

    @Test
    fun `scanner ignores a function declared with an alarm method name`() {
        assertEquals(emptyList<Pair<Int, String>>(), alarmSetCalls("fun setExact(x: Int) = x"))
    }

    @Test
    fun `scanner ignores alarm calls in comments and strings`() {
        val source = """
            /** Uses setExactAndAllowWhileIdle() for Doze. */
            fun describe() = "alarmManager.setExact(0, 1L, pi)" // am.setWindow(
        """.trimIndent()

        assertEquals(emptyList<Pair<Int, String>>(), alarmSetCalls(source))
    }

    @Test
    fun `scanner flags a bare set call only in a file that uses AlarmManager`() {
        val withAlarmManager = """
            import android.app.AlarmManager
            fun arm(am: AlarmManager, pi: android.app.PendingIntent) { am.set(0, 1L, pi) }
        """.trimIndent()
        val withoutAlarmManager = """
            fun stamp(c: java.util.Calendar) { c.set(2026, 0, 1) }
        """.trimIndent()

        assertEquals(listOf(2 to ".set("), alarmSetCalls(withAlarmManager))
        assertEquals(emptyList<Pair<Int, String>>(), alarmSetCalls(withoutAlarmManager))
    }

    @Test
    fun `scanner flags AlarmManagerCompat set calls`() {
        val source = "fun arm() { AlarmManagerCompat.setExactAndAllowWhileIdle(am, 0, 1L, pi) }"

        assertEquals(listOf(1 to "setExactAndAllowWhileIdle("), alarmSetCalls(source))
    }
}
