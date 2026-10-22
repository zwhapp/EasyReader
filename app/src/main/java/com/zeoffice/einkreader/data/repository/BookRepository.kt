package com.zeoffice.einkreader.data.repository

import com.zeoffice.einkreader.data.db.BookEntity
import com.zeoffice.einkreader.data.db.EinkDatabase

class BookRepository(private val db: EinkDatabase) {
    private val dao = db.bookDao()

    suspend fun getAllBooks(): List<BookEntity> = dao.getAll()
    suspend fun getBook(id: Int): BookEntity? = dao.getById(id)
    suspend fun insert(book: BookEntity) = dao.insert(book)
    suspend fun update(book: BookEntity) = dao.update(book)
    suspend fun delete(book: BookEntity) = dao.delete(book)
    suspend fun deleteById(id: Int) = dao.deleteById(id)
}
