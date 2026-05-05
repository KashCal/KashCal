package org.onekash.kashcal.domain.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.IcsSubscriptionsDao
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
import java.time.Instant

class SettingsBackupExporterTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var kashcalDataStore: KashCalDataStore
    private lateinit var icsSubscriptionsDao: IcsSubscriptionsDao

    private val fixedInstant: Instant = Instant.parse("2026-04-23T14:30:05Z")

    @Before
    fun setup() {
        dataStore = mockk()
        kashcalDataStore = KashCalDataStore(mockk(relaxed = true), dataStore)
        icsSubscriptionsDao = mockk()
    }

    private fun newExporter(): SettingsBackupExporter =
        SettingsBackupExporter(
            dataStore = kashcalDataStore,
            icsSubscriptionsDao = icsSubscriptionsDao,
            appVersionProvider = { "23.6.4" },
            nowProvider = { fixedInstant },
        )

    private fun stubPrefs(block: androidx.datastore.preferences.core.MutablePreferences.() -> Unit) {
        val prefs = mutablePreferencesOf().apply(block)
        every { dataStore.data } returns flowOf(prefs)
    }

    private fun stubNoSubs() {
        coEvery { icsSubscriptionsDao.getAllOnce() } returns emptyList()
    }

    @Test
    fun `exports envelope with version metadata`() = runBlocking {
        stubPrefs { }
        stubNoSubs()

        val json = newExporter().exportSettings()
        val envelope = BackupJson.decodeFromString(BackupEnvelope.serializer(), json)

        assertEquals(1, envelope.fileFormatVersion)
        assertEquals("23.6.4", envelope.appVersion)
        assertEquals("2026-04-23T14:30:05Z", envelope.exportedAt)
    }

    @Test
    fun `exports only allow-listed preferences`() = runBlocking {
        stubPrefs {
            // Should be included
            set(PreferencesKeys.THEME, "dark")
            set(PreferencesKeys.FIRST_DAY_OF_WEEK, 2)
            set(PreferencesKeys.AUTO_SYNC_ENABLED, true)
            set(PreferencesKeys.DEVICE_CALENDARS_ENABLED, true)
            // Should be excluded (runtime state + device ID set)
            set(PreferencesKeys.LAST_SYNC_TIME, 123456789L)
            set(PreferencesKeys.ENABLED_DEVICE_CALENDAR_IDS, setOf("42", "43"))
            set(PreferencesKeys.ONBOARDING_COMPLETED, true)
        }
        stubNoSubs()

        val json = newExporter().exportSettings()
        val envelope = BackupJson.decodeFromString(BackupEnvelope.serializer(), json)

        assertEquals("dark", (envelope.preferences["theme"] as? BackupPreferenceValue.StringPref)?.value)
        assertEquals(2, (envelope.preferences["first_day_of_week"] as? BackupPreferenceValue.IntPref)?.value)
        assertEquals(true, (envelope.preferences["auto_sync_enabled"] as? BackupPreferenceValue.BoolPref)?.value)
        assertEquals(true, (envelope.preferences["device_calendars_enabled"] as? BackupPreferenceValue.BoolPref)?.value)

        assertNull("LAST_SYNC_TIME must be excluded", envelope.preferences["last_sync_time"])
        assertNull("ENABLED_DEVICE_CALENDAR_IDS must be excluded", envelope.preferences["enabled_device_calendar_ids"])
        assertNull("ONBOARDING_COMPLETED must be excluded", envelope.preferences["onboarding_completed"])
    }

    @Test
    fun `exports subscriptions excluding runtime and password`() = runBlocking {
        val sub = IcsSubscription(
            id = 3,
            url = "https://example.com/feed.ics",
            name = "Holidays",
            color = 0x00FF00,
            calendarId = 42,
            lastSync = 9999L,
            syncIntervalHours = 12,
            enabled = true,
            etag = "server-etag",
            lastModified = "Wed, 21 Oct 2025 07:28:00 GMT",
            username = "readonly-user",
            lastError = "previous failure",
            createdAt = 100L,
        )
        stubPrefs { }
        coEvery { icsSubscriptionsDao.getAllOnce() } returns listOf(sub)

        val json = newExporter().exportSettings()

        // Runtime/diagnostic fields must not leak
        assertFalse("lastSync must not appear", json.contains("lastSync"))
        assertFalse("etag must not appear", json.contains("etag"))
        assertFalse("lastModified must not appear", json.contains("lastModified"))
        assertFalse("lastError must not appear", json.contains("lastError"))
        assertFalse("calendarId must not appear", json.contains("calendarId"))

        val envelope = BackupJson.decodeFromString(BackupEnvelope.serializer(), json)
        val exported = envelope.subscriptions.single()
        assertEquals("https://example.com/feed.ics", exported.url)
        assertEquals("Holidays", exported.name)
        assertEquals(sub.color, exported.color)
        assertEquals(12, exported.syncIntervalHours)
        assertTrue(exported.enabled)
        assertEquals("readonly-user", exported.username)
    }

    @Test
    fun `exports empty envelope when nothing is configured`() = runBlocking {
        stubPrefs { }
        stubNoSubs()

        val json = newExporter().exportSettings()
        val envelope = BackupJson.decodeFromString(BackupEnvelope.serializer(), json)

        assertEquals(0, envelope.subscriptions.size)
        assertEquals(0, envelope.preferences.size)
        assertNotNull(envelope.appVersion)
        assertNotNull(envelope.exportedAt)
    }
}
