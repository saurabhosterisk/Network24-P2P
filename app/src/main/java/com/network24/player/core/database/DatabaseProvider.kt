package com.network24.player.core.database

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile private var INSTANCE: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "network24.db"
            )
                // Last resort only. Bumping AppDatabase.version needs an
                // @AutoMigration (schemas in app/schemas) - without one this
                // silently wipes history and continue-watching; favorites
                // come back from Firestore on the next login.
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
