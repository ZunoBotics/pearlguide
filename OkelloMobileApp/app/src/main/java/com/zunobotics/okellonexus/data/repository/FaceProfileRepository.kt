package com.zunobotics.okellonexus.data.repository

import com.zunobotics.okellonexus.data.db.dao.FaceProfileDao
import com.zunobotics.okellonexus.data.db.entity.FaceProfile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceProfileRepository @Inject constructor(
    private val dao: FaceProfileDao
) {
    val profiles: Flow<List<FaceProfile>> = dao.allProfiles()

    suspend fun getById(id: String): FaceProfile? = dao.getById(id)
    suspend fun getCurrentVip(): FaceProfile? = dao.getCurrentVip()
    suspend fun save(profile: FaceProfile) = dao.upsert(profile)
    suspend fun delete(profile: FaceProfile) = dao.delete(profile)
    suspend fun deleteAll() = dao.deleteAll()

    suspend fun assignVip(id: String) {
        dao.clearVip()
        dao.setVip(id)
    }

    suspend fun clearVip() = dao.clearVip()

    suspend fun recordInteraction(id: String, location: String) =
        dao.recordInteraction(id, System.currentTimeMillis(), location)
}
