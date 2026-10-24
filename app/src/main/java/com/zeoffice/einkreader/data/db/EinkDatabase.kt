package com.zeoffice.einkreader.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ReadingSettingsEntity::class,
        BookmarkEntity::class,
        CloudAccountEntity::class,
        SyncStateEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class EinkDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingSettingsDao(): ReadingSettingsDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cloudAccountDao(): CloudAccountDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile
        private var INSTANCE: EinkDatabase? = null

        /** v2 → v3: books 新增封面路径列 (保留原有数据) */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverPath TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 → v4: books 新增进度更新时间列 + 新建 sync_state 同步基线表 (保留原有数据) */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN progressUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_state (" +
                        "bookKey TEXT NOT NULL PRIMARY KEY, " +
                        "localHash TEXT NOT NULL, " +
                        "remoteUpdatedAt INTEGER NOT NULL, " +
                        "lastSyncAt INTEGER NOT NULL)"
                )
            }
        }

        /** v4 → v5: reading_settings 新增电纸书适配字段 (einkMode / pageKeyAction) */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_settings ADD COLUMN einkMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_settings ADD COLUMN pageKeyAction TEXT NOT NULL DEFAULT 'prev_next'")
            }
        }

        fun get(context: Context): EinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EinkDatabase::class.java,
                    "eink_reader.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
