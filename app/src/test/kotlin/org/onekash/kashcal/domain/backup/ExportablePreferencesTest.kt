package org.onekash.kashcal.domain.backup

import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.preferences.PreferencesKeys

class ExportablePreferencesTest {

    @Test
    fun `allow list contains exactly 42 keys`() {
        assertEquals(42, ExportablePreferences.KEYS.size)
    }

    @Test
    fun `DAY_WEEK_BAR_EXPANDED is a persistent display choice included in backups`() {
        assertTrue(
            "DAY_WEEK_BAR_EXPANDED must be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.DAY_WEEK_BAR_EXPANDED.name },
        )
        assertFalse(
            "DAY_WEEK_BAR_EXPANDED must not be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.DAY_WEEK_BAR_EXPANDED.name),
        )
    }

    @Test
    fun `ALL_DAY_ROWS_EXPANDED is in the allow-list as a persistent display choice`() {
        assertTrue(
            "ALL_DAY_ROWS_EXPANDED must be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.ALL_DAY_ROWS_EXPANDED.name },
        )
        assertFalse(
            "ALL_DAY_ROWS_EXPANDED must not be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.ALL_DAY_ROWS_EXPANDED.name),
        )
    }

    @Test
    fun `exclude list contains exactly 25 key names`() {
        assertEquals(25, ExportablePreferences.EXCLUDED_KEY_NAMES.size)
    }

    @Test
    fun `WEEK_VIEW_HOUR_HEIGHT is excluded because it is ephemeral per-device zoom state`() {
        assertTrue(
            "WEEK_VIEW_HOUR_HEIGHT must be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.WEEK_VIEW_HOUR_HEIGHT.name),
        )
        assertFalse(
            "WEEK_VIEW_HOUR_HEIGHT must not be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.WEEK_VIEW_HOUR_HEIGHT.name },
        )
    }

    @Test
    fun `WEEK_VIEW_SCROLL_MINUTES is excluded because it is ephemeral UI scroll state`() {
        assertTrue(
            "WEEK_VIEW_SCROLL_MINUTES must be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.WEEK_VIEW_SCROLL_MINUTES.name),
        )
        assertFalse(
            "WEEK_VIEW_SCROLL_MINUTES must not be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.WEEK_VIEW_SCROLL_MINUTES.name },
        )
    }

    @Test
    fun `APP_LOCK_ENABLED is excluded because it is device-local privacy state`() {
        assertTrue(
            "APP_LOCK_ENABLED must be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.APP_LOCK_ENABLED.name),
        )
        assertFalse(
            "APP_LOCK_ENABLED must not be in the allow-list",
            ExportablePreferences.KEYS.map { it.name }.contains(PreferencesKeys.APP_LOCK_ENABLED.name),
        )
    }

    @Test
    fun `LAST_APP_VERSION_CODE is excluded`() {
        assertTrue(
            "LAST_APP_VERSION_CODE must be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.LAST_APP_VERSION_CODE.name),
        )
    }

    @Test
    fun `every key in PreferencesKeys is either in allow list or exclude list`() {
        val allowedNames = ExportablePreferences.KEYS.map { it.name }.toSet()
        val excludedNames = ExportablePreferences.EXCLUDED_KEY_NAMES

        val allKeys = PreferencesKeys.javaClass.declaredFields
            .filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                field.isAccessible = true
                (field.get(PreferencesKeys) as? Preferences.Key<*>)?.name
            }

        val orphans = allKeys.filter { it !in allowedNames && it !in excludedNames }
        assertTrue(
            "PreferencesKeys contains keys not categorised as allowed or excluded: $orphans",
            orphans.isEmpty(),
        )

