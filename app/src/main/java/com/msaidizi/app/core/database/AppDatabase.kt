package com.msaidizi.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.msaidizi.app.core.model.*

/**
 * Room database for Msaidizi.
 *
 * Stores all business transactions, inventory, patterns, vocabulary,
 * user corrections, and adaptive learning data.
 * Optimized for 2GB devices:
 * - WAL mode for concurrent reads during writes
 * - Minimal indices (only what's needed for common queries)
 * - Integer timestamps (not datetime strings)
 *
 * Version 2: Added UserVocabulary and UserCorrection for adaptive learning.
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
        LearnedWord::class
    ],
    version = 3,
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
