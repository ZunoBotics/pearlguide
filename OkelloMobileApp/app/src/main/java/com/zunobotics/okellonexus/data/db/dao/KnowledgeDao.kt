package com.zunobotics.okellonexus.data.db.dao

import androidx.room.*
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge WHERE deletedAt IS NULL AND personaId = :personaId ORDER BY createdAt DESC")
    fun getByPersonaFlow(personaId: String): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge WHERE deletedAt IS NULL AND category = :category ORDER BY createdAt DESC")
    fun getByCategory(category: String): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    suspend fun getAll(): List<KnowledgeEntry>

    @Query("SELECT * FROM knowledge WHERE deletedAt IS NULL AND personaId = :personaId ORDER BY createdAt DESC")
    suspend fun getByPersona(personaId: String): List<KnowledgeEntry>

    @Query("SELECT * FROM knowledge WHERE syncedAt IS NULL AND deletedAt IS NULL")
    suspend fun getUnsynced(): List<KnowledgeEntry>

    @Query("SELECT * FROM knowledge WHERE id = :id")
    suspend fun getById(id: String): KnowledgeEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: KnowledgeEntry)

    @Update
    suspend fun update(entry: KnowledgeEntry)

    @Query("UPDATE knowledge SET deletedAt = :timestamp WHERE id = :id")
    suspend fun softDelete(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE knowledge SET syncedAt = :timestamp WHERE id = :id")
    suspend fun markSynced(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM knowledge WHERE deletedAt IS NULL")
    fun countFlow(): Flow<Int>
}
