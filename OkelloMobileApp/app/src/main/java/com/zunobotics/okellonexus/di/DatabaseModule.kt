package com.zunobotics.okellonexus.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.zunobotics.okellonexus.data.db.RobotDatabase
import com.zunobotics.okellonexus.data.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_prefs")

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): RobotDatabase =
        Room.databaseBuilder(ctx, RobotDatabase::class.java, "robot_db")
            .addMigrations(
                com.zunobotics.okellonexus.data.db.MIGRATION_1_2,
                com.zunobotics.okellonexus.data.db.MIGRATION_2_3,
                com.zunobotics.okellonexus.data.db.MIGRATION_3_4
            )
            .build()

    @Provides fun providePersonaDao(db: RobotDatabase): PersonaDao = db.personaDao()
    @Provides fun provideLocationDao(db: RobotDatabase): LocationDao = db.locationDao()
    @Provides fun provideKnowledgeDao(db: RobotDatabase): KnowledgeDao = db.knowledgeDao()
    @Provides fun provideCommandDao(db: RobotDatabase): CommandDao = db.commandDao()
    @Provides fun provideFaceProfileDao(db: RobotDatabase): FaceProfileDao = db.faceProfileDao()

    @Provides @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.dataStore
}
