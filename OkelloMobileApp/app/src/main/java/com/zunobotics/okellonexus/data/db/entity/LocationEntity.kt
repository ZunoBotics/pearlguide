package com.zunobotics.okellonexus.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,               // museum, office, mall, hospital, airport, school, event, other
    val personaId: String = "",     // links location to a specific persona/identity
    val description: String = "",
    val address: String = "",
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    val openingHours: String = "",
    val notes: String = "",
    val specialInstructions: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
