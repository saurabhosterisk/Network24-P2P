package com.network24.player.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.network24.player.core.database.entity.HistoryEntity

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY updatedAtMs DESC")
    suspend fun getRecent(): List<HistoryEntity>

    @Query("""
        SELECT * FROM history
        WHERE itemType = :itemType
        ORDER BY updatedAtMs DESC
        LIMIT :limit
    """)
    suspend fun getRecentByType(
        itemType: String,
        limit: Int
    ): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity)

    @Query("DELETE FROM history WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("DELETE FROM history WHERE itemType = :itemType")
    suspend fun deleteByType(itemType: String)

    @Query("""
        DELETE FROM history
        WHERE itemType = :itemType
          AND `key` NOT IN (
            SELECT `key` FROM history
            WHERE itemType = :itemType
            ORDER BY updatedAtMs DESC
            LIMIT :keepCount
          )
    """)
    suspend fun trimToRecent(
        itemType: String,
        keepCount: Int
    )
}
