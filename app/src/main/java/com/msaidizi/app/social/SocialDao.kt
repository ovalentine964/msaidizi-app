package com.msaidizi.app.social

import androidx.room.*

/**
 * DAO for social layer data — peer comparison, leaderboard, community tips,
 * and WhatsApp group management.
 *
 * All queries optimized for 2GB devices with proper indices.
 */
@Dao
interface SocialDao {

    // ═══════════════════════════════════════════════════════════════
    // PEER METRICS
    // ═══════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeerMetrics(metrics: PeerMetrics): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPeerMetrics(metrics: List<PeerMetrics>)

    /**
     * Get the most recent peer metrics for a location × business type.
     * Used to generate comparison messages.
     */
    @Query("""
        SELECT * FROM peer_metrics 
        WHERE location = :location AND businessType = :businessType 
        ORDER BY periodStart DESC 
        LIMIT 1
    """)
    suspend fun getLatestPeerMetrics(location: String, businessType: String): PeerMetrics?

    /**
     * Get peer metrics for a specific period.
     */
    @Query("""
        SELECT * FROM peer_metrics 
        WHERE location = :location AND businessType = :businessType 
        AND periodStart = :periodStart 
        LIMIT 1
    """)
    suspend fun getPeerMetricsForPeriod(
        location: String, businessType: String, periodStart: Long
    ): PeerMetrics?

    /**
     * Delete old peer metrics to save storage (keep last 30 days).
     */
    @Query("DELETE FROM peer_metrics WHERE periodStart < :cutoff")
    suspend fun deleteOldPeerMetrics(cutoff: Long)

    // ═══════════════════════════════════════════════════════════════
    // PEER COMPARISON RESULT
    // ═══════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeerComparison(result: PeerComparisonResult)

    @Query("SELECT * FROM peer_comparisons WHERE id = 1")
    suspend fun getPeerComparison(): PeerComparisonResult?

    // ═══════════════════════════════════════════════════════════════
    // LEADERBOARD
    // ═══════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeaderboardEntries(entries: List<LeaderboardEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLeaderboardSummary(summary: LeaderboardSummary)

    /**
     * Get leaderboard entries for a specific week, ordered by rank.
     * Returns top N entries for display.
     */
    @Query("""
        SELECT * FROM leaderboard_entries 
        WHERE location = :location AND businessType = :businessType AND weekStart = :weekStart 
        ORDER BY rank ASC 
        LIMIT :limit
    """)
    suspend fun getLeaderboard(
        location: String, businessType: String, weekStart: Long, limit: Int = 20
    ): List<LeaderboardEntry>

    /**
     * Get entries near the current user's rank (for showing context).
     * Returns 5 entries above and 5 below.
     */
    @Query("""
        SELECT * FROM leaderboard_entries 
        WHERE location = :location AND businessType = :businessType AND weekStart = :weekStart 
        AND rank BETWEEN :fromRank AND :toRank 
        ORDER BY rank ASC
    """)
    suspend fun getLeaderboardNearRank(
        location: String, businessType: String, weekStart: Long,
        fromRank: Int, toRank: Int
    ): List<LeaderboardEntry>

    /**
     * Get the current user's leaderboard entry for a specific week.
     */
    @Query("""
        SELECT * FROM leaderboard_entries 
        WHERE location = :location AND businessType = :businessType 
        AND weekStart = :weekStart AND isCurrentUser = 1 
        LIMIT 1
    """)
    suspend fun getCurrentUserEntry(
        location: String, businessType: String, weekStart: Long
    ): LeaderboardEntry?

    /**
     * Get the leaderboard summary (cached rank info).
     */
    @Query("SELECT * FROM leaderboard_summary WHERE id = 1")
    suspend fun getLeaderboardSummary(): LeaderboardSummary?

    /**
     * Delete old leaderboard entries (keep last 8 weeks).
     */
    @Query("DELETE FROM leaderboard_entries WHERE weekStart < :cutoff")
    suspend fun deleteOldLeaderboardEntries(cutoff: Long)

    // ═══════════════════════════════════════════════════════════════
    // COMMUNITY TIPS
    // ═══════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTip(tip: CommunityTip): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTips(tips: List<CommunityTip>)

    /**
     * Get top tips for a location × business type, ordered by upvotes.
     * Used for morning briefing tip injection.
     */
    @Query("""
        SELECT * FROM community_tips 
        WHERE location = :location AND businessType = :businessType 
        ORDER BY upvotes DESC 
        LIMIT :limit
    """)
    suspend fun getTopTips(location: String, businessType: String, limit: Int = 10): List<CommunityTip>

    /**
     * Get tips not yet shown to the worker (for fresh content in briefings).
     */
    @Query("""
        SELECT * FROM community_tips 
        WHERE location = :location AND businessType = :businessType 
        AND id NOT IN (SELECT tipId FROM tip_delivery_log) 
        ORDER BY upvotes DESC 
        LIMIT :limit
    """)
    suspend fun getUndeliveredTips(location: String, businessType: String, limit: Int = 5): List<CommunityTip>

    /**
     * Get a random high-quality tip for variety in briefings.
     * Picks from top 20% by upvotes.
     */
    @Query("""
        SELECT * FROM community_tips 
        WHERE location = :location AND businessType = :businessType 
        AND upvotes >= (
            SELECT COALESCE(MIN(upvotes), 0) FROM (
                SELECT upvotes FROM community_tips 
                WHERE location = :location AND businessType = :businessType 
                ORDER BY upvotes DESC LIMIT 5
            )
        )
        ORDER BY RANDOM() 
        LIMIT 1
    """)
    suspend fun getRandomTopTip(location: String, businessType: String): CommunityTip?

