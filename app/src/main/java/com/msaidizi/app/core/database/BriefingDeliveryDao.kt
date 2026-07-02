package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.BriefingDeliveryEntity

/**
 * DAO for briefing delivery tracking — closes the Morning Briefing feedback loop.
 *
 * Tracks: Delivered → Opened → Acted On → Outcome
 * Enables: Predicted vs Actual comparison, advice-follow tracking,
 *          personalized briefing adjustments over time.
 */
@Dao
interface BriefingDeliveryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BriefingDeliveryEntity): Long

    @Query("UPDATE briefing_deliveries SET opened = 1, openedAt = :timestamp WHERE id = :id")
    suspend fun markOpened(id: Long, timestamp: Long = System.currentTimeMillis() / 1000)

    @Query("""
        UPDATE briefing_deliveries SET 
            actedOn = 1, 
            actedOnAt = :timestamp,
            actualSales = :actualSales,
            actualProfit = :actualProfit,
            outcomeScore = :outcomeScore,
            adviceFollowed = :adviceFollowed
        WHERE id = :id
    """)
    suspend fun markActedOn(
        id: Long,
        actualSales: Double,
        actualProfit: Double,
        outcomeScore: Double,
        adviceFollowed: Boolean?,
        timestamp: Long = System.currentTimeMillis() / 1000
    )

    @Query("SELECT * FROM briefing_deliveries ORDER BY deliveredAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 10): List<BriefingDeliveryEntity>

    @Query("""
        SELECT * FROM briefing_deliveries 
        WHERE briefingType = :type 
        ORDER BY deliveredAt DESC 
        LIMIT :limit
    """)
    suspend fun getRecentByType(type: String, limit: Int = 5): List<BriefingDeliveryEntity>

    @Query("""
        SELECT * FROM briefing_deliveries 
        WHERE actedOn = 1 
        ORDER BY deliveredAt DESC 
        LIMIT :limit
    """)
    suspend fun getActedOnBriefings(limit: Int = 10): List<BriefingDeliveryEntity>

    @Query("""
        SELECT AVG(outcomeScore) FROM briefing_deliveries 
        WHERE actedOn = 1 AND briefingType = :type
    """)
    suspend fun getAverageOutcomeScore(type: String): Double?

    @Query("""
        SELECT AVG(outcomeScore) FROM briefing_deliveries 
        WHERE actedOn = 1 AND deliveredAt >= :sinceTimestamp
    """)
    suspend fun getAverageOutcomeScoreSince(sinceTimestamp: Long): Double?

    @Query("""
        SELECT COUNT(*) FROM briefing_deliveries 
        WHERE actedOn = 1 AND deliveredAt >= :sinceTimestamp
    """)
    suspend fun getActedOnCountSince(sinceTimestamp: Long): Int

    @Query("""
        SELECT COUNT(*) FROM briefing_deliveries 
        WHERE deliveredAt >= :sinceTimestamp
    """)
    suspend fun getDeliveredCountSince(sinceTimestamp: Long): Int

    /** Get the most recent briefing that hasn't been acted on yet (for pending action tracking) */
    @Query("""
        SELECT * FROM briefing_deliveries 
        WHERE actedOn = 0 AND opened = 1
        ORDER BY deliveredAt DESC 
        LIMIT 1
    """)
    suspend fun getLatestPendingBriefing(): BriefingDeliveryEntity?

    /** Get advice follow-through rate for a given advice type */
    @Query("""
        SELECT COUNT(*) FROM briefing_deliveries 
        WHERE adviceFollowed = 1 AND keyAdvice LIKE '%' || :adviceKeyword || '%'
    """)
    suspend fun getAdviceFollowCount(adviceKeyword: String): Int

    @Query("""
        SELECT COUNT(*) FROM briefing_deliveries 
        WHERE keyAdvice LIKE '%' || :adviceKeyword || '%'
    """)
    suspend fun getAdviceTotalCount(adviceKeyword: String): Int
}
