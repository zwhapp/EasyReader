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
    version = 9,
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

        /**
         * v6 → v7: reading_settings 新增自定义兼容键值（customPrevKeys / customNextKeys）与按键诊断开关（keyDebug）。
         * 预置常见媒体/导航键（87/88/269/270）作为默认兼容键值，提高厂商非常规键值一次命中率。
         * 幂等 (列已存在则跳过 ALTER)；NOT NULL 列带 DEFAULT，SQLite 会自动把默认值写入已有行。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("reading_settings", "customPrevKeys")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN customPrevKeys TEXT NOT NULL DEFAULT '87,269'")
                }
                if (!db.hasColumn("reading_settings", "customNextKeys")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN customNextKeys TEXT NOT NULL DEFAULT '88,270'")
                }
                if (!db.hasColumn("reading_settings", "keyDebug")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN keyDebug INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * v7 → v8: reading_settings 新增 WebDAV 自动同步配置字段。
         *  - autoSyncOnStartup / autoSyncOnExit：启动自动拉取 / 退出自动推送 开关；
         *  - syncPolicy：冲突解决策略（NEWER_WINS=以较新修改时间为准，默认）；
         *  - syncFiles / syncBookmarks：自动同步范围（书籍文件 / 书签笔记）。
         * 幂等 (列已存在则跳过 ALTER)；NOT NULL 列带 DEFAULT，SQLite 自动把默认值写入已有行，
         * 老用户默认关闭自动同步（需手动开启），默认冲突策略为"以较新者为准"。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("reading_settings", "autoSyncOnStartup")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN autoSyncOnStartup INTEGER NOT NULL DEFAULT 0")
                }
                if (!db.hasColumn("reading_settings", "autoSyncOnExit")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN autoSyncOnExit INTEGER NOT NULL DEFAULT 0")
                }
                if (!db.hasColumn("reading_settings", "syncPolicy")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN syncPolicy TEXT NOT NULL DEFAULT 'NEWER_WINS'")
                }
                if (!db.hasColumn("reading_settings", "syncFiles")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN syncFiles INTEGER NOT NULL DEFAULT 1")
                }
                if (!db.hasColumn("reading_settings", "syncBookmarks")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN syncBookmarks INTEGER NOT NULL DEFAULT 1")
                }
            }
        }

        /**
         * v8 → v9: reading_settings 新增「正文智能整理」两个开关。
         *  - cleanText：自动删除多余空行/空格、合并 TXT 硬换行（默认开）
         *  - firstLineIndent：段落首行缩进 2 字（默认开）
         * 幂等；老用户升级后默认开启新排版，可在设置里单独关闭。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("reading_settings", "cleanText")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN cleanText INTEGER NOT NULL DEFAULT 1")
                }
                if (!db.hasColumn("reading_settings", "firstLineIndent")) {
                    db.execSQL("ALTER TABLE reading_settings ADD COLUMN firstLineIndent INTEGER NOT NULL DEFAULT 1")
                }
            }
        }

        fun get(context: Context): EinkDatabase {
            // 外层快速路径 + 同步块内二次判空：避免两个线程同时看到 null 时各建一个实例。
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EinkDatabase::class.java,
                    "eink_reader.db"
                ).addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
                )
                    // 不使用 fallbackToDestructiveMigration：迁移链 2→9 已完整，
                    // 任何缺失迁移都应报错而不是静默清空书库/进度/书签。
                    .build()
                    .also { INSTANCE = it }
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
