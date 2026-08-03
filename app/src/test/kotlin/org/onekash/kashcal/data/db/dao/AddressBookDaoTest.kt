package org.onekash.kashcal.data.db.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.AddressBook
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Integration tests for AddressBookDao.
 *
 * Tests CRUD operations, per-account URL uniqueness, FK constraints, and
 * cascade deletes for the CardDAV address-book collection table.
 */
class AddressBookDaoTest : BaseDaoTest() {

    private val accountsDao by lazy { database.accountsDao() }
    private val addressBookDao by lazy { database.addressBookDao() }

    private var testAccountId: Long = 0

    @Before
    override fun setup() {
        super.setup()
        runTest {
            testAccountId = accountsDao.insert(
                Account(provider = AccountProvider.ICLOUD, email = "test@example.test")
            )
        }
    }

    private fun createBook(
        accountId: Long = testAccountId,
        url: String = "https://contacts.example.test/books/${System.nanoTime()}/",
        displayName: String = "Contacts"
    ) = AddressBook(
        accountId = accountId,
        url = url,
        displayName = displayName
    )

    // ========== Insert / read ==========

    @Test
    fun `insert returns generated id`() = runTest {
        val id = addressBookDao.insert(createBook())
        assertTrue(id > 0)
    }

    @Test
    fun `insert and retrieve preserves fields and defaults`() = runTest {
        val id = addressBookDao.insert(createBook(displayName = "Work Contacts"))
        val retrieved = addressBookDao.getById(id)
        assertNotNull(retrieved)
        assertEquals("Work Contacts", retrieved!!.displayName)
        assertEquals(testAccountId, retrieved.accountId)
        // Defaults from §2b: vCard 3.0 fallback, read-only MVP, sync enabled.
        assertEquals("3.0", retrieved.vcardVersion)
        assertTrue(retrieved.isReadOnly)
        assertTrue(retrieved.isSyncEnabled)
        assertNull(retrieved.ctag)
        assertNull(retrieved.syncToken)
        assertNull(retrieved.description)
    }

    @Test
    fun `getByAccountId returns books for account`() = runTest {
        addressBookDao.insert(createBook(displayName = "A"))
        addressBookDao.insert(createBook(displayName = "B"))
        val books = addressBookDao.getByAccountId(testAccountId).first()
        assertEquals(2, books.size)
    }

    @Test
    fun `getByAccountIdOnce returns books for account`() = runTest {
        addressBookDao.insert(createBook())
        val books = addressBookDao.getByAccountIdOnce(testAccountId)
        assertEquals(1, books.size)
    }

    // ========== Uniqueness ==========

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun `duplicate url within same account throws`() = runTest {
        val url = "https://contacts.example.test/books/dup/"
        addressBookDao.insert(createBook(url = url))
        addressBookDao.insert(createBook(url = url))
    }

    @Test
    fun `same url under different accounts is allowed`() = runTest {
        val otherAccountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "other@example.test")
        )
        val url = "https://contacts.example.test/books/shared-path/"
        addressBookDao.insert(createBook(accountId = testAccountId, url = url))
        addressBookDao.insert(createBook(accountId = otherAccountId, url = url))
        assertEquals(1, addressBookDao.getByAccountIdOnce(testAccountId).size)
        assertEquals(1, addressBookDao.getByAccountIdOnce(otherAccountId).size)
    }

    // ========== FK constraint + cascade ==========

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun `insert with invalid accountId throws`() = runTest {
        addressBookDao.insert(createBook(accountId = 999L))
    }

    @Test
    fun `deleting account cascades to its address books`() = runTest {
        addressBookDao.insert(createBook())
        addressBookDao.insert(createBook())
        assertEquals(2, addressBookDao.getByAccountIdOnce(testAccountId).size)

        accountsDao.deleteById(testAccountId)

        assertEquals(0, addressBookDao.getByAccountIdOnce(testAccountId).size)
    }

    @Test
    fun `deleteByAccountId removes only that account's books`() = runTest {
        val otherAccountId = accountsDao.insert(
            Account(provider = AccountProvider.CALDAV, email = "other@example.test")
        )
        addressBookDao.insert(createBook(accountId = testAccountId))
        addressBookDao.insert(createBook(accountId = otherAccountId))

        addressBookDao.deleteByAccountId(testAccountId)

        assertEquals(0, addressBookDao.getByAccountIdOnce(testAccountId).size)
        assertEquals(1, addressBookDao.getByAccountIdOnce(otherAccountId).size)
    }

    // ========== Sync-cursor updates ==========

    @Test
    fun `updateSyncToken and updateCtag persist cursors`() = runTest {
        val id = addressBookDao.insert(createBook())

        addressBookDao.updateSyncToken(id, "sync-1", "ctag-1")
        addressBookDao.getById(id)!!.let {
            assertEquals("sync-1", it.syncToken)
            assertEquals("ctag-1", it.ctag)
        }

        addressBookDao.updateCtag(id, "ctag-2")
        assertEquals("ctag-2", addressBookDao.getById(id)!!.ctag)
    }

    // ========== Upsert (id-stable re-sync write path) ==========

    @Test
    fun `upsert inserts a new book and returns its id`() = runTest {
        val id = addressBookDao.upsert(createBook(displayName = "New Book"))
        assertTrue(id > 0)
        assertEquals("New Book", addressBookDao.getById(id)!!.displayName)
    }

    @Test
    fun `upsert on an existing account and url updates in place and preserves the id`() = runTest {
        val url = "https://contacts.example.test/books/stable/"
        val originalId = addressBookDao.upsert(createBook(url = url, displayName = "Before"))

        // Re-sync the same collection with refreshed metadata. The id must NOT
        // change (a child table will FK-reference it), so a fresh-id REPLACE
        // would silently break those links on every pull.
        val returnedId = addressBookDao.upsert(
            createBook(url = url, displayName = "After").copy(ctag = "ctag-2")
        )

        assertEquals(originalId, returnedId)
        assertEquals(1, addressBookDao.getByAccountIdOnce(testAccountId).size)
        addressBookDao.getById(originalId)!!.let {
            assertEquals("After", it.displayName)
            assertEquals("ctag-2", it.ctag)
        }
    }
}
