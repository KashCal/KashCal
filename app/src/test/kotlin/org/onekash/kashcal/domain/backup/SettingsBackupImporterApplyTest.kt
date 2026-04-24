package org.onekash.kashcal.domain.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Unit tests for SettingsBackupImporter.applyBackup.
 *
 * Uses MockK to verify subscription orchestration and preference application. Accounts and
 * calendars are no longer in the backup envelope — see BackupRoundTripIntegrationTest for
 * end-to-end coverage of the new bottom-up shape.
 */
class SettingsBackupImporterApplyTest {

    private lateinit var database: KashCalDatabase
    private lateinit var icsSubscriptionsDao: IcsSubscriptionsDao
    private lateinit var accountRepository: AccountRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var kashcalDataStore: KashCalDataStore
    private lateinit var importer: SettingsBackupImporter
    private lateinit var currentPrefs: MutablePreferences

    @Before
    fun setup() {
        database = mockk()
        icsSubscriptionsDao = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        calendarRepository = mockk(relaxed = true)
        dataStore = mockk()
        kashcalDataStore = KashCalDataStore(mockk(relaxed = true), dataStore)

        // Pass-through Room transaction — just executes the block synchronously.
        coEvery { database.runInTransaction<Unit>(any<suspend () -> Unit>()) } coAnswers {
            val block = firstArg<suspend () -> Unit>()
            block()
        }

        // Default: empty prefs, `edit` accumulates into a held MutablePreferences.
        currentPrefs = mutablePreferencesOf()
        every { dataStore.data } returns flowOf(currentPrefs)
        coEvery { dataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            val newPrefs = transform(currentPrefs) as MutablePreferences
            currentPrefs = newPrefs
            newPrefs
        }

        importer = SettingsBackupImporter(
            database = database,
            dataStore = kashcalDataStore,
            accountRepository = accountRepository,
            calendarRepository = calendarRepository,
            icsSubscriptionsDao = icsSubscriptionsDao,
        )
    }

    private fun envelope(
        prefs: Map<String, BackupPreferenceValue> = emptyMap(),
        subs: List<BackupSubscription> = emptyList(),
    ) = BackupEnvelope(
        fileFormatVersion = 1,
        appVersion = "t",
        exportedAt = "t",
        preferences = prefs,
        subscriptions = subs,
    )

    @Test
    fun `subscription match preserves runtime state and calendarId`() = runBlocking {
        val existing = IcsSubscription(
            id = 8,
            url = "https://feed/a.ics",
            name = "Old name",
            color = 0x111,
            calendarId = 44,
            lastSync = 99999L,
            syncIntervalHours = 24,
            enabled = true,
            etag = "SAVED_ETAG",
            lastModified = "SAVED_LM",
            username = null,
            lastError = "previous",
            createdAt = 1L,
        )
        coEvery { icsSubscriptionsDao.getByUrl("https://feed/a.ics") } returns existing
        val updated = slot<IcsSubscription>()
        coEvery { icsSubscriptionsDao.update(capture(updated)) } coAnswers { }

        importer.applyBackup(envelope(subs = listOf(
            BackupSubscription(
                url = "https://feed/a.ics",
                name = "New name",
                color = 0x222,
                syncIntervalHours = 6,
                enabled = false,
                username = "newuser",
            )
        )))

        val r = updated.captured
        assertEquals("SAVED_ETAG", r.etag)
        assertEquals("SAVED_LM", r.lastModified)
        assertEquals(99999L, r.lastSync)
        assertEquals("previous", r.lastError)
        assertEquals(44L, r.calendarId)
        assertEquals(8L, r.id)
        assertEquals(1L, r.createdAt)
        // User-preference fields updated.
        assertEquals("New name", r.name)
        assertEquals(0x222, r.color)
        assertEquals(6, r.syncIntervalHours)
        assertFalse(r.enabled)
        assertEquals("newuser", r.username)
    }

