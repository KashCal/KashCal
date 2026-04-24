package org.onekash.kashcal.domain.backup

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupEnvelopeTest {

    @Test
    fun `minimal envelope round-trips`() {
        val original = BackupEnvelope(
            fileFormatVersion = 1,
            appVersion = "23.6.4",
            exportedAt = "2026-04-23T14:30:00Z",
            preferences = emptyMap(),
            subscriptions = emptyList(),
        )

        val json = BackupJson.encodeToString(BackupEnvelope.serializer(), original)
        val decoded = BackupJson.decodeFromString(BackupEnvelope.serializer(), json)

        assertEquals(original, decoded)
    }

    @Test
    fun `full envelope round-trips with subscriptions and prefs`() {
        val original = BackupEnvelope(
            fileFormatVersion = 1,
            appVersion = "23.6.4",
            exportedAt = "2026-04-23T14:30:00Z",
            preferences = mapOf(
                "theme" to BackupPreferenceValue.StringPref("dark"),
                "first_day_of_week" to BackupPreferenceValue.IntPref(2),
                "auto_sync_enabled" to BackupPreferenceValue.BoolPref(true),
                "default_event_duration_ms" to BackupPreferenceValue.LongPref(1_800_000L),
                "enabled_device_calendar_ids" to BackupPreferenceValue.StringSetPref(setOf("a", "b")),
            ),
            subscriptions = listOf(
                BackupSubscription(
                    url = "https://example.com/feed.ics",
                    name = "Holidays",
                    color = 0x7A1F2C,
                    syncIntervalHours = 24,
                    enabled = true,
                    username = null,
                )
            ),
        )

        val json = BackupJson.encodeToString(BackupEnvelope.serializer(), original)
        val decoded = BackupJson.decodeFromString(BackupEnvelope.serializer(), json)

        assertEquals(original, decoded)
    }

    @Test
    fun `StringSetPref variant round-trips`() {
        val envelope = BackupEnvelope(
            fileFormatVersion = 1,
            appVersion = "x",
            exportedAt = "x",
            preferences = mapOf("s" to BackupPreferenceValue.StringSetPref(setOf("a", "b", "c"))),
            subscriptions = emptyList(),
        )
        val json = BackupJson.encodeToString(BackupEnvelope.serializer(), envelope)
        val decoded = BackupJson.decodeFromString(BackupEnvelope.serializer(), json)
        val value = decoded.preferences["s"]
        assertTrue(value is BackupPreferenceValue.StringSetPref)
        assertEquals(setOf("a", "b", "c"), (value as BackupPreferenceValue.StringSetPref).value)
    }

    @Test
    fun `missing file_format_version is rejected`() {
        val badJson = """
            {
              "app_version": "x",
              "exported_at": "x",
              "preferences": {},
              "subscriptions": []
            }
        """.trimIndent()
        assertThrows(SerializationException::class.java) {
            BackupJson.decodeFromString(BackupEnvelope.serializer(), badJson)
        }
    }

    @Test
    fun `missing preferences field is rejected`() {
        val badJson = """
            {
              "file_format_version": 1,
              "app_version": "x",
              "exported_at": "x",
              "subscriptions": []
            }
        """.trimIndent()
        assertThrows(SerializationException::class.java) {
            BackupJson.decodeFromString(BackupEnvelope.serializer(), badJson)
        }
    }

    @Test
    fun `missing subscriptions field is rejected`() {
        val badJson = """
            {
              "file_format_version": 1,
              "app_version": "x",
              "exported_at": "x",
              "preferences": {}
            }
        """.trimIndent()
        assertThrows(SerializationException::class.java) {
            BackupJson.decodeFromString(BackupEnvelope.serializer(), badJson)
        }
    }

    @Test
    fun `unknown extra top-level key is ignored`() {
        val futureExtraJson = """
            {
              "file_format_version": 1,
              "app_version": "x",
              "exported_at": "x",
              "preferences": {},
              "subscriptions": [],
              "future_feature": {"some": "value"}
            }
        """.trimIndent()
        val decoded = BackupJson.decodeFromString(BackupEnvelope.serializer(), futureExtraJson)
        assertEquals(1, decoded.fileFormatVersion)
    }

    @Test
    fun `legacy envelope with accounts and calendars fields parses cleanly and ignores them`() {
        // Verifies backward compatibility: accounts/calendars fields are safely ignored.
        val legacyJson = """
            {
              "file_format_version": 1,
              "app_version": "23.6.5",
              "exported_at": "2026-04-23T14:30:00Z",
              "preferences": {"theme": {"type": "string", "value": "dark"}},
              "accounts": [{"provider": "icloud", "email": "u@e"}],
              "calendars": [{"caldavUrl": "https://x", "displayName": "c", "color": 0, "isVisible": true, "isDefault": false, "isReadOnly": false, "sortOrder": 0, "isNotificationMuted": false, "accountEmail": "u@e"}],
              "subscriptions": [{"url": "https://feed.ics", "name": "F", "color": 0, "syncIntervalHours": 24, "enabled": true}]
            }
        """.trimIndent()

        val decoded = BackupJson.decodeFromString(BackupEnvelope.serializer(), legacyJson)

        assertEquals(1, decoded.fileFormatVersion)
        assertEquals(1, decoded.preferences.size)
        assertEquals(1, decoded.subscriptions.size)
        assertEquals("https://feed.ics", decoded.subscriptions[0].url)
    }
}
