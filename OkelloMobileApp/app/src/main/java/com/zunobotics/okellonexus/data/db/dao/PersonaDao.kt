package com.zunobotics.okellonexus.data.db.dao

import androidx.room.*
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE isActive = 1 LIMIT 1")
    fun getActiveFlow(): Flow<PersonaEntity?>

    @Query("SELECT * FROM personas WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): PersonaEntity?

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getById(id: String): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(persona: PersonaEntity)

    @Update
    suspend fun update(persona: PersonaEntity)

    @Delete
    suspend fun delete(persona: PersonaEntity)

    @Query("UPDATE personas SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE personas SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Transaction
    suspend fun activatePersona(id: String) {
        deactivateAll()
        setActive(id)
    }
}