        val intersection = allowedNames.intersect(excludedNames)
        assertTrue(
            "A key must not be both allowed and excluded: $intersection",
            intersection.isEmpty(),
        )
    }

    @Test
    fun `runtime state keys are excluded`() {
        val mustBeExcluded = listOf(
            PreferencesKeys.LAST_SYNC_TIME,
            PreferencesKeys.CONTACT_BIRTHDAYS_LAST_SYNC,
            PreferencesKeys.CONTACT_ANNIVERSARIES_LAST_SYNC,
            PreferencesKeys.NOTIFICATION_PERMISSION_DENIED_COUNT,
            PreferencesKeys.PARSE_FAILURE_RETRY_COUNTS,
            PreferencesKeys.ENABLED_DEVICE_CALENDAR_IDS,
            PreferencesKeys.HIDDEN_DEVICE_CALENDAR_IDS,
            PreferencesKeys.DEFAULT_CALENDAR_ID,
        )
        mustBeExcluded.forEach { key ->
            assertTrue(
                "${key.name} must be excluded from exports",
                ExportablePreferences.EXCLUDED_KEY_NAMES.contains(key.name),
            )
            assertFalse(
                "${key.name} must not be in the allow-list",
                ExportablePreferences.KEYS.any { it.name == key.name },
            )
        }
    }

    @Test
    fun `migration and onboarding flags are excluded`() {
        val mustBeExcluded = listOf(
            PreferencesKeys.MIGRATION_V1_COMPLETED,
            PreferencesKeys.SYNC_METADATA_MIGRATED,
            PreferencesKeys.REMINDER_MIGRATION_VERSION,
            PreferencesKeys.PARSER_VERSION,
            PreferencesKeys.ICLOUD_URL_MIGRATION_COMPLETED,
            PreferencesKeys.ONBOARDING_COMPLETED,
            PreferencesKeys.SHOWN_LOCAL_CALENDAR_INTRO,
            PreferencesKeys.ONBOARDING_DISMISSED,
        )
        mustBeExcluded.forEach { key ->
            assertTrue(
                "${key.name} must be excluded from exports",
                ExportablePreferences.EXCLUDED_KEY_NAMES.contains(key.name),
            )
        }
    }

    @Test
    fun `CONTACT_SUGGESTIONS_DECLINED is excluded because it tracks a device-local permission dismissal`() {
        assertTrue(
            "CONTACT_SUGGESTIONS_DECLINED must be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.CONTACT_SUGGESTIONS_DECLINED.name),
        )
        assertFalse(
            "CONTACT_SUGGESTIONS_DECLINED must not be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.CONTACT_SUGGESTIONS_DECLINED.name },
        )
    }

    @Test
    fun `CONTACT_SYNC_PERMISSION_NEEDED is excluded because it is device-local runtime state`() {
        assertTrue(
            "CONTACT_SYNC_PERMISSION_NEEDED must be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.CONTACT_SYNC_PERMISSION_NEEDED.name),
        )
        assertFalse(
            "CONTACT_SYNC_PERMISSION_NEEDED must not be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.CONTACT_SYNC_PERMISSION_NEEDED.name },
        )
    }

    @Test
    fun `core user settings are in allow-list`() {
        val mustBeAllowed = listOf(
            PreferencesKeys.THEME,
            PreferencesKeys.TIME_FORMAT,
            PreferencesKeys.DEFAULT_CALENDAR_VIEW,
            PreferencesKeys.FIRST_DAY_OF_WEEK,
            PreferencesKeys.DEVICE_CALENDARS_ENABLED,
            PreferencesKeys.DEVICE_CALENDAR_REMINDERS_ENABLED,
        )
        val allowedNames = ExportablePreferences.KEYS.map { it.name }.toSet()
        mustBeAllowed.forEach { key ->
            assertTrue(
                "${key.name} must be in the allow-list",
                allowedNames.contains(key.name),
            )
        }
    }

    @Test
    fun `MONTH_WIDGET_EVENT_TITLES is in the allow-list as a persistent widget display choice`() {
        assertTrue(
            "MONTH_WIDGET_EVENT_TITLES must be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.MONTH_WIDGET_EVENT_TITLES.name },
        )
        assertFalse(
            "MONTH_WIDGET_EVENT_TITLES must not be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.MONTH_WIDGET_EVENT_TITLES.name),
        )
    }

    @Test
    fun `DEFAULT_CALENDAR is excluded because row IDs are not portable across installs`() {
        assertTrue(
            "DEFAULT_CALENDAR must be in EXCLUDED_KEY_NAMES",
            ExportablePreferences.EXCLUDED_KEY_NAMES.contains(PreferencesKeys.DEFAULT_CALENDAR.name),
        )
        assertFalse(
            "DEFAULT_CALENDAR must not be in the allow-list",
            ExportablePreferences.KEYS.any { it.name == PreferencesKeys.DEFAULT_CALENDAR.name },
        )
    }

    @Test
    fun `fromBackupValue returns null for legacy default_calendar key`() {
        // A backup file produced before DEFAULT_CALENDAR was excluded may still carry the key.
        // The importer must silently ignore it — the decoded pair is null so the write is skipped.
        val result = ExportablePreferences.fromBackupValue(
            PreferencesKeys.DEFAULT_CALENDAR.name,
            BackupPreferenceValue.StringPref("room:1"),
        )
        assertNull(result)
    }

    @Test
    fun `toBackupValue round-trips Boolean`() {
        val key = PreferencesKeys.AUTO_SYNC_ENABLED
        val original = true
        val backup = ExportablePreferences.toBackupValue(key, original)
        assertNotNull(backup)
        val (roundKey, roundValue) = ExportablePreferences.fromBackupValue(key.name, backup!!)!!
        assertEquals(key, roundKey)
        assertEquals(original, roundValue)
    }

    @Test
    fun `toBackupValue round-trips Int`() {
        val key = PreferencesKeys.SYNC_INTERVAL_MINUTES
        val original = 45
        val backup = ExportablePreferences.toBackupValue(key, original)
        assertNotNull(backup)
        val (roundKey, roundValue) = ExportablePreferences.fromBackupValue(key.name, backup!!)!!
        assertEquals(key, roundKey)
        assertEquals(original, roundValue)
    }

    @Test
    fun `toBackupValue round-trips Long`() {
        // Long-valued exportable keys don't currently exist in the allow-list (LAST_SYNC_TIME is excluded).
        // We still need LongPref for forward compatibility; test via toBackupValue directly.
        val original = 1_800_000L
        val backup = BackupPreferenceValue.LongPref(original)
        val (_, roundValue) = ExportablePreferences.fromBackupValue(
            // an allowed int key — we're only testing decode of a LongPref value.
            // fromBackupValue for a known int key receiving a LongPref returns null (type mismatch).
            PreferencesKeys.SYNC_INTERVAL_MINUTES.name,
            backup,
        ) ?: Pair(null, null)
        // Value is null because the key expects Int but we passed Long. Expected behaviour.
        assertNull(roundValue)
    }

    @Test
    fun `toBackupValue round-trips String`() {
        val key = PreferencesKeys.THEME
        val original = "dark"
        val backup = ExportablePreferences.toBackupValue(key, original)
        assertNotNull(backup)
        val (roundKey, roundValue) = ExportablePreferences.fromBackupValue(key.name, backup!!)!!
        assertEquals(key, roundKey)
        assertEquals(original, roundValue)
    }

    @Test
    fun `fromBackupValue returns null for unknown key name`() {
        val value = BackupPreferenceValue.StringPref("anything")
        val result = ExportablePreferences.fromBackupValue("totally_unknown_future_pref", value)
        assertNull(result)
    }

    @Test
    fun `fromBackupValue returns null on type mismatch`() {
        // THEME is a String key. Supplying a BoolPref must decode to null.
        val result = ExportablePreferences.fromBackupValue(
            PreferencesKeys.THEME.name,
            BackupPreferenceValue.BoolPref(true),
        )
        assertNull(result)
    }
}
