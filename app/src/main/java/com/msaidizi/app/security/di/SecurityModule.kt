package com.msaidizi.app.security.di

import com.msaidizi.app.security.crypto.pqc.HybridKeyExchange
import com.msaidizi.app.security.crypto.pqc.MlKemProvider
import com.msaidizi.app.security.crypto.pqc.MlKemParameterSet
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for security components.
 *
 * Most security classes are auto-provided by Hilt via their @Inject constructors.
 * Only HybridKeyExchange is provided here because it requires a specific MlKemProvider
 * configuration that Hilt cannot auto-resolve.
 *
 * Duplicate @Provides methods removed to fix Dagger/DuplicateBindings errors with KSP:
 *   SecureTokenStorage, JwtTokenManager, OtpManager, BiometricAuthManager,
 *   SessionManager, KeyManager, EncryptedStorage, TlsConfig, CryptoAuditLogger,
 *   AlgorithmRegistry, DocumentSigner, DeviceBinder, SuspiciousLoginDetector,
 *   ConsentManager, DataRetentionManager, DataMinimizer, DifferentialPrivacy,
 *   FederatedLearningPrivacy — all 18 now auto-provided via @Inject constructors.
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
