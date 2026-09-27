package org.onekash.kashcal.widget

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.testutil.resolveProjectRoot
import java.io.File
import java.time.LocalDate

/**
 * Tests [WidgetPreviewRegistrar]'s registration policy: the SDK gate, the publish stamp, the
 * per-receiver batch walk and the backup exclusion of its preferences file.
 *
 * The policy takes the SDK level as a parameter, not `Build.VERSION.SDK_INT`, because unit tests
 * run below API 35 (this class on the plain JVM, Robolectric tests at `sdk=34`), so a framework
 * read would leave the only interesting branch, API 35 and up, untestable.
 *
 * The batch walk matters as much as the per-receiver decision: the platform allows roughly two
 * calls an hour and there are five receivers, so retrying every receiver would re-burn the quota
 * on widgets already registered and starve the ones at the end of the list.
 */
class WidgetPreviewRegistrarTest {

    private val currentStamp = 4242

    // ---- SDK gate -------------------------------------------------------------------

    @Test
    fun `registration is skipped below API 35 regardless of stored state`() {
        listOf(31, 32, 33, 34).forEach { sdk ->
            assertFalse(
                "sdk $sdk must not attempt registration — setWidgetPreviews is API 35+",
                WidgetPreviewRegistrar.shouldRegister(
                    sdkInt = sdk,
                    lastPublishedStamp = 0,
                    currentStamp = currentStamp
                )
            )
        }
    }

    @Test
    fun `registration is attempted from API 35 upward`() {
        listOf(35, 36, 37).forEach { sdk ->
            assertTrue(
                "sdk $sdk should register on a fresh install",
                WidgetPreviewRegistrar.shouldRegister(
                    sdkInt = sdk,
                    lastPublishedStamp = 0,
                    currentStamp = currentStamp
                )
            )
        }
    }

    // ---- Publish stamp --------------------------------------------------------------

    @Test
    fun `the stamp changes at a month rollover so previews cannot advertise a stale month`() {
        // Previews are rasterized once and kept by the system, with sample content built from
        // the publish date. Without the month in the stamp, a preview published in July would
        // still show July's grid and date number in December.
        val version = 100
        val july = WidgetPreviewRegistrar.publishStamp(version, LocalDate.of(2026, 7, 24))
        val laterInJuly = WidgetPreviewRegistrar.publishStamp(version, LocalDate.of(2026, 7, 31))
        val august = WidgetPreviewRegistrar.publishStamp(version, LocalDate.of(2026, 8, 1))

        assertEquals("the same month must not re-publish", july, laterInJuly)
        assertTrue("a new month must re-publish", july != august)
    }

    @Test
    fun `the stamp changes across a year boundary and across app versions`() {
        val dec = WidgetPreviewRegistrar.publishStamp(100, LocalDate.of(2026, 12, 1))
        val jan = WidgetPreviewRegistrar.publishStamp(100, LocalDate.of(2027, 1, 1))
        val sameMonthNewBuild = WidgetPreviewRegistrar.publishStamp(101, LocalDate.of(2026, 12, 1))

        assertTrue("December and January must differ", dec != jan)
        assertTrue("a new build must re-publish", dec != sameMonthNewBuild)
    }

    @Test
    fun `no version and month combination collides with another or overflows`() {
        // The stamp packs a version code and a month into one Int. A collision silently
        // suppresses a re-publish, leaving that widget stale until something else changes; a
        // `k * version + month` packing would collide once the months drift past k.
        val stamps = mutableMapOf<Int, Pair<Int, LocalDate>>()
        (680..760).forEach { version ->
            var date = LocalDate.of(2026, 1, 1)
            while (date.year < 2076) {
                val stamp = WidgetPreviewRegistrar.publishStamp(version, date)
                assertTrue("stamp overflowed to negative for ($version, $date)", stamp > 0)
                val clash = stamps.put(stamp, version to date)
                assertTrue("stamp $stamp collides: ($version, $date) and $clash", clash == null)
                date = date.plusMonths(1)
            }
        }
    }

    // ---- Stamp gate ---------------------------------------------------------------

    @Test
    fun `already published for this stamp skips`() {
        assertFalse(
            WidgetPreviewRegistrar.shouldRegister(
                sdkInt = 35,
                lastPublishedStamp = currentStamp,
                currentStamp = currentStamp
            )
        )
    }

    @Test
    fun `a changed stamp registers again so previews reflect it`() {
        assertTrue(
            WidgetPreviewRegistrar.shouldRegister(
                sdkInt = 35,
                lastPublishedStamp = currentStamp - 1,
                currentStamp = currentStamp
            )
        )
    }

    @Test
    fun `a stored stamp ahead of the current one still registers`() {
        // A downgrade, a restored then rolled-back install, or a clock moved backwards:
        // re-register, since this build never wrote that stamp.
        assertTrue(
            WidgetPreviewRegistrar.shouldRegister(
                sdkInt = 35,
                lastPublishedStamp = currentStamp + 5,
                currentStamp = currentStamp
            )
        )
    }

    // ---- Batch walk -----------------------------------------------------------------

    @Test
    fun `batch advances only the receivers that succeeded and stops at the first throttle`() = runTest {
        val stored = mutableMapOf<String, Int>()
        val attempted = mutableListOf<String>()

        val outcome = WidgetPreviewRegistrar.registerBatch(
            sdkInt = 35,
            currentStamp = currentStamp,
            receivers = FIVE_RECEIVERS,
            lastPublishedStamp = { stored[it] ?: 0 },
            recordRegistered = { key, stamp -> stored[key] = stamp },
            setPreviews = { key ->
                attempted += key
                if (attempted.size >= 3) RATE_LIMITED else SUCCESS
            }
        )

        assertEquals("should stop after the throttled call", 3, attempted.size)
        assertEquals(listOf("a", "b", "c"), attempted)
        assertEquals("only the two successes advance", setOf("a", "b"), stored.keys)
        assertTrue("outcome should report the throttle", outcome.rateLimited)
        assertEquals(2, outcome.registered)
    }

