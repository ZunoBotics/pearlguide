package com.zunobotics.okellonexus.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_history")
data class CommandHistory(
    @PrimaryKey val id: String,
    val type: String,
    val payload: String,            // full JSON payload
    val sentAt: Long = System.currentTimeMillis(),
    val acknowledged: Boolean = false
)
