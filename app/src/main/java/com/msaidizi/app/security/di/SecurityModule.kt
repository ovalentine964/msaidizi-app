package com.msaidizi.app.security.di

import com.msaidizi.app.security.crypto.CryptoService
import com.msaidizi.app.security.crypto.CryptoServiceImpl
import com.msaidizi.app.security.crypto.pqc.HybridKeyExchange
import com.msaidizi.app.security.crypto.pqc.MlKemProvider
import com.msaidizi.app.security.crypto.pqc.MlKemParameterSet
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for security components.
 *
 * Provides the unified [CryptoService] binding and other security singletons
 * that cannot be auto-resolved by Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    // HybridKeyExchange needs a specific MlKemProvider config (ML-KEM-768).
    // No @Inject constructor — must be provided explicitly.
    @Provides
    @Singleton
    fun provideHybridKeyExchange(): HybridKeyExchange = HybridKeyExchange(
        MlKemProvider(MlKemParameterSet.ML_KEM_768)
    )
}

/**
 * Binds [CryptoService] interface to [CryptoServiceImpl].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoServiceModule {
    @Binds
    @Singleton
    abstract fun bindCryptoService(impl: CryptoServiceImpl): CryptoService
}
