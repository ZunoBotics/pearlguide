package com.zunobotics.okellonexus.data.repository

import com.zunobotics.okellonexus.data.db.dao.PersonaDao
import com.zunobotics.okellonexus.data.db.entity.PersonaEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonaRepository @Inject constructor(
    private val dao: PersonaDao,
    private val mqttRepo: MqttRepository
) {
    val personas: Flow<List<PersonaEntity>> = dao.getAllFlow()
    val activePersona: Flow<PersonaEntity?> = dao.getActiveFlow()

    suspend fun getActive(): PersonaEntity? = dao.getActive()

    suspend fun save(persona: PersonaEntity) {
        val entity = if (persona.id.isEmpty()) persona.copy(id = UUID.randomUUID().toString()) else persona
        dao.insert(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(persona: PersonaEntity) = dao.delete(persona)

    suspend fun activate(persona: PersonaEntity) {
        dao.activatePersona(persona.id)
        val tags = try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(persona.personality)
        } catch (_: Exception) { emptyList() }
        mqttRepo.sendPersona(
            name = persona.name,
            role = persona.role,
            greeting = persona.greeting,
            personality = tags,
            voiceSpeed = persona.voiceSpeed,
            extraInstructions = persona.extraInstructions,
            languageCode = persona.languageCode
        )
    }

    suspend fun getById(id: String): PersonaEntity? = dao.getById(id)
}
