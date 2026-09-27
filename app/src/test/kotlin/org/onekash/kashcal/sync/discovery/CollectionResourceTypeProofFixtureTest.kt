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
 * Replays the multi-server collection-discovery safety proof offline.
 *
 * `MultiServerCalendarResourceTypeProofTest` and `MultiServerAddressBookResourceTypeProofTest`
 * capture the invariant live (behind `-Pintegration`, against real servers). This test
 * replays the redacted PROPFIND fixtures those runs committed under
 * `resources/{caldav,carddav}/resourcetype_proof/`, so the proof runs in the ordinary suite
 * with no network and an edit to the discovery or quirks filtering can't silently break it.
 *
 * The guarantee, per fixture: every collection the production name filter skips is one the
 * app wouldn't surface anyway, because it lacks the `<calendar>` or `<addressbook>`
 * resourcetype or, for a VTODO-only calendar like iCloud's `tasks`, fails the VEVENT
 * component gate. The name filter is then safe redundancy that can only add false drops if
 * it widens back to substring matching.
 *
 * The test calls the shipped predicates (`DefaultQuirks.shouldSkipCalendar`,
 * `ICloudQuirks.shouldSkipCalendar`, `DefaultCardDavQuirks.shouldSkipAddressBook`) through
 * [CollectionResourceTypeProof], never a copy, so a regression in the production filter (a
 * revert to substring matching, a change to tasks or reminders handling) fails it.
 *
 * Two captured server behaviours are pinned as named cases because a naive gate gets them
 * wrong:
 *   - SOGo folds `schedule-outbox` onto its real primary calendar, so the gate must be a
 *     positive `has <calendar>` test, never a negative `lacks scheduling` one.
 *   - Cyrus advertises a full `supported-calendar-component-set` (VEVENT and others) on its
 *     Inbox/Outbox, so the component set isn't a safe discriminator; resourcetype is.
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
        // Positive coverage: the redundancy must fire. Every standalone scheduling or
        // notification collection a fixture exposes must be skipped by the production name
        // filter; otherwise a broken filter that skips nothing would still pass the
        // "only skips non-surfaced" invariant above.
        val caldav = fixtures("caldav")
        var schedulingSeen = 0
        caldav.forEach { (server, xml) ->
            val isICloud = server.equals("icloud", ignoreCase = true)
            CollectionResourceTypeProof.parseCollections(xml)
                .filter { row ->
                    // Standalone scheduling collections only. A scheduling resourcetype
                    // folded onto a real calendar (SOGo puts schedule-outbox on its
                    // primary calendar) must not be name-skipped; its own test below
                    // covers that fold.
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
            // ...and the production name filter must not skip it (segment 'personal' isn't
            // reserved).
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
        // The resourcetype gate must exclude these even though their component set looks
        // calendar-like, and the production name filter skips them too (the redundancy).
        schedulingWithComps.forEach {
            assertFalse("Cyrus scheduling collection must not surface: ${it.href}", it.appSurfacesAsCalendar)
            assertTrue(
                "expected the name filter to skip scheduling collection ${it.href}",
                CollectionResourceTypeProof.calDavNameFilterSkips(it, isICloud = false),
            )
        }
    }

    companion object {
        // Fixture basenames per protocol, matching the lowercased server names
        // `CollectionResourceTypeProof.writeFixture` writes. Only listed names are replayed;
        // an absent file is skipped (mapNotNull), so a server unreachable at capture time
        // never fails this offline test.
        private val SERVERS = mapOf(
            "caldav" to listOf(
                "icloud", "baikal", "baikaldigest", "radicale",
                "nextcloud", "zoho", "sogo", "cyrus", "stalwart", "xandikos",
            ),
            "carddav" to listOf("icloud", "radicale", "baikal", "nextcloud", "cyrus", "xandikos"),
        )
    }
}
