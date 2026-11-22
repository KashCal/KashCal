package org.onekash.kashcal.sync.contacts

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import org.robolectric.Robolectric

/**
 * A small Contacts Provider stand-in for Robolectric tests, backed by in-memory SQLite, so
 * [AndroidContactsProviderRepository]'s real queries, selections and batches run against real
 * SQL instead of a mocked resolver.
 *
 * It follows the platform provider (ContactsProvider2, ContactsDatabaseHelper,
 * TransactionContext and AbstractContactsProvider in the AOSP ContactsProvider sources; the
 * main and android12-release branches agree on every rule here). `SqliteContactsProviderTest`
 * pins each one:
 * - RawContacts, Data, Groups and Settings with the platform column names. Writing a column the
 *   table doesn't have throws, so a misspelt column fails the test. Data is read through a view
 *   that adds the raw contact's account and a GroupMembership row's `group_sourceid`, as the
 *   platform's Data view does, and a GroupMembership insert carrying only `group_sourceid` is
 *   resolved to the account's group, created if missing.
 * - RawContacts.VERSION goes up on a Data insert (once per raw contact between flushes, even
 *   for a sync adapter), on a Data update or delete (one per row, by trigger) and when DELETED
 *   changes (trigger). Writing SYNC1-4, SOURCE_ID or DIRTY does not change it.
 * - An app (non-sync-adapter) Data insert, update or delete marks the raw contact DIRTY at the
 *   next flush; a sync adapter's does not. An app's STARRED update marks it DIRTY without a
 *   VERSION change. A raw contact insert is not DIRTY by itself.
 * - Pending DIRTY and VERSION changes are flushed before every update and delete and when the
 *   transaction ends (ContactsProvider2.updateInTransaction, deleteInTransaction and
 *   flushTransactionalChanges), so an update later in a batch sees an earlier Data insert's
 *   VERSION bump.
 * - An app Data update matches only rows with `is_read_only = 0`, and RAW_CONTACT_ID and
 *   MIMETYPE in its values are dropped (ContactsProvider2.updateData).
 * - `raw_contacts/#` update runs `_id = ? AND (selection)`, and for an app also requires
 *   `raw_contact_is_read_only = 0`. `raw_contacts/#` delete ignores the selection. The
 *   ACCOUNT_NAME and ACCOUNT_TYPE query parameters set the account on an insert and scope
 *   directory (`raw_contacts`, `data`) updates and deletes, never `raw_contacts/#`.
 * - Deleting a raw contact as an app sets DELETED = 1 and DIRTY = 1 when it belongs to an
 *   account; a sync adapter's delete, or one of a raw contact without an account, removes the
 *   row and its Data.
 * - applyBatch runs in one transaction; an operation that throws rolls back the whole batch.
 *
 * Not modelled: aggregation into Contacts, lookup keys, photo files and display-photo URIs,
 * name lookup and search, directory and profile URIs, stream items, Groups and Settings beyond
 * the columns the repository writes, query-side account scoping (queries honour only their
 * selection; the repository's RawContacts and Groups queries carry their own account scope, and
 * its Data reads select by raw contact id), and applyBatch yield points
 * (AbstractContactsProvider.applyBatch can commit earlier operations at a yield under contention
 * and enforces MAX_OPERATIONS_PER_YIELD_POINT; here a batch is always one transaction).
 */
class SqliteContactsProvider : ContentProvider() {

    lateinit var db: SQLiteDatabase
        private set

