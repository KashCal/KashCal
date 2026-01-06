package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * FTS4 virtual table for full-text search on events.
 *
 * Room automatically keeps this in sync with the Event table via triggers
 * when using contentEntity. Searches are 10-100x faster than LIKE queries.
 *
 * Indexed fields:
 * - title: Event summary/title
 * - location: Event location
 * - description: Event notes/description
 *
 * @see <a href="https://developer.android.com/training/data-storage/room/defining-data#fts">Room FTS</a>
 */
@Fts4(contentEntity = Event::class)
@Entity(tableName = "events_fts")
data class EventFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "location")
    val location: String?,

    @ColumnInfo(name = "description")
    val description: String?
)
