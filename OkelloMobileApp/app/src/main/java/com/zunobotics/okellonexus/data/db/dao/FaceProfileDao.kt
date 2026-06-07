package com.zunobotics.okellonexus.data.db.dao

import androidx.room.*
import com.zunobotics.okellonexus.data.db.entity.FaceProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceProfileDao {
    @Query("SELECT * FROM face_profiles ORDER BY name ASC")
    fun allProfiles(): Flow<List<FaceProfile>>

    @Query("SELECT * FROM face_profiles WHERE id = :id")
    suspend fun getById(id: String): FaceProfile?

    @Query("SELECT * FROM face_profiles WHERE isCurrentVip = 1 LIMIT 1")
    suspend fun getCurrentVip(): FaceProfile?

    @Upsert
    suspend fun upsert(profile: FaceProfile)

    @Delete
    suspend fun delete(profile: FaceProfile)

    @Query("UPDATE face_profiles SET isCurrentVip = 0")
    suspend fun clearVip()

    @Query("UPDATE face_profiles SET isCurrentVip = 1 WHERE id = :id")
    suspend fun setVip(id: String)

    @Query("UPDATE face_profiles SET totalInteractions = totalInteractions + 1, lastSeenAt = :ts, lastSeenLocation = :location WHERE id = :id")
    suspend fun recordInteraction(id: String, ts: Long, location: String)

    @Query("DELETE FROM face_profiles")
    suspend fun deleteAll()
}
