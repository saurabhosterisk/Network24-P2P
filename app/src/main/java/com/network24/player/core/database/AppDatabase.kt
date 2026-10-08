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
    version = 3,
    // Schemas are exported to app/schemas; version bumps use an auto-migration so history,
    // continue-watching and favorites survive (see DatabaseProvider).
    // 2 -> 3: favorites.position ("My order" on the favorites screen).
    exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 2, to = 3)]
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
