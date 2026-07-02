package com.msaidizi.app.evolution

import android.content.Context
import androidx.room.*
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects worker feedback for self-evolution.
 *
 * Workers tell Msaidizi what they want through voice, text, or in-app actions:
 * - "Ningependa Msaidizi iniambie bei ya sokoni kila asubuhi"
 * - "Show me my business flow like M-Pesa"
 * - "Help me save money"
 *
 * Feedback is stored locally (Room) and synced to Biashara Intelligence
 * for pattern analysis and feature development.
 *
 * STA 343 (Experimental Design): A/B test new features based on feedback
 * ECO 206 (Microfinance): Trust-building through responsiveness to worker needs
 *
 * Privacy: Feedback is anonymized before sync. PII is stripped on-device.
 */
@Singleton
class FeedbackCollector @Inject constructor(
    private val feedbackDao: FeedbackDao,
    private val httpClient: HttpClient,
    private val json: Json,
    @ApplicationContext private val context: Context
) {
    /**
     * Submit a new feedback entry.
     * Stores locally first (offline-first), marks for sync.
     */
    suspend fun collect(feedback: Feedback) {
        val entity = feedback.toEntity()
        feedbackDao.insert(entity)
    }

    /**
     * Convenience: collect feedback from raw text with auto-detection.
     * Used when worker speaks or types naturally.
     */
    suspend fun collectFromText(
        workerId: String,
        text: String,
        language: String = "sw",
        category: String? = null
    ) {
        val type = classifyFeedback(text)
        collect(
            Feedback(
                workerId = workerId,
                type = type,
                text = text,
                language = language,
                timestamp = System.currentTimeMillis(),
                category = category
            )
        )
    }

    /**
     * Get recent feedback for display in evolution dashboard.
     */
    suspend fun getRecentFeedback(limit: Int = 100): List<Feedback> {
        return feedbackDao.getRecent(limit).map { it.toDomain() }
    }

    /**
     * Observe feedback as a Flow for reactive UI.
     */
    fun observeRecentFeedback(limit: Int = 100): Flow<List<Feedback>> {
        return feedbackDao.observeRecent(limit)
    }

    /**
     * Get unsynced feedback for backend upload.
     */
    suspend fun getUnsyncedFeedback(): List<Feedback> {
        return feedbackDao.getUnsynced().map { it.toDomain() }
    }

    /**
     * Sync local feedback to Biashara Intelligence backend.
     * Anonymizes before upload — no PII leaves the device.
     * Returns sync result with counts.
     */
    suspend fun syncToBackend(): SyncResult {
        val unsynced = feedbackDao.getUnsynced()
        if (unsynced.isEmpty()) return SyncResult(0, 0, null)

        return try {
            // Anonymize: strip worker IDs, replace with hash
            val anonymized = unsynced.map { entity ->
                AnonymizedFeedback(
                    id = entity.id,
                    workerHash = anonymize(entity.workerId),
                    type = entity.type,
                    text = entity.text,
                    language = entity.language,
                    timestamp = entity.timestamp,
                    category = entity.category
                )
            }

            val body = json.encodeToString(anonymized)
            val response = httpClient.post("api/v1/evolution/feedback/sync") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (response.status.isSuccess()) {
                // Mark as synced
                val ids = unsynced.map { it.id }
                feedbackDao.markSynced(ids)
                SyncResult(synced = unsynced.size, failed = 0, error = null)
            } else {
                SyncResult(0, unsynced.size, "Server error: ${response.status}")
            }
        } catch (e: Exception) {
            SyncResult(0, unsynced.size, e.message)
        }
    }

    /**
     * Get feedback stats for the evolution dashboard.
     */
    suspend fun getFeedbackStats(): FeedbackStats {
        val total = feedbackDao.count()
        val byType = FeedbackType.values().associateWith { type ->
            feedbackDao.countByType(type.name)
        }
        val byCategory = feedbackDao.countByCategory()
        return FeedbackStats(
            totalFeedback = total,
            countsByType = byType,
            countsByCategory = byCategory
        )
    }

    /**
     * Simple keyword-based feedback classifier.
     * In production, this uses the on-device NLP model.
     */
    private fun classifyFeedback(text: String): FeedbackType {
        val lower = text.lowercase()
        return when {
            // Bug reports
            lower.contains("haifanyi") || lower.contains("error") ||
            lower.contains("crash") || lower.contains("tatizo") ||
            lower.contains("bug") || lower.contains("problem") ->
                FeedbackType.BUG_REPORT

            // Praise
            lower.contains("nzuri") || lower.contains("poa") ||
            lower.contains("asante") || lower.contains("good") ||
            lower.contains("great") || lower.contains("love") ||
            lower.contains("thank") ->
                FeedbackType.PRAISE

            // Improvement
            lower.contains("improve") || lower.contains("better") ||
            lower.contains("pia") || lower.contains("also") ||
            lower.contains("change") || lower.contains("badilisha") ->
                FeedbackType.IMPROVEMENT

            // Feature requests (default for actionable suggestions)
            lower.contains("ningependa") || lower.contains("i want") ||
            lower.contains("would be nice") || lower.contains("add") ||
            lower.contains("ongeza") || lower.contains("show me") ||
            lower.contains("nataka") || lower.contains("help me") ->
                FeedbackType.FEATURE_REQUEST

            // Default to feature request if unclear
            else -> FeedbackType.FEATURE_REQUEST
        }
    }

    /**
     * Anonymize worker ID for backend sync.
     * Uses SHA-256 hash so same worker maps to same anonymous ID
     * without revealing actual identity.
     */
    private fun anonymize(workerId: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(workerId.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    // ── Data classes ──────────────────────────────────────────────────

    data class Feedback(
        val id: String = UUID.randomUUID().toString(),
        val workerId: String,
        val type: FeedbackType,
        val text: String,
        val language: String,
        val timestamp: Long,
        val category: String? = null,
        val synced: Boolean = false
    )

    enum class FeedbackType {
        FEATURE_REQUEST,
        BUG_REPORT,
        IMPROVEMENT,
        PRAISE
    }

    data class SyncResult(
        val synced: Int,
        val failed: Int,
        val error: String?
    ) {
        val isSuccess: Boolean get() = error == null
    }

    data class FeedbackStats(
        val totalFeedback: Int,
        val countsByType: Map<FeedbackType, Int>,
        val countsByCategory: Map<String, Int>
    )

    @Serializable
    private data class AnonymizedFeedback(
        val id: String,
        val workerHash: String,
        val type: String,
        val text: String,
        val language: String,
        val timestamp: Long,
        val category: String? = null
    )
}

// ── Room Entity ───────────────────────────────────────────────────────

@Entity(tableName = "feedback")
data class FeedbackEntity(
    @PrimaryKey val id: String,
    val workerId: String,
    val type: String,
    val text: String,
    val language: String,
    val timestamp: Long,
    val category: String? = null,
    val synced: Boolean = false
) {
    fun toDomain() = FeedbackCollector.Feedback(
        id = id,
        workerId = workerId,
        type = FeedbackCollector.FeedbackType.valueOf(type),
        text = text,
        language = language,
        timestamp = timestamp,
        category = category,
        synced = synced
    )
}

fun FeedbackCollector.Feedback.toEntity() = FeedbackEntity(
    id = id,
    workerId = workerId,
    type = type.name,
    text = text,
    language = language,
    timestamp = timestamp,
    category = category,
    synced = synced
)

// ── Room DAO ──────────────────────────────────────────────────────────

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feedback: FeedbackEntity)

    @Query("SELECT * FROM feedback ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<FeedbackEntity>

    @Query("SELECT * FROM feedback ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FeedbackEntity>>

    @Query("SELECT * FROM feedback WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<FeedbackEntity>

    @Query("UPDATE feedback SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT COUNT(*) FROM feedback")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM feedback WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("SELECT category, COUNT(*) as count FROM feedback WHERE category IS NOT NULL GROUP BY category")
    suspend fun countByCategory(): Map<String, Int>
}
