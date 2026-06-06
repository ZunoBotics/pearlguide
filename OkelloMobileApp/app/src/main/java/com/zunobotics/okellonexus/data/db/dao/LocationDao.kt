package com.zunobotics.okellonexus.data.db.dao

import androidx.room.*
import com.zunobotics.okellonexus.data.db.entity.LocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE personaId = :personaId ORDER BY createdAt DESC")
    fun getByPersonaFlow(personaId: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE isActive = 1 LIMIT 1")
    fun getActiveFlow(): Flow<LocationEntity?>

    @Query("SELECT * FROM locations WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): LocationEntity?

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getById(id: String): LocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: LocationEntity)

    @Update
    suspend fun update(location: LocationEntity)

    @Delete
    suspend fun delete(location: LocationEntity)

    @Query("UPDATE locations SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE locations SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Transaction
    suspend fun activateLocation(id: String) {
        deactivateAll()
        setActive(id)
    }
}
