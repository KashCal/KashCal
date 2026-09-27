package org.onekash.kashcal.data.db.entity

/** Tracks which local changes to an event still wait for the next push to the server. */
enum class SyncStatus {
    /** In step with the server, or never synced (local calendars, ICS subscriptions). */
    SYNCED,

    /** New event not yet on the server; the next sync creates it. */
    PENDING_CREATE,

    /** Local changes not yet on the server; the next sync updates it. */
    PENDING_UPDATE,

    /** Soft-deleted locally; the next sync deletes it on the server, then removes the row. */
    PENDING_DELETE
}
