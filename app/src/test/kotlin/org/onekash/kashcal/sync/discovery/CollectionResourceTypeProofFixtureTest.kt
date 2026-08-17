package org.onekash.kashcal.sync.discovery

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.sync.integration.multiserver.CollectionResourceTypeProof
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Durable, offline replay of the multi-server collection-discovery safety proof.
 *
 * The parameterized `*ResourceTypeProofTest` classes capture the invariant LIVE
 * (behind `-Pintegration`, needing real servers). This test replays the redacted
 * PROPFIND fixtures those runs committed under
 * `resources/{caldav,carddav}/resourcetype_proof/` so the proof stays green in the
 * ordinary PR-gated suite with no network. It is what stops a future edit to the
 * discovery / quirks filtering from silently regressing the guarantee.
 *
 * The guarantee, per fixture:
 *   For every collection, if the PRODUCTION name filter skips it then the app would
 *   NOT have surfaced it anyway — because it lacks the `<calendar>` / `<addressbook>`
 *   resourcetype, or (for a real-but-VTODO-only calendar like iCloud's `tasks`) it
 *   fails the VEVENT component gate. So the resourcetype + component gates already
 *   exclude everything the name filter skips, making the name filter pure (safe)
 *   redundancy that can only ever add false-drops if it broadens back to a substring
 *   match.
 *
 * This test calls the REAL shipped predicates (`DefaultQuirks.shouldSkipCalendar`,
 * `ICloudQuirks.shouldSkipCalendar`, `DefaultCardDavQuirks.shouldSkipAddressBook`)
 * via [CollectionResourceTypeProof], never a reimplemented copy — so a regression in
 * the production filter (a revert to substring matching, a change to tasks/reminders
 * handling) makes this test fail.
 *
 * Two captured server behaviours are pinned as named regression cases because they
 * are exactly what a naive gate would get wrong:
 *   - SOGo folds `schedule-outbox` onto its REAL primary calendar → the gate must
 *     be a POSITIVE `has <calendar>` test, never a negative `lacks scheduling` one.
 *   - Cyrus advertises a full `supported-calendar-component-set` (VEVENT…) on its
 *     Inbox/Outbox → the component set is NOT a safe discriminator; resourcetype is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CollectionResourceTypeProofFixtureTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun load(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!
            .use { it.readBytes().decodeToString() }

    private fun fixtures(protocol: String): List<Pair<String, String>> =
        SERVERS.getValue(protocol).mapNotNull { name ->
            javaClass.classLoader
                ?.getResourceAsStream("$protocol/resourcetype_proof/$name.xml")
                ?.use { it.readBytes().decodeToString() }
                ?.let { name to it }
        }

    @Test
    fun `every caldav fixture - production name filter only skips collections the app would not surface`() {
        val loaded = fixtures("caldav")
        assertTrue("no CalDAV proof fixtures on the classpath", loaded.isNotEmpty())
        loaded.forEach { (server, xml) ->
            val rows = CollectionResourceTypeProof.parseCollections(xml)
            assertTrue("$server: fixture parsed to zero collections", rows.isNotEmpty())
            assertTrue("$server: fixture surfaced no app-visible calendar", rows.any { it.appSurfacesAsCalendar })
            val isICloud = server.equals("icloud", ignoreCase = true)
            val disagreements = rows.filter {
                CollectionResourceTypeProof.calDavNameFilterSkips(it, isICloud) && it.appSurfacesAsCalendar
            }
            assertTrue(
                "$server: production name filter skips a collection the app WOULD surface " +
                    "(resourcetype + VEVENT gate) — the filter is not safe redundancy on " +
                    disagreements.map { it.href },
                disagreements.isEmpty(),
            )
        }
    }

    @Test
    fun `every carddav fixture - production name filter only skips collections the app would not surface`() {
        val loaded = fixtures("carddav")
        assertTrue("no CardDAV proof fixtures on the classpath", loaded.isNotEmpty())
        loaded.forEach { (server, xml) ->
            val rows = CollectionResourceTypeProof.parseCollections(xml)
            assertTrue("$server: fixture parsed to zero collections", rows.isNotEmpty())
            assertTrue("$server: fixture surfaced no app-visible address book", rows.any { it.appSurfacesAsAddressBook })
            val disagreements = rows.filter {
                CollectionResourceTypeProof.cardDavNameFilterSkips(it) && it.appSurfacesAsAddressBook
            }
            assertTrue(
                "$server: production name filter skips an address book the app WOULD surface on " +
                    disagreements.map { it.href },
                disagreements.isEmpty(),
            )
        }
    }

    @Test
    fun `production name filter still drops every scheduling collection each fixture exposes`() {
        // Positive coverage: the redundancy must actually fire. Every fixture that
        // exposes an inbox/outbox/notification collection must have the production
        // name filter skip it — otherwise a broken filter that skips NOTHING would
        // still pass the "only skips non-surfaced" invariant above.
        val caldav = fixtures("caldav")
        var schedulingSeen = 0
        caldav.forEach { (server, xml) ->
            val isICloud = server.equals("icloud", ignoreCase = true)
            CollectionResourceTypeProof.parseCollections(xml)
                .filter { row ->
                    // STANDALONE scheduling collections only. A scheduling resourcetype
                    // FOLDED onto a real calendar (SOGo puts schedule-outbox on its
                    // primary calendar) is a collection the name filter must NOT skip —
                    // that fold is covered by its own dedicated test below.
                    !row.isCalendar &&
                        row.resourceTypes.any { it.startsWith("schedule-") || it == "notification" }
                }
                .forEach { row ->
                    schedulingSeen++
                    assertTrue(
                        "$server: production name filter FAILED to skip scheduling collection ${row.href}",
                        CollectionResourceTypeProof.calDavNameFilterSkips(row, isICloud),
                    )
                }
        }
        assertTrue("no scheduling collections found in any fixture — coverage is hollow", schedulingSeen > 0)
    }

    @Test
    fun `SOGo folds schedule-outbox onto its real calendar so the gate must be positive`() {
        val rows = CollectionResourceTypeProof.parseCollections(load("caldav/resourcetype_proof/sogo.xml"))
        val folded = rows.filter { it.foldsSchedulingResourceType }
        assertTrue("SOGo fixture no longer shows the schedule-outbox fold", folded.isNotEmpty())
        folded.forEach {
            // Real calendar despite the folded scheduling resourcetype...
            assertTrue("SOGo folded collection should still be a real calendar: ${it.href}", it.isCalendar)
            // ...and the production name filter must NOT skip it (segment 'personal' is not reserved).
            assertFalse(
                "SOGo folded real calendar must not be name-skipped: ${it.href}",
                CollectionResourceTypeProof.calDavNameFilterSkips(it, isICloud = false),
            )
        }
    }

    @Test
    fun `Cyrus advertises full component set on scheduling collections so component-set is not a discriminator`() {
        val rows = CollectionResourceTypeProof.parseCollections(load("caldav/resourcetype_proof/cyrus.xml"))
        val schedulingWithComps = rows.filter {
            !it.isCalendar && it.supportedComponents.contains("VEVENT")
        }
        assertTrue(
            "Cyrus fixture no longer shows a scheduling collection advertising VEVENT",
            schedulingWithComps.isNotEmpty(),
        )
        // These are precisely the collections the resourcetype gate must still
        // exclude even though their component set looks calendar-like — and the
        // production name filter also skips them (redundant belt-and-braces).
        schedulingWithComps.forEach {
            assertFalse("Cyrus scheduling collection must not surface: ${it.href}", it.appSurfacesAsCalendar)
            assertTrue(
                "expected the name filter to skip scheduling collection ${it.href}",
                CollectionResourceTypeProof.calDavNameFilterSkips(it, isICloud = false),
            )
        }
    }

    companion object {
        // Server fixture basenames per protocol, matching writeFixture()'s
        // lowercased server names. Absent files are skipped (mapNotNull), so a
        // server that was unreachable at capture time never fails this offline test.
        private val SERVERS = mapOf(
            "caldav" to listOf(
                "icloud", "baikal", "baikaldigest", "radicale",
                "nextcloud", "zoho", "sogo", "cyrus", "stalwart", "xandikos",
            ),
            "carddav" to listOf("icloud", "radicale", "baikal", "nextcloud", "cyrus"),
        )
    }
}
