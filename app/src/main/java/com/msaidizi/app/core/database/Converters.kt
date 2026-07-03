package com.msaidizi.app.core.database

import androidx.room.TypeConverter
import com.msaidizi.app.core.model.CorrectionType
import com.msaidizi.app.core.model.PatternType
import com.msaidizi.app.core.model.TransactionType

/**
 * Room TypeConverters for custom types.
 * Converts enums to strings for SQLite storage.
 */
class Converters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType =
        TransactionType.valueOf(value)

    @TypeConverter
    fun fromPatternType(value: PatternType): String = value.name

    @TypeConverter
    fun toPatternType(value: String): PatternType =
        PatternType.valueOf(value)

    @TypeConverter
    fun fromCorrectionType(value: CorrectionType): String = value.name

    @TypeConverter
    fun toCorrectionType(value: String): CorrectionType =
        CorrectionType.valueOf(value)
}
