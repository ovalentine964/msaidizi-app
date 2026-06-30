package com.msaidizi.app.core.model

import androidx.room.*

/**
 * Enhanced vocabulary entry with confidence scoring and price tracking.
 * Tracks the user's product vocabulary, typical price ranges, and usage patterns.
 *
 * This is the core entity for Level 2 (Context Injection) adaptive learning.
 * As the user records more transactions, confidence scores increase and
 * price ranges narrow, enabling personalized LLM prompts.
 *
 * Memory impact: ~2KB per 100 entries (trivial on 2GB devices).
 */
@Entity(
    tableName = "user_vocabulary",
    indices = [
        Index(value = ["spokenForm"], unique = true),
        Index(value = ["canonicalForm"]),
        Index(value = ["confidence"]),
        Index(value = ["lastUsedAt"])
    ]
)
data class UserVocabulary(
    /** Spoken form as the user says it (e.g., "maandazi", "mandazi") */
    @PrimaryKey
    val spokenForm: String,

    /** Canonical/normalized form (e.g., "mandazi") */
    val canonicalForm: String,

    /** Language code (sw, en, sheng) */
    val language: String = "sw",

    /** How many times the user has used this term */
    val frequency: Int = 1,

    /** Confidence score (0.0–1.0) based on usage consistency */
    val confidence: Double = 0.1,

    /** Minimum price observed for this product (KSh) */
    val minPrice: Double = 0.0,

    /** Maximum price observed for this product (KSh) */
    val maxPrice: Double = 0.0,

    /** Weighted average price (exponential moving average) */
    val avgPrice: Double = 0.0,

    /** Number of price observations used for averaging */
    val priceObservations: Int = 0,

    /** Average quantity per transaction */
    val avgQuantity: Double = 0.0,

    /** Category (produce, grains, cooking, etc.) */
    val category: String = "",

    /** Whether this is a user-customized term (not from default dictionary) */
    val isUserDefined: Boolean = false,

    /** Last time user used this term (Unix seconds) */
    val lastUsedAt: Long = System.currentTimeMillis() / 1000,

    /** When this entry was first created */
    val createdAt: Long = System.currentTimeMillis() / 1000
)

/**
 * DAO for user vocabulary operations.
 * All queries are optimized for 2GB device performance.
 */
@Dao
interface UserVocabularyDao {

    // === INSERT / UPDATE ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vocabulary: UserVocabulary)

    @Update
    suspend fun update(vocabulary: UserVocabulary)

    // === LOOKUP ===

    @Query("SELECT * FROM user_vocabulary WHERE spokenForm = :spoken LIMIT 1")
    suspend fun getBySpokenForm(spoken: String): UserVocabulary?

    @Query("SELECT * FROM user_vocabulary WHERE spokenForm = :spoken LIMIT 1")
    fun getBySpokenFormFlow(spoken: String): kotlinx.coroutines.flow.Flow<UserVocabulary?>

    @Query("SELECT * FROM user_vocabulary WHERE canonicalForm = :canonical")
    suspend fun getByCanonicalForm(canonical: String): List<UserVocabulary>

    @Query("SELECT * FROM user_vocabulary WHERE language = :language ORDER BY frequency DESC")
    suspend fun getForLanguage(language: String): List<UserVocabulary>

    // === FREQUENCY & CONFIDENCE ===

    @Query("SELECT * FROM user_vocabulary ORDER BY frequency DESC LIMIT :limit")
    suspend fun getTopByFrequency(limit: Int = 20): List<UserVocabulary>

    @Query("SELECT * FROM user_vocabulary WHERE confidence >= :minConfidence ORDER BY confidence DESC")
    suspend fun getHighConfidence(minConfidence: Double = 0.7): List<UserVocabulary>

    @Query("""
        UPDATE user_vocabulary 
        SET frequency = frequency + 1, 
            lastUsedAt = :now 
        WHERE spokenForm = :spoken
    """)
    suspend fun incrementFrequency(spoken: String, now: Long = System.currentTimeMillis() / 1000)

    /**
     * Update confidence using exponential moving average.
     * newConfidence = oldConfidence * alpha + observedConfidence * (1 - alpha)
     * alpha = 0.8 (slow adaptation, stable long-term memory)
     */
    @Query("""
        UPDATE user_vocabulary 
        SET confidence = confidence * 0.8 + :observedConfidence * 0.2,
            lastUsedAt = :now
        WHERE spokenForm = :spoken
    """)
    suspend fun updateConfidence(spoken: String, observedConfidence: Double, now: Long = System.currentTimeMillis() / 1000)

    // === PRICE TRACKING ===

    /**
     * Update price range using exponential moving average.
     * This learns the user's typical price for each product over time.
     */
    @Query("""
        UPDATE user_vocabulary 
        SET minPrice = CASE WHEN :price < minPrice OR minPrice = 0 THEN :price ELSE minPrice END,
            maxPrice = CASE WHEN :price > maxPrice THEN :price ELSE maxPrice END,
            avgPrice = CASE 
                WHEN priceObservations = 0 THEN :price 
                ELSE avgPrice * 0.7 + :price * 0.3 
            END,
            priceObservations = priceObservations + 1,
            lastUsedAt = :now
        WHERE spokenForm = :spoken
    """)
    suspend fun updatePrice(spoken: String, price: Double, now: Long = System.currentTimeMillis() / 1000)

    /**
     * Update average quantity per transaction.
     */
    @Query("""
        UPDATE user_vocabulary 
        SET avgQuantity = CASE 
                WHEN frequency <= 1 THEN :quantity 
                ELSE avgQuantity * 0.7 + :quantity * 0.3 
            END
        WHERE spokenForm = :spoken
    """)
    suspend fun updateAvgQuantity(spoken: String, quantity: Double)

    // === SEARCH ===

    @Query("""
        SELECT * FROM user_vocabulary 
        WHERE spokenForm LIKE '%' || :query || '%' 
           OR canonicalForm LIKE '%' || :query || '%'
        ORDER BY frequency DESC 
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 10): List<UserVocabulary>

    /**
     * Fuzzy match: find vocabulary entries that start with the given prefix.
     * Useful for autocomplete and ASR correction.
     */
    @Query("""
        SELECT * FROM user_vocabulary 
        WHERE spokenForm LIKE :prefix || '%' 
        ORDER BY frequency DESC 
        LIMIT :limit
    """)
    suspend fun findByPrefix(prefix: String, limit: Int = 5): List<UserVocabulary>

    // === STATISTICS ===

    @Query("SELECT COUNT(*) FROM user_vocabulary")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM user_vocabulary WHERE confidence >= 0.7")
    suspend fun getHighConfidenceCount(): Int

    @Query("SELECT AVG(confidence) FROM user_vocabulary")
    suspend fun getAverageConfidence(): Double?

    @Query("SELECT DISTINCT category FROM user_vocabulary WHERE category != ''")
    suspend fun getCategories(): List<String>

    // === CLEANUP ===

    @Query("DELETE FROM user_vocabulary WHERE spokenForm = :spoken")
    suspend fun delete(spoken: String)

    @Query("DELETE FROM user_vocabulary WHERE frequency = 0 AND confidence < 0.1")
    suspend fun cleanupUnused()
}
