package com.zunobotics.okellonexus.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge")
data class KnowledgeEntry(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val category: String,           // exhibit, rule, fact, person, product, faq
    val personaId: String = "",     // links fact to a specific persona/identity
    val tags: String = "[]",        // JSON array as string
    val locationId: String? = null,
    val locationSpecific: Boolean = false,
    val source: String = "manual",  // manual, document, taught, voice
    val documentName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val deletedAt: Long? = null     // soft delete
)