    @Test
    fun `the next launch attempts exactly the receivers that did not register`() = runTest {
        val stored = mutableMapOf("a" to currentStamp, "b" to currentStamp)
        val attempted = mutableListOf<String>()

        WidgetPreviewRegistrar.registerBatch(
            sdkInt = 35,
            currentStamp = currentStamp,
            receivers = FIVE_RECEIVERS,
            lastPublishedStamp = { stored[it] ?: 0 },
            recordRegistered = { key, stamp -> stored[key] = stamp },
            setPreviews = { key ->
                attempted += key
                SUCCESS
            }
        )

        assertEquals(listOf("c", "d", "e"), attempted)
        assertEquals(FIVE_RECEIVERS.toSet(), stored.keys)
    }

    @Test
    fun `a fully registered set attempts nothing`() = runTest {
        val stored = FIVE_RECEIVERS.associateWith { currentStamp }.toMutableMap()
        val attempted = mutableListOf<String>()

        val outcome = WidgetPreviewRegistrar.registerBatch(
            sdkInt = 35,
            currentStamp = currentStamp,
            receivers = FIVE_RECEIVERS,
            lastPublishedStamp = { stored[it] ?: 0 },
            recordRegistered = { key, stamp -> stored[key] = stamp },
            setPreviews = { key ->
                attempted += key
                SUCCESS
            }
        )

        assertTrue("nothing should be attempted", attempted.isEmpty())
        assertEquals(0, outcome.registered)
        assertFalse(outcome.rateLimited)
    }

    @Test
    fun `below API 35 the batch attempts nothing and records nothing`() = runTest {
        val stored = mutableMapOf<String, Int>()
        val attempted = mutableListOf<String>()

        val outcome = WidgetPreviewRegistrar.registerBatch(
            sdkInt = 34,
            currentStamp = currentStamp,
            receivers = FIVE_RECEIVERS,
            lastPublishedStamp = { stored[it] ?: 0 },
            recordRegistered = { key, stamp -> stored[key] = stamp },
            setPreviews = { key ->
                attempted += key
                SUCCESS
            }
        )

        assertTrue("no platform call may happen below API 35", attempted.isEmpty())
        assertTrue("nothing should be recorded", stored.isEmpty())
        assertEquals(0, outcome.registered)
        assertFalse(outcome.rateLimited)
    }

    @Test
    fun `a throttle on the very first receiver leaves the whole set unregistered`() = runTest {
        val stored = mutableMapOf<String, Int>()
        val attempted = mutableListOf<String>()

        val outcome = WidgetPreviewRegistrar.registerBatch(
            sdkInt = 35,
            currentStamp = currentStamp,
            receivers = FIVE_RECEIVERS,
            lastPublishedStamp = { stored[it] ?: 0 },
            recordRegistered = { key, stamp -> stored[key] = stamp },
            setPreviews = { key ->
                attempted += key
                RATE_LIMITED
            }
        )

        assertEquals(listOf("a"), attempted)
        assertTrue(stored.isEmpty())
        assertTrue(outcome.rateLimited)
        assertEquals(0, outcome.registered)
    }

    @Test
    fun `a thrown platform error does not advance the stored version`() = runTest {
        val stored = mutableMapOf<String, Int>()
        val attempted = mutableListOf<String>()

        val outcome = WidgetPreviewRegistrar.registerBatch(
            sdkInt = 35,
            currentStamp = currentStamp,
            receivers = FIVE_RECEIVERS,
            lastPublishedStamp = { stored[it] ?: 0 },
            recordRegistered = { key, stamp -> stored[key] = stamp },
            setPreviews = { key ->
                attempted += key
                if (key == "b") throw IllegalStateException("platform blew up")
                SUCCESS
            }
        )

        assertFalse("the failing receiver must not be marked registered", stored.containsKey("b"))
        assertTrue("earlier success stands", stored.containsKey("a"))
        assertFalse("a crash is not a throttle", outcome.rateLimited)
    }

    // ---- Backup exclusion -----------------------------------------------------------

    /**
     * Registration state must never be restored onto another device. Both rule files are
     * checked against [WidgetPreviewRegistrar.PREFS_NAME], so renaming the preferences file
     * without updating the rules fails here, not silently leaving restored devices on
     * placeholder previews.
     */
    @Test
    fun `the registration preferences file is excluded from backup and device transfer`() {
        val expected = """path="${WidgetPreviewRegistrar.PREFS_NAME}.xml""""

        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { rules ->
            val file = File(resolveProjectRoot(), "app/src/main/res/xml/$rules")
            assertTrue("$rules not found at ${file.absolutePath}", file.exists())
            val text = file.readText()
            val excludes = Regex("""<exclude domain="sharedpref" [^>]*>""")
                .findAll(text)
                .map { it.value }
                .filter { it.contains(expected) }
                .count()
            val sections = if (rules == "backup_rules.xml") 1 else 2
            assertEquals(
                "$rules must exclude ${WidgetPreviewRegistrar.PREFS_NAME}.xml in every section",
                sections,
                excludes
            )
        }
    }

    private companion object {
        val FIVE_RECEIVERS = listOf("a", "b", "c", "d", "e")
        const val SUCCESS = 0
        const val RATE_LIMITED = 1
    }
}
