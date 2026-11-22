package org.onekash.kashcal.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey

/**
 * Holds one piece of an event's stored ICS: the text of a calendar object resource (RFC 4791
 * §4.1) as the server or an imported file gave it.
 *
 * A resource's ICS is stored once, on its series row; a changed occurrence owns parts only when
 * its ICS differs from its series' ([org.onekash.kashcal.data.db.EventIcsSource]). The pieces,
 * joined in [partIndex] order, are the whole text; each holds at most
 * [org.onekash.kashcal.data.db.EventIcsStore.PART_CHARS] characters, so no row can outgrow
 * Android's 2 MB CursorWindow however large the resource.
 * Deleting the event deletes its parts.
 */
@Entity(
    tableName = "event_ics",
    primaryKeys = ["event_id", "part_index"],
    foreignKeys = [
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EventIcsPart(
    @ColumnInfo(name = "event_id")
    val eventId: Long,

    /** Position of this piece, 0 for the first; an event's pieces run 0..n-1 without gaps. */
    @ColumnInfo(name = "part_index")
    val partIndex: Int,

    @ColumnInfo(name = "content")
    val content: String,
)
