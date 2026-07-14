package com.msaidizi.app.core.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Per-worker personalized vocabulary with dialect-specific words and pronunciation variants.
 *
 * Links to the worker profile (worker_profile table) and persists across sessions.
 * Each entry captures:
 * - The word as spoken by this specific worker (may differ from standard)
 * - The canonical/standard form
 * - Pronunciation variants the worker uses
 * - How often they use it and how confident we are
 * - Whether it's Sheng, Dholuo, or standard Swahili
 *
 * This is distinct from UserVocabulary (which tracks product prices)
 * and LearnedWord (which tracks raw unknown words).
 * WorkerVocabulary is the FINAL learned vocabulary per worker.
 *
 * Learning flow:
 * 1. ASR returns low-confidence word → tracked in LearnedWord
 * 2. Worker uses word 3+ times → auto-promoted to WorkerVocabulary
 * 3. Worker corrects a word → pronunciation variant added
 * 4. Vocabulary persists across sessions via Room DB
 *
 * Memory: ~200 bytes per entry. 500 entries = ~100KB.
 */
@Entity(
    tableName = "worker_vocabulary",
    indices = [
        Index(value = ["workerId"]),
        Index(value = ["spokenForm"]),
        Index(value = ["wordType"]),
        Index(value = ["frequency"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = com.msaidizi.app.onboarding.WorkerProfile::class,
            parentColumns = ["id"],
            childColumns = ["workerId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class WorkerVocabulary(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Worker profile ID (foreign key to worker_profile) */
    val workerId: Long = 1,

    /** The word as this worker speaks it (lowercase, trimmed) */
    val spokenForm: String,

    /** Canonical/standard form (e.g., standard Swahili) */
    val canonicalForm: String,

    /** Language/dialect: "sw", "sheng", "luo", "mixed" */
    val language: String = "sw",

    /** Word type classification */
    val wordType: WordType = WordType.PRODUCT,

    /** How many times the worker has used this word */
    val frequency: Int = 1,

    /** Confidence score (0.0–1.0) based on usage consistency */
    val confidence: Double = 0.3,

    /** JSON array of pronunciation variants the worker uses.
     *  E.g., ["mandazi", "maandazi", "mandazii"] */
    val pronunciationVariants: String = "[]",

    /** Category hint: "produce", "grains", "cooking", "action", etc. */
    val categoryHint: String = "unknown",

    /** Dialect region where this word was heard */
    val dialectRegion: String = "STANDARD",

    /** Average ASR confidence when this word is recognized */
    val avgAsrConfidence: Double = 0.0,

    /** Number of times ASR flagged this word as low-confidence */
    val lowConfidenceCount: Int = 0,

    /** Whether this word was auto-promoted from LearnedWord */
    val autoPromoted: Boolean = false,

    /** Whether the worker has confirmed this mapping */
    val workerConfirmed: Boolean = false,

    /** First time this word was encountered (Unix seconds) */
    val firstSeenAt: Long = System.currentTimeMillis() / 1000,

    /** Last time this word was used (Unix seconds) */
    val lastSeenAt: Long = System.currentTimeMillis() / 1000
)

/**
 * Word type classification for worker vocabulary.
 */
enum class WordType {
    /** Product or item name (e.g., "mboga", "nyama") */
    PRODUCT,
    /** Unit of measurement (e.g., "kilo", "debe", "fundo") */
    UNIT,
    /** Currency or number term */
    CURRENCY,
    /** Action word / verb (e.g., "nimeuza", "nimenunua") */
    ACTION,
    /** Sheng slang term */
    SHENG,
    /** Dholuo word used in Swahili context */
    DHOLUO,
    /** Business-specific jargon */
    JARGON,
    /** General/unknown word */
    GENERAL
}

/**
 * DAO for per-worker vocabulary operations.
 * All queries scoped to a specific workerId.
 */
@Dao
interface WorkerVocabularyDao {

    // ────────────────────── CRUD ──────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WorkerVocabulary): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<WorkerVocabulary>)

    @Update
    suspend fun update(entry: WorkerVocabulary)

    @Query("DELETE FROM worker_vocabulary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM worker_vocabulary WHERE workerId = :workerId AND spokenForm = :spokenForm")
    suspend fun deleteBySpokenForm(workerId: Long, spokenForm: String)

    // ────────────────────── LOOKUP ──────────────────────

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND spokenForm = :spoken LIMIT 1")
    suspend fun getBySpokenForm(workerId: Long, spoken: String): WorkerVocabulary?

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND canonicalForm = :canonical")
    suspend fun getByCanonicalForm(workerId: Long, canonical: String): List<WorkerVocabulary>

    @Query("""
        SELECT * FROM worker_vocabulary 
        WHERE workerId = :workerId 
          AND (spokenForm LIKE '%' || :query || '%' OR canonicalForm LIKE '%' || :query || '%')
        ORDER BY frequency DESC 
        LIMIT :limit
    """)
    suspend fun search(workerId: Long, query: String, limit: Int = 10): List<WorkerVocabulary>

    @Query("""
        SELECT * FROM worker_vocabulary 
        WHERE workerId = :workerId AND spokenForm LIKE :prefix || '%' 
        ORDER BY frequency DESC 
        LIMIT :limit
    """)
    suspend fun findByPrefix(workerId: Long, prefix: String, limit: Int = 5): List<WorkerVocabulary>

    // ────────────────────── FREQUENCY & CONFIDENCE ──────────────────────

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId ORDER BY frequency DESC LIMIT :limit")
    suspend fun getTopByFrequency(workerId: Long, limit: Int = 20): List<WorkerVocabulary>

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND confidence >= :minConfidence ORDER BY confidence DESC")
    suspend fun getHighConfidence(workerId: Long, minConfidence: Double = 0.7): List<WorkerVocabulary>

    @Query("""
        UPDATE worker_vocabulary 
        SET frequency = frequency + 1, 
            lastSeenAt = :now 
        WHERE workerId = :workerId AND spokenForm = :spoken
    """)
    suspend fun incrementFrequency(workerId: Long, spoken: String, now: Long = System.currentTimeMillis() / 1000)

    @Query("""
        UPDATE worker_vocabulary 
        SET confidence = confidence * 0.8 + :observedConfidence * 0.2,
            lastSeenAt = :now
        WHERE workerId = :workerId AND spokenForm = :spoken
    """)
    suspend fun updateConfidence(workerId: Long, spoken: String, observedConfidence: Double, now: Long = System.currentTimeMillis() / 1000)

    // ────────────────────── PRONUNCIATION VARIANTS ──────────────────────

    @Query("SELECT pronunciationVariants FROM worker_vocabulary WHERE workerId = :workerId AND spokenForm = :spoken LIMIT 1")
    suspend fun getPronunciationVariants(workerId: Long, spoken: String): String?

    @Query("""
        UPDATE worker_vocabulary 
        SET pronunciationVariants = :variantsJson,
            lastSeenAt = :now
        WHERE workerId = :workerId AND spokenForm = :spoken
    """)
    suspend fun updatePronunciationVariants(workerId: Long, spoken: String, variantsJson: String, now: Long = System.currentTimeMillis() / 1000)

    // ────────────────────── ASR CONFIDENCE TRACKING ──────────────────────

    @Query("""
        UPDATE worker_vocabulary 
        SET avgAsrConfidence = CASE 
                WHEN lowConfidenceCount = 0 THEN :asrConfidence 
                ELSE avgAsrConfidence * 0.7 + :asrConfidence * 0.3 
            END,
            lowConfidenceCount = CASE 
                WHEN :isLowConfidence THEN lowConfidenceCount + 1 
                ELSE lowConfidenceCount 
            END,
            lastSeenAt = :now
        WHERE workerId = :workerId AND spokenForm = :spoken
    """)
    suspend fun updateAsrConfidence(
        workerId: Long,
        spoken: String,
        asrConfidence: Double,
        isLowConfidence: Boolean,
        now: Long = System.currentTimeMillis() / 1000
    )

    // ────────────────────── LANGUAGE / DIALECT FILTERS ──────────────────────

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND language = :language ORDER BY frequency DESC")
    suspend fun getForLanguage(workerId: Long, language: String): List<WorkerVocabulary>

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND wordType = :type ORDER BY frequency DESC")
    suspend fun getByWordType(workerId: Long, type: WordType): List<WorkerVocabulary>

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND wordType = 'SHENG' ORDER BY frequency DESC")
    suspend fun getShengVocabulary(workerId: Long): List<WorkerVocabulary>

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND wordType = 'DHOLUO' ORDER BY frequency DESC")
    suspend fun getDholuoVocabulary(workerId: Long): List<WorkerVocabulary>

    // ────────────────────── CONFIRMATION ──────────────────────

    @Query("UPDATE worker_vocabulary SET workerConfirmed = 1 WHERE workerId = :workerId AND spokenForm = :spoken")
    suspend fun markConfirmed(workerId: Long, spoken: String)

    @Query("SELECT * FROM worker_vocabulary WHERE workerId = :workerId AND workerConfirmed = 0 AND frequency >= :minFrequency ORDER BY frequency DESC")
    suspend fun getUnconfirmed(workerId: Long, minFrequency: Int = 3): List<WorkerVocabulary>

    // ────────────────────── STATISTICS ──────────────────────

    @Query("SELECT COUNT(*) FROM worker_vocabulary WHERE workerId = :workerId")
    suspend fun getCount(workerId: Long): Int

    @Query("SELECT COUNT(*) FROM worker_vocabulary WHERE workerId = :workerId AND confidence >= 0.7")
    suspend fun getHighConfidenceCount(workerId: Long): Int

    @Query("SELECT AVG(confidence) FROM worker_vocabulary WHERE workerId = :workerId")
    suspend fun getAverageConfidence(workerId: Long): Double?

    @Query("SELECT DISTINCT categoryHint FROM worker_vocabulary WHERE workerId = :workerId AND categoryHint != 'unknown'")
    suspend fun getCategories(workerId: Long): List<String>

    @Query("SELECT DISTINCT language FROM worker_vocabulary WHERE workerId = :workerId")
    suspend fun getLanguages(workerId: Long): List<String>

    // ────────────────────── CLEANUP ──────────────────────

    @Query("DELETE FROM worker_vocabulary WHERE workerId = :workerId AND frequency = 0 AND confidence < 0.1 AND workerConfirmed = 0")
    suspend fun cleanupUnused(workerId: Long)

    @Query("DELETE FROM worker_vocabulary WHERE lastSeenAt < :before AND frequency < 2 AND workerConfirmed = 0")
    suspend fun pruneOld(before: Long)

    // ────────────────────── ALL WORKERS (admin) ──────────────────────

    @Query("SELECT * FROM worker_vocabulary ORDER BY workerId, frequency DESC")
    fun getAllVocabulary(): Flow<List<WorkerVocabulary>>
}
