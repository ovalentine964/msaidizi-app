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
        SyncStateEntity::class
    ],
    version = 2,
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
}
