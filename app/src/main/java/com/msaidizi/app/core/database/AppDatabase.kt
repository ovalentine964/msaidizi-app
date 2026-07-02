package com.msaidizi.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.msaidizi.app.core.model.*
import com.msaidizi.app.evolution.FeedbackEntity
import com.msaidizi.app.evolution.FeatureRequestEntity
import com.msaidizi.app.evolution.FeedbackDao
import com.msaidizi.app.evolution.FeatureRequestDao

/**
 * Room database for Msaidizi.
 *
 * Stores all business transactions, inventory, patterns, vocabulary,
 * user corrections, adaptive learning data, and evolution feedback.
 * Optimized for 2GB devices:
 * - WAL mode for concurrent reads during writes
 * - Minimal indices (only what's needed for common queries)
 * - Integer timestamps (not datetime strings)
 *
 * Version 2: Added UserVocabulary and UserCorrection for adaptive learning.
 * Version 4: Added Feedback and FeatureRequest for self-evolution.
 */
@Database(
    entities = [
        Transaction::class,
        InventoryItem::class,
        BusinessPattern::class,
        VocabularyEntry::class,
        DailySummary::class,
        UserVocabulary::class,
        UserCorrection::class,
        LearnedWord::class,
        FeedbackEntity::class,
        FeatureRequestEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun patternDao(): PatternDao
    abstract fun userVocabularyDao(): UserVocabularyDao
    abstract fun userCorrectionDao(): UserCorrectionDao
    abstract fun vocabularyLearningDao(): VocabularyLearningDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun featureRequestDao(): FeatureRequestDao
}

/**
 * Type converters for Room.
 * Handles enum serialization and nullable types.
 */
class Converters {
    @androidx.room.TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @androidx.room.TypeConverter
    fun toTransactionType(value: String): TransactionType =
        TransactionType.valueOf(value)

    @androidx.room.TypeConverter
    fun fromPatternType(value: PatternType): String = value.name

    @androidx.room.TypeConverter
    fun toPatternType(value: String): PatternType =
        PatternType.valueOf(value)

    @androidx.room.TypeConverter
    fun fromCorrectionType(value: CorrectionType): String = value.name

    @androidx.room.TypeConverter
    fun toCorrectionType(value: String): CorrectionType =
        CorrectionType.valueOf(value)
}
