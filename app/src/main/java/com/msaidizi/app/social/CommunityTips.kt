package com.msaidizi.app.social

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.onboarding.WorkerProfile
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

/**
 * Community Tips — workers share business tips anonymously.
 *
 * "Mama mboga mmoja Migori anasema: 'Nunua nyanya asubuhi, bei ni ndogo'"
 * (A mama mboga in Migori says: 'Buy tomatoes in the morning, prices are lower')
 *
 * Design principles:
 * - Anonymous: Tips are attributed to "Mama mboga mmoja" or "Mfanyabiashara mmoja"
 * - Relevant: Tips are filtered by location × business type
 * - Quality: Upvote system surfaces the best tips
 * - Fresh: Msaidizi shares top tips during morning briefings
 * - Safe: Content moderation before tips are shown
 *
 * Tip lifecycle:
 * 1. Worker submits tip (voice or text)
 * 2. Basic content filter applied
 * 3. Tip appears in community feed
 * 4. Other workers upvote useful tips
 * 5. Top tips get featured in morning briefings
 * 6. Featured tips get a "Msaidizi inapendekeza" badge
 *
 * @param socialDao Local storage for tips
 * @param tipsSource Server source for community tips
 */
class CommunityTips(
    private val socialDao: SocialDao,
    private val tipsSource: CommunityTipsSource? = null
) {
    companion object {
        private const val TAG = "CommunityTips"

        /** Maximum tips shown per day in briefings */
        private const val MAX_DAILY_TIPS = 1

        /** Minimum upvotes before a tip can be featured */
        private const val MIN_UPVOTES_FOR_FEATURE = 3

        /** Maximum tip content length */
        private const val MAX_TIP_LENGTH = 280

        /** Tip categories */
        val TIP_CATEGORIES = listOf(
            "pricing",      // Bei — pricing strategies
            "stocking",     // Stock — what to buy and when
            "marketing",    // Masoko — attracting customers
            "savings",      // Akiba — saving money
            "general"       // General — other tips
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // SUBMIT TIP — Worker shares a business tip
    // ═══════════════════════════════════════════════════════════════

    /**
     * Submit a new community tip.
     *
     * @param content Tip content (in worker's language)
     * @param profile Worker's profile (for location/business type)
     * @param category Tip category
     * @param language Language of the tip
     * @return TipSubmissionResult with status
     */
    suspend fun submitTip(
        content: String,
        profile: WorkerProfile,
        category: String = "general",
        language: String = "sw"
    ): TipSubmissionResult {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        // Validate
        if (content.isBlank()) {
            return TipSubmissionResult(
                success = false,
                message = if (language == "sw") "Tafadhali andika ushauri wako." else "Please write your tip."
            )
        }

        if (content.length > MAX_TIP_LENGTH) {
            return TipSubmissionResult(
                success = false,
                message = if (language == "sw") "Ushauri wako ni mrefu sana. Fupisha maneno yako." else "Your tip is too long. Keep it brief."
            )
        }

        // Content filter
        if (!isContentSafe(content)) {
            return TipSubmissionResult(
                success = false,
                message = if (language == "sw") "Ushauri wako haufai. Tafadhali jaribu tena." else "Your tip was flagged. Please try again."
            )
        }

        // Create tip
        val tip = CommunityTip(
            content = content.trim(),
            location = location,
            businessType = businessType,
            category = category,
            isOwnTip = true,
            createdAt = System.currentTimeMillis() / 1000
        )

        val tipId = socialDao.insertTip(tip)

        // Sync to server in background
        if (tipsSource != null) {
            try {
                tipsSource.submitTip(
                    SubmitTipRequest(
                        content = content.trim(),
                        location = location,
                        businessType = businessType,
                        category = category,
                        language = language
                    )
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to sync tip to server (still saved locally)")
            }
        }

        Timber.tag(TAG).d("Tip submitted: id=%d category=%s", tipId, category)

        return TipSubmissionResult(
            success = true,
            tipId = tipId,
            message = if (language == "sw") {
                "🙏 Asante! Ushauri wako utasaidia wafanyabiashara wenzako."
            } else {
                "🙏 Thank you! Your tip will help other businesses."
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // GET TIPS — For display and briefings
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get community tips for the worker's area.
     *
     * Returns tips sorted by upvotes, filtered by location × business type.
     * Used for the community feed in the app.
     *
     * @param profile Worker's profile
     * @param limit Maximum tips to return
     * @return List of tips for display
     */
    suspend fun getCommunityTips(
        profile: WorkerProfile,
        limit: Int = 20
    ): List<CommunityTip> {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        if (location.isBlank()) return emptyList()

        // Try server for fresh tips
        syncTipsFromServer(location, businessType)

        return socialDao.getTopTips(location, businessType, limit)
    }

    /**
     * Get tips by category for targeted advice.
     */
    suspend fun getTipsByCategory(
        profile: WorkerProfile,
        category: String,
        limit: Int = 5
    ): List<CommunityTip> {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        if (location.isBlank()) return emptyList()

        return socialDao.getTipsByCategory(location, businessType, category, limit)
    }

    /**
     * Get the worker's own submitted tips.
     */
    suspend fun getOwnTips(): List<CommunityTip> {
        return socialDao.getOwnTips()
    }

    // ═══════════════════════════════════════════════════════════════
    // UPVOTE — Community quality signal
    // ═══════════════════════════════════════════════════════════════

    /**
     * Upvote a tip.
     *
     * @param tipId ID of the tip to upvote
     * @return UpvoteResult with status
     */
    suspend fun upvoteTip(tipId: Long): UpvoteResult {
        try {
            socialDao.upvoteTip(tipId)

            // Sync to server
            if (tipsSource != null) {
                tipsSource.upvoteTip(UpvoteTipRequest(tipId = tipId.toString()))
            }

            Timber.tag(TAG).d("Tip %d upvoted", tipId)
            return UpvoteResult(success = true)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to upvote tip %d", tipId)
            return UpvoteResult(success = false, message = "Failed to upvote")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MORNING BRIEFING INJECTION — Top tips for daily briefings
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a tip for the morning briefing.
     *
     * Picks from undelivered tips first (fresh content), then falls back
     * to top tips. Returns null if no tips available.
     *
     * @param profile Worker's profile
     * @param language Language for the attribution message
     * @return A formatted tip message for the briefing, or null
     */
    suspend fun getBriefingTip(
        profile: WorkerProfile,
        language: String = "sw"
    ): String? {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        if (location.isBlank()) return null

        // Check daily limit
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val recentDeliveries = socialDao.getRecentTipDeliveries(todayStart)
        if (recentDeliveries >= MAX_DAILY_TIPS) return null

        // Try undelivered tips first (fresh content)
        var tip = socialDao.getUndeliveredTips(location, businessType, 1).firstOrNull()

        // Fall back to random top tip for variety
        if (tip == null) {
            tip = socialDao.getRandomTopTip(location, businessType)
        }

        if (tip == null) return null

        // Format for briefing
        val typeName = getAnonymousAttribution(profile.businessType, language)
        val formattedTip = formatTipForBriefing(tip, typeName, location, language)

        // Log delivery
        socialDao.logTipDelivery(
            TipDeliveryLog(tipId = tip.id)
        )
        socialDao.markTipFeatured(tip.id)

        return formattedTip
    }

    /**
     * Get multiple tips for a weekly digest.
     */
    suspend fun getWeeklyDigestTips(
        profile: WorkerProfile,
        limit: Int = 3,
        language: String = "sw"
    ): List<String> {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        if (location.isBlank()) return emptyList()

        val tips = socialDao.getTopTips(location, businessType, limit)
        val typeName = getAnonymousAttribution(profile.businessType, language)

        return tips.map { tip ->
            formatTipForBriefing(tip, typeName, location, language)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TIP CATEGORIZATION — Auto-categorize submitted tips
    // ═══════════════════════════════════════════════════════════════

    /**
     * Auto-categorize a tip based on its content.
     * Uses keyword matching for fast, offline categorization.
     */
    fun categorizeTip(content: String): String {
        val lower = content.lowercase()

        return when {
            // Pricing tips
            lower.contains("bei") || lower.contains("price") ||
            lower.contains("ghali") || lower.contains("rahisi") ||
            lower.contains("punguza") || lower.contains("ongeza") -> "pricing"

            // Stocking tips
            lower.contains("nunua") || lower.contains("stock") ||
            lower.contains("supplier") || lower.contains("soko") ||
            lower.contains("buy") || lower.contains("order") -> "stocking"

            // Marketing tips
            lower.contains("mteja") || lower.contains("customer") ||
            lower.contains("soko") || lower.contains("market") ||
            lower.contains("tangaza") || lower.contains("advertise") -> "marketing"

            // Savings tips
            lower.contains("akiba") || lower.contains("save") ||
            lower.contains("weka") || lower.contains("hifadhi") ||
            lower.contains("bank") || lower.contains("mpesa") -> "savings"

            else -> "general"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTENT MODERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Basic content safety check.
     * Filters out obviously inappropriate content.
     * For production, this should be backed by a server-side moderation API.
     */
    private fun isContentSafe(content: String): Boolean {
        val lower = content.lowercase()

        // Block obviously inappropriate content
        val blockedPatterns = listOf(
            "scam", "fake", "cheat", "steal", "wizi", "udanganyifu",
            "dawa za kulevya", "drugs", "alcohol", "pombe"
        )

        for (pattern in blockedPatterns) {
            if (lower.contains(pattern)) return false
        }

        // Block very short or empty tips
        if (content.trim().length < 10) return false

        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // FORMATTING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Format a tip for inclusion in a morning briefing.
     *
     * "Mama mboga mmoja Migori anasema: 'Nunua nyanya asubuhi, bei ni ndogo'"
     */
    private fun formatTipForBriefing(
        tip: CommunityTip,
        typeName: String,
        location: String,
        language: String
    ): String {
        return if (language == "sw") {
            "💡 $typeName mmoja $location anasema: '${tip.content}'"
        } else {
            "💡 A ${typeName.lowercase()} in $location says: '${tip.content}'"
        }
    }

    /**
     * Get anonymous attribution for a business type.
     * "Mama mboga mmoja" or "Mfanyabiashara mmoja"
     */
    private fun getAnonymousAttribution(businessType: WorkerType, language: String): String {
        return if (language == "sw") {
            when (businessType) {
                WorkerType.TRADER -> "Mama mboga"
                WorkerType.TRANSPORT -> "Boda boda rider"
                WorkerType.FARMER -> "Mkulima"
                WorkerType.SERVICE -> "Fundi"
                WorkerType.MANUFACTURING -> "Mfanyikazi"
                WorkerType.DIGITAL -> "Mfanyabiashara wa kidijitali"
                WorkerType.UNKNOWN -> "Mfanyabiashara"
            }
        } else {
            when (businessType) {
                WorkerType.TRADER -> "Market trader"
                WorkerType.TRANSPORT -> "Boda boda rider"
                WorkerType.FARMER -> "Farmer"
                WorkerType.SERVICE -> "Service provider"
                WorkerType.MANUFACTURING -> "Artisan"
                WorkerType.DIGITAL -> "Digital business"
                WorkerType.UNKNOWN -> "Business owner"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SERVER SYNC
    // ═══════════════════════════════════════════════════════════════

    /**
     * Sync tips from server to local cache.
     */
    private suspend fun syncTipsFromServer(location: String, businessType: String) {
        if (tipsSource == null) return

        try {
            val response = tipsSource.fetchTips(location, businessType)
            if (response != null && response.tips.isNotEmpty()) {
                socialDao.insertTips(response.tips.map { it.copy(isOwnTip = false) })
                Timber.tag(TAG).d("Synced %d tips from server", response.tips.size)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to sync tips from server")
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Result of submitting a tip.
 */
data class TipSubmissionResult(
    val success: Boolean,
    val tipId: Long = 0,
    val message: String = ""
)

/**
 * Result of upvoting a tip.
 */
data class UpvoteResult(
    val success: Boolean,
    val message: String = ""
)

/**
 * Interface for fetching tips from the server.
 */
interface CommunityTipsSource {
    /**
     * Fetch community tips for a location × business type.
     */
    suspend fun fetchTips(location: String, businessType: String): CommunityTipsResponse?

    /**
     * Submit a new tip to the server.
     */
    suspend fun submitTip(request: SubmitTipRequest)

    /**
     * Upvote a tip on the server.
     */
    suspend fun upvoteTip(request: UpvoteTipRequest)
}