    /**
     * Get tips submitted by the current user.
     */
    @Query("SELECT * FROM community_tips WHERE isOwnTip = 1 ORDER BY createdAt DESC")
    suspend fun getOwnTips(): List<CommunityTip>

    /**
     * Update upvote count for a tip.
     */
    @Query("UPDATE community_tips SET upvotes = upvotes + 1, hasUpvoted = 1 WHERE id = :tipId")
    suspend fun upvoteTip(tipId: Long)

    /**
     * Mark a tip as featured (shown in briefing).
     */
    @Query("UPDATE community_tips SET featured = 1, featuredCount = featuredCount + 1 WHERE id = :tipId")
    suspend fun markTipFeatured(tipId: Long)

    /**
     * Get tip count for a location × business type.
     */
    @Query("""
        SELECT COUNT(*) FROM community_tips 
        WHERE location = :location AND businessType = :businessType
    """)
    suspend fun getTipCount(location: String, businessType: String): Int

    /**
     * Get tips by category for targeted advice.
     */
    @Query("""
        SELECT * FROM community_tips 
        WHERE location = :location AND businessType = :businessType AND category = :category 
        ORDER BY upvotes DESC 
        LIMIT :limit
    """)
    suspend fun getTipsByCategory(
        location: String, businessType: String, category: String, limit: Int = 5
    ): List<CommunityTip>

    // ═══════════════════════════════════════════════════════════════
    // TIP DELIVERY LOG
    // ═══════════════════════════════════════════════════════════════

    @Insert
    suspend fun logTipDelivery(log: TipDeliveryLog)

    /**
     * Get count of tips delivered recently (to limit daily tip frequency).
     */
    @Query("SELECT COUNT(*) FROM tip_delivery_log WHERE deliveredAt > :since")
    suspend fun getRecentTipDeliveries(since: Long): Int

    /**
     * Delete old delivery logs (keep last 30 days).
     */
    @Query("DELETE FROM tip_delivery_log WHERE deliveredAt < :cutoff")
    suspend fun deleteOldTipLogs(cutoff: Long)

    // ═══════════════════════════════════════════════════════════════
    // WHATSAPP GROUPS
    // ═══════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhatsAppGroup(group: WhatsAppGroup): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhatsAppGroups(groups: List<WhatsAppGroup>)

    /**
     * Get the WhatsApp group for a location × business type.
     */
    @Query("""
        SELECT * FROM whatsapp_groups 
        WHERE location = :location AND businessType = :businessType 
        LIMIT 1
    """)
    suspend fun getWhatsAppGroup(location: String, businessType: String): WhatsAppGroup?

    /**
     * Get all groups the user is a member of.
     */
    @Query("SELECT * FROM whatsapp_groups WHERE isMember = 1 ORDER BY groupName ASC")
    suspend fun getMemberGroups(): List<WhatsAppGroup>

    /**
     * Update last brief shared timestamp.
     */
    @Query("UPDATE whatsapp_groups SET lastBriefSharedAt = :timestamp WHERE id = :groupId")
    suspend fun updateLastBriefShared(groupId: Long, timestamp: Long)

    /**
     * Update last challenge timestamp.
     */
    @Query("UPDATE whatsapp_groups SET lastChallengeAt = :timestamp WHERE id = :groupId")
    suspend fun updateLastChallenge(groupId: Long, timestamp: Long)

    /**
     * Update member status.
     */
    @Query("UPDATE whatsapp_groups SET isMember = :isMember, memberCount = memberCount + :delta WHERE id = :groupId")
    suspend fun updateMemberStatus(groupId: Long, isMember: Boolean, delta: Int = 0)

    // ═══════════════════════════════════════════════════════════════
    // PEER CHALLENGES
    // ═══════════════════════════════════════════════════════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChallenge(challenge: PeerChallenge): Long

    /**
     * Get active challenges for a group.
     */
    @Query("""
        SELECT * FROM peer_challenges 
        WHERE groupId = :groupId AND status = 'ACTIVE' 
        ORDER BY endsAt ASC
    """)
    suspend fun getActiveChallenges(groupId: Long): List<PeerChallenge>

    /**
     * Get all active challenges across all groups.
     */
    @Query("SELECT * FROM peer_challenges WHERE status = 'ACTIVE' ORDER BY endsAt ASC")
    suspend fun getAllActiveChallenges(): List<PeerChallenge>

    /**
     * Update challenge progress.
     */
    @Query("UPDATE peer_challenges SET currentProgress = :progress WHERE id = :challengeId")
    suspend fun updateChallengeProgress(challengeId: Long, progress: Double)

    /**
     * Mark challenge as completed.
     */
    @Query("UPDATE peer_challenges SET status = 'COMPLETED' WHERE id = :challengeId")
    suspend fun markChallengeCompleted(challengeId: Long)

    /**
     * Expire old challenges.
     */
    @Query("UPDATE peer_challenges SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND endsAt < :now")
    suspend fun expireOldChallenges(now: Long)

    /**
     * Get challenges ending soon (within 24h) for reminder notifications.
     */
    @Query("""
        SELECT * FROM peer_challenges 
        WHERE status = 'ACTIVE' AND endsAt BETWEEN :now AND :deadline 
        ORDER BY endsAt ASC
    """)
    suspend fun getChallengesEndingSoon(now: Long, deadline: Long): List<PeerChallenge>
}
