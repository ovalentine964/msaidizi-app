package com.msaidizi.app.core.di

import android.content.Context
import androidx.room.Room
import com.msaidizi.app.core.database.*
import com.msaidizi.app.core.security.EncryptionManager
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
}

    @Provides
    @Singleton
    fun provideToolRegistry(
        transactionRecorder: com.msaidizi.app.superagent.tools.TransactionRecorder,
        inventoryTracker: com.msaidizi.app.superagent.tools.InventoryTracker,
        cfoEngine: com.msaidizi.app.superagent.tools.CFOEngine,
        voicePipeline: com.msaidizi.app.superagent.tools.VoicePipeline,
        gamificationEngine: com.msaidizi.app.superagent.tools.GamificationEngine,
        goalTracker: com.msaidizi.app.superagent.tools.GoalTracker,
        receiptScanner: com.msaidizi.app.superagent.tools.ReceiptScanner,
        whatsappReporter: com.msaidizi.app.superagent.tools.WhatsAppReporter,
        syncEngine: com.msaidizi.app.superagent.tools.SyncEngine,
        securityGuard: com.msaidizi.app.superagent.tools.SecurityGuard,
        modelDownloader: com.msaidizi.app.superagent.tools.ModelDownloader,
        adaptiveLearner: com.msaidizi.app.superagent.tools.AdaptiveLearner,
        memoryManager: com.msaidizi.app.superagent.tools.MemoryManager,
        guardrailsEngine: com.msaidizi.app.superagent.tools.GuardrailsEngine,
        anomalyDetector: com.msaidizi.app.superagent.tools.AnomalyDetector,
        mpesaParser: com.msaidizi.app.superagent.tools.MpesaParser,
        pricingAdvisor: com.msaidizi.app.superagent.tools.PricingAdvisor,
        restockPredictor: com.msaidizi.app.superagent.tools.RestockPredictor
    ): com.msaidizi.app.superagent.tools.ToolRegistry {
        val registry = com.msaidizi.app.superagent.tools.ToolRegistry()
        registry.register(transactionRecorder)
        registry.register(inventoryTracker)
        registry.register(cfoEngine)
        registry.register(voicePipeline)
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
        return registry
    }
