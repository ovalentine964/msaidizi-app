package com.msaidizi.app.core.di

import com.msaidizi.app.security.crypto.CryptoService
import com.msaidizi.app.security.crypto.CryptoServiceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Security-related dependencies: encryption, privacy consent, pinned HTTP.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindCryptoService(impl: CryptoServiceImpl): CryptoService
}
