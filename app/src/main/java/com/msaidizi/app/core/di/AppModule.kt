package com.msaidizi.app.core.di

import android.content.Context
import androidx.room.Room
import com.msaidizi.app.core.database.*
import com.msaidizi.app.core.security.BiometricAuthManager
import com.msaidizi.app.core.security.EncryptionManager
import com.msaidizi.app.core.security.PinHasher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        encryptionManager: EncryptionManager
    ): MsaidiziDatabase {
        val passphrase = encryptionManager.getDatabasePassphrase()
        val factory = SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            MsaidiziDatabase::class.java,
            "msaidizi.db"
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideSaleDao(db: MsaidiziDatabase): SaleDao = db.saleDao()
    @Provides fun provideProductDao(db: MsaidiziDatabase): ProductDao = db.productDao()
    @Provides fun provideExpenseDao(db: MsaidiziDatabase): ExpenseDao = db.expenseDao()
    @Provides fun provideCustomerDao(db: MsaidiziDatabase): CustomerDao = db.customerDao()
    @Provides fun provideDailySummaryDao(db: MsaidiziDatabase): DailySummaryDao = db.dailySummaryDao()
    @Provides fun provideStockMovementDao(db: MsaidiziDatabase): StockMovementDao = db.stockMovementDao()
    @Provides fun provideConversationDao(db: MsaidiziDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideKnowledgeDao(db: MsaidiziDatabase): KnowledgeDao = db.knowledgeDao()
    @Provides fun provideUserProfileDao(db: MsaidiziDatabase): UserProfileDao = db.userProfileDao()
    @Provides fun provideAnomalyHistoryDao(db: MsaidiziDatabase): AnomalyHistoryDao = db.anomalyHistoryDao()
    @Provides fun provideLearnedVocabularyDao(db: MsaidiziDatabase): LearnedVocabularyDao = db.learnedVocabularyDao()
    @Provides fun provideBusinessPatternDao(db: MsaidiziDatabase): BusinessPatternDao = db.businessPatternDao()
    @Provides fun provideSyncStateDao(db: MsaidiziDatabase): SyncStateDao = db.syncStateDao()
    @Provides fun provideDebtDao(db: MsaidiziDatabase): DebtDao = db.debtDao()
    @Provides fun provideDebtRepaymentDao(db: MsaidiziDatabase): DebtRepaymentDao = db.debtRepaymentDao()
    @Provides fun provideChamaDao(db: MsaidiziDatabase): ChamaDao = db.chamaDao()
    @Provides fun provideChamaMemberDao(db: MsaidiziDatabase): ChamaMemberDao = db.chamaMemberDao()
    @Provides fun provideChamaContributionDao(db: MsaidiziDatabase): ChamaContributionDao = db.chamaContributionDao()
    @Provides fun provideChamaPayoutDao(db: MsaidiziDatabase): ChamaPayoutDao = db.chamaPayoutDao()

    @Provides
    @Singleton
    fun provideToolRegistry(
        transactionRecorder: com.msaidizi.app.superagent.tools.TransactionRecorder,
        inventoryTracker: com.msaidizi.app.superagent.tools.InventoryTracker,
        cfoEngine: com.msaidizi.app.superagent.tools.CFOEngine,
        voicePipeline: com.msaidizi.app.superagent.tools.VoicePipeline,
        languageDetector: com.msaidizi.app.superagent.tools.LanguageDetector,
        codeSwitchHandler: com.msaidizi.app.superagent.tools.CodeSwitchHandler,
        gamificationEngine: com.msaidizi.app.superagent.tools.GamificationEngine,
        goalTracker: com.msaidizi.app.superagent.tools.GoalTracker,
        receiptScanner: com.msaidizi.app.superagent.tools.ReceiptScanner,
        whatsappReporter: com.msaidizi.app.superagent.tools.WhatsAppReporter,
        syncEngine: com.msaidizi.app.superagent.tools.SyncEngine,
        securityGuard: com.msaidizi.app.superagent.tools.SecurityGuard,
        modelDownloader: com.msaidizi.app.superagent.tools.ModelDownloader,
        adaptiveLearner: com.msaidizi.app.superagent.tools.AdaptiveLearner,
        memoryManager: com.msaidizi.app.superagent.memory.MemoryManager,
        guardrailsEngine: com.msaidizi.app.superagent.tools.GuardrailsEngine,
        anomalyDetector: com.msaidizi.app.superagent.tools.AnomalyDetector,
        mpesaParser: com.msaidizi.app.superagent.tools.MpesaParser,
        pricingAdvisor: com.msaidizi.app.superagent.tools.PricingAdvisor,
        restockPredictor: com.msaidizi.app.superagent.tools.RestockPredictor,
        alamaScore: com.msaidizi.app.superagent.tools.AlamaScore,
        serviceMenu: com.msaidizi.app.superagent.tools.ServiceMenu,
        serviceVoiceCommands: com.msaidizi.app.superagent.tools.ServiceVoiceCommands,
        debtTracker: com.msaidizi.app.superagent.tools.DebtTracker,
        chamaManager: com.msaidizi.app.superagent.tools.ChamaManager
    ): com.msaidizi.app.superagent.tools.ToolRegistry {
        val registry = com.msaidizi.app.superagent.tools.ToolRegistry()
        registry.register(transactionRecorder)
        registry.register(inventoryTracker)
        registry.register(cfoEngine)
        registry.register(voicePipeline)
        registry.register(languageDetector)
        registry.register(codeSwitchHandler)
        registry.register(gamificationEngine)
        registry.register(goalTracker)
        registry.register(receiptScanner)
        registry.register(whatsappReporter)
        registry.register(syncEngine)
        registry.register(securityGuard)
        registry.register(modelDownloader)
        registry.register(adaptiveLearner)
        registry.register(memoryManager)
        registry.register(guardrailsEngine)
        registry.register(anomalyDetector)
        registry.register(mpesaParser)
        registry.register(pricingAdvisor)
        registry.register(restockPredictor)
        registry.register(alamaScore)
        registry.register(serviceMenu)
        registry.register(serviceVoiceCommands)
        registry.register(debtTracker)
        registry.register(chamaManager)
        return registry
    }
}
