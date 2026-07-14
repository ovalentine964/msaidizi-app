package com.msaidizi.app.security.di

import android.content.Context
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.security.auth.BiometricAuthManager
import com.msaidizi.app.security.auth.JwtTokenManager
import com.msaidizi.app.security.auth.OtpManager
import com.msaidizi.app.security.auth.SecureTokenStorage
import com.msaidizi.app.security.auth.SessionManager
import com.msaidizi.app.security.crypto.EncryptedStorage
import com.msaidizi.app.security.crypto.KeyManager
import com.msaidizi.app.security.crypto.TlsConfig
import com.msaidizi.app.security.crypto.pqc.AlgorithmRegistry
import com.msaidizi.app.security.crypto.pqc.CryptoAuditLogger
import com.msaidizi.app.security.crypto.pqc.DocumentSigner
import com.msaidizi.app.security.crypto.pqc.HybridKeyExchange
import com.msaidizi.app.security.crypto.pqc.MlDsaProvider
import com.msaidizi.app.security.crypto.pqc.MlDsaParameterSet
import com.msaidizi.app.security.crypto.pqc.MlKemProvider
import com.msaidizi.app.security.crypto.pqc.MlKemParameterSet
import com.msaidizi.app.security.privacy.ConsentManager
import com.msaidizi.app.security.privacy.DataMinimizer
import com.msaidizi.app.security.privacy.DataRetentionManager
import com.msaidizi.app.security.privacy.DifferentialPrivacy
import com.msaidizi.app.security.privacy.FederatedLearningPrivacy
import com.msaidizi.app.security.simswap.DeviceBinder
import com.msaidizi.app.security.simswap.SuspiciousLoginDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for all security components.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    // === AUTHENTICATION ===

    @Provides
    @Singleton
    fun provideSecureTokenStorage(
        @ApplicationContext context: Context
    ): SecureTokenStorage = SecureTokenStorage(context)

    @Provides
    @Singleton
    fun provideJwtTokenManager(
        @ApplicationContext context: Context,
        tokenStorage: SecureTokenStorage,
        api: MsaidiziApi
    ): JwtTokenManager = JwtTokenManager(context, tokenStorage, api)

    @Provides
    @Singleton
    fun provideOtpManager(
        @ApplicationContext context: Context,
        tokenStorage: SecureTokenStorage
    ): OtpManager = OtpManager(context, tokenStorage)

    @Provides
    @Singleton
    fun provideBiometricAuthManager(
        @ApplicationContext context: Context
    ): BiometricAuthManager = BiometricAuthManager(context)

    @Provides
    @Singleton
    fun provideSessionManager(
        @ApplicationContext context: Context,
        tokenStorage: SecureTokenStorage,
        jwtTokenManager: JwtTokenManager
    ): SessionManager = SessionManager(context, tokenStorage, jwtTokenManager)

    // === ENCRYPTION ===

    @Provides
    @Singleton
    fun provideKeyManager(
        @ApplicationContext context: Context
    ): KeyManager = KeyManager(context)

    @Provides
    @Singleton
    fun provideEncryptedStorage(
        @ApplicationContext context: Context,
        keyManager: KeyManager
    ): EncryptedStorage = EncryptedStorage(context, keyManager)

    @Provides
    @Singleton
    fun provideTlsConfig(
        @ApplicationContext context: Context,
        auditLogger: CryptoAuditLogger
    ): TlsConfig = TlsConfig(context, auditLogger)

    // === POST-QUANTUM CRYPTOGRAPHY ===

    @Provides
    @Singleton
    fun provideCryptoAuditLogger(
        @ApplicationContext context: Context
    ): CryptoAuditLogger = CryptoAuditLogger(context)

    @Provides
    @Singleton
    fun provideAlgorithmRegistry(): AlgorithmRegistry = AlgorithmRegistry()

    @Provides
    @Singleton
    fun provideDocumentSigner(
        algorithmRegistry: AlgorithmRegistry,
        auditLogger: CryptoAuditLogger
    ): DocumentSigner = DocumentSigner(algorithmRegistry, auditLogger)

    @Provides
    @Singleton
    fun provideHybridKeyExchange(): HybridKeyExchange = HybridKeyExchange(
        MlKemProvider(MlKemParameterSet.ML_KEM_768)
    )

    // === SIM SWAP DEFENSE ===

    @Provides
    @Singleton
    fun provideDeviceBinder(
        @ApplicationContext context: Context,
        tokenStorage: SecureTokenStorage,
        encryptedStorage: EncryptedStorage
    ): DeviceBinder = DeviceBinder(context, tokenStorage, encryptedStorage)

    @Provides
    @Singleton
    fun provideSuspiciousLoginDetector(
        @ApplicationContext context: Context,
        deviceBinder: DeviceBinder,
        encryptedStorage: EncryptedStorage
    ): SuspiciousLoginDetector = SuspiciousLoginDetector(context, deviceBinder, encryptedStorage)

    // === PRIVACY ===

    @Provides
    @Singleton
    fun provideConsentManager(
        @ApplicationContext context: Context
    ): ConsentManager = ConsentManager(context)

    @Provides
    @Singleton
    fun provideDataRetentionManager(
        @ApplicationContext context: Context,
        encryptedStorage: EncryptedStorage
    ): DataRetentionManager = DataRetentionManager(context, encryptedStorage)

    @Provides
    @Singleton
    fun provideDataMinimizer(
        @ApplicationContext context: Context
    ): DataMinimizer = DataMinimizer(context)

    @Provides
    @Singleton
    fun provideDifferentialPrivacy(): DifferentialPrivacy = DifferentialPrivacy()

    @Provides
    @Singleton
    fun provideFederatedLearningPrivacy(
        hybridKeyExchange: HybridKeyExchange,
        documentSigner: DocumentSigner
    ): FederatedLearningPrivacy = FederatedLearningPrivacy(hybridKeyExchange, documentSigner)
}
