package com.msaidizi.core.di

import android.content.Context
import androidx.room.Room
import com.msaidizi.core.database.*
// Tool registry moved to app module's AppModule to avoid circular dependency with :agent
import com.msaidizi.core.security.BiometricAuthManager
import com.msaidizi.core.security.EncryptionManager
import com.msaidizi.core.security.PinHasher
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
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
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            MsaidiziDatabase::class.java,
            "msaidizi.db"
        )
            .openHelperFactory(factory)
            .addMigrations(*com.msaidizi.core.database.Migrations.ALL_MIGRATIONS)
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
    @Provides fun provideMpesaTransactionDao(db: MsaidiziDatabase): MpesaTransactionDao = db.mpesaTransactionDao()
    // KgNodeDao, KgEdgeDao, KgFactDao are provided by GraphModule (superagent/graph/GraphModule.kt)

    // NOTE: provideToolRegistry removed from core — it belongs in app's AppModule
    // to avoid circular dependency (core cannot depend on :agent).
}
