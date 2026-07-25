package com.msaidizi.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.msaidizi.app.model.*

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
        CustomerVisitEntity::class
    ],
    version = 6,
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
}
