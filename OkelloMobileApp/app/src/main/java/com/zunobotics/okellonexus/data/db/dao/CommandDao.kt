package com.zunobotics.okellonexus.data.db.dao

import androidx.room.*
import com.zunobotics.okellonexus.data.db.entity.CommandHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandDao {
    @Query("SELECT * FROM command_history ORDER BY sentAt DESC LIMIT 100")
    fun getRecentFlow(): Flow<List<CommandHistory>>

    @Query("SELECT * FROM command_history ORDER BY sentAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 10): List<CommandHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CommandHistory)

    @Query("DELETE FROM command_history WHERE sentAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
