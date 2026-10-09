package com.posecam.core.sync

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Persistent upload queue. Room is used ONLY for cloud sync state; the session catalog stays
 * file-based. The queue survives process death and reboots, and there is deliberately no
 * destructive-migration fallback: queue state is not something to silently drop.
 */
@Database(
    entities = [UploadEntity::class, CloudSessionEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        private const val NAME = "posecam-uploads.db"

        /**
         * Version 2: a recording's pipeline export is tracked per session. Sessions that exist already start at PENDING,
         * which is what queues their export (their raw files may be uploaded or even synced already; the export is
         * additional and never un-syncs a session).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN exportState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN exportNote TEXT")
            }
        }

        /**
         * Version 3: whether the backend published a synced recording to the website. Existing rows start at PENDING,
         * so the first check after the update learns the real answer for every recording already uploaded.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN publishState TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN publishedSets INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cloud_sessions ADD COLUMN publishNote TEXT")
            }
        }

        @Volatile
        private var instance: UploadDatabase? = null

        fun get(context: Context): UploadDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, UploadDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
