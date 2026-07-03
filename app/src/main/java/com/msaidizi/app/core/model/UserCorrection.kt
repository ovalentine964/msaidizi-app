package com.msaidizi.app.core.model

import androidx.room.*
/**
 * Tracks user corrections to system interpretations.
 * When the user says "no, that was X not Y", we store the correction
 * to learn from it and improve future predictions.
 *
 * This is the foundation of the correction-learning loop:
 * 1. System interprets input → records transaction
 * 2. User corrects → correction stored here
 * 3. AdaptiveLearningEngine analyzes patterns in corrections
 * 4. Learned patterns are injected into future LLM prompts
 *
 * Memory: ~500 bytes per correction. 1000 corrections = ~500KB.
 */
@Entity(
    tableName = "user_corrections",
    indices = [
        Index(value = ["originalTransactionId"]),
        Index(value = ["correctionType"]),
        Index(value = ["createdAt"])
    ]
)
data class UserCorrection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** ID of the original transaction that was corrected (0 if none) */
    val originalTransactionId: Long = 0,

    /** What was corrected: ITEM, PRICE, QUANTITY, INTENT */
    val correctionType: CorrectionType,

    /** The wrong value the system had */
    val originalValue: String,

    /** The correct value the user provided */
    val correctedValue: String,

    /** The original user input that was misinterpreted */
    val originalInput: String = "",

    /** The correction input from the user */
    val correctionInput: String = "",

    /** Context at the time of correction (JSON) */
    val context: String = "{}",

    /** Whether this correction has been applied to vocabulary/patterns */
    val applied: Boolean = false,

    /** When the correction was made (Unix seconds) */
    val createdAt: Long = System.currentTimeMillis() / 1000
)

enum class CorrectionType {
    /** Wrong item identified */
    ITEM,

    /** Wrong price recorded */
    PRICE,

    /** Wrong quantity recorded */
    QUANTITY,

    /** Wrong intent detected */
    INTENT,

    /** Wrong category */
    CATEGORY,

    /** Other correction */
    OTHER
}

/**
 * DAO for correction tracking.
 */
@Dao
interface UserCorrectionDao {

    @Insert
    suspend fun insert(correction: UserCorrection): Long

    @Update
    suspend fun update(correction: UserCorrection)

    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<UserCorrection>

    @Query("SELECT * FROM user_corrections WHERE applied = 0 ORDER BY createdAt ASC")
    suspend fun getUnapplied(): List<UserCorrection>

    @Query("SELECT * FROM user_corrections WHERE correctionType = :type ORDER BY createdAt DESC")
    suspend fun getByType(type: CorrectionType): List<UserCorrection>

    @Query("SELECT * FROM user_corrections WHERE originalTransactionId = :transactionId")
    suspend fun getForTransaction(transactionId: Long): List<UserCorrection>

    @Query("SELECT * FROM user_corrections WHERE createdAt >= :since ORDER BY createdAt DESC")
    suspend fun getSince(since: Long): List<UserCorrection>

    @Query("SELECT COUNT(*) FROM user_corrections")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM user_corrections WHERE correctionType = :type")
    suspend fun getCountByType(type: CorrectionType): Int

    /**
     * Get the most commonly corrected values.
     * Useful for identifying systematic errors.
     */
    @Query("""
        SELECT originalValue, COUNT(*) as count 
        FROM user_corrections 
        GROUP BY originalValue 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    suspend fun getMostCorrectedValues(limit: Int = 10): List<CorrectionFrequency>

    @Query("UPDATE user_corrections SET applied = 1 WHERE id IN (:ids)")
    suspend fun markApplied(ids: List<Long>)

    @Query("DELETE FROM user_corrections WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

/**
 * Tuple for correction frequency query results.
 */
data class CorrectionFrequency(
    val originalValue: String,
    val count: Int
)
