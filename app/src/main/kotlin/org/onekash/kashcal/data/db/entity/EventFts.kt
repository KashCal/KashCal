package org.onekash.kashcal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Indexes event title, location and description for full-text search (FTS4).
 *
 * With `contentEntity`, Room keeps this table in step with [Event] through triggers. Searches
 * are 10-100x faster than LIKE queries.
 *
 * @see <a href="https://developer.android.com/training/data-storage/room/defining-data#fts">Room
 *      FTS</a>
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
