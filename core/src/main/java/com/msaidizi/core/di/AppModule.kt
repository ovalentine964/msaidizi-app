package com.msaidizi.core.di

import android.content.Context
import androidx.room.Room
import com.msaidizi.core.database.*
import com.msaidizi.app.superagent.tools.*
import com.msaidizi.core.security.BiometricAuthManager
import com.msaidizi.core.security.EncryptionManager
import com.msaidizi.core.security.PinHasher
import com.google.gson.Gson
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
    fun provideGson(): Gson = Gson()

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
    @Provides fun provideRestockThresholdDao(db: MsaidiziDatabase): RestockThresholdDao = db.restockThresholdDao()
    @Provides fun provideCustomerProfileDao(db: MsaidiziDatabase): CustomerProfileDao = db.customerProfileDao()
    @Provides fun provideCustomerVisitDao(db: MsaidiziDatabase): CustomerVisitDao = db.customerVisitDao()
    @Provides fun provideMarketPoolDao(db: MsaidiziDatabase): MarketPoolDao = db.marketPoolDao()
    @Provides fun provideMarketPoolMemberDao(db: MsaidiziDatabase): MarketPoolMemberDao = db.marketPoolMemberDao()
    @Provides fun provideMarketPoolTripDao(db: MsaidiziDatabase): MarketPoolTripDao = db.marketPoolTripDao()
    @Provides fun provideMarketPoolOrderDao(db: MsaidiziDatabase): MarketPoolOrderDao = db.marketPoolOrderDao()
    @Provides fun provideMarketPoolContributionDao(db: MsaidiziDatabase): MarketPoolContributionDao = db.marketPoolContributionDao()
    @Provides fun provideRideUserDao(db: MsaidiziDatabase): RideUserDao = db.rideUserDao()
    @Provides fun provideRideOfferDao(db: MsaidiziDatabase): RideOfferDao = db.rideOfferDao()
    @Provides fun provideRideRequestDao(db: MsaidiziDatabase): RideRequestDao = db.rideRequestDao()
    @Provides fun provideRideTripDao(db: MsaidiziDatabase): RideTripDao = db.rideTripDao()
    @Provides fun provideRideRatingDao(db: MsaidiziDatabase): RideRatingDao = db.rideRatingDao()
    @Provides fun provideServiceTransactionDao(db: MsaidiziDatabase): ServiceTransactionDao = db.serviceTransactionDao()
    @Provides fun provideServiceMenuDao(db: MsaidiziDatabase): ServiceMenuDao = db.serviceMenuDao()
    @Provides fun provideBulkOrderDao(db: MsaidiziDatabase): BulkOrderDao = db.bulkOrderDao()
    @Provides fun provideBulkCommitmentDao(db: MsaidiziDatabase): BulkCommitmentDao = db.bulkCommitmentDao()
    @Provides fun provideBulkEscrowDao(db: MsaidiziDatabase): BulkEscrowDao = db.bulkEscrowDao()
    // Boda boda DAOs (Fix 1-5)
    @Provides fun provideEmergencyContactDao(db: MsaidiziDatabase): EmergencyContactDao = db.emergencyContactDao()
    @Provides fun provideSosEventDao(db: MsaidiziDatabase): SOSEventDao = db.sosEventDao()
    @Provides fun provideBodaIncomeDao(db: MsaidiziDatabase): BodaIncomeDao = db.bodaIncomeDao()
    @Provides fun provideBodaExpenseDao(db: MsaidiziDatabase): BodaExpenseDao = db.bodaExpenseDao()
    @Provides fun provideFuelPurchaseDao(db: MsaidiziDatabase): FuelPurchaseDao = db.fuelPurchaseDao()
    @Provides fun provideTripKilometersDao(db: MsaidiziDatabase): TripKilometersDao = db.tripKilometersDao()
    @Provides fun provideFareRecordDao(db: MsaidiziDatabase): FareRecordDao = db.fareRecordDao()
    @Provides fun provideHirePurchaseAgreementDao(db: MsaidiziDatabase): HirePurchaseAgreementDao = db.hirePurchaseAgreementDao()
    @Provides fun provideHirePaymentDao(db: MsaidiziDatabase): HirePaymentDao = db.hirePaymentDao()
    // KgNodeDao, KgEdgeDao, KgFactDao are provided by GraphModule (superagent/graph/GraphModule.kt)

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
        chamaManager: com.msaidizi.app.superagent.tools.ChamaManager,
        customerInsights: com.msaidizi.app.superagent.tools.CustomerInsights,
        marketPooling: com.msaidizi.app.superagent.tools.MarketPooling,
        rideShare: com.msaidizi.app.superagent.tools.RideShare,
        receiptScannerCV: com.msaidizi.app.superagent.tools.ReceiptScannerCV,
        autoRestock: com.msaidizi.app.superagent.tools.AutoRestock,
        bulkOrderCoordinator: com.msaidizi.app.superagent.tools.BulkOrderCoordinator,
        businessHealthDashboard: com.msaidizi.app.superagent.tools.BusinessHealthDashboard,
        competitorTracker: com.msaidizi.app.superagent.tools.CompetitorTracker,
        creditReadiness: com.msaidizi.app.superagent.tools.CreditReadiness,
        insuranceMatcher: com.msaidizi.app.superagent.tools.InsuranceMatcher,
        loanComparison: com.msaidizi.app.superagent.tools.LoanComparison,
        marketDayPlanner: com.msaidizi.app.superagent.tools.MarketDayPlanner,
        marketPriceBroadcaster: com.msaidizi.app.superagent.tools.MarketPriceBroadcaster,
        mpesaAutoLogger: com.msaidizi.app.superagent.tools.MpesaAutoLogger,
        priceNegotiator: com.msaidizi.app.superagent.tools.PriceNegotiator,
        profitByProduct: com.msaidizi.app.superagent.tools.ProfitByProduct,
        proofOfIncome: com.msaidizi.app.superagent.tools.ProofOfIncome,
        quickSale: com.msaidizi.app.superagent.tools.QuickSale,
        supplierMatcher: com.msaidizi.app.superagent.tools.SupplierMatcher,
        wasteReducer: com.msaidizi.app.superagent.tools.WasteReducer,
        bookingScheduler: com.msaidizi.app.superagent.tools.BookingScheduler,
        customerMatcher: com.msaidizi.app.superagent.tools.CustomerMatcher,
        fishingLog: com.msaidizi.app.superagent.tools.FishingLog,
        harvestTracker: com.msaidizi.app.superagent.tools.HarvestTracker,
        jobMatcher: com.msaidizi.app.superagent.tools.JobMatcher,
        miningLog: com.msaidizi.app.superagent.tools.MiningLog,
        postHarvestLossTracker: com.msaidizi.app.superagent.tools.PostHarvestLossTracker,
        producePriceTracker: com.msaidizi.app.superagent.tools.ProducePriceTracker,
        ratingSystem: com.msaidizi.app.superagent.tools.RatingSystem,
        serviceMarketBroadcaster: com.msaidizi.app.superagent.tools.ServiceMarketBroadcaster,
        servicePriceAdvisor: com.msaidizi.app.superagent.tools.ServicePriceAdvisor,
        wageCalculator: com.msaidizi.app.superagent.tools.WageCalculator,
        yieldPredictor: com.msaidizi.app.superagent.tools.YieldPredictor,
        // Boda boda tools (Fix 1-5)
        sosSafetyButton: com.msaidizi.app.superagent.tools.SOSSafetyButton,
        netIncomeCalculator: com.msaidizi.app.superagent.tools.NetIncomeCalculator,
        fuelEfficiencyTracker: com.msaidizi.app.superagent.tools.FuelEfficiencyTracker,
        fareIntelligence: com.msaidizi.app.superagent.tools.FareIntelligence,
        hirePurchaseTracker: com.msaidizi.app.superagent.tools.HirePurchaseTracker
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
        registry.register(customerInsights)
        registry.register(marketPooling)
        registry.register(rideShare)
        registry.register(receiptScannerCV)
        registry.register(autoRestock)
        registry.register(bulkOrderCoordinator)
        registry.register(businessHealthDashboard)
        registry.register(competitorTracker)
        registry.register(creditReadiness)
        registry.register(insuranceMatcher)
        registry.register(loanComparison)
        registry.register(marketDayPlanner)
        registry.register(marketPriceBroadcaster)
        registry.register(mpesaAutoLogger)
        registry.register(priceNegotiator)
        registry.register(profitByProduct)
        registry.register(proofOfIncome)
        registry.register(quickSale)
        registry.register(supplierMatcher)
        registry.register(wasteReducer)
        registry.register(bookingScheduler)
        registry.register(customerMatcher)
        registry.register(fishingLog)
        registry.register(harvestTracker)
        registry.register(jobMatcher)
        registry.register(miningLog)
        registry.register(postHarvestLossTracker)
        registry.register(producePriceTracker)
        registry.register(ratingSystem)
        registry.register(serviceMarketBroadcaster)
        registry.register(servicePriceAdvisor)
        registry.register(wageCalculator)
        registry.register(yieldPredictor)
        // Boda boda tools (Fix 1-5)
        registry.register(sosSafetyButton)
        registry.register(netIncomeCalculator)
        registry.register(fuelEfficiencyTracker)
        registry.register(fareIntelligence)
        registry.register(hirePurchaseTracker)
        return registry
    }
}
