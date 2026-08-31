package com.network24.player.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.network24.player.core.database.entity.EpgEntity

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg WHERE streamId = :streamId ORDER BY startTimestamp ASC")
    suspend fun getByStream(streamId: Int): List<EpgEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EpgEntity>)

    @Query("DELETE FROM epg WHERE streamId = :streamId")
    suspend fun deleteForStream(streamId: Int)

    @Transaction
    suspend fun replaceForStream(streamId: Int, items: List<EpgEntity>) {
        deleteForStream(streamId)
        insertAll(items)
    }

    @Query("""
        SELECT * FROM epg
        WHERE epgChannelId = :epgChannelId
          AND startTimestamp IS NOT NULL
        ORDER BY startTimestamp ASC
    """)
    suspend fun getByEpgChannelId(epgChannelId: String): List<EpgEntity>

    @Query("""
        SELECT * FROM epg
        WHERE epgChannelId = :epgChannelId
          AND startTimestamp IS NOT NULL
          AND stopTimestamp IS NOT NULL
          AND startTimestamp <= :nowTs
          AND stopTimestamp > :nowTs
        ORDER BY startTimestamp DESC
        LIMIT 1
    """)
    suspend fun getNowByEpgChannelId(epgChannelId: String, nowTs: Long): EpgEntity?

    @Query("""
        SELECT * FROM epg
        WHERE epgChannelId IN (:epgChannelIds)
          AND startTimestamp IS NOT NULL
          AND stopTimestamp IS NOT NULL
          AND startTimestamp <= :nowTs
          AND stopTimestamp > :nowTs
        ORDER BY epgChannelId ASC, startTimestamp DESC
    """)
    suspend fun getNowByEpgChannelIds(
        epgChannelIds: List<String>,
        nowTs: Long
    ): List<EpgEntity>

    @Query("""
        SELECT * FROM epg
        WHERE epgChannelId = :epgChannelId
          AND startTimestamp IS NOT NULL
          AND startTimestamp > :nowTs
        ORDER BY startTimestamp ASC
        LIMIT 1
    """)
    suspend fun getNextByEpgChannelId(epgChannelId: String, nowTs: Long): EpgEntity?

    /**
     * Loads the guide for all channels in one Room query. This is important for
     * the grid-style Live With EPG screen so we don't perform one DB query per
     * channel row.
     */
    @Query("""
        SELECT * FROM epg
        WHERE epgChannelId IN (:epgChannelIds)
          AND startTimestamp IS NOT NULL
          AND stopTimestamp IS NOT NULL
          AND stopTimestamp > :fromTs
          AND startTimestamp < :toTs
        ORDER BY epgChannelId ASC, startTimestamp ASC
    """)
    suspend fun getByEpgChannelIds(
        epgChannelIds: List<String>,
        fromTs: Long,
        toTs: Long
    ): List<EpgEntity>

    /**
     * Finds programme titles that are currently airing or start before the
     * supplied window closes. The title filter is case-insensitive so partial
     * searches such as "family" can find "Family Feud".
     */
    @Query("""
        SELECT * FROM epg
        WHERE title IS NOT NULL
          AND TRIM(title) != ''
          AND LOWER(title) LIKE '%' || LOWER(:query) || '%'
          AND startTimestamp IS NOT NULL
          AND stopTimestamp IS NOT NULL
          AND stopTimestamp > :nowTs
          AND startTimestamp < :windowEndTs
        ORDER BY
          CASE WHEN startTimestamp <= :nowTs AND stopTimestamp > :nowTs
               THEN 0 ELSE 1 END,
          startTimestamp ASC
    """)
    suspend fun searchProgramsInWindow(
        query: String,
        nowTs: Long,
        windowEndTs: Long
    ): List<EpgEntity>

    @Query("""
        SELECT COUNT(*) FROM epg
        WHERE startTimestamp IS NOT NULL
          AND stopTimestamp IS NOT NULL
          AND stopTimestamp > :nowTs
          AND startTimestamp < :windowEndTs
    """)
    suspend fun countProgramsInWindow(nowTs: Long, windowEndTs: Long): Int

    @Query("DELETE FROM epg")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAllEpgs(epgs: List<EpgEntity>) {
        deleteAll()
        insertAll(epgs)
    }
}
