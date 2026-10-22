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
        CloudAccountEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class EinkDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingSettingsDao(): ReadingSettingsDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun cloudAccountDao(): CloudAccountDao

    companion object {
        @Volatile
        private var INSTANCE: EinkDatabase? = null

        /** v2 → v3: books 新增封面路径列 (保留原有数据) */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverPath TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): EinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EinkDatabase::class.java,
                    "eink_reader.db"
                ).addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
