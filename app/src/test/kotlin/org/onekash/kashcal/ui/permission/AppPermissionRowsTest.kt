package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.R

/**
 * Pure-logic tests for the app-permissions row builder.
 *
 * The builder turns the current OS level plus a granted reading per permission
 * into the ordered list of rows the sheet renders. Two properties matter:
 *
 * 1. OS gating — Contacts and Calendars have been runtime permissions on every
 *    supported level and are always listed; Notifications appears from API 33,
 *    Local network from API 37. Below those levels the OS grants them
 *    implicitly, so a row would be a no-op.
 * 2. Trailing action — a granted permission shows the quiet "Allowed" state;
 *    anything not granted shows the "Allow" action. Crucially, not-granted maps
 *    to Allow even when the permission is permanently denied, so the sheet never
 *    presents a dead end (the deep-link fallback lives in the composable).
 *
 * Row order follows the sheet mockup: Notifications, Contacts, Calendars,
 * Local network — with the gated rows simply absent on older OS levels.
 */
class AppPermissionRowsTest {

    // ===== OS gating + order =====

    @Test
    fun `api 31 lists only the always-runtime permissions`() {
        val rows = buildAppPermissionRows(
            sdkInt = 31,
            notificationsGranted = false,
            contactsGranted = false,
            calendarsGranted = false,
            localNetworkGranted = false,
        )
        assertEquals(
            listOf(AppPermissionKind.CONTACTS, AppPermissionKind.CALENDARS),
            rows.map { it.kind },
        )
    }

    @Test
    fun `api 33 adds notifications ahead of contacts`() {
        val rows = buildAppPermissionRows(
            sdkInt = 33,
            notificationsGranted = false,
            contactsGranted = false,
            calendarsGranted = false,
            localNetworkGranted = false,
        )
        assertEquals(
            listOf(
                AppPermissionKind.NOTIFICATIONS,
                AppPermissionKind.CONTACTS,
                AppPermissionKind.CALENDARS,
            ),
            rows.map { it.kind },
        )
    }

    @Test
    fun `api 37 adds local network at the end`() {
        val rows = buildAppPermissionRows(
            sdkInt = 37,
            notificationsGranted = false,
            contactsGranted = false,
            calendarsGranted = false,
            localNetworkGranted = false,
        )
        assertEquals(
            listOf(
                AppPermissionKind.NOTIFICATIONS,
                AppPermissionKind.CONTACTS,
                AppPermissionKind.CALENDARS,
                AppPermissionKind.LOCAL_NETWORK,
            ),
            rows.map { it.kind },
        )
    }

    @Test
    fun `notifications gate is exclusive below api 33`() {
        val rows = buildAppPermissionRows(
            sdkInt = 32,
            notificationsGranted = false,
            contactsGranted = false,
            calendarsGranted = false,
            localNetworkGranted = false,
        )
        assertEquals(
            listOf(AppPermissionKind.CONTACTS, AppPermissionKind.CALENDARS),
            rows.map { it.kind },
        )
    }

    @Test
    fun `local network gate is exclusive below api 37`() {
        val rows = buildAppPermissionRows(
            sdkInt = 36,
            notificationsGranted = false,
            contactsGranted = false,
            calendarsGranted = false,
            localNetworkGranted = false,
        )
        assertEquals(
            listOf(
                AppPermissionKind.NOTIFICATIONS,
                AppPermissionKind.CONTACTS,
                AppPermissionKind.CALENDARS,
            ),
            rows.map { it.kind },
        )
    }

    // ===== trailing action =====

    @Test
    fun `granted permission shows the Allowed trailing`() {
        val rows = buildAppPermissionRows(
            sdkInt = 37,
            notificationsGranted = true,
            contactsGranted = true,
            calendarsGranted = true,
            localNetworkGranted = true,
        )
        assertEquals(4, rows.size)
        rows.forEach { assertEquals(PermissionTrailing.ALLOWED, it.trailing) }
    }

    @Test
    fun `not-granted permission shows the Allow trailing`() {
        val rows = buildAppPermissionRows(
            sdkInt = 37,
            notificationsGranted = false,
            contactsGranted = false,
            calendarsGranted = false,
            localNetworkGranted = false,
        )
        rows.forEach { assertEquals(PermissionTrailing.ALLOW, it.trailing) }
    }

