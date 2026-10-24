package com.zeoffice.einkreader.data

import com.zeoffice.einkreader.data.db.*
import com.zeoffice.einkreader.domain.model.*

// ---- ReadingSettings ----
fun ReadingSettingsEntity.toDomain() = ReadingSettings(
    fontSizeSp, lineSpacing, paragraphSpacing, marginHorizontalDp, marginVerticalDp,
    theme, fontFamily, pageTurn, textAlign, brightness, keepScreenOn,
    einkMode, pageKeyAction, volumeKeyPageTurn, pageKeyPageTurn,
    customPrevKeys, customNextKeys, keyDebug
)
fun ReadingSettings.toEntity() = ReadingSettingsEntity(
    id = 1, fontSizeSp, lineSpacing, paragraphSpacing, marginHorizontalDp, marginVerticalDp,
    theme, fontFamily, pageTurn, textAlign, brightness, keepScreenOn,
    einkMode, pageKeyAction, volumeKeyPageTurn, pageKeyPageTurn,
    customPrevKeys, customNextKeys, keyDebug
)

// ---- Bookmark ----
fun BookmarkEntity.toDomain() = Bookmark(id, bookId, chapterIndex, charOffset, quote, note, createdAt)
fun Bookmark.toEntity() = BookmarkEntity(id, bookId, chapterIndex, charOffset, quote, note, createdAt)

// ---- CloudAccount ----
fun CloudAccountEntity.toDomain() = CloudAccount(id, provider, name, host, username, password,
    accessToken, refreshToken, expiresAt, extra)
fun CloudAccount.toEntity() = CloudAccountEntity(id, provider, name, host, username, password,
    accessToken, refreshToken, expiresAt, extra)
