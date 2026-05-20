package org.onekash.kashcal.sync.strategy.integration

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.auth.Credentials
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.OkHttpCalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.onekash.kashcal.sync.session.SyncSessionBuilder
import org.onekash.kashcal.sync.session.SyncSessionStore
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.sync.session.SyncType
import org.onekash.kashcal.sync.strategy.PullStrategy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Diagnostic: compare current schema vs proposed schema on real iCloud pull.
 *
 * Current:  UNIQUE(original_event_id, original_instance_time)
 * Proposed: UNIQUE(calendar_id, uid, original_instance_time)
 *
 * Simulates schema change via raw SQL — no Room entity changes needed.
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*ConstraintDiagnosticTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ConstraintDiagnosticTest {

    private var username: String? = null
    private var password: String? = null
    private var serverUrl: String = "https://caldav.icloud.com"
    private lateinit var client: CalDavClient

    // Test-scoped DataStore cleanup tracking
    private val dataStoreScopes = mutableListOf<CoroutineScope>()
    private val dataStoreFiles = mutableListOf<File>()

    @Before
    fun setup() {
        loadCredentials()
        val quirks = ICloudQuirks()
        client = if (username != null && password != null) {
            OkHttpCalDavClientFactory().createClient(
                Credentials(username!!, password!!, serverUrl), quirks
            )
        } else {
            OkHttpCalDavClientFactory().createClient(
                Credentials("", "", serverUrl), quirks
            )
        }
    }

    private fun loadCredentials() {
        val possiblePaths = listOf(
            "local.properties",
            "../local.properties",
            "/onekash/KashCal/local.properties"
        )
        for (path in possiblePaths) {
            val propsFile = File(path)
            if (propsFile.exists()) {
                propsFile.readLines().forEach { line ->
                    val parts = line.split("=", limit = 2).map { it.trim() }
                    if (parts.size == 2) {
                        when (parts[0]) {
                            "ICLOUD_USERNAME" -> username = parts[1]
                            "ICLOUD_PASSWORD", "ICLOUD_APP_PASSWORD" -> password = parts[1]
                        }
                    }
                }
                if (username != null && password != null) break
            }
        }
    }

    @After
    fun tearDown() {
        dataStoreScopes.forEach { it.cancel() }
        dataStoreFiles.forEach { it.delete() }
    }

    private fun assumeCredentialsAvailable() {
        assumeTrue("iCloud credentials not available", username != null && password != null)
    }

    private suspend fun discoverAllCalendars(): List<CalDavCalendar> {
        val principal = client.discoverPrincipal(serverUrl).getOrNull() ?: return emptyList()
        val homeUrls = client.discoverCalendarHome(principal).getOrNull() ?: return emptyList()
        val all = mutableListOf<CalDavCalendar>()
        for (homeUrl in homeUrls) {
            val calendars = client.listCalendars(homeUrl).getOrNull() ?: continue
            all.addAll(calendars.filter { !it.url.contains("inbox") && !it.url.contains("outbox") })
        }
        return all
    }

    /**
     * Build a fresh DB + PullStrategy with the given schema variant.
     *
     * @param variant "current" keeps Room's schema as-is.
     *                "proposed" drops UNIQUE(original_event_id, original_instance_time)
     *                and creates UNIQUE(calendar_id, uid, original_instance_time).
     */
    private fun buildEnv(variant: String): TestEnv {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(KashCalDatabase.testCallback())
            .build()

        if (variant == "proposed") {
            // Swap unique indexes via raw SQL
            val sqlDb = db.openHelper.writableDatabase
            // Drop current wrong unique index
            // Room names it: index_events_original_event_id_original_instance_time
            sqlDb.execSQL("DROP INDEX IF EXISTS index_events_original_event_id_original_instance_time")
            // Add non-unique replacement (still useful for FK lookups)
            sqlDb.execSQL("CREATE INDEX IF NOT EXISTS index_events_original_event_id_original_instance_time ON events(original_event_id, original_instance_time)")
            // Drop current non-unique composite
            sqlDb.execSQL("DROP INDEX IF EXISTS index_events_calendar_id_uid_original_instance_time")
            // Create the RFC-correct unique index
            sqlDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_events_calendar_id_uid_original_instance_time ON events(calendar_id, uid, original_instance_time)")
        }

        val eventsDao = db.eventsDao()
        val occurrencesDao = db.occurrencesDao()
        val calendarRepository = CalendarRepositoryImpl(db.calendarsDao())
        val dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val testDataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        dataStoreScopes.add(dataStoreScope)
        dataStoreFiles.add(testDataStoreFile)
        val testPrefsDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope
        ) { testDataStoreFile }
        val dataStore = KashCalDataStore(context, testPrefsDataStore)
        val occurrenceGenerator = OccurrenceGenerator(db, occurrencesDao, eventsDao, dataStore)
        val syncSessionStore = SyncSessionStore(context)

        val pullStrategy = PullStrategy(
            database = db,
            calendarRepository = calendarRepository,
            eventsDao = eventsDao,
            attendeesDao = db.attendeesDao(),
            occurrenceGenerator = occurrenceGenerator,
            defaultQuirks = ICloudQuirks(),
            dataStore = dataStore,
            inviteNotifier = mockk(relaxed = true),
            accountRepository = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true)
        )

        return TestEnv(db, eventsDao, occurrencesDao, pullStrategy)
    }

    private data class TestEnv(
        val db: KashCalDatabase,
        val eventsDao: EventsDao,
        val occurrencesDao: OccurrencesDao,
        val pullStrategy: PullStrategy
    )

    private suspend fun setupDbCalendar(env: TestEnv, caldavUrl: String, displayName: String): Pair<Long, Calendar> {
        val accountId = env.db.accountsDao().insert(
            Account(provider = AccountProvider.ICLOUD, email = "test@icloud.com")
        )
        val calId = env.db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = caldavUrl,
                displayName = displayName, color = 0xFF0000)
        )
        return Pair(calId, env.db.calendarsDao().getById(calId)!!)
    }

    private data class PullStats(
        val written: Int,
        val updated: Int,
        val constraintErrors: Int,
        val orphaned: Int,
        val parseErrors: Int,
        val etagSkipped: Int,
        val totalEvents: Int,
        val exceptions: Int,
        val masters: Int
    )

    private suspend fun runPull(env: TestEnv, calendar: Calendar, calId: Long): PullStats {
        val sb = SyncSessionBuilder(calId, calendar.displayName, SyncType.FULL, SyncTrigger.FOREGROUND_MANUAL)
        env.pullStrategy.pull(calendar = calendar, forceFullSync = true, client = client, sessionBuilder = sb)
        val session = sb.build()

        val farPast = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000 * 10
        val farFuture = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000 * 10
        val dbEvents = env.eventsDao.getByCalendarIdInRange(calId, farPast, farFuture)

        return PullStats(
            written = session.eventsWritten,
            updated = session.eventsUpdated,
            constraintErrors = session.skippedAlreadySynced,
            orphaned = session.skippedOrphanedException,
            parseErrors = session.skippedParseError,
            etagSkipped = session.skippedEtagUnchanged,
            totalEvents = dbEvents.size,
            exceptions = dbEvents.count { it.originalEventId != null },
            masters = dbEvents.count { it.originalEventId == null && it.rrule != null }
        )
    }

    // ========== Test: Side-by-side comparison ==========

    @Test
    fun `compare current vs proposed schema - fresh pull`() = runBlocking {
        assumeCredentialsAvailable()
        val serverCalendars = discoverAllCalendars()
        assumeTrue("Should discover calendars", serverCalendars.isNotEmpty())

        println("=== Schema Comparison: Current vs Proposed ===")
        println("Current:  UNIQUE(original_event_id, original_instance_time)")
        println("Proposed: UNIQUE(calendar_id, uid, original_instance_time)")
        println()

        for (variant in listOf("current", "proposed")) {
            val env = buildEnv(variant)
            println("--- $variant schema ---")

            for (serverCal in serverCalendars) {
                val (calId, calendar) = setupDbCalendar(env, serverCal.url, serverCal.displayName)
                val stats = runPull(env, calendar, calId)

                println("  ${serverCal.displayName}: " +
                    "written=${stats.written} updated=${stats.updated} " +
                    "constraint=${stats.constraintErrors} orphan=${stats.orphaned} " +
                    "| DB: total=${stats.totalEvents} masters=${stats.masters} exc=${stats.exceptions}")
            }

            // Second pull on same DB to test re-sync behavior
            println("  --- second pull (re-sync) ---")
            for (serverCal in serverCalendars) {
                // Re-read calendar with updated ctag/syncToken
                val calendar = env.db.calendarsDao().getByCaldavUrl(serverCal.url) ?: continue
                val calId = calendar.id
                val stats = runPull(env, calendar, calId)

                println("  ${serverCal.displayName}: " +
                    "written=${stats.written} updated=${stats.updated} " +
                    "constraint=${stats.constraintErrors} orphan=${stats.orphaned} " +
                    "| DB: total=${stats.totalEvents} masters=${stats.masters} exc=${stats.exceptions}")
            }

            env.db.close()
            println()
        }
    }

    // ========== Test: Multi-session overlap (ctag reset) ==========

    /**
     * Simulates WorkManager session interruption: first pull writes all events,
     * then ctag is reset (as if the session crashed before saving sync state).
     * Second pull re-fetches everything — etag-based skipping should handle it.
     *
     * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*ConstraintDiagnosticTest.multi-session*"
     */
    @Test
    fun `multi-session overlap - ctag reset re-pull`() = runBlocking {
        assumeCredentialsAvailable()
        val serverCalendars = discoverAllCalendars()
        assumeTrue("Should discover calendars", serverCalendars.isNotEmpty())

        println("=== Multi-Session Overlap: ctag Reset ===")
        println("Simulates session crash before ctag saved. Etags on events are preserved.")
        println()

        for (variant in listOf("current", "proposed")) {
            val env = buildEnv(variant)
            println("--- $variant schema ---")

            for (serverCal in serverCalendars) {
                val (calId, calendar) = setupDbCalendar(env, serverCal.url, serverCal.displayName)

                // Session 1: Full pull (baseline)
                val s1 = runPull(env, calendar, calId)
                println("  ${serverCal.displayName} session 1 (fresh): " +
                    "written=${s1.written} constraint=${s1.constraintErrors} " +
                    "| DB: total=${s1.totalEvents} exc=${s1.exceptions}")

                // Simulate interrupted session: reset ctag/syncToken
                env.db.calendarsDao().updateSyncToken(calId, null, null)
                val resetCal = env.db.calendarsDao().getById(calId)!!

                // Session 2: Full re-pull (etags intact → should skip everything)
                val s2 = runPull(env, resetCal, calId)
                println("  ${serverCal.displayName} session 2 (ctag reset): " +
                    "written=${s2.written} updated=${s2.updated} " +
                    "constraint=${s2.constraintErrors} etagSkip=${s2.etagSkipped} " +
                    "| DB: total=${s2.totalEvents}")

                assertEquals(
                    "DB total should not change after ctag-reset re-pull (${serverCal.displayName})",
                    s1.totalEvents, s2.totalEvents
                )
            }

            env.db.close()
            println()
        }
    }

    // ========== Test: Multi-session overlap (ctag + etag reset) ==========

    /**
     * Most aggressive re-processing test: reset ctag AND clear all etags,
     * forcing every event through the full upsert path (no etag shortcut).
     * This stresses the UID-based lookup + @Upsert conflict resolution.
     *
     * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*ConstraintDiagnosticTest.multi-session*"
     */
    @Test
    fun `multi-session overlap - ctag and etag reset full re-processing`() = runBlocking {
        assumeCredentialsAvailable()
        val serverCalendars = discoverAllCalendars()
        assumeTrue("Should discover calendars", serverCalendars.isNotEmpty())

        println("=== Multi-Session Overlap: ctag + etag Reset ===")
        println("Forces full re-processing through upsert path (no etag shortcut)")
        println()

        for (variant in listOf("current", "proposed")) {
            val env = buildEnv(variant)
            println("--- $variant schema ---")

            for (serverCal in serverCalendars) {
                val (calId, calendar) = setupDbCalendar(env, serverCal.url, serverCal.displayName)

                // Session 1: Full pull (baseline)
                val s1 = runPull(env, calendar, calId)
                println("  ${serverCal.displayName} session 1 (fresh): " +
                    "written=${s1.written} constraint=${s1.constraintErrors} " +
                    "| DB: total=${s1.totalEvents} exc=${s1.exceptions}")

                // Simulate complete restart: reset ctag + clear all etags
                env.db.calendarsDao().updateSyncToken(calId, null, null)
                env.db.openHelper.writableDatabase.execSQL(
                    "UPDATE events SET etag = NULL WHERE calendar_id = $calId"
                )
                val resetCal = env.db.calendarsDao().getById(calId)!!

                // Session 2: Full re-processing (everything through upsert)
                val s2 = runPull(env, resetCal, calId)
                println("  ${serverCal.displayName} session 2 (ctag+etag reset): " +
                    "written=${s2.written} updated=${s2.updated} " +
                    "constraint=${s2.constraintErrors} etagSkip=${s2.etagSkipped} " +
                    "| DB: total=${s2.totalEvents}")

                assertEquals(
                    "DB total should not change after full re-processing (${serverCal.displayName})",
                    s1.totalEvents, s2.totalEvents
                )
            }

            env.db.close()
            println()
        }
    }

    // ========== Test: Orphan exception dedup (local, no credentials) ==========

    /**
     * Tests the unique index correctness gap for orphan exceptions.
     *
     * Scenario: A properly linked exception exists (original_event_id = masterId).
     * Then an orphan duplicate with the same (calendar_id, uid, original_instance_time)
     * but original_event_id = NULL is inserted.
     *
     * Current schema: UNIQUE(original_event_id, original_instance_time)
     *   → (masterId, T) vs (NULL, T) are distinct → allows duplicate
     *
     * Proposed schema: UNIQUE(calendar_id, uid, original_instance_time)
     *   → (calId, uid, T) vs (calId, uid, T) are identical → blocks duplicate
     *
     * Run: ./gradlew testDebugUnitTest --tests "*ConstraintDiagnosticTest.orphan*"
     */
    @Test
    fun `orphan exception dedup - current allows duplicate, proposed blocks`() = runBlocking {
        println("=== Orphan Exception Dedup Test ===")
        println("Tests whether duplicate orphan exceptions are prevented by the unique index")
        println()

        for (variant in listOf("current", "proposed")) {
            val env = buildEnv(variant)
            val (calId, _) = setupDbCalendar(env, "https://example.com/cal", "Test")

            val now = System.currentTimeMillis()
            val instanceTime = now + 86400000L
            val uid = "test-uid-orphan-dedup"

            // Insert master event
            val masterId = env.eventsDao.upsert(Event(
                uid = uid,
                calendarId = calId,
                title = "Master Event",
                startTs = now,
                endTs = now + 3600000,
                dtstamp = now
            ))

            // Insert properly linked exception via DAO
            env.eventsDao.upsert(Event(
                uid = uid,
                calendarId = calId,
                title = "Exception (linked)",
                startTs = instanceTime,
                endTs = instanceTime + 3600000,
                dtstamp = now,
                originalEventId = masterId,
                originalInstanceTime = instanceTime
            ))

            // Drop master insert trigger so we can insert an orphan with same UID
            val sqlDb = env.db.openHelper.writableDatabase
            sqlDb.execSQL("DROP TRIGGER IF EXISTS trigger_master_event_unique_insert")

            // Try inserting orphan exception: same (calendar_id, uid, original_instance_time)
            // but original_event_id = NULL
            val duplicateInserted = try {
                sqlDb.execSQL("""
                    INSERT INTO events (uid, calendar_id, title, start_ts, end_ts, dtstamp,
                        original_event_id, original_instance_time, created_at, updated_at)
                    VALUES ('$uid', $calId, 'Exception (orphan)', $instanceTime,
                        ${instanceTime + 3600000}, $now, NULL, $instanceTime, $now, $now)
                """)
                true
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                false
            }

            // Count exceptions with this UID and instance time
            val cursor = sqlDb.query(
                "SELECT COUNT(*) FROM events WHERE uid = '$uid' AND original_instance_time = $instanceTime"
            )
            cursor.moveToFirst()
            val count = cursor.getInt(0)
            cursor.close()

            println("  $variant: orphan duplicate ${if (duplicateInserted) "ALLOWED" else "BLOCKED"} " +
                "(exception rows with same uid+instanceTime: $count)")

            env.db.close()
        }

        println()
        println("  Expected: current=ALLOWED(2), proposed=BLOCKED(1)")
    }

    // ========== Test: Migration dedup robustness (local, no credentials) ==========

    /**
     * Tests that the two-step migration dedup handles all duplicate patterns
     * and that CREATE UNIQUE INDEX succeeds afterward.
     *
     * Patterns tested:
     *   1. Orphan + linked (same uid, instanceTime) → keeps linked
     *   2. Two orphans (same uid, instanceTime) → keeps highest id
     *   3. Two linked, different masters (same uid, instanceTime) → keeps highest id
     *   4. No duplicates (single exception) → untouched
     *   5. Master events (original_instance_time IS NULL) → untouched
     *
     * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*ConstraintDiagnosticTest.migration dedup*"
     */
    @Test
    fun `migration dedup handles all duplicate patterns`() = runBlocking {
        println("=== Migration Dedup Robustness Test ===")
        println()

        val env = buildEnv("current")
        val (calId, _) = setupDbCalendar(env, "https://example.com/cal", "Test")
        val sqlDb = env.db.openHelper.writableDatabase

        val now = System.currentTimeMillis()
        val uid1 = "uid-orphan-linked"
        val uid2 = "uid-two-orphans"
        val uid3 = "uid-two-linked"
        val uid4 = "uid-no-duplicate"
        val uid5 = "uid-master-only"
        val t1 = now + 100000L
        val t2 = now + 200000L
        val t3 = now + 300000L
        val t4 = now + 400000L

        // Drop triggers to allow inserting test data freely
        sqlDb.execSQL("DROP TRIGGER IF EXISTS trigger_master_event_unique_insert")
        sqlDb.execSQL("DROP TRIGGER IF EXISTS trigger_master_event_unique_update")

        // Helper to insert a test event via raw SQL
        fun insertEvent(uid: String, originalEventId: Long?, instanceTime: Long?, title: String): Long {
            sqlDb.execSQL("""
                INSERT INTO events (uid, calendar_id, title, start_ts, end_ts, dtstamp,
                    original_event_id, original_instance_time, created_at, updated_at)
                VALUES ('$uid', $calId, '$title', $now, ${now + 3600000}, $now,
                    ${originalEventId ?: "NULL"}, ${instanceTime ?: "NULL"}, $now, $now)
            """)
            val cursor = sqlDb.query("SELECT last_insert_rowid()")
            cursor.moveToFirst()
            val id = cursor.getLong(0)
            cursor.close()
            return id
        }

        // --- Pattern 1: Orphan + linked ---
        val master1Id = insertEvent(uid1, null, null, "Master 1")
        val linked1Id = insertEvent(uid1, master1Id, t1, "Linked exception")
        val orphan1Id = insertEvent(uid1, null, t1, "Orphan exception")

        // --- Pattern 2: Two orphans ---
        insertEvent(uid2, null, null, "Master 2")
        val orphan2aId = insertEvent(uid2, null, t2, "Orphan A")
        val orphan2bId = insertEvent(uid2, null, t2, "Orphan B")

        // --- Pattern 3: Two linked, different masters ---
        val master3aId = insertEvent(uid3, null, null, "Master 3a")
        val master3bId = insertEvent("${uid3}-alt", null, null, "Master 3b")
        val linked3aId = insertEvent(uid3, master3aId, t3, "Linked to master 3a")
        // Second linked exception with same uid+instanceTime but different master
        sqlDb.execSQL("""
            INSERT INTO events (uid, calendar_id, title, start_ts, end_ts, dtstamp,
                original_event_id, original_instance_time, created_at, updated_at)
            VALUES ('$uid3', $calId, 'Linked to master 3b', $now, ${now + 3600000}, $now,
                $master3bId, $t3, $now, $now)
        """)
        val cursorLinked3b = sqlDb.query("SELECT last_insert_rowid()")
        cursorLinked3b.moveToFirst()
        val linked3bId = cursorLinked3b.getLong(0)
        cursorLinked3b.close()

        // --- Pattern 4: Single exception (no duplicate) ---
        val master4Id = insertEvent(uid4, null, null, "Master 4")
        val single4Id = insertEvent(uid4, master4Id, t4, "Single exception")

        // --- Pattern 5: Master-only (no exceptions) ---
        val master5Id = insertEvent(uid5, null, null, "Master 5")

        // Verify pre-dedup state
        fun countEvents(): Int {
            val c = sqlDb.query("SELECT COUNT(*) FROM events WHERE calendar_id = $calId")
            c.moveToFirst()
            val n = c.getInt(0)
            c.close()
            return n
        }

        fun eventExists(id: Long): Boolean {
            val c = sqlDb.query("SELECT COUNT(*) FROM events WHERE id = $id")
            c.moveToFirst()
            val n = c.getInt(0)
            c.close()
            return n > 0
        }

        val preCount = countEvents()
        println("  Pre-dedup: $preCount events")

        // Run the two-step dedup (same SQL as migration)
        // Step 1: Delete orphans that have a linked counterpart
        sqlDb.execSQL("""
            DELETE FROM events WHERE id IN (
                SELECT e1.id FROM events e1
                INNER JOIN events e2
                    ON e1.calendar_id = e2.calendar_id
                    AND e1.uid = e2.uid
                    AND e1.original_instance_time = e2.original_instance_time
                WHERE e1.original_event_id IS NULL
                    AND e2.original_event_id IS NOT NULL
                    AND e1.original_instance_time IS NOT NULL
            )
        """)

        // Step 2: Generic dedup — keep MAX(id) per group
        sqlDb.execSQL("""
            DELETE FROM events
            WHERE original_instance_time IS NOT NULL
            AND id NOT IN (
                SELECT MAX(id) FROM events
                WHERE original_instance_time IS NOT NULL
                GROUP BY calendar_id, uid, original_instance_time
            )
        """)

        val postCount = countEvents()
        println("  Post-dedup: $postCount events (deleted ${preCount - postCount})")

        // Verify each pattern
        // Pattern 1: linked survives, orphan deleted
        println("  Pattern 1 (orphan+linked): linked=${eventExists(linked1Id)} orphan=${eventExists(orphan1Id)}")
        assertEquals("Linked exception should survive", true, eventExists(linked1Id))
        assertEquals("Orphan exception should be deleted", false, eventExists(orphan1Id))

        // Pattern 2: highest id survives
        println("  Pattern 2 (two orphans): A=${eventExists(orphan2aId)} B=${eventExists(orphan2bId)}")
        assertEquals("Older orphan should be deleted", false, eventExists(orphan2aId))
        assertEquals("Newer orphan should survive", true, eventExists(orphan2bId))

        // Pattern 3: highest id survives
        println("  Pattern 3 (two linked): a=${eventExists(linked3aId)} b=${eventExists(linked3bId)}")
        assertEquals("Older linked should be deleted", false, eventExists(linked3aId))
        assertEquals("Newer linked should survive", true, eventExists(linked3bId))

        // Pattern 4: single exception untouched
        println("  Pattern 4 (no duplicate): single=${eventExists(single4Id)}")
        assertEquals("Single exception should survive", true, eventExists(single4Id))

        // Pattern 5: masters untouched
        println("  Pattern 5 (masters): m1=${eventExists(master1Id)} m2=${eventExists(master5Id)}")
        assertEquals("Master 1 should survive", true, eventExists(master1Id))
        assertEquals("Master 5 should survive", true, eventExists(master5Id))

        // Verify CREATE UNIQUE INDEX succeeds after dedup
        sqlDb.execSQL("DROP INDEX IF EXISTS index_events_calendar_id_uid_original_instance_time")
        try {
            sqlDb.execSQL(
                "CREATE UNIQUE INDEX index_events_calendar_id_uid_original_instance_time " +
                "ON events(calendar_id, uid, original_instance_time)"
            )
            println("  CREATE UNIQUE INDEX: SUCCESS")
        } catch (e: Exception) {
            println("  CREATE UNIQUE INDEX: FAILED — ${e.message}")
            throw AssertionError("Unique index creation should succeed after dedup", e)
        }

        env.db.close()
    }
}
