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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS face_profiles (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                roleTag TEXT NOT NULL DEFAULT 'Guest',
                notes TEXT NOT NULL DEFAULT '',
                photoBase64 TEXT NOT NULL DEFAULT '',
                isCurrentVip INTEGER NOT NULL DEFAULT 0,
                lastSeenAt INTEGER,
                lastSeenLocation TEXT NOT NULL DEFAULT '',
                totalInteractions INTEGER NOT NULL DEFAULT 0,
                enrolledAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

@Database(
    entities = [PersonaEntity::class, LocationEntity::class, KnowledgeEntry::class, CommandHistory::class, FaceProfile::class],
    version = 3,
    exportSchema = false
)
abstract class RobotDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun locationDao(): LocationDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun commandDao(): CommandDao
    abstract fun faceProfileDao(): FaceProfileDao
}
