package org.onekash.kashcal.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.converter.Converters

/**
 * Unit tests for [AccountProvider] enum and its TypeConverter.
 */
class AccountProviderTest {

    private val converters = Converters()

    // ========== fromString Tests ==========

    @Test
    fun `fromString returns LOCAL for local`() {
        assertEquals(AccountProvider.LOCAL, AccountProvider.fromString("local"))
    }

    @Test
    fun `fromString returns ICLOUD for icloud`() {
        assertEquals(AccountProvider.ICLOUD, AccountProvider.fromString("icloud"))
    }

    @Test
    fun `fromString returns ICS for ics`() {
        assertEquals(AccountProvider.ICS, AccountProvider.fromString("ics"))
    }

    @Test
    fun `fromString returns CALDAV for caldav`() {
        assertEquals(AccountProvider.CALDAV, AccountProvider.fromString("caldav"))
    }

    @Test
    fun `fromString returns CONTACTS for contacts`() {
        assertEquals(AccountProvider.CONTACTS, AccountProvider.fromString("contacts"))
    }

    @Test
    fun `fromString is case insensitive`() {
        assertEquals(AccountProvider.ICLOUD, AccountProvider.fromString("ICLOUD"))
        assertEquals(AccountProvider.ICLOUD, AccountProvider.fromString("ICloud"))
        assertEquals(AccountProvider.LOCAL, AccountProvider.fromString("LOCAL"))
        assertEquals(AccountProvider.LOCAL, AccountProvider.fromString("Local"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromString throws for unknown provider`() {
        AccountProvider.fromString("unknown")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromString throws for empty string`() {
        AccountProvider.fromString("")
    }

    // ========== requiresSync Tests ==========

    @Test
    fun `requiresSync is false for LOCAL`() {
        assertFalse(AccountProvider.LOCAL.requiresSync)
    }

    @Test
    fun `requiresSync is true for ICLOUD`() {
        assertTrue(AccountProvider.ICLOUD.requiresSync)
    }

    @Test
    fun `requiresSync is true for ICS`() {
        assertTrue(AccountProvider.ICS.requiresSync)
    }

    @Test
    fun `requiresSync is true for CALDAV`() {
        assertTrue(AccountProvider.CALDAV.requiresSync)
    }

    // ========== pullOnly Tests ==========

    @Test
    fun `pullOnly is true only for ICS`() {
        assertTrue(AccountProvider.ICS.pullOnly)
        assertFalse(AccountProvider.LOCAL.pullOnly)
        assertFalse(AccountProvider.ICLOUD.pullOnly)
        assertFalse(AccountProvider.CALDAV.pullOnly)
    }

    // ========== supportsCalDAV Tests ==========

    @Test
    fun `supportsCalDAV is true for ICLOUD and CALDAV`() {
        assertTrue(AccountProvider.ICLOUD.supportsCalDAV)
        assertTrue(AccountProvider.CALDAV.supportsCalDAV)
    }

    @Test
    fun `supportsCalDAV is false for LOCAL and ICS`() {
        assertFalse(AccountProvider.LOCAL.supportsCalDAV)
        assertFalse(AccountProvider.ICS.supportsCalDAV)
    }

    // ========== requiresNetwork Tests ==========

    @Test
    fun `requiresNetwork is false only for LOCAL`() {
        assertFalse(AccountProvider.LOCAL.requiresNetwork)
        assertTrue(AccountProvider.ICLOUD.requiresNetwork)
        assertTrue(AccountProvider.ICS.requiresNetwork)
        assertTrue(AccountProvider.CALDAV.requiresNetwork)
    }

    // ========== supportsIncrementalSync Tests ==========

    @Test
    fun `supportsIncrementalSync matches CalDAV providers`() {
        assertTrue(AccountProvider.ICLOUD.supportsIncrementalSync)
        assertTrue(AccountProvider.CALDAV.supportsIncrementalSync)
        assertFalse(AccountProvider.LOCAL.supportsIncrementalSync)
        assertFalse(AccountProvider.ICS.supportsIncrementalSync)
    }

    // ========== supportsReminders Tests ==========

    @Test
    fun `supportsReminders is true for all providers`() {
        AccountProvider.entries.forEach { provider ->
            assertTrue("${provider.name} should support reminders", provider.supportsReminders)
        }
    }

    // ========== displayName Tests ==========

    @Test
    fun `displayName is human readable`() {
        assertEquals("Local", AccountProvider.LOCAL.displayName)
        assertEquals("iCloud", AccountProvider.ICLOUD.displayName)
        assertEquals("ICS Subscription", AccountProvider.ICS.displayName)
        assertEquals("Contact Birthdays", AccountProvider.CONTACTS.displayName)
        assertEquals("CalDAV", AccountProvider.CALDAV.displayName)
    }

    // ========== TypeConverter Tests ==========

    @Test
    fun `TypeConverter round-trip preserves LOCAL`() {
        val original = AccountProvider.LOCAL
        val stored = converters.fromAccountProvider(original)
        val restored = converters.toAccountProvider(stored)
        assertEquals(original, restored)
    }

    @Test
    fun `TypeConverter round-trip preserves ICLOUD`() {
        val original = AccountProvider.ICLOUD
        val stored = converters.fromAccountProvider(original)
        val restored = converters.toAccountProvider(stored)
        assertEquals(original, restored)
    }

    @Test
    fun `TypeConverter round-trip preserves all providers`() {
        AccountProvider.entries.forEach { provider ->
            val stored = converters.fromAccountProvider(provider)
            val restored = converters.toAccountProvider(stored)
            assertEquals(provider, restored)
        }
    }

    @Test
    fun `TypeConverter stores lowercase value`() {
        assertEquals("local", converters.fromAccountProvider(AccountProvider.LOCAL))
        assertEquals("icloud", converters.fromAccountProvider(AccountProvider.ICLOUD))
        assertEquals("ics", converters.fromAccountProvider(AccountProvider.ICS))
        assertEquals("contacts", converters.fromAccountProvider(AccountProvider.CONTACTS))
        assertEquals("caldav", converters.fromAccountProvider(AccountProvider.CALDAV))
    }

    @Test
    fun `TypeConverter reads existing database values`() {
        // These are the values currently stored in the database
        assertEquals(AccountProvider.LOCAL, converters.toAccountProvider("local"))
        assertEquals(AccountProvider.ICLOUD, converters.toAccountProvider("icloud"))
        assertEquals(AccountProvider.ICS, converters.toAccountProvider("ics"))
        assertEquals(AccountProvider.CONTACTS, converters.toAccountProvider("contacts"))
        assertEquals(AccountProvider.CALDAV, converters.toAccountProvider("caldav"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TypeConverter throws for unknown value`() {
        converters.toAccountProvider("google")
    }
}
