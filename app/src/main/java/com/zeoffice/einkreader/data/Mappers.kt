package com.zeoffice.einkreader.data

import com.zeoffice.einkreader.data.db.*
import com.zeoffice.einkreader.domain.model.*

// ---- ReadingSettings ----
fun ReadingSettingsEntity.toDomain() = ReadingSettings(
    fontSizeSp, lineSpacing, paragraphSpacing, marginHorizontalDp, marginVerticalDp,
    theme, fontFamily, pageTurn, textAlign, cleanText, firstLineIndent,
    brightness, keepScreenOn,
    einkMode, pageKeyAction, volumeKeyPageTurn, pageKeyPageTurn,
    customPrevKeys, customNextKeys, keyDebug,
    autoSyncOnStartup, autoSyncOnExit, syncPolicy, syncFiles, syncBookmarks
)
fun ReadingSettings.toEntity() = ReadingSettingsEntity(
    id = 1, fontSizeSp, lineSpacing, paragraphSpacing, marginHorizontalDp, marginVerticalDp,
    theme, fontFamily, pageTurn, textAlign, cleanText, firstLineIndent,
    brightness, keepScreenOn,
    einkMode, pageKeyAction, volumeKeyPageTurn, pageKeyPageTurn,
    customPrevKeys, customNextKeys, keyDebug,
    autoSyncOnStartup, autoSyncOnExit, syncPolicy, syncFiles, syncBookmarks
)

// ---- Bookmark ----
fun BookmarkEntity.toDomain() = Bookmark(
    id, bookId, chapterIndex, charOffset, quote, note, createdAt,
    uid, updatedAt, deleted, stamp
)
fun Bookmark.toEntity() = BookmarkEntity(
    id, bookId, chapterIndex, charOffset, quote, note,
    // 兼容旧数据 / 远端同步过来的 createdAt=0：补当前时间，避免排序与冲突裁决失效
    if (createdAt > 0) createdAt else System.currentTimeMillis(),
    uid, updatedAt, deleted, stamp
)

// ---- CloudAccount ----
fun CloudAccountEntity.toDomain() = CloudAccount(id, provider, name, host, username, password,
    accessToken, refreshToken, expiresAt, extra)
fun CloudAccount.toEntity() = CloudAccountEntity(id, provider, name, host, username, password,
    accessToken, refreshToken, expiresAt, extra)
