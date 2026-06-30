package com.msaidizi.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.msaidizi.app.core.model.*

/**
 * Room database for Msaidizi.
 *
 * Stores all business transactions, inventory, patterns, and vocabulary.
 * Optimized for 2GB devices:
 * - WAL mode for concurrent reads during writes
 * - Minimal indices (only what's needed for common queries)
 * - Integer timestamps (not datetime strings)
 */
@Database(
    entities = [
        Transaction::class,
        InventoryItem::class,
        BusinessPattern::class,
        VocabularyEntry::class,
        DailySummary::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun patternDao(): PatternDao
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
}