    @Test
    fun `new subscription creates linked Calendar and ICS account if missing`() = runBlocking {
        coEvery { icsSubscriptionsDao.getByUrl("https://feed/new.ics") } returns null
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.ICS, any())
        } returns null
        coEvery { calendarRepository.getCalendarByUrl("https://feed/new.ics") } returns null
        val createdAccount = slot<Account>()
        coEvery { accountRepository.createAccount(capture(createdAccount)) } returns 77L
        val createdCalendar = slot<Calendar>()
        coEvery { calendarRepository.createCalendar(capture(createdCalendar)) } returns 88L
        val insertedSub = slot<IcsSubscription>()
        coEvery { icsSubscriptionsDao.insert(capture(insertedSub)) } returns 90L

        val result = importer.applyBackup(envelope(subs = listOf(
            BackupSubscription(
                url = "https://feed/new.ics",
                name = "New Feed",
                color = 0x333,
                syncIntervalHours = 12,
                enabled = true,
                username = "u",
            )
        )))

        assertEquals(AccountProvider.ICS, createdAccount.captured.provider)
        assertEquals(77L, createdCalendar.captured.accountId)
        assertTrue(createdCalendar.captured.isReadOnly)
        assertEquals("https://feed/new.ics", createdCalendar.captured.caldavUrl)
        assertEquals(88L, insertedSub.captured.calendarId)
        assertEquals(1, result.subscriptionsCreated)
    }

    @Test
    fun `new subscription reuses existing ICS account`() = runBlocking {
        val icsAccount = Account(
            id = 5, provider = AccountProvider.ICS, email = "subscriptions",
        )
        coEvery { icsSubscriptionsDao.getByUrl(any()) } returns null
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.ICS, any())
        } returns icsAccount
        coEvery { calendarRepository.getCalendarByUrl(any()) } returns null
        val cal = slot<Calendar>()
        coEvery { calendarRepository.createCalendar(capture(cal)) } returns 1L
        coEvery { icsSubscriptionsDao.insert(any()) } returns 2L

        importer.applyBackup(envelope(subs = listOf(
            BackupSubscription(
                url = "https://feed.ics",
                name = "F",
                color = 0,
                syncIntervalHours = 24,
                enabled = true,
            )
        )))

        coVerifyNoAccountCreate()
        assertEquals(5L, cal.captured.accountId)
    }

    @Test
    fun `subscription reuses calendar row already present with same URL`() = runBlocking {
        // If an ICS calendar row exists for this URL (e.g., user previously subscribed), the
        // importer must reuse it rather than attempt a duplicate insert that would hit the
        // unique index on caldav_url.
        val icsAccount = Account(
            id = 5, provider = AccountProvider.ICS, email = "subscriptions",
        )
        coEvery { icsSubscriptionsDao.getByUrl("https://feed/ics") } returns null
        coEvery {
            accountRepository.getAccountByProviderAndEmail(AccountProvider.ICS, any())
        } returns icsAccount
        val existingCalendar = Calendar(
            id = 77L,
            accountId = 5L,
            caldavUrl = "https://feed/ics",
            displayName = "Feed",
            color = 0x111,
            isReadOnly = true,
            isVisible = true,
            isDefault = false,
        )
        coEvery { calendarRepository.getCalendarByUrl("https://feed/ics") } returns existingCalendar
        val insertedSub = slot<IcsSubscription>()
        coEvery { icsSubscriptionsDao.insert(capture(insertedSub)) } returns 90L

        val result = importer.applyBackup(envelope(subs = listOf(
            BackupSubscription(
                url = "https://feed/ics",
                name = "Feed",
                color = 0x111,
                syncIntervalHours = 24,
                enabled = true,
            )
        )))

        // A duplicate createCalendar here would throw SQLiteConstraintException in production.
        coVerifyNoCalendarCreate()
        assertEquals(77L, insertedSub.captured.calendarId)
        assertEquals(1, result.subscriptionsCreated)
    }

    @Test
    fun `preferences present in envelope overwrite local values`() = runBlocking {
        currentPrefs = mutablePreferencesOf(
            PreferencesKeys.THEME to "light",
            PreferencesKeys.FIRST_DAY_OF_WEEK to 1,
        )
        every { dataStore.data } returns flowOf(currentPrefs)

        val result = importer.applyBackup(envelope(prefs = mapOf(
            "theme" to BackupPreferenceValue.StringPref("dark"),
            "first_day_of_week" to BackupPreferenceValue.IntPref(2),
        )))

        assertEquals("dark", currentPrefs[PreferencesKeys.THEME])
        assertEquals(2, currentPrefs[PreferencesKeys.FIRST_DAY_OF_WEEK])
        assertEquals(2, result.preferencesApplied)
    }

    @Test
    fun `preferences absent from envelope are left untouched`() = runBlocking {
        currentPrefs = mutablePreferencesOf(
            PreferencesKeys.THEME to "light",
            PreferencesKeys.AUTO_SYNC_ENABLED to true,
        )
        every { dataStore.data } returns flowOf(currentPrefs)

        importer.applyBackup(envelope(prefs = mapOf(
            "theme" to BackupPreferenceValue.StringPref("dark"),
        )))

        assertEquals("dark", currentPrefs[PreferencesKeys.THEME])
        // Not in backup — preserved.
        assertEquals(true, currentPrefs[PreferencesKeys.AUTO_SYNC_ENABLED])
    }

    @Test
    fun `unknown preference keys in envelope are silently ignored`() = runBlocking {
        importer.applyBackup(envelope(prefs = mapOf(
            "future_pref" to BackupPreferenceValue.StringPref("value"),
        )))
        assertNull(currentPrefs.asMap().entries.firstOrNull { it.key.name == "future_pref" })
    }

    @Test
    fun `legacy default_calendar key in envelope preferences is silently dropped on import`() = runBlocking {
        // Backup files produced before DEFAULT_CALENDAR was excluded may still carry the key.
        // The key must not reach the target DataStore (source-device row IDs don't match target).
        currentPrefs = mutablePreferencesOf()
        every { dataStore.data } returns flowOf(currentPrefs)

        val result = importer.applyBackup(envelope(prefs = mapOf(
            "default_calendar" to BackupPreferenceValue.StringPref("room:999"),
            "theme" to BackupPreferenceValue.StringPref("dark"),
        )))

        assertEquals("dark", currentPrefs[PreferencesKeys.THEME])
        assertNull(currentPrefs[PreferencesKeys.DEFAULT_CALENDAR])
        assertEquals(1, result.preferencesApplied)
    }

    @Test
    fun `deviceCalendarsNoteNeeded true when DEVICE_CALENDARS_ENABLED is true in backup`() = runBlocking {
        val result = importer.applyBackup(envelope(prefs = mapOf(
            "device_calendars_enabled" to BackupPreferenceValue.BoolPref(true),
        )))
        assertTrue(result.deviceCalendarsNoteNeeded)
    }

    @Test
    fun `deviceCalendarsNoteNeeded false when DEVICE_CALENDARS_ENABLED is false in backup`() = runBlocking {
        val result = importer.applyBackup(envelope(prefs = mapOf(
            "device_calendars_enabled" to BackupPreferenceValue.BoolPref(false),
        )))
        assertFalse(result.deviceCalendarsNoteNeeded)
    }

    @Test
    fun `deviceCalendarsNoteNeeded false when pref absent from backup`() = runBlocking {
        val result = importer.applyBackup(envelope())
        assertFalse(result.deviceCalendarsNoteNeeded)
    }

    @Test
    fun `failure during subscription write propagates and no prefs are written`() = runBlocking {
        coEvery { icsSubscriptionsDao.getByUrl(any()) } throws RuntimeException("db unavailable")

        currentPrefs = mutablePreferencesOf(PreferencesKeys.THEME to "light")
        every { dataStore.data } returns flowOf(currentPrefs)

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                importer.applyBackup(envelope(
                    subs = listOf(BackupSubscription(
                        url = "https://feed.ics",
                        name = "F",
                        color = 0,
                        syncIntervalHours = 24,
                        enabled = true,
                    )),
                    prefs = mapOf("theme" to BackupPreferenceValue.StringPref("dark")),
                ))
            }
        }

        // Prefs untouched — txn block threw before post-txn pref writes.
        assertEquals("light", currentPrefs[PreferencesKeys.THEME])
    }

    private suspend fun coVerifyNoAccountCreate() {
        io.mockk.coVerify(exactly = 0) { accountRepository.createAccount(any()) }
    }

    private suspend fun coVerifyNoCalendarCreate() {
        io.mockk.coVerify(exactly = 0) { calendarRepository.createCalendar(any()) }
    }
}
