package com.zeoffice.einkreader.data.repository

import com.zeoffice.einkreader.data.db.EinkDatabase
import com.zeoffice.einkreader.data.toDomain
import com.zeoffice.einkreader.data.toEntity
import com.zeoffice.einkreader.domain.model.Bookmark

class BookmarkRepository(private val db: EinkDatabase) {
    private val dao = db.bookmarkDao()

    suspend fun getByBook(bookId: Int): List<Bookmark> = dao.getByBook(bookId).map { it.toDomain() }
    suspend fun add(b: Bookmark) = dao.insert(b.toEntity())
    suspend fun remove(id: Int) = dao.deleteById(id)
}
