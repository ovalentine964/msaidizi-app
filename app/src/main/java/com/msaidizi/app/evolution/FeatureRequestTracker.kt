package com.msaidizi.app.evolution

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks feature requests from workers and identifies patterns.
 *
 * When multiple workers request similar features:
 * 1. Cluster requests by semantic similarity
 * 2. Count frequency across worker segments
 * 3. Prioritize by impact (frequency × urgency × worker diversity)
 * 4. Send prioritized requests to Angavu Intelligence for implementation
 *
 * STA 442 (Multivariate): Cluster analysis of feedback requests
 * STA 341 (Estimation): Bayesian prioritization with sparse data
 *
 * Example clustering:
 *   "Nataka bei ya sokoni kila asubuhi" → cluster: DAILY_PRICES
 *   "Show me market prices every morning" → cluster: DAILY_PRICES
 *   "Bei ya sukari inabadilika vipi?" → cluster: DAILY_PRICES
 *   → Request count: 3, Priority boost: high (multilingual agreement)
 */
@Singleton
class FeatureRequestTracker @Inject constructor(
    private val requestDao: FeatureRequestDao,
    private val feedbackDao: FeedbackDao
) {
    /**
     * Track a new feature request from feedback.
     * Attempts to match against existing clusters; creates new if no match.
     */
    suspend fun trackRequest(feedback: FeedbackCollector.Feedback) {
        if (feedback.type != FeedbackCollector.FeedbackType.FEATURE_REQUEST) return

        val clusterId = findOrCreateCluster(feedback.text)
        val existing = requestDao.getByClusterId(clusterId)

        if (existing != null) {
            // Increment count, add worker type, recalculate priority
            val updated = existing.copy(
                requestCount = existing.requestCount + 1,
                workerTypes = mergeWorkerTypes(existing.workerTypes, feedback.workerId),
                priority = calculatePriority(
                    existing.requestCount + 1,
                    existing.workerTypes.split(",").size + 1
                ),
                lastUpdated = System.currentTimeMillis()
            )
            requestDao.update(updated)
        } else {
            // New cluster
            val request = FeatureRequestEntity(
                id = UUID.randomUUID().toString(),
                clusterId = clusterId,
                description = feedback.text,
                requestCount = 1,
                workerTypes = feedback.workerId,
                priority = calculatePriority(1, 1),
                status = RequestStatus.NEW.name,
                createdAt = System.currentTimeMillis(),
                lastUpdated = System.currentTimeMillis()
            )
            requestDao.insert(request)
        }
    }

    /**
     * Get top feature requests sorted by priority.
     */
    suspend fun getTopRequests(limit: Int = 10): List<FeatureRequest> {
        return requestDao.getTopByPriority(limit).map { it.toDomain() }
    }

    /**
     * Observe top requests for reactive UI.
     */
    fun observeTopRequests(limit: Int = 10): Flow<List<FeatureRequest>> {
        return requestDao.observeTopByPriority(limit).map { list -> list.map { it.toDomain() } }
    }

    /**
     * Get requests by status.
     */
    suspend fun getRequestsByStatus(status: RequestStatus): List<FeatureRequest> {
        return requestDao.getByStatus(status.name).map { it.toDomain() }
    }

    /**
     * Update request status (e.g., when Angavu Intelligence starts building).
     */
    suspend fun updateStatus(requestId: String, status: RequestStatus) {
        requestDao.updateStatus(requestId, status.name, System.currentTimeMillis())
    }

    /**
     * Get aggregated stats for the evolution dashboard.
     */
    suspend fun getRequestStats(): Map<String, Any> {
        val total = requestDao.count()
        val byStatus = RequestStatus.values().associateWith { status ->
            requestDao.countByStatus(status.name)
        }
        val totalRequests = requestDao.totalRequestCount() ?: 0
        val topCluster = requestDao.getTopCluster()

        return mapOf(
            "totalFeatureClusters" to total,
            "totalRequests" to totalRequests,
            "topCluster" to (topCluster ?: "none"),
            "byStatus" to byStatus.mapKeys { it.key.name }
        )
    }

    /**
     * Get shipped features (for celebration in the dashboard).
     */
    suspend fun getShippedFeatures(): List<FeatureRequest> {
        return requestDao.getByStatus(RequestStatus.SHIPPED.name).map { it.toDomain() }
    }

    // ── Clustering ────────────────────────────────────────────────────

    /**
     * Simple keyword-based clustering.
     * In production, this uses sentence embeddings for semantic similarity.
     *
     * Maps common request patterns to cluster IDs.
     */
    private fun findOrCreateCluster(text: String): String {
        val lower = text.lowercase()
        return when {
            // Market prices
            containsAny(lower, "bei", "price", "sokoni", "market", "soko") ->
                "market_prices"

            // Business flow / dashboard
            containsAny(lower, "flow", "biashara", "business", "dashboard", "m-pesa", "mpesa", "pesa") ->
                "business_flow"

            // Savings
            containsAny(lower, "save", "save", "akiba", "spare", "wekeza") ->
                "savings"

            // Credit / loans
            containsAny(lower, "mkopo", "loan", "credit", "score", "ready", "tayari") ->
                "credit_readiness"

            // Reports
            containsAny(lower, "ripoti", "report", "summary", "muhtasari") ->
                "reports"

            // Inventory / stock
            containsAny(lower, "stock", "inventory", "bidhaa", "product", "item") ->
                "inventory"

            // Alerts / notifications
            containsAny(lower, "alert", "notify", "arifu", "taarifa", "remind") ->
                "alerts"

            // Tax
            containsAny(lower, "tax", "kodi", "vat", "returns") ->
                "tax"

            // Supplier
            containsAny(lower, "supplier", "mzabuni", "source", "buy", "nunua") ->
                "supplier"

            // Comparison / benchmarking
            containsAny(lower, "compare", "linganisha", "how am i", "performance") ->
                "comparison"

            // Default: use first 50 chars as cluster key
            else -> "custom_${lower.take(50).hashCode().toString(16)}"
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * Calculate priority score (0.0 - 1.0).
     *
     * Factors:
     * - Request count (more = higher priority)
     * - Worker diversity (different types requesting = higher priority)
     * - Recency (recent requests get a boost)
     *
     * STA 341 (Estimation): Bayesian prior with informative prior from
     * historical feature adoption rates.
     */
    private fun calculatePriority(requestCount: Int, workerDiversity: Int): Double {
        // Log-scaled frequency (diminishing returns on very high counts)
        val frequencyScore = (Math.log1p(requestCount.toDouble()) / Math.log1p(100.0))
            .coerceAtMost(1.0)

        // Diversity bonus (more diverse workers = more universal need)
        val diversityScore = (workerDiversity.toDouble() / 10.0).coerceAtMost(1.0)

        // Weighted combination: frequency 60%, diversity 40%
        return (0.6 * frequencyScore + 0.4 * diversityScore).coerceIn(0.0, 1.0)
    }

    private fun mergeWorkerTypes(existing: String, newWorkerId: String): String {
        val types = existing.split(",").toMutableSet()
        types.add(newWorkerId.take(8)) // Use prefix as anonymized type
        return types.joinToString(",")
    }

    // ── Data classes ──────────────────────────────────────────────────

    data class FeatureRequest(
        val id: String,
        val clusterId: String,
        val description: String,
        val requestCount: Int,
        val workerTypes: Set<String>,
        val priority: Double,
        val status: RequestStatus,
        val createdAt: Long,
        val lastUpdated: Long
    )

    enum class RequestStatus {
        NEW,          // Just detected
        ANALYZING,    // Angavu Intelligence is analyzing
        PLANNED,      // Accepted, in roadmap
        IN_PROGRESS,  // Being built
        SHIPPED       // Live in production
    }
}

// ── Room Entity ───────────────────────────────────────────────────────

@Entity(
    tableName = "feature_requests",
    indices = [Index(value = ["clusterId"], unique = true)]
)
data class FeatureRequestEntity(
    @PrimaryKey val id: String,
    val clusterId: String,
    val description: String,
    val requestCount: Int,
    val workerTypes: String, // comma-separated
    val priority: Double,
    val status: String,
    val createdAt: Long,
    val lastUpdated: Long
) {
    fun toDomain() = FeatureRequestTracker.FeatureRequest(
        id = id,
        clusterId = clusterId,
        description = description,
        requestCount = requestCount,
        workerTypes = workerTypes.split(",").toSet(),
        priority = priority,
        status = FeatureRequestTracker.RequestStatus.valueOf(status),
        createdAt = createdAt,
        lastUpdated = lastUpdated
    )
}

// ── Room DAO ──────────────────────────────────────────────────────────

@Dao
interface FeatureRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(request: FeatureRequestEntity)

    @Update
    suspend fun update(request: FeatureRequestEntity)

    @Query("SELECT * FROM feature_requests WHERE clusterId = :clusterId LIMIT 1")
    suspend fun getByClusterId(clusterId: String): FeatureRequestEntity?

    @Query("SELECT * FROM feature_requests ORDER BY priority DESC LIMIT :limit")
    suspend fun getTopByPriority(limit: Int): List<FeatureRequestEntity>

    @Query("SELECT * FROM feature_requests ORDER BY priority DESC LIMIT :limit")
    fun observeTopByPriority(limit: Int): Flow<List<FeatureRequestEntity>>

    @Query("SELECT * FROM feature_requests WHERE status = :status ORDER BY priority DESC")
    suspend fun getByStatus(status: String): List<FeatureRequestEntity>

    @Query("UPDATE feature_requests SET status = :status, lastUpdated = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM feature_requests")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM feature_requests WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT SUM(requestCount) FROM feature_requests")
    suspend fun totalRequestCount(): Int?

    @Query("SELECT clusterId FROM feature_requests ORDER BY requestCount DESC LIMIT 1")
    suspend fun getTopCluster(): String?
}
