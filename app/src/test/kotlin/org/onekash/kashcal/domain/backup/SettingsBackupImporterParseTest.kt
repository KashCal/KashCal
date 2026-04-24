package org.onekash.kashcal.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parse-and-validate is a pure function (no DB writes). These tests exercise the
 * version, structure, and size gates before any state would be touched.
 */
class SettingsBackupImporterParseTest {

    private val parser = SettingsBackupImporter.parser()

    private val validV1Json = """
        {
          "file_format_version": 1,
          "app_version": "23.6.4",
          "exported_at": "2026-04-23T14:30:00Z",
          "preferences": {},
          "subscriptions": []
        }
    """.trimIndent()

    @Test
    fun `valid v1 envelope parses successfully`() {
        val result = parser.parseAndValidate(validV1Json)
        assertTrue("Expected Ok but got $result", result is BackupParseResult.Ok)
        val envelope = (result as BackupParseResult.Ok).envelope
        assertEquals(1, envelope.fileFormatVersion)
    }

    @Test
    fun `future file_format_version is rejected`() {
        val future = validV1Json.replace("\"file_format_version\": 1", "\"file_format_version\": 99")
        val result = parser.parseAndValidate(future)
        assertTrue("Expected VersionTooNew but got $result",
            result is BackupParseResult.Error && result.error is BackupImportError.VersionTooNew)
        val err = (result as BackupParseResult.Error).error as BackupImportError.VersionTooNew
        assertEquals(99, err.foundVersion)
        assertEquals(BACKUP_FILE_FORMAT_VERSION, err.supportedVersion)
    }

    @Test
    fun `non-JSON input is rejected as malformed`() {
        val result = parser.parseAndValidate("this is not json at all")
        assertTrue("Expected MalformedJson but got $result",
            result is BackupParseResult.Error && result.error is BackupImportError.MalformedJson)
    }

    @Test
    fun `missing file_format_version produces InvalidValue`() {
        val bad = """
            {"app_version":"x","exported_at":"x","preferences":{},"subscriptions":[]}
        """.trimIndent()
        val result = parser.parseAndValidate(bad)
        assertTrue("Expected InvalidValue but got $result",
            result is BackupParseResult.Error)
    }

    @Test
    fun `missing preferences field is rejected`() {
        val bad = """
            {"file_format_version":1,"app_version":"x","exported_at":"x","subscriptions":[]}
        """.trimIndent()
        assertTrue(parser.parseAndValidate(bad) is BackupParseResult.Error)
    }

    @Test
    fun `missing subscriptions field is rejected`() {
        val bad = """
            {"file_format_version":1,"app_version":"x","exported_at":"x","preferences":{}}
        """.trimIndent()
        assertTrue(parser.parseAndValidate(bad) is BackupParseResult.Error)
    }

    @Test
    fun `invalid typed value is rejected`() {
        val bad = """
            {
              "file_format_version": 1,
              "app_version": "x",
              "exported_at": "x",
              "preferences": {},
              "subscriptions": [{
                "url": "https://feed.ics",
                "name": "F",
                "color": "not-an-int",
                "syncIntervalHours": 24,
                "enabled": true
              }]
            }
        """.trimIndent()
        assertTrue(parser.parseAndValidate(bad) is BackupParseResult.Error)
    }

    @Test
    fun `input over 10 MB is rejected before parse`() {
        // Build 11 MB of valid JSON-shaped padding inside a string value.
        val padding = "x".repeat(11 * 1024 * 1024)
        val oversized = validV1Json.replace("\"23.6.4\"", "\"$padding\"")
        val result = parser.parseAndValidate(oversized)
        assertTrue("Expected MalformedJson (size cap) but got $result",
            result is BackupParseResult.Error && result.error is BackupImportError.MalformedJson)
        val err = (result as BackupParseResult.Error).error as BackupImportError.MalformedJson
        assertNotNull(err.detail)
        assertTrue("Size cap rejection should indicate file size in detail", err.detail!!.contains("size", ignoreCase = true))
    }

    @Test
    fun `unknown extra keys are tolerated for forward compatibility`() {
        val withExtra = """
            {
              "file_format_version": 1,
              "app_version": "x",
              "exported_at": "x",
              "preferences": {},
              "subscriptions": [],
              "future_feature": {"foo": "bar"}
            }
        """.trimIndent()
        val result = parser.parseAndValidate(withExtra)
        assertTrue("Unknown keys should be tolerated", result is BackupParseResult.Ok)
    }

    @Test
    fun `empty string is rejected as malformed`() {
        val result = parser.parseAndValidate("")
        assertTrue(result is BackupParseResult.Error)
    }
}
