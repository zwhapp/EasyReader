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
    version = 6,
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

        /** v4 → v5: reading_settings 新增电纸书适配字段 (einkMode / pageKeyAction), 幂等 (列已存在则跳过) */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("reading_settings", "einkMode")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN einkMode INTEGER NOT NULL DEFAULT 0")
                }
                if (!db.hasColumn("reading_settings", "pageKeyAction")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN pageKeyAction TEXT NOT NULL DEFAULT 'prev_next'")
                }
            }
        }

        /**
         * v5 → v6: reading_settings 新增两个独立翻页键开关 (volumeKeyPageTurn / pageKeyPageTurn)。
         * 兼容老数据 (pageKeyAction):
         *  - 老用户曾选 'off'（关闭翻页）→ 两个开关都置 0，保持"关闭"语义；
         *  - 老用户曾启用翻页 → pageKeyPageTurn 保持 1；若同时开启了电纸书模式，volumeKeyPageTurn 也置 1。
         * 幂等 (列已存在则跳过 ALTER)，再用 UPDATE 修正默认值，保证任意起点都可安全升级。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("reading_settings", "volumeKeyPageTurn")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN volumeKeyPageTurn INTEGER NOT NULL DEFAULT 0")
                }
                if (!db.hasColumn("reading_settings", "pageKeyPageTurn")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN pageKeyPageTurn INTEGER NOT NULL DEFAULT 1")
                }
                // 老 'off' 用户：保持两个开关都关
                db.execSQL("UPDATE reading_settings SET pageKeyPageTurn = 0 WHERE pageKeyAction = 'off'")
                // 老启用用户：已开电纸书模式者，默认也开启音量键翻页
                db.execSQL("UPDATE reading_settings SET volumeKeyPageTurn = 1 WHERE einkMode = 1 AND pageKeyAction <> 'off'")
            }
        }

        fun get(context: Context): EinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EinkDatabase::class.java,
                    "eink_reader.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/** 判断某表是否已存在指定列 (用于迁移的幂等保护) */
private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    var found = false
    val cursor = query("PRAGMA table_info($table)")
    try {
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                found = true
                break
            }
        }
    } finally {
        cursor.close()
    }
    return found
}
