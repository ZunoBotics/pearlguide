package com.zunobotics.okellonexus.di

import com.zunobotics.okellonexus.data.db.dao.*
import com.zunobotics.okellonexus.data.repository.*
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Repositories use @Inject constructor + @Singleton — no explicit @Provides needed when using Hilt.
// This module is a placeholder for any interface bindings needed in the future.
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule
