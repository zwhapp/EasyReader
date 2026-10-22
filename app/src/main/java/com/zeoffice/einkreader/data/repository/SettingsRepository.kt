package com.zeoffice.einkreader.data.repository

import com.zeoffice.einkreader.data.db.EinkDatabase
import com.zeoffice.einkreader.data.toDomain
import com.zeoffice.einkreader.data.toEntity
import com.zeoffice.einkreader.domain.model.ReadingSettings

class SettingsRepository(private val db: EinkDatabase) {
    private val dao = db.readingSettingsDao()

    suspend fun getSettings(): ReadingSettings = dao.get()?.toDomain() ?: ReadingSettings()

    suspend fun saveSettings(s: ReadingSettings) = dao.insert(s.toEntity())
}
