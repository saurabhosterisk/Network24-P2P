package com.network24.player.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["itemType", "itemId"], unique = true),
        Index(value = ["createdAtMs"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey val key: String,          // e.g. "LIVE_CHANNEL:123"
    val itemType: String,                 // LIVE_CHANNEL / MOVIE / SERIES / EPISODE
    val itemId: String,                   // store as String
    val createdAtMs: Long,
    // "My order" on the favorites screen: 1..n once the viewer has arranged them, 0 = never arranged (newest first)
    @androidx.room.ColumnInfo(defaultValue = "0") val position: Int = 0
)
