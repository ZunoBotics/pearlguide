package com.zunobotics.okellonexus.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val greeting: String,
    val personality: String,        // JSON array as string e.g. ["friendly","energetic"]
    val voiceSpeed: Float = 1.0f,
    val extraInstructions: String = "",
    val languageCode: String = "en",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
