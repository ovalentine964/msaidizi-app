package com.msaidizi.app.core.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Security-related dependencies: encryption, privacy consent, pinned HTTP.
 * CryptoService is bound in security/di/SecurityModule.kt (CryptoServiceModule).
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
}
