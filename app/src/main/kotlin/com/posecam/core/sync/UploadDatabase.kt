package com.posecam.core.sync

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Persistent upload queue. Room is used ONLY for cloud sync state; the session catalog stays
 * file-based. The queue survives process death and reboots, and there is deliberately no
 * destructive-migration fallback: queue state is not something to silently drop.
 */
@Database(
    entities = [UploadEntity::class, CloudSessionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        private const val NAME = "posecam-uploads.db"

        @Volatile
        private var instance: UploadDatabase? = null

        fun get(context: Context): UploadDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, UploadDatabase::class.java, NAME)
                .build()
                .also { instance = it }
        }
    }
}
