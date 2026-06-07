package com.zunobotics.okellonexus.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "face_profiles")
data class FaceProfile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val roleTag: String = "Guest",       // VIP | Staff | Regular | Guest
    val notes: String = "",
    val photoBase64: String = "",        // thumbnail JPEG, base64-encoded
    val isCurrentVip: Boolean = false,
    val lastSeenAt: Long? = null,
    val lastSeenLocation: String = "",
    val totalInteractions: Int = 0,
    val enrolledAt: Long = System.currentTimeMillis()
)
