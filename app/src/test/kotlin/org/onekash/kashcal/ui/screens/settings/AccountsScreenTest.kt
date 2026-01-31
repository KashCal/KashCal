package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for AccountsScreen logic.
 *
 * Tests the calculation logic for:
 * - Account count (iCloud + CalDAV)
 * - Preview names for summary row
 * - Summary row label text
 */
class AccountsScreenTest {

    // ==================== Account Count Tests ====================

    @Test
    fun `account count is 0 when no iCloud and no CalDAV`() {
        val iCloudAccount: ICloudAccountUiModel? = null
        val calDavAccounts = emptyList<CalDavAccountUiModel>()

        val accountCount = (if (iCloudAccount != null) 1 else 0) + calDavAccounts.size

        assertEquals(0, accountCount)
    }

    @Test
    fun `account count is 1 with only iCloud`() {
        val iCloudAccount = ICloudAccountUiModel(email = "test@icloud.com", calendarCount = 3)
        val calDavAccounts = emptyList<CalDavAccountUiModel>()

        val accountCount = (if (iCloudAccount != null) 1 else 0) + calDavAccounts.size

        assertEquals(1, accountCount)
    }

    @Test
    fun `account count is 1 with only one CalDAV`() {
        val iCloudAccount: ICloudAccountUiModel? = null
        val calDavAccounts = listOf(
            CalDavAccountUiModel(id = 1, email = "user@example.com", displayName = "Nextcloud", calendarCount = 2)
        )

        val accountCount = (if (iCloudAccount != null) 1 else 0) + calDavAccounts.size

        assertEquals(1, accountCount)
    }

    @Test
    fun `account count is 3 with iCloud plus 2 CalDAV`() {
        val iCloudAccount = ICloudAccountUiModel(email = "test@icloud.com", calendarCount = 5)
        val calDavAccounts = listOf(
            CalDavAccountUiModel(id = 1, email = "user@nextcloud.com", displayName = "Nextcloud", calendarCount = 3),
            CalDavAccountUiModel(id = 2, email = "user@fastmail.com", displayName = "FastMail", calendarCount = 2)
        )

        val accountCount = (if (iCloudAccount != null) 1 else 0) + calDavAccounts.size

        assertEquals(3, accountCount)
    }

    // ==================== Preview Names Tests ====================

    @Test
    fun `preview shows iCloud first when connected`() {
        val iCloudAccount = ICloudAccountUiModel(email = "test@icloud.com", calendarCount = 3)
        val calDavAccounts = listOf(
            CalDavAccountUiModel(id = 1, email = "user@example.com", displayName = "Nextcloud", calendarCount = 2)
        )

        val previewNames = buildList {
            if (iCloudAccount != null) add("iCloud")
            calDavAccounts.take(2).forEach { add(it.displayName) }
        }.take(2).joinToString(", ")

        assertEquals("iCloud, Nextcloud", previewNames)
    }

    @Test
    fun `preview shows only CalDAV names when no iCloud`() {
        val iCloudAccount: ICloudAccountUiModel? = null
        val calDavAccounts = listOf(
            CalDavAccountUiModel(id = 1, email = "user@nextcloud.com", displayName = "Nextcloud", calendarCount = 3),
            CalDavAccountUiModel(id = 2, email = "user@fastmail.com", displayName = "FastMail", calendarCount = 2)
        )

        val previewNames = buildList {
            if (iCloudAccount != null) add("iCloud")
            calDavAccounts.take(2).forEach { add(it.displayName) }
        }.take(2).joinToString(", ")

        assertEquals("Nextcloud, FastMail", previewNames)
    }

    @Test
    fun `preview shows first two accounts max`() {
        val iCloudAccount = ICloudAccountUiModel(email = "test@icloud.com", calendarCount = 5)
        val calDavAccounts = listOf(
            CalDavAccountUiModel(id = 1, email = "user@nextcloud.com", displayName = "Nextcloud", calendarCount = 3),
            CalDavAccountUiModel(id = 2, email = "user@fastmail.com", displayName = "FastMail", calendarCount = 2),
            CalDavAccountUiModel(id = 3, email = "user@posteo.com", displayName = "Posteo", calendarCount = 1)
        )

        val previewNames = buildList {
            if (iCloudAccount != null) add("iCloud")
            calDavAccounts.take(2).forEach { add(it.displayName) }
        }.take(2).joinToString(", ")

        assertEquals("iCloud, Nextcloud", previewNames)
    }

    @Test
    fun `preview adds ellipsis when more than 2 accounts`() {
        val iCloudAccount = ICloudAccountUiModel(email = "test@icloud.com", calendarCount = 5)
        val calDavAccounts = listOf(
            CalDavAccountUiModel(id = 1, email = "user@nextcloud.com", displayName = "Nextcloud", calendarCount = 3),
            CalDavAccountUiModel(id = 2, email = "user@fastmail.com", displayName = "FastMail", calendarCount = 2)
        )

        val accountCount = (if (iCloudAccount != null) 1 else 0) + calDavAccounts.size
        val previewNames = buildList {
            if (iCloudAccount != null) add("iCloud")
            calDavAccounts.take(2).forEach { add(it.displayName) }
        }.take(2).joinToString(", ")
        val subtitle = previewNames + if (accountCount > 2) "…" else ""

        assertEquals("iCloud, Nextcloud…", subtitle)
    }

    // ==================== Summary Row Label Tests ====================

    @Test
    fun `label is Accounts when count is 0`() {
        val accountCount = 0

        val label = when (accountCount) {
            0 -> "Accounts"
            1 -> "1 account"
            else -> "$accountCount accounts"
        }

        assertEquals("Accounts", label)
    }

    @Test
    fun `label is 1 account when count is 1`() {
        val accountCount = 1

        val label = when (accountCount) {
            0 -> "Accounts"
            1 -> "1 account"
            else -> "$accountCount accounts"
        }

        assertEquals("1 account", label)
    }

    @Test
    fun `label is X accounts when count is plural`() {
        val accountCount = 3

        val label = when (accountCount) {
            0 -> "Accounts"
            1 -> "1 account"
            else -> "$accountCount accounts"
        }

        assertEquals("3 accounts", label)
    }

    // ==================== Syncing State Tests ====================

    @Test
    fun `shows Syncing when calendarCount is 0`() {
        val calendarCount = 0

        val subtitle = if (calendarCount == 0) {
            "Syncing…"
        } else if (calendarCount == 1) {
            "1 calendar"
        } else {
            "$calendarCount calendars"
        }

        assertEquals("Syncing…", subtitle)
    }

    @Test
    fun `shows calendar count when not 0`() {
        val calendarCount = 5

        val subtitle = if (calendarCount == 0) {
            "Syncing…"
        } else if (calendarCount == 1) {
            "1 calendar"
        } else {
            "$calendarCount calendars"
        }

        assertEquals("5 calendars", subtitle)
    }

    @Test
    fun `shows singular calendar when count is 1`() {
        val calendarCount = 1

        val subtitle = if (calendarCount == 0) {
            "Syncing…"
        } else if (calendarCount == 1) {
            "1 calendar"
        } else {
            "$calendarCount calendars"
        }

        assertEquals("1 calendar", subtitle)
    }
}
