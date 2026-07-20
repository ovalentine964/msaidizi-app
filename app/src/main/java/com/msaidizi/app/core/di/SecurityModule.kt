package com.msaidizi.app.core.di

import com.msaidizi.app.security.privacy.ConsentManager
import com.msaidizi.app.core.network.PinnedHttpClient
import com.msaidizi.app.security.crypto.DatabaseKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Security-related dependencies: encryption, privacy consent, pinned HTTP.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    // DatabaseKeyManager, ConsentManager, and PinnedHttpClient
    // are auto-provided by Hilt via their @Inject constructors.
    // This module exists as a logical grouping for future security provisions.
}
