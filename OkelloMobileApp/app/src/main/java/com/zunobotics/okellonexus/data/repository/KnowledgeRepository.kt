package com.zunobotics.okellonexus.data.repository

import com.zunobotics.okellonexus.data.db.dao.KnowledgeDao
import com.zunobotics.okellonexus.data.db.entity.KnowledgeEntry
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KnowledgeRepository @Inject constructor(
    private val dao: KnowledgeDao,
    private val mqttRepo: MqttRepository
) {
    val entries: Flow<List<KnowledgeEntry>> = dao.getAllFlow()
    val count: Flow<Int> = dao.countFlow()

    fun entriesForPersona(personaId: String): Flow<List<KnowledgeEntry>> = dao.getByPersonaFlow(personaId)

    suspend fun getAll(): List<KnowledgeEntry> = dao.getAll()
    suspend fun getByPersona(personaId: String): List<KnowledgeEntry> = dao.getByPersona(personaId)

    private suspend fun publishSnapshot() {
        mqttRepo.sendKnowledgeSnapshot(dao.getAll())
    }

    suspend fun add(entry: KnowledgeEntry) {
        val e = if (entry.id.isEmpty()) entry.copy(id = UUID.randomUUID().toString()) else entry
        dao.insert(e)
        val tags = try { kotlinx.serialization.json.Json.decodeFromString<List<String>>(e.tags) } catch (_: Exception) { emptyList() }
        mqttRepo.addKnowledge(e.id, e.title, e.content, e.category, tags, e.locationSpecific, e.source)
        dao.markSynced(e.id)
        publishSnapshot()
    }

    suspend fun delete(id: String) {
        dao.softDelete(id)
        mqttRepo.deleteKnowledge(id)
        publishSnapshot()
    }

    suspend fun getById(id: String): KnowledgeEntry? = dao.getById(id)
    fun getByCategory(category: String): Flow<List<KnowledgeEntry>> = dao.getByCategory(category)
}
