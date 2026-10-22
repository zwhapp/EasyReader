package com.zeoffice.einkreader.data.repository

import com.zeoffice.einkreader.data.db.EinkDatabase
import com.zeoffice.einkreader.data.toDomain
import com.zeoffice.einkreader.data.toEntity
import com.zeoffice.einkreader.domain.model.CloudAccount

class CloudRepository(private val db: EinkDatabase) {
    private val dao = db.cloudAccountDao()

    suspend fun getAll(): List<CloudAccount> = dao.getAll().map { it.toDomain() }
    suspend fun getById(id: Int): CloudAccount? = dao.getById(id)?.toDomain()
    suspend fun save(a: CloudAccount): Long =
        if (a.id == 0) dao.insert(a.toEntity()) else { dao.update(a.toEntity()); a.id.toLong() }
    suspend fun delete(id: Int) = dao.deleteById(id)
}