    @Test
    fun `trailing is resolved per permission independently`() {
        val rows = buildAppPermissionRows(
            sdkInt = 37,
            notificationsGranted = true,
            contactsGranted = false,
            calendarsGranted = true,
            localNetworkGranted = false,
        ).associateBy { it.kind }

        assertEquals(PermissionTrailing.ALLOWED, rows.getValue(AppPermissionKind.NOTIFICATIONS).trailing)
        assertEquals(PermissionTrailing.ALLOW, rows.getValue(AppPermissionKind.CONTACTS).trailing)
        assertEquals(PermissionTrailing.ALLOWED, rows.getValue(AppPermissionKind.CALENDARS).trailing)
        assertEquals(PermissionTrailing.ALLOW, rows.getValue(AppPermissionKind.LOCAL_NETWORK).trailing)
    }

    @Test
    fun `re-reading a revoked grant flips the row from Allowed back to Allow`() {
        // Models what the sheet does on resume: it rebuilds the rows from a fresh
        // grant reading, so a permission revoked in system settings during a
        // deep-link round trip reverts from the quiet Allowed state to Allow.
        val granted = buildAppPermissionRows(
            sdkInt = 37,
            notificationsGranted = true,
            contactsGranted = true,
            calendarsGranted = true,
            localNetworkGranted = true,
        ).associateBy { it.kind }
        assertEquals(PermissionTrailing.ALLOWED, granted.getValue(AppPermissionKind.CONTACTS).trailing)

        val afterRevoke = buildAppPermissionRows(
            sdkInt = 37,
            notificationsGranted = true,
            contactsGranted = false,
            calendarsGranted = true,
            localNetworkGranted = true,
        ).associateBy { it.kind }
        assertEquals(PermissionTrailing.ALLOW, afterRevoke.getValue(AppPermissionKind.CONTACTS).trailing)
        // The untouched permissions stay Allowed.
        assertEquals(PermissionTrailing.ALLOWED, afterRevoke.getValue(AppPermissionKind.CALENDARS).trailing)
    }

    // ===== each row carries its own name + tooltip text =====

    // ===== escape-hatch: a fired Allow that can't surface a dialog falls back to settings =====

    @Test
    fun `a granted request does not need the settings fallback`() {
        assertEquals(false, allowRequestNeedsSettingsFallback(granted = true, rationaleAfter = false))
    }

    @Test
    fun `a denial that can still be re-asked does not need the settings fallback`() {
        // Rationale still offered afterwards -> the next Allow tap can surface a dialog.
        assertEquals(false, allowRequestNeedsSettingsFallback(granted = false, rationaleAfter = true))
    }

    @Test
    fun `a permanently-denied request needs the settings fallback`() {
        // Denied with no rationale afterwards -> "don't ask again"; only system settings can grant it.
        assertEquals(true, allowRequestNeedsSettingsFallback(granted = false, rationaleAfter = false))
    }

    @Test
    fun `each kind carries its own name and why strings`() {
        val rows = buildAppPermissionRows(
            sdkInt = 37,
            notificationsGranted = false,
            contactsGranted = false,
            calendarsGranted = false,
            localNetworkGranted = false,
        ).associateBy { it.kind }

        assertEquals(R.string.permission_name_notifications, rows.getValue(AppPermissionKind.NOTIFICATIONS).nameRes)
        assertEquals(R.string.permission_why_notifications, rows.getValue(AppPermissionKind.NOTIFICATIONS).whyRes)
        assertEquals(R.string.permission_name_contacts, rows.getValue(AppPermissionKind.CONTACTS).nameRes)
        assertEquals(R.string.permission_why_contacts, rows.getValue(AppPermissionKind.CONTACTS).whyRes)
        assertEquals(R.string.permission_name_calendars, rows.getValue(AppPermissionKind.CALENDARS).nameRes)
        assertEquals(R.string.permission_why_calendars, rows.getValue(AppPermissionKind.CALENDARS).whyRes)
        assertEquals(R.string.permission_name_local_network, rows.getValue(AppPermissionKind.LOCAL_NETWORK).nameRes)
        assertEquals(R.string.permission_why_local_network, rows.getValue(AppPermissionKind.LOCAL_NETWORK).whyRes)
    }
}
