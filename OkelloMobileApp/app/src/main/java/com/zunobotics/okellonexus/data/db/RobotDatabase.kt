package com.zunobotics.okellonexus.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zunobotics.okellonexus.data.db.dao.*
import com.zunobotics.okellonexus.data.db.entity.*

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE knowledge ADD COLUMN personaId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE locations ADD COLUMN personaId TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [PersonaEntity::class, LocationEntity::class, KnowledgeEntry::class, CommandHistory::class],
    version = 2,
    exportSchema = false
)
abstract class RobotDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun locationDao(): LocationDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun commandDao(): CommandDao
}
