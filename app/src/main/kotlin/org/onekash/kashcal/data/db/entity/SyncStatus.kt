package org.onekash.kashcal.data.db.entity

/**
 * Sync status for events in offline-first architecture.
 *
 * RFC 5545 compliant - tracks local changes pending sync to CalDAV server.
 */
enum class SyncStatus {
    /**
     * Event is in sync with server. No pending changes.
     */
    SYNCED,

    /**
     * New local event, not yet pushed to server.
     * Will be created on server during next sync.
     */
    PENDING_CREATE,

    /**
     * Local changes made, not yet pushed to server.
     * Will update server event during next sync.
     */
    PENDING_UPDATE,

    /**
     * Soft deleted locally, awaiting server delete.
     * Will be deleted from server during next sync, then removed locally.
     */
    PENDING_DELETE
}
