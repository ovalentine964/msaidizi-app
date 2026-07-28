package com.msaidizi.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.msaidizi.core.model.*
import com.msaidizi.app.superagent.graph.*
import com.msaidizi.app.superagent.tools.*

@Database(
    entities = [
        SaleEntity::class,
        ProductEntity::class,
        ExpenseEntity::class,
        CustomerEntity::class,
        DailySummaryEntity::class,
        StockMovementEntity::class,
        ConversationEntity::class,
        KnowledgeEntity::class,
        UserProfileEntity::class,
        AnomalyHistoryEntity::class,
        LearnedVocabularyEntity::class,
        BusinessPatternEntity::class,
        SyncStateEntity::class,
        DebtEntity::class,
        DebtRepaymentEntity::class,
        ChamaEntity::class,
        ChamaMemberEntity::class,
        ChamaContributionEntity::class,
        ChamaPayoutEntity::class,
        RestockThresholdEntity::class,
        CustomerProfileEntity::class,
        CustomerVisitEntity::class,
        MarketPoolEntity::class,
        MarketPoolMemberEntity::class,
        MarketPoolOrderEntity::class,
        MarketPoolContributionEntity::class,
        RideUserEntity::class,
        RideOfferEntity::class,
        RideRequestEntity::class,
        RideTripEntity::class,
        RideRatingEntity::class,
        ServiceTransactionEntity::class,
        ServiceMenuEntity::class,
        BulkOrderEntity::class,
        BulkCommitmentEntity::class,
        BulkEscrowEntity::class,
        KgNodeEntity::class,
        KgEdgeEntity::class,
        KgFactEntity::class,
        TraceEntity::class,
        EmergencyContactEntity::class,
        SOSEventEntity::class,
        BodaIncomeEntity::class,
        BodaExpenseEntity::class,
        FuelPurchaseEntity::class,
        TripKilometersEntity::class,
        FareRecordEntity::class,
        HirePurchaseAgreementEntity::class,
        HirePaymentEntity::class
    ],
    version = 13,
    exportSchema = true
)
abstract class MsaidiziDatabase : RoomDatabase() {
    abstract fun saleDao(): SaleDao
    abstract fun productDao(): ProductDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun customerDao(): CustomerDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun stockMovementDao(): StockMovementDao
    abstract fun conversationDao(): ConversationDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun anomalyHistoryDao(): AnomalyHistoryDao
    abstract fun learnedVocabularyDao(): LearnedVocabularyDao
    abstract fun businessPatternDao(): BusinessPatternDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun debtDao(): DebtDao
    abstract fun debtRepaymentDao(): DebtRepaymentDao
    abstract fun chamaDao(): ChamaDao
    abstract fun chamaMemberDao(): ChamaMemberDao
    abstract fun chamaContributionDao(): ChamaContributionDao
    abstract fun chamaPayoutDao(): ChamaPayoutDao
    abstract fun restockThresholdDao(): RestockThresholdDao
    abstract fun customerProfileDao(): CustomerProfileDao
    abstract fun customerVisitDao(): CustomerVisitDao
    abstract fun marketPoolDao(): MarketPoolDao
    abstract fun marketPoolMemberDao(): MarketPoolMemberDao
    abstract fun marketPoolTripDao(): MarketPoolTripDao
    abstract fun marketPoolOrderDao(): MarketPoolOrderDao
    abstract fun marketPoolContributionDao(): MarketPoolContributionDao
    abstract fun rideUserDao(): RideUserDao
    abstract fun rideOfferDao(): RideOfferDao
    abstract fun rideRequestDao(): RideRequestDao
    abstract fun rideTripDao(): RideTripDao
    abstract fun rideRatingDao(): RideRatingDao
    abstract fun serviceTransactionDao(): ServiceTransactionDao
    abstract fun serviceMenuDao(): ServiceMenuDao
    abstract fun bulkOrderDao(): BulkOrderDao
    abstract fun bulkCommitmentDao(): BulkCommitmentDao
    abstract fun bulkEscrowDao(): BulkEscrowDao
    abstract fun kgNodeDao(): KgNodeDao
    abstract fun kgEdgeDao(): KgEdgeDao
    abstract fun kgFactDao(): KgFactDao
    abstract fun traceDao(): TraceDao
    abstract fun emergencyContactDao(): EmergencyContactDao
    abstract fun sosEventDao(): SOSEventDao
    abstract fun bodaIncomeDao(): BodaIncomeDao
    abstract fun bodaExpenseDao(): BodaExpenseDao
    abstract fun fuelPurchaseDao(): FuelPurchaseDao
    abstract fun tripKilometersDao(): TripKilometersDao
    abstract fun fareRecordDao(): FareRecordDao
    abstract fun hirePurchaseAgreementDao(): HirePurchaseAgreementDao
    abstract fun hirePaymentDao(): HirePaymentDao
}
