package com.network24.player.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.network24.player.core.database.dao.*
import com.network24.player.core.database.entity.*

@Database(
    entities = [
        CategoryEntity::class,
        ChannelEntity::class,
        EpgEntity::class,

        HistoryEntity::class,
        FavoriteEntity::class,
        ContinueWatchingEntity::class,
        DownloadEntity::class,

        SyncMetaEntity::class,
    ],
    version = 2,
    // Schemas are exported to app/schemas so a future version bump can use
    // @AutoMigration(from = 2, to = 3) instead of wiping history and
    // continue-watching (see DatabaseProvider).
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun syncMetaDao(): SyncMetaDao

    abstract fun favoritesDao(): FavoritesDao
    abstract fun historyDao(): HistoryDao
    abstract fun continueWatchingDao(): ContinueWatchingDao
    abstract fun downloadsDao(): DownloadsDao

}
