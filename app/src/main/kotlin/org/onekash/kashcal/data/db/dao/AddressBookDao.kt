package org.onekash.kashcal.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.onekash.kashcal.data.db.entity.AddressBook

/** Reads and writes the `address_books` table of CardDAV collections, in [CalendarsDao]'s style. */
@Dao
interface AddressBookDao {

    // ========== Read Operations ==========

    @Query("SELECT * FROM address_books ORDER BY display_name ASC")
    fun getAll(): Flow<List<AddressBook>>

    @Query("SELECT * FROM address_books WHERE id = :id")
    suspend fun getById(id: Long): AddressBook?

    /** The collection URL is unique per account. */
    @Query("SELECT * FROM address_books WHERE account_id = :accountId AND url = :url")
    suspend fun getByAccountIdAndUrl(accountId: Long, url: String): AddressBook?

    @Query("SELECT * FROM address_books WHERE account_id = :accountId ORDER BY display_name ASC")
    fun getByAccountId(accountId: Long): Flow<List<AddressBook>>

    @Query("SELECT * FROM address_books WHERE account_id = :accountId ORDER BY display_name ASC")
    suspend fun getByAccountIdOnce(accountId: Long): List<AddressBook>

    // ========== Write Operations ==========

    /** Returns the new row ID. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(addressBook: AddressBook): Long

    /**
     * Inserts the book, or updates the one with the same `(account_id, url)`, and returns its
     * row id.
     *
     * Don't switch to `OnConflictStrategy.REPLACE`: it deletes and re-inserts on conflict,
     * minting a new `id` on every re-pull, and would cascade-delete the rows of any future table
     * that references `address_books.id`. The [update] keeps a book's `id` stable across syncs,
     * as [AddressBook] documents.
     */
    @Transaction
    suspend fun upsert(addressBook: AddressBook): Long {
        val existing = getByAccountIdAndUrl(addressBook.accountId, addressBook.url)
        return if (existing == null) {
            insert(addressBook)
        } else {
            update(addressBook.copy(id = existing.id))
            existing.id
        }
    }

    @Update
    suspend fun update(addressBook: AddressBook)

    @Delete
    suspend fun delete(addressBook: AddressBook)

    @Query("DELETE FROM address_books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM address_books WHERE account_id = :accountId")
    suspend fun deleteByAccountId(accountId: Long)

    // ========== Sync Metadata Updates ==========

    /** Stores the book's sync-token and ctag after a sync. */
    @Query("UPDATE address_books SET sync_token = :syncToken, ctag = :ctag WHERE id = :id")
    suspend fun updateSyncToken(id: Long, syncToken: String?, ctag: String?)

    /** Stores only the ctag, for servers without sync-tokens. */
    @Query("UPDATE address_books SET ctag = :ctag WHERE id = :id")
    suspend fun updateCtag(id: Long, ctag: String?)

    /** Sets the per-book "sync this address book" toggle. */
    @Query("UPDATE address_books SET is_sync_enabled = :enabled WHERE id = :id")
    suspend fun setSyncEnabled(id: Long, enabled: Boolean)
}
