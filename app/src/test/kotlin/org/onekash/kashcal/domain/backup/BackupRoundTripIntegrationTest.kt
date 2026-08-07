package org.onekash.kashcal.domain.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.credential.CredentialManager
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Real-Room + real-DataStore round-trip tests for the settings backup feature.
 *
 * Pure-unit tests use MockK to stub repository behaviour, which means SQL constraint violations
 * and FK cascades are invisible. These tests build two independent in-memory databases (source +
 * target) and exercise the real exporter against one, then the real importer against the other,
 * so structural failures surface as actual SQLiteConstraintExceptions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BackupRoundTripIntegrationTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    private lateinit var sourceDb: KashCalDatabase
    private lateinit var sourceDataStoreFile: File
    private lateinit var sourceDataStore: KashCalDataStore
    private lateinit var sourceExporter: SettingsBackupExporter

    private lateinit var targetDb: KashCalDatabase
    private lateinit var targetDataStoreFile: File
    private lateinit var targetDataStore: KashCalDataStore
    private lateinit var targetImporter: SettingsBackupImporter
    private lateinit var targetPrefs: DataStore<Preferences>

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        sourceDb = buildDatabase()
        sourceDataStoreFile = File(context.filesDir, "source_prefs_${System.nanoTime()}.preferences_pb")
        sourceDataStore = KashCalDataStore(
            context,
            PreferenceDataStoreFactory.create(scope = scope) { sourceDataStoreFile },
        )
        sourceExporter = SettingsBackupExporter(
            dataStore = sourceDataStore,
            icsSubscriptionsDao = sourceDb.icsSubscriptionsDao(),
            categoryDao = sourceDb.categoryDao(),
            appVersionProvider = { "test" },
            nowProvider = { java.time.Instant.EPOCH },
        )

        targetDb = buildDatabase()
        targetDataStoreFile = File(context.filesDir, "target_prefs_${System.nanoTime()}.preferences_pb")
        targetPrefs = PreferenceDataStoreFactory.create(scope = scope) { targetDataStoreFile }
        targetDataStore = KashCalDataStore(context, targetPrefs)
        targetImporter = SettingsBackupImporter(
            database = targetDb,
            dataStore = targetDataStore,
            accountRepository = buildAccountRepo(targetDb),
            calendarRepository = CalendarRepositoryImpl(targetDb.calendarsDao()),
            icsSubscriptionsDao = targetDb.icsSubscriptionsDao(),
            categoryDao = targetDb.categoryDao(),
            context = context,
        )
    }

    @After
    fun teardown() {
        // Cancel the scope before deleting DataStore files — the factory's write coroutines
        // may still hold the file open otherwise.
        scope.cancel()
        sourceDb.close()
        targetDb.close()
        sourceDataStoreFile.delete()
        targetDataStoreFile.delete()
    }

    private fun buildDatabase(): KashCalDatabase =
        Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun buildAccountRepo(db: KashCalDatabase): AccountRepositoryImpl =
        AccountRepositoryImpl(
            accountsDao = db.accountsDao(),
            addressBookDao = db.addressBookDao(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            pendingOperationsDao = db.pendingOperationsDao(),
            credentialManager = mockk<CredentialManager>(relaxed = true),
            reminderScheduler = mockk<ReminderScheduler>(relaxed = true),
            workManager = mockk<WorkManager>(relaxed = true),
            contactSystemAccountRegistrar = mockk(relaxed = true),
            contactsProviderRepository = mockk(relaxed = true),
        )

    @Test
    fun `round trip with ICS subscription applies cleanly`() = runTest {
        // Direct DAO seeding matches the post-state of IcsSubscriptionRepository.addSubscription
        // minus the network refresh — intentional scope for this test.
        val icsAccountId = sourceDb.accountsDao().insert(
            Account(provider = AccountProvider.ICS, email = IcsSubscription.ACCOUNT_EMAIL)
        )
        val icsCalendarId = sourceDb.calendarsDao().insert(
            Calendar(
                accountId = icsAccountId,
                caldavUrl = "https://example.com/holidays.ics",
                displayName = "Holidays",
                color = 0xFF4CAF50.toInt(),
                isReadOnly = true,
            )
        )
        sourceDb.icsSubscriptionsDao().insert(
            IcsSubscription(
                url = "https://example.com/holidays.ics",
                name = "Holidays",
                color = 0xFF4CAF50.toInt(),
                calendarId = icsCalendarId,
                syncIntervalHours = 24,
                enabled = true,
            )
        )

        val json = sourceExporter.exportSettings()
        val parseResult = targetImporter.parseAndValidate(json)
        assertTrue("parse must succeed", parseResult is BackupParseResult.Ok)
        val envelope = (parseResult as BackupParseResult.Ok).envelope

        val result = targetImporter.applyBackup(envelope)

        assertEquals(1, targetDb.icsSubscriptionsDao().getAllOnce().size)
        assertEquals(
            "ICS calendar was rebuilt from the subscription list",
            1,
            targetDb.calendarsDao().getAllOnce().size,
        )
        assertEquals(1, result.subscriptionsCreated)
    }

    @Test
    fun `applying subscription backup twice is idempotent`() = runTest {
        val icsAccountId = sourceDb.accountsDao().insert(
            Account(provider = AccountProvider.ICS, email = IcsSubscription.ACCOUNT_EMAIL)
        )
        val icsCalendarId = sourceDb.calendarsDao().insert(
            Calendar(
                accountId = icsAccountId,
                caldavUrl = "https://example.com/feed.ics",
                displayName = "Feed",
                color = 0xFF4CAF50.toInt(),
                isReadOnly = true,
            )
        )
        sourceDb.icsSubscriptionsDao().insert(
            IcsSubscription(
                url = "https://example.com/feed.ics",
                name = "Feed",
                color = 0xFF4CAF50.toInt(),
                calendarId = icsCalendarId,
                syncIntervalHours = 24,
                enabled = true,
            )
        )

        val json = sourceExporter.exportSettings()
        val envelope = (targetImporter.parseAndValidate(json) as BackupParseResult.Ok).envelope

        val first = targetImporter.applyBackup(envelope)
        val second = targetImporter.applyBackup(envelope)

        assertEquals(1, first.subscriptionsCreated)
        assertEquals("second apply creates nothing new", 0, second.subscriptionsCreated)
        assertEquals("second apply updates what first created", 1, second.subscriptionsUpdated)
    }

    @Test
    fun `round trip preserves tag custom colors and drops null-color tags`() = runTest {
        // A recolored tag (custom color) and a plain tag (null color, renders via hash).
        sourceDb.categoryDao().insertIgnore(
            org.onekash.kashcal.data.db.entity.Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 500L)
        )
        sourceDb.categoryDao().insertIgnore(
            org.onekash.kashcal.data.db.entity.Category(name = "Gym", color = null, lastUsedAt = 900L)
        )

        val json = sourceExporter.exportSettings()
        val envelope = (targetImporter.parseAndValidate(json) as BackupParseResult.Ok).envelope

        // Only the recolored tag is worth carrying — a null-color tag reappears on its
        // own via sync/usage and its swatch is derived, so exporting it is pointless.
        assertEquals(1, envelope.categories.size)
        assertEquals("Work", envelope.categories.single().name)

        val result = targetImporter.applyBackup(envelope)

        val restored = targetDb.categoryDao().getByName("Work")
        assertEquals("custom color survives restore", 0xFF4457C9.toInt(), restored!!.color)
        assertEquals(500L, restored.lastUsedAt)
        assertNull("a null-color tag is not carried in the backup", targetDb.categoryDao().getByName("Gym"))
        assertEquals(1, result.categoriesRestored)
    }

    @Test
    fun `restoring a tag color does not clobber a newer local recency`() = runTest {
        sourceDb.categoryDao().insertIgnore(
            org.onekash.kashcal.data.db.entity.Category(name = "Work", color = 0xFF4457C9.toInt(), lastUsedAt = 100L)
        )
        // Target already has the tag, used more recently than the backup snapshot.
        targetDb.categoryDao().insertIgnore(
            org.onekash.kashcal.data.db.entity.Category(name = "Work", color = null, lastUsedAt = 999L)
        )

        val json = sourceExporter.exportSettings()
        val envelope = (targetImporter.parseAndValidate(json) as BackupParseResult.Ok).envelope
        targetImporter.applyBackup(envelope)

        val restored = targetDb.categoryDao().getByName("Work")!!
        assertEquals("backed-up custom color is applied", 0xFF4457C9.toInt(), restored.color)
        assertEquals("newer local recency is kept", 999L, restored.lastUsedAt)
    }

    @Test
    fun `legacy envelope containing default_calendar preference imports without side effects`() = runTest {
        val legacyEnvelope = BackupEnvelope(
            fileFormatVersion = BACKUP_FILE_FORMAT_VERSION,
            appVersion = "legacy",
            exportedAt = "1970-01-01T00:00:00Z",
            preferences = mapOf(
                PreferencesKeys.DEFAULT_CALENDAR.name to BackupPreferenceValue.StringPref("room:999"),
                PreferencesKeys.THEME.name to BackupPreferenceValue.StringPref("dark"),
            ),
            subscriptions = emptyList(),
        )

        val result = targetImporter.applyBackup(legacyEnvelope)
        val snapshot = targetPrefs.data.first()

        assertEquals("only theme is applied; legacy key is dropped", 1, result.preferencesApplied)
        assertNull(
            "target DataStore must not contain DEFAULT_CALENDAR from a legacy envelope",
            snapshot[PreferencesKeys.DEFAULT_CALENDAR],
        )
        assertEquals("dark", snapshot[PreferencesKeys.THEME])
    }

    @Test
    fun `legacy v1 envelope with accounts and calendars fields parses cleanly and applies only subscriptions and prefs`() = runTest {
        // Verifies backward compatibility: accounts/calendars fields are safely ignored.
        val legacyJson = """
            {
              "file_format_version": 1,
              "app_version": "23.6.5",
              "exported_at": "2026-04-23T14:30:00Z",
              "preferences": {"theme": {"type": "string", "value": "dark"}},
              "accounts": [{"provider": "icloud", "email": "u@e"}],
              "calendars": [
                {"caldavUrl": "https://ignored.caldav/cal", "displayName": "c", "color": 0, "isVisible": true, "isDefault": false, "isReadOnly": false, "sortOrder": 0, "isNotificationMuted": false, "accountEmail": "u@e"}
              ],
              "subscriptions": [{"url": "https://feed.ics", "name": "F", "color": 0, "syncIntervalHours": 24, "enabled": true}]
            }
        """.trimIndent()

        val parseResult = targetImporter.parseAndValidate(legacyJson)
        assertTrue(parseResult is BackupParseResult.Ok)
        val envelope = (parseResult as BackupParseResult.Ok).envelope

        val result = targetImporter.applyBackup(envelope)
        val snapshot = targetPrefs.data.first()

        assertEquals("only subscription was applied", 1, result.subscriptionsCreated)
        assertEquals("theme pref was applied", 1, result.preferencesApplied)
        assertEquals("dark", snapshot[PreferencesKeys.THEME])
        // The accounts/calendars in the legacy payload were silently dropped — target has only
        // what the subscription path created (1 ICS account + 1 calendar for the feed URL).
        val targetAccounts = targetDb.accountsDao().getAllOnce()
        assertEquals(1, targetAccounts.size)
        assertEquals(AccountProvider.ICS, targetAccounts[0].provider)
        assertFalse(
            "no caldav account should be created from the legacy payload",
            targetAccounts.any { it.provider == AccountProvider.ICLOUD },
        )
    }
}