    private var inBatch = false
    private val dirtyAtFlush = mutableSetOf<Long>()
    private val versionAtFlush = mutableSetOf<Long>()

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE $RAW (${RawContacts._ID} INTEGER PRIMARY KEY AUTOINCREMENT, " +
                RAW_TEXT.joinToString(", ") { "$it TEXT" } + ", " +
                RAW_INT.joinToString(", ") { "$it INTEGER NOT NULL DEFAULT 0" } + ", " +
                "${RawContacts.VERSION} INTEGER NOT NULL DEFAULT 1)",
        )
        db.execSQL(
            "CREATE TABLE $DATA (${Data._ID} INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "${Data.RAW_CONTACT_ID} INTEGER NOT NULL, ${Data.MIMETYPE} TEXT, " +
                (1..14).joinToString(", ") { "data$it TEXT" } + ", data15 BLOB, " +
                (1..4).joinToString(", ") { "data_sync$it TEXT" } + ", " +
                "${Data.IS_PRIMARY} INTEGER NOT NULL DEFAULT 0, " +
                "${Data.IS_SUPER_PRIMARY} INTEGER NOT NULL DEFAULT 0, " +
                "${Data.IS_READ_ONLY} INTEGER NOT NULL DEFAULT 0, " +
                "${Data.DATA_VERSION} INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE $GROUPS (${Groups._ID} INTEGER PRIMARY KEY AUTOINCREMENT, " +
                GROUP_TEXT.joinToString(", ") { "$it TEXT" } + ", " +
                GROUP_INT.joinToString(", ") { "$it INTEGER NOT NULL DEFAULT 0" } + ")",
        )
        db.execSQL(
            "CREATE TABLE $SETTINGS (${ContactsContract.Settings.ACCOUNT_NAME} TEXT, " +
                "${ContactsContract.Settings.ACCOUNT_TYPE} TEXT, ${ContactsContract.Settings.DATA_SET} TEXT, " +
                "${ContactsContract.Settings.UNGROUPED_VISIBLE} INTEGER NOT NULL DEFAULT 0, " +
                "${ContactsContract.Settings.SHOULD_SYNC} INTEGER NOT NULL DEFAULT 1, " +
                "UNIQUE (${ContactsContract.Settings.ACCOUNT_NAME}, ${ContactsContract.Settings.ACCOUNT_TYPE}) ON CONFLICT REPLACE)",
        )
        // ContactsDatabaseHelper: raw_contacts_marked_deleted, data_updated, data_deleted.
        db.execSQL(
            "CREATE TRIGGER raw_contacts_marked_deleted AFTER UPDATE ON $RAW BEGIN " +
                "UPDATE $RAW SET ${RawContacts.VERSION} = OLD.${RawContacts.VERSION} + 1 " +
                "WHERE ${RawContacts._ID} = OLD.${RawContacts._ID} " +
                "AND NEW.${RawContacts.DELETED} != OLD.${RawContacts.DELETED}; END",
        )
        db.execSQL(
            "CREATE TRIGGER data_updated AFTER UPDATE ON $DATA BEGIN " +
                "UPDATE $DATA SET ${Data.DATA_VERSION} = OLD.${Data.DATA_VERSION} + 1 WHERE ${Data._ID} = OLD.${Data._ID}; " +
                "UPDATE $RAW SET ${RawContacts.VERSION} = ${RawContacts.VERSION} + 1 " +
                "WHERE ${RawContacts._ID} = OLD.${Data.RAW_CONTACT_ID}; END",
        )
        db.execSQL(
            "CREATE TRIGGER data_deleted BEFORE DELETE ON $DATA BEGIN " +
                "UPDATE $RAW SET ${RawContacts.VERSION} = ${RawContacts.VERSION} + 1 " +
                "WHERE ${RawContacts._ID} = OLD.${Data.RAW_CONTACT_ID}; END",
        )
        db.execSQL(
            "CREATE VIEW $DATA_VIEW AS SELECT $DATA.*, " +
                "$RAW.${RawContacts.ACCOUNT_NAME} AS ${RawContacts.ACCOUNT_NAME}, " +
                "$RAW.${RawContacts.ACCOUNT_TYPE} AS ${RawContacts.ACCOUNT_TYPE}, " +
                "$GROUPS.${Groups.SOURCE_ID} AS ${GroupMembership.GROUP_SOURCE_ID} " +
                "FROM $DATA JOIN $RAW ON $DATA.${Data.RAW_CONTACT_ID} = $RAW.${RawContacts._ID} " +
                "LEFT JOIN $GROUPS ON $DATA.${Data.MIMETYPE} = '${GroupMembership.CONTENT_ITEM_TYPE}' " +
                "AND $DATA.data1 = $GROUPS.${Groups._ID}",
        )
        return true
    }

    // ---- ContentProvider ----

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val (table, id) = resolve(uri)
        val source = if (table == DATA) DATA_VIEW else table
        val (sel, args) = withId(id, selection, selectionArgs)
        return db.query(source, projection, sel, args, null, null, sortOrder)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val (table, _) = resolve(uri)
        val v = ContentValues(values ?: ContentValues())
        val syncAdapter = isSyncAdapter(uri)
        val newId = when (table) {
            RAW -> {
                checkColumns(RAW, v, RAW_COLUMNS)
                applyAccount(uri, v)
                db.insertOrThrow(RAW, null, v)
            }
            DATA -> {
                checkColumns(DATA, v, DATA_COLUMNS + GroupMembership.GROUP_SOURCE_ID)
                val rawId = v.getAsLong(Data.RAW_CONTACT_ID)
                    ?: throw IllegalArgumentException("${Data.RAW_CONTACT_ID} is required")
                resolveGroupSourceId(rawId, v)
                val dataId = db.insertOrThrow(DATA, null, v)
                versionAtFlush += rawId
                if (!syncAdapter) dirtyAtFlush += rawId
                dataId
            }
            GROUPS -> {
                checkColumns(GROUPS, v, GROUP_COLUMNS)
                applyAccount(uri, v)
                db.insertOrThrow(GROUPS, null, v)
            }
            else -> {
                checkColumns(SETTINGS, v, SETTINGS_COLUMNS)
                applyAccount(uri, v)
                db.insertOrThrow(SETTINGS, null, v)
            }
        }
        flushUnlessInBatch()
        return ContentUris.withAppendedId(uri.buildUpon().clearQuery().build(), newId)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        flush()
        val (table, id) = resolve(uri)
        val v = ContentValues(values ?: ContentValues())
        val syncAdapter = isSyncAdapter(uri)
        val count = when (table) {
            RAW -> {
                checkColumns(RAW, v, RAW_COLUMNS)
                var (sel, args) = if (id != null) withId(id, selection, selectionArgs)
                else scopeToAccount(uri, selection, selectionArgs)
                if (!syncAdapter) sel = and(sel, "${RawContacts.RAW_CONTACT_IS_READ_ONLY} = 0")
                val ids = idsOf(RAW, sel, args)
                if (!syncAdapter && v.containsKey(RawContacts.STARRED)) dirtyAtFlush += ids
                ids.forEach { db.update(RAW, v, "${RawContacts._ID} = ?", arrayOf(it.toString())) }
                ids.size
            }
            DATA -> {
                checkColumns(DATA, v, DATA_COLUMNS)
                v.remove(Data.RAW_CONTACT_ID)
                v.remove(Data.MIMETYPE)
                var (sel, args) = if (id != null) withId(id, selection, selectionArgs)
                else scopeToAccount(uri, selection, selectionArgs)
                if (!syncAdapter) sel = and(sel, "${Data.IS_READ_ONLY} = 0")
                val rows = dataRowsOf(sel, args)
                rows.forEach { (dataId, rawId) ->
                    db.update(DATA, v, "${Data._ID} = ?", arrayOf(dataId.toString()))
                    if (!syncAdapter) dirtyAtFlush += rawId
                }
                rows.size
            }
            else -> {
                checkColumns(table, v, if (table == GROUPS) GROUP_COLUMNS else SETTINGS_COLUMNS)
                val (sel, args) = withId(id, selection, selectionArgs)
                db.update(table, v, sel, args)
            }
        }
        flushUnlessInBatch()
        return count
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        flush()
        val (table, id) = resolve(uri)
        val syncAdapter = isSyncAdapter(uri)
        val count = when (table) {
            RAW -> {
                val ids = if (id != null) {
                    // The platform deletes raw_contacts/# by id alone; a selection is ignored.
                    idsOf(RAW, "${RawContacts._ID} = ?", arrayOf(id.toString()))
                } else {
                    val (sel, args) = scopeToAccount(uri, selection, selectionArgs)
                    idsOf(RAW, sel, args)
                }
                ids.forEach { deleteRawContact(it, syncAdapter) }
                ids.size
            }
            DATA -> {
                val (sel, args) = if (id != null) withId(id, selection, selectionArgs)
                else scopeToAccount(uri, selection, selectionArgs)
                val rows = dataRowsOf(sel, args)
                rows.forEach { (dataId, rawId) ->
                    db.delete(DATA, "${Data._ID} = ?", arrayOf(dataId.toString()))
                    if (!syncAdapter) dirtyAtFlush += rawId
                }
                rows.size
            }
            else -> {
                val (sel, args) = withId(id, selection, selectionArgs)
                db.delete(table, sel, args)
            }
        }
        flushUnlessInBatch()
        return count
    }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        db.beginTransaction()
        inBatch = true
        try {
            val results = super.applyBatch(operations)
            flush()
            db.setTransactionSuccessful()
            return results
        } finally {
            inBatch = false
            dirtyAtFlush.clear()
            versionAtFlush.clear()
            db.endTransaction()
        }
    }

    override fun getType(uri: Uri): String? = null

    // ---- seeding and reading helpers (bypass every rule) ----

    /** Inserts a row straight into [table] and returns its id; no rule runs. */
    fun insertRow(table: String, vararg pairs: Pair<String, Any?>): Long {
        val v = ContentValues()
        pairs.forEach { (k, value) ->
            when (value) {
                null -> v.putNull(k)
                is String -> v.put(k, value)
                is Int -> v.put(k, value)
                is Long -> v.put(k, value)
                is Boolean -> v.put(k, if (value) 1 else 0)
                is ByteArray -> v.put(k, value)
                else -> error("unsupported value type for $k")
            }
        }
        return db.insertOrThrow(table, null, v)
    }

    /** Every row of [table] (or the Data view for [DATA]) matching [selection], as column maps. */
    fun rows(table: String, selection: String? = null, vararg args: String): List<Map<String, String?>> =
        db.query(if (table == DATA) DATA_VIEW else table, null, selection, args, null, null, BaseColumns._ID).use { c ->
            val out = ArrayList<Map<String, String?>>()
            while (c.moveToNext()) {
                out += (0 until c.columnCount).associate { i ->
                    c.getColumnName(i) to if (c.getType(i) == Cursor.FIELD_TYPE_BLOB) "<blob>" else c.getString(i)
                }
            }
            out
        }

    /** The raw contact row [rawContactId] as a column map, or null. */
    fun rawContact(rawContactId: Long): Map<String, String?>? =
        rows(RAW, "${RawContacts._ID} = ?", rawContactId.toString()).singleOrNull()

    // ---- rules ----

    private fun deleteRawContact(rawId: Long, syncAdapter: Boolean) {
        val local = rawContact(rawId)!![RawContacts.ACCOUNT_NAME] == null
        if (syncAdapter || local) {
            db.delete(DATA, "${Data.RAW_CONTACT_ID} = ?", arrayOf(rawId.toString()))
            db.delete(RAW, "${RawContacts._ID} = ?", arrayOf(rawId.toString()))
        } else {
            db.update(
                RAW,
                ContentValues().apply {
                    put(RawContacts.DELETED, 1)
                    put(RawContacts.DIRTY, 1)
                    put(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DISABLED)
                },
                "${RawContacts._ID} = ?",
                arrayOf(rawId.toString()),
            )
        }
    }

    /** Resolves a GroupMembership's `group_sourceid` to the account's group id, creating it. */
    private fun resolveGroupSourceId(rawId: Long, v: ContentValues) {
        val sourceId = v.getAsString(GroupMembership.GROUP_SOURCE_ID) ?: return
        v.remove(GroupMembership.GROUP_SOURCE_ID)
        val raw = rawContact(rawId)!!
        val name = raw[RawContacts.ACCOUNT_NAME]
        val type = raw[RawContacts.ACCOUNT_TYPE]
        val existing = idsOf(
            GROUPS,
            "${Groups.SOURCE_ID} = ? AND ${Groups.ACCOUNT_NAME} IS ? AND ${Groups.ACCOUNT_TYPE} IS ?",
            arrayOf(sourceId, name, type),
        ).firstOrNull()
        val groupId = existing ?: db.insertOrThrow(
            GROUPS,
            null,
            ContentValues().apply {
                put(Groups.SOURCE_ID, sourceId)
                put(Groups.ACCOUNT_NAME, name)
                put(Groups.ACCOUNT_TYPE, type)
            },
        )
        v.put(GroupMembership.GROUP_ROW_ID, groupId)
    }

    private fun flushUnlessInBatch() {
        if (!inBatch) flush()
    }

    /** ContactsProvider2.flushTransactionalChanges: DIRTY, then VERSION, once per raw contact. */
    private fun flush() {
        if (dirtyAtFlush.isNotEmpty()) {
            db.execSQL("UPDATE $RAW SET ${RawContacts.DIRTY} = 1 WHERE ${RawContacts._ID} IN (${dirtyAtFlush.joinToString()})")
        }
        if (versionAtFlush.isNotEmpty()) {
            db.execSQL(
                "UPDATE $RAW SET ${RawContacts.VERSION} = ${RawContacts.VERSION} + 1 " +
                    "WHERE ${RawContacts._ID} IN (${versionAtFlush.joinToString()})",
            )
        }
        dirtyAtFlush.clear()
        versionAtFlush.clear()
    }

    private fun applyAccount(uri: Uri, v: ContentValues) {
        val name = uri.getQueryParameter(RawContacts.ACCOUNT_NAME) ?: return
        val type = uri.getQueryParameter(RawContacts.ACCOUNT_TYPE)
        if (!v.containsKey(RawContacts.ACCOUNT_NAME)) v.put(RawContacts.ACCOUNT_NAME, name)
        if (!v.containsKey(RawContacts.ACCOUNT_TYPE)) v.put(RawContacts.ACCOUNT_TYPE, type)
    }

    /** ContactsProvider2.appendAccountToSelection: only when the URI names an account. */
    private fun scopeToAccount(uri: Uri, selection: String?, args: Array<out String>?): Pair<String?, Array<String>?> {
        val base = args?.map { it }?.toTypedArray()
        val name = uri.getQueryParameter(RawContacts.ACCOUNT_NAME) ?: return selection to base
        val type = uri.getQueryParameter(RawContacts.ACCOUNT_TYPE).orEmpty()
        val scope = "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?"
        return and(selection, scope) to ((base ?: emptyArray()) + arrayOf(name, type))
    }

    private fun dataRowsOf(selection: String?, args: Array<String>?): List<Pair<Long, Long>> =
        db.query(DATA_VIEW, arrayOf(Data._ID, Data.RAW_CONTACT_ID), selection, args, null, null, null).use { c ->
            val out = ArrayList<Pair<Long, Long>>()
            while (c.moveToNext()) out += c.getLong(0) to c.getLong(1)
            out
        }

    private fun idsOf(table: String, selection: String?, args: Array<out String?>?): List<Long> =
        db.query(table, arrayOf(BaseColumns._ID), selection, args?.map { it }?.toTypedArray(), null, null, null).use { c ->
            val out = ArrayList<Long>()
            while (c.moveToNext()) out += c.getLong(0)
            out
        }

    private fun isSyncAdapter(uri: Uri) = uri.getQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER) == "true"

    private fun resolve(uri: Uri): Pair<String, Long?> {
        val segments = uri.pathSegments
        val table = segments.firstOrNull()?.takeIf { it in PATHS } ?: error("unsupported uri $uri")
        return table to segments.getOrNull(1)?.toLongOrNull()
    }

    private fun withId(id: Long?, selection: String?, args: Array<out String>?): Pair<String?, Array<String>?> {
        val base = args?.map { it }?.toTypedArray()
        if (id == null) return selection to base
        return and("${BaseColumns._ID} = ?", selection) to (arrayOf(id.toString()) + (base ?: emptyArray()))
    }

    private fun and(a: String?, b: String?): String? = when {
        a.isNullOrBlank() -> b
        b.isNullOrBlank() -> a
        else -> "($a) AND ($b)"
    }

    private fun checkColumns(table: String, values: ContentValues, known: Set<String>) {
        val unknown = values.keySet().filterNot { it in known }
        require(unknown.isEmpty()) { "$table has no column(s) $unknown" }
    }

    companion object {
        const val RAW = "raw_contacts"
        const val DATA = "data"
        const val GROUPS = "groups"
        const val SETTINGS = "settings"
        private const val DATA_VIEW = "view_data"
        
        private val PATHS = setOf(RAW, DATA, GROUPS, SETTINGS)

        private val RAW_TEXT = listOf(
            RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE, RawContacts.DATA_SET, RawContacts.SOURCE_ID,
            RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.SYNC3, RawContacts.SYNC4, RawContacts.CUSTOM_RINGTONE,
        )
        private val RAW_INT = listOf(
            RawContacts.DIRTY, RawContacts.DELETED, RawContacts.RAW_CONTACT_IS_READ_ONLY,
            RawContacts.AGGREGATION_MODE, RawContacts.STARRED, RawContacts.SEND_TO_VOICEMAIL,
        )
        private val RAW_COLUMNS = (RAW_TEXT + RAW_INT + RawContacts._ID + RawContacts.VERSION).toSet()

        private val DATA_COLUMNS = (
            listOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY, Data.IS_READ_ONLY, Data.DATA_VERSION) +
                (1..15).map { "data$it" } + (1..4).map { "data_sync$it" }
            ).toSet()

        private val GROUP_TEXT = listOf(
            Groups.ACCOUNT_NAME, Groups.ACCOUNT_TYPE, Groups.DATA_SET, Groups.SOURCE_ID, Groups.TITLE,
            Groups.NOTES, Groups.SYSTEM_ID, Groups.SYNC1, Groups.SYNC2, Groups.SYNC3, Groups.SYNC4,
        )
        private val GROUP_INT = listOf(
            Groups.SHOULD_SYNC, Groups.GROUP_VISIBLE, Groups.DELETED, Groups.DIRTY, Groups.VERSION,
            Groups.AUTO_ADD, Groups.FAVORITES, Groups.GROUP_IS_READ_ONLY,
        )
        private val GROUP_COLUMNS = (GROUP_TEXT + GROUP_INT + Groups._ID).toSet()

        private val SETTINGS_COLUMNS = setOf(
            ContactsContract.Settings.ACCOUNT_NAME, ContactsContract.Settings.ACCOUNT_TYPE,
            ContactsContract.Settings.DATA_SET, ContactsContract.Settings.UNGROUPED_VISIBLE,
            ContactsContract.Settings.SHOULD_SYNC,
        )

        /** Registers a fresh provider for the contacts authority and returns it. */
        fun install(): SqliteContactsProvider =
            Robolectric.setupContentProvider(SqliteContactsProvider::class.java, ContactsContract.AUTHORITY)

        /** The real repository over the registered provider. */
        fun repository(context: Context): AndroidContactsProviderRepository =
            AndroidContactsProviderRepository(context.contentResolver, ContactPhotoTranscoder())
    }
}
