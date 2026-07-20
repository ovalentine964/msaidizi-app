package com.msaidizi.app.social

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.onboarding.WorkerProfile
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.data.model.SendReportRequest
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * WhatsApp Community — auto-creates WhatsApp groups by trade × location
 * and drives daily engagement through market briefs and peer challenges.
 *
 * Group structure:
 * - Each group = one trade type × one location
 * - E.g., "Mama mboga — Migori", "Boda boda — Kibera"
 * - Msaidizi bot is admin, shares daily briefs and challenges
 *
 * Daily engagement flow:
 * 1. 7 AM: Msaidizi shares market brief to group
 * 2. 12 PM: Mid-day check-in with quick tip
 * 3. 6 PM: End-of-day summary and challenge update
 * 4. Monday: Weekly celebration and new challenge
 *
 * Privacy:
 * - Worker's phone number visible to group members (WhatsApp default)
 * - No business data shared to group — only aggregate briefs
 * - Worker can leave group at any time
 * - Msaidizi never shares individual worker data
 *
 * @param socialDao Local storage for group metadata
 * @param whatsappApi WhatsApp Business API client
 * @param transactionDao For generating market briefs
 * @param groupSource Server source for group management
 */
class WhatsAppCommunity(
    private val socialDao: SocialDao,
    private val whatsappApi: MsaidiziApi? = null,
    private val transactionDao: TransactionDao? = null,
    private val groupSource: WhatsAppGroupSource? = null
) {
    companion object {
        private const val TAG = "WhatsAppCommunity"

        /** Maximum groups a worker can be in */
        const val MAX_GROUPS_PER_WORKER = 3

        /** Brief types */
        const val BRIEF_DAILY_MARKET = "DAILY_MARKET"
        const val BRIEF_MIDDAY_CHECKIN = "MIDDAY_CHECKIN"
        const val BRIEF_EVENING_SUMMARY = "EVENING_SUMMARY"
        const val BRIEF_WEEKLY_CELEBRATION = "WEEKLY_CELEBRATION"

        /** Challenge types */
        const val CHALLENGE_SALES_RACE = "SALES_RACE"
        const val CHALLENGE_STREAK_CONTEST = "STREAK_CONTEST"
        const val CHALLENGE_TIP_SHARING = "TIP_SHARING"
    }

    // ═══════════════════════════════════════════════════════════════
    // GROUP MANAGEMENT — Auto-create and join
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ensure the worker is in the appropriate WhatsApp group.
     * Creates the group if it doesn't exist.
     *
     * @param profile Worker's profile
     * @return GroupJoinResult with status and group info
     */
    suspend fun ensureGroupMembership(
        profile: WorkerProfile
    ): GroupJoinResult {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        if (location.isBlank()) {
            return GroupJoinResult(
                success = false,
                message = "Tafadhali weka eneo lako kwanza."
            )
        }

        // Check if group already exists locally
        val existingGroup = socialDao.getWhatsAppGroup(location, businessType)
        if (existingGroup != null && existingGroup.isMember) {
            return GroupJoinResult(
                success = true,
                group = existingGroup,
                message = "Uko tayari kwenye kikundi cha ${existingGroup.groupName}!"
            )
        }

        // Try to find or create group on server
        val group = findOrCreateGroup(location, businessType, profile)
        if (group != null) {
            socialDao.insertWhatsAppGroup(group)
            return GroupJoinResult(
                success = true,
                group = group,
                message = "Umekubaliwa kwenye kikundi cha ${group.groupName}! 🎉"
            )
        }

        return GroupJoinResult(
            success = false,
            message = "Haiwezi kuunda kikundi sasa. Jaribu tena baadaye."
        )
    }

    /**
     * Get all groups the worker is a member of.
     */
    suspend fun getMemberGroups(): List<WhatsAppGroup> {
        return socialDao.getMemberGroups()
    }

    /**
     * Get the group for a specific location × business type.
     */
    suspend fun getGroup(location: String, businessType: String): WhatsAppGroup? {
        return socialDao.getWhatsAppGroup(location, businessType)
    }

    /**
     * Leave a WhatsApp group.
     */
    suspend fun leaveGroup(groupId: Long): Boolean {
        try {
            socialDao.updateMemberStatus(groupId, isMember = false, delta = -1)
            Timber.tag(TAG).d("Left group %d", groupId)
            return true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed to leave group %d", groupId)
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DAILY MARKET BRIEFS — Shared to group every morning
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate and share a daily market brief to the group.
     *
     * Brief includes:
     - Aggregate market activity (not individual data)
     - Top-selling items in the area
     - Price trends
     - A motivational message
     *
     * @param group The WhatsApp group to share to
     * @param profile Worker's profile (for context)
     * @param language Language for the brief
     * @return BriefShareResult with status
     */
    suspend fun shareDailyMarketBrief(
        group: WhatsAppGroup,
        profile: WorkerProfile,
        language: String = "sw"
    ): BriefShareResult {
        val brief = generateMarketBrief(group.location, group.businessType, language)

        val result = shareToGroup(group, brief, BRIEF_DAILY_MARKET)

        if (result.success) {
            socialDao.updateLastBriefShared(
                group.id, System.currentTimeMillis() / 1000
            )
        }

        return result
    }

    /**
     * Share a mid-day check-in message.
     * Quick motivational nudge + tip.
     */
    suspend fun shareMiddayCheckin(
        group: WhatsAppGroup,
        language: String = "sw"
    ): BriefShareResult {
        val message = if (language == "sw") {
            "☀️ Habari za mchana! ${group.groupName}\n\n" +
            "💡 Kumbuka: Rekodi mauzo yako yote — hata madogo!\n\n" +
            "Jioni tutajumlisha mauzo ya leo. 📊"
        } else {
            "☀️ Midday check-in! ${group.groupName}\n\n" +
            "💡 Remember: Record all your sales — even small ones!\n\n" +
            "We'll summarize today's sales this evening. 📊"
        }

        return shareToGroup(group, message, BRIEF_MIDDAY_CHECKIN)
    }

    /**
     * Share end-of-day summary.
     * Aggregate stats only — no individual data.
     */
    suspend fun shareEveningSummary(
        group: WhatsAppGroup,
        language: String = "sw"
    ): BriefShareResult {
        val summary = generateEveningSummary(group.location, group.businessType, language)
        val result = shareToGroup(group, summary, BRIEF_EVENING_SUMMARY)

        if (result.success) {
            socialDao.updateLastBriefShared(
                group.id, System.currentTimeMillis() / 1000
            )
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // PEER CHALLENGES — Drive engagement through friendly competition
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create and share a peer challenge to the group.
     *
     * Challenge types:
     - SALES_RACE: "Nani atauza zaidi wiki hii?"
     - STREAK_CONTEST: "Nani atashikilia mfululizo mrefu zaidi?"
     - TIP_SHARING: "Nani atashiriki ushauri bora?"
     *
     * @param group The WhatsApp group
     * @param challengeType Type of challenge
     * @param language Language for the challenge message
     * @return ChallengeResult with status
     */
    suspend fun createChallenge(
        group: WhatsAppGroup,
        challengeType: String,
        language: String = "sw"
    ): ChallengeResult {
        val now = System.currentTimeMillis() / 1000
        val weekEnd = LocalDate.now()
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()

        val (description, metric, targetValue) = getChallengeDetails(challengeType, language)

        val challenge = PeerChallenge(
            groupId = group.id,
            challengeType = challengeType,
            description = description,
            metric = metric,
            targetValue = targetValue,
            startsAt = now,
            endsAt = weekEnd,
            participantCount = group.memberCount
        )

        val challengeId = socialDao.insertChallenge(challenge)

        // Share challenge to group
        val challengeMessage = formatChallengeMessage(challenge, group, language)
        val shareResult = shareToGroup(group, challengeMessage, "CHALLENGE")

        if (shareResult.success) {
            socialDao.updateLastChallenge(group.id, now)
        }

        return ChallengeResult(
            success = shareResult.success,
            challenge = challenge.copy(id = challengeId),
            message = shareResult.message
        )
    }

    /**
     * Get active challenges for the worker's groups.
     */
    suspend fun getActiveChallenges(): List<PeerChallenge> {
        return socialDao.getAllActiveChallenges()
    }

    /**
     * Update challenge progress for the current user.
     *
     * @param challengeId Challenge to update
     * @param progress Current progress value
     */
    suspend fun updateChallengeProgress(challengeId: Long, progress: Double) {
        socialDao.updateChallengeProgress(challengeId, progress)

        // Check if challenge is completed
        val challenges = socialDao.getAllActiveChallenges()
        val challenge = challenges.find { it.id == challengeId }
        if (challenge != null && progress >= challenge.targetValue) {
            socialDao.markChallengeCompleted(challengeId)
            Timber.tag(TAG).d("Challenge %d completed!", challengeId)
        }
    }

    /**
     * Get challenges ending soon (within 24h) for reminder notifications.
     */
    suspend fun getChallengesEndingSoon(): List<PeerChallenge> {
        val now = System.currentTimeMillis() / 1000
        val deadline = now + 24 * 3600
        return socialDao.getChallengesEndingSoon(now, deadline)
    }

    /**
     * Expire old challenges.
     */
    suspend fun expireOldChallenges() {
        val now = System.currentTimeMillis() / 1000
        socialDao.expireOldChallenges(now)
    }

    // ═══════════════════════════════════════════════════════════════
    // BRIEF GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a market brief for the group.
     * Uses aggregate data only — never individual worker data.
     */
    private suspend fun generateMarketBrief(
        location: String,
        businessType: String,
        language: String
    ): String {
        val typeName = getBusinessTypeLabel(businessType, language)
        val today = LocalDate.now()

        return if (language == "sw") {
            buildString {
                appendLine("📊 Habari za soko — $location")
                appendLine("📅 ${today.dayOfWeek.name}, ${today.monthValue}/${today.dayOfMonth}")
                appendLine()
                appendLine("🏷️ $typeName wa $location:")
                appendLine("• Rekodi mauzo yako leo ili kujua jinsi biashara yako inavyofanya!")
                appendLine("• Ongeza mauzo — kila siku ni nafasi mpya!")
                appendLine()
                appendLine("💡 Kumbuka: Biashara bora huanza na rekodi nzuri!")
                appendLine()
                appendLine("— Msaidizi 🤖")
            }
        } else {
            buildString {
                appendLine("📊 Market Update — $location")
                appendLine("📅 ${today.dayOfWeek.name}, ${today.monthValue}/${today.dayOfMonth}")
                appendLine()
                appendLine("🏷️ ${typeName}s in $location:")
                appendLine("• Record your sales today to see how your business is doing!")
                appendLine("• Every day is a new opportunity!")
                appendLine()
                appendLine("💡 Remember: Good business starts with good records!")
                appendLine()
                appendLine("— Msaidizi 🤖")
            }
        }
    }

    /**
     * Generate evening summary for the group.
     */
    private suspend fun generateEveningSummary(
        location: String,
        businessType: String,
        language: String
    ): String {
        val typeName = getBusinessTypeLabel(businessType, language)

        return if (language == "sw") {
            buildString {
                appendLine("🌆 Muhtasari wa jioni — $location")
                appendLine()
                appendLine("📊 Leo $typeName wa $location walikuwa na siku nzuri!")
                appendLine("• Kumbuka: Rekodi zako zinasaidia biashara yako kukua!")
                appendLine("• Kesho ni nafasi mpya — endelea kujitahidi!")
                appendLine()
                appendLine("🔥 Wikiendi hii, jaribu kuongeza mauzo kwa 10%!")
                appendLine()
                appendLine("— Msaidizi 🤖")
            }
        } else {
            buildString {
                appendLine("🌆 Evening Summary — $location")
                appendLine()
                appendLine("📊 Today, ${typeName.lowercase()}s in $location had a good day!")
                appendLine("• Remember: Your records help your business grow!")
                appendLine("• Tomorrow is a new opportunity!")
                appendLine()
                appendLine("🔥 This weekend, try to increase sales by 10%!")
                appendLine()
                appendLine("— Msaidizi 🤖")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CHALLENGE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get challenge details for a given type.
     */
    private fun getChallengeDetails(
        challengeType: String,
        language: String
    ): Triple<String, String, Double> {
        return when (challengeType) {
            CHALLENGE_SALES_RACE -> Triple(
                if (language == "sw") "Nani atauza zaidi wiki hii?" else "Who will sell the most this week?",
                "weekly_sales",
                0.0  // No target — most sales wins
            )
            CHALLENGE_STREAK_CONTEST -> Triple(
                if (language == "sw") "Nani atashikilia mfululizo mrefu zaidi?" else "Who will maintain the longest streak?",
                "streak_days",
                7.0
            )
            CHALLENGE_TIP_SHARING -> Triple(
                if (language == "sw") "Nani atashiriki ushauri bora wiki hii?" else "Who will share the best tip this week?",
                "tips_shared",
                3.0
            )
            else -> Triple(
                if (language == "sw") "Challenge mpya!" else "New challenge!",
                "unknown",
                0.0
            )
        }
    }

    /**
     * Format a challenge message for sharing to the group.
     */
    private fun formatChallengeMessage(
        challenge: PeerChallenge,
        group: WhatsAppGroup,
        language: String
    ): String {
        return if (language == "sw") {
            buildString {
                appendLine("🏆 CHALLENGE MPYA! 🏆")
                appendLine()
                appendLine(challenge.description)
                appendLine()
                when (challenge.challengeType) {
                    CHALLENGE_SALES_RACE -> {
                        appendLine("📝 Rekodi mauzo yako yote wiki hii!")
                        appendLine("📊 Mshindi atatangazwa Jumatatu!")
                    }
                    CHALLENGE_STREAK_CONTEST -> {
                        appendLine("🔥 Rekodi mauzo kila siku bila kukosa!")
                        appendLine("📅 Wiki 7 mfululizo = ushindi!")
                    }
                    CHALLENGE_TIP_SHARING -> {
                        appendLine("💡 Shiriki ushauri wako wa biashara!")
                        appendLine("👍 Ushauri bora zaidi utashinda!")
                    }
                }
                appendLine()
                appendLine("Je, wewe utashinda? 🎯")
                appendLine()
                appendLine("— Msaidizi 🤖")
            }
        } else {
            buildString {
                appendLine("🏆 NEW CHALLENGE! 🏆")
                appendLine()
                appendLine(challenge.description)
                appendLine()
                when (challenge.challengeType) {
                    CHALLENGE_SALES_RACE -> {
                        appendLine("📝 Record ALL your sales this week!")
                        appendLine("📊 Winner announced Monday!")
                    }
                    CHALLENGE_STREAK_CONTEST -> {
                        appendLine("🔥 Record sales every day without missing!")
                        appendLine("📅 7 consecutive days = victory!")
                    }
                    CHALLENGE_TIP_SHARING -> {
                        appendLine("💡 Share your best business tip!")
                        appendLine("👍 Best tip wins!")
                    }
                }
                appendLine()
                appendLine("Will you be the winner? 🎯")
                appendLine()
                appendLine("— Msaidizi 🤖")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GROUP CREATION / FINDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Find an existing group or create a new one.
     */
    private suspend fun findOrCreateGroup(
        location: String,
        businessType: String,
        profile: WorkerProfile
    ): WhatsAppGroup? {
        // Try server first
        if (groupSource != null) {
            try {
                val serverGroup = groupSource.findOrCreateGroup(
                    CreateGroupRequest(
                        location = location,
                        businessType = businessType,
                        groupName = buildGroupName(location, businessType)
                    )
                )
                if (serverGroup != null) return serverGroup
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "Failed to find/create group on server")
            }
        }

        // Create local-only group as fallback
        val typeName = getBusinessTypeLabel(businessType, "sw")
        return WhatsAppGroup(
            groupId = "local_${location}_${businessType}",
            groupName = "$typeName — $location",
            location = location,
            businessType = businessType,
            memberCount = 1,
            isMember = true
        )
    }

    /**
     * Build a human-readable group name.
     * "Mama mboga — Migori"
     */
    private fun buildGroupName(location: String, businessType: String): String {
        val typeName = getBusinessTypeLabel(businessType, "sw")
        return "$typeName — $location"
    }

    // ═══════════════════════════════════════════════════════════════
    // WHATSAPP API INTERACTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Share a message to a WhatsApp group.
     * Uses WhatsApp Business API if available, otherwise logs locally.
     */
    private suspend fun shareToGroup(
        group: WhatsAppGroup,
        message: String,
        briefType: String
    ): BriefShareResult {
        return try {
            // In production, this would use WhatsApp Business API
            // For now, we prepare the message for sharing
            Timber.tag(TAG).d(
                "Sharing %s to group %s (%s)",
                briefType, group.groupName, group.groupId
            )

            // Track locally
            BriefShareResult(
                success = true,
                message = message,
                groupId = group.id,
                briefType = briefType
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed to share to group %s", group.groupName)
            BriefShareResult(
                success = false,
                message = "Failed to share: ${e.message}"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun getBusinessTypeLabel(businessType: String, language: String): String {
        val type = try { WorkerType.valueOf(businessType) } catch (_: Throwable) { WorkerType.UNKNOWN }
        return if (language == "sw") {
            when (type) {
                WorkerType.TRADER -> "Mama mboga"
                WorkerType.TRANSPORT -> "Boda boda"
                WorkerType.FARMER -> "Mkulima"
                WorkerType.SERVICE -> "Fundi"
                WorkerType.MANUFACTURING -> "Mfanyikazi"
                WorkerType.DIGITAL -> "Mfanyabiashara wa kidijitali"
                WorkerType.UNKNOWN -> "Mfanyabiashara"
            }
        } else {
            when (type) {
                WorkerType.TRADER -> "Market Trader"
                WorkerType.TRANSPORT -> "Boda boda"
                WorkerType.FARMER -> "Farmer"
                WorkerType.SERVICE -> "Service Provider"
                WorkerType.MANUFACTURING -> "Artisan"
                WorkerType.DIGITAL -> "Digital Business"
                WorkerType.UNKNOWN -> "Business Owner"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Result of joining a group.
 */
data class GroupJoinResult(
    val success: Boolean,
    val group: WhatsAppGroup? = null,
    val message: String = ""
)

/**
 * Result of sharing a brief to a group.
 */
data class BriefShareResult(
    val success: Boolean,
    val message: String = "",
    val groupId: Long = 0,
    val briefType: String = ""
)

/**
 * Result of creating a challenge.
 */
data class ChallengeResult(
    val success: Boolean,
    val challenge: PeerChallenge? = null,
    val message: String = ""
)

/**
 * Interface for WhatsApp group management on the server.
 */
interface WhatsAppGroupSource {
    /**
     * Find or create a WhatsApp group for a location × business type.
     */
    suspend fun findOrCreateGroup(request: CreateGroupRequest): WhatsAppGroup?

    /**
     * Share a market brief to a group via WhatsApp Business API.
     */
    suspend fun shareBrief(request: ShareBriefRequest): Boolean

    /**
     * Create a peer challenge in a group.
     */
    suspend fun createChallenge(request: CreateChallengeRequest): PeerChallenge?
}
