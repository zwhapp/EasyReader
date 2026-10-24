package com.zeoffice.einkreader.data.repository

import com.zeoffice.einkreader.data.db.EinkDatabase
import com.zeoffice.einkreader.data.db.ReadingSettingsEntity
import com.zeoffice.einkreader.data.toDomain
import com.zeoffice.einkreader.data.toEntity
import com.zeoffice.einkreader.domain.model.ReadingSettings

class SettingsRepository(private val db: EinkDatabase) {
    private val dao = db.readingSettingsDao()

    suspend fun getSettings(): ReadingSettings = dao.get()?.toDomain() ?: ReadingSettings()

    /** 返回原始实体 (用于判断是否为首次启动) */
    suspend fun getEntity(): ReadingSettingsEntity? = dao.get()

    suspend fun saveSettings(s: ReadingSettings) = dao.insert(s.toEntity())
}
