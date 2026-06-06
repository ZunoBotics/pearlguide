package com.zunobotics.okellonexus.data.repository

import com.zunobotics.okellonexus.data.db.dao.LocationDao
import com.zunobotics.okellonexus.data.db.entity.LocationEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepository @Inject constructor(
    private val dao: LocationDao,
    private val mqttRepo: MqttRepository
) {
    val locations: Flow<List<LocationEntity>> = dao.getAllFlow()
    val activeLocation: Flow<LocationEntity?> = dao.getActiveFlow()

    fun locationsForPersona(personaId: String): Flow<List<LocationEntity>> = dao.getByPersonaFlow(personaId)

    suspend fun getActive(): LocationEntity? = dao.getActive()

    suspend fun save(location: LocationEntity) {
        val entity = if (location.id.isEmpty()) location.copy(id = UUID.randomUUID().toString()) else location
        dao.insert(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(location: LocationEntity) = dao.delete(location)

    suspend fun activate(location: LocationEntity) {
        dao.activateLocation(location.id)
        mqttRepo.sendLocation(
            name = location.name, type = location.type, description = location.description,
            gpsLat = location.gpsLat, gpsLng = location.gpsLng,
            openingHours = location.openingHours, notes = location.notes,
            specialInstructions = location.specialInstructions
        )
    }

    suspend fun getById(id: String): LocationEntity? = dao.getById(id)
}
