package com.msaidizi.app.core.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.msaidizi.app.core.model.CorrectionType
import com.msaidizi.app.core.model.PatternType
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.onboarding.WorkingHours
import com.msaidizi.app.agent.recovery.TaskState
import com.msaidizi.app.agent.recovery.TraceType

/**
 * Room TypeConverters for custom types.
 * Converts enums and complex types to strings for SQLite storage.
 */
class Converters {
    private val gson = Gson()

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

    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type) ?: emptyList()
    }

    @TypeConverter
    fun fromWorkingHours(value: WorkingHours): String = gson.toJson(value)

    @TypeConverter
    fun toWorkingHours(value: String): WorkingHours {
        return try {
            gson.fromJson(value, WorkingHours::class.java) ?: WorkingHours()
        } catch (e: Exception) {
            WorkingHours()
        }
    }

    // ── Agent Recovery Types ──

    @TypeConverter
    fun fromTaskState(value: TaskState): String = value.name

    @TypeConverter
    fun toTaskState(value: String): TaskState = TaskState.valueOf(value)

    @TypeConverter
    fun fromTraceType(value: TraceType): String = value.name

    @TypeConverter
    fun toTraceType(value: String): TraceType = TraceType.valueOf(value)
}
