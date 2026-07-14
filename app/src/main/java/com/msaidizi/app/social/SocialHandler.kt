package com.msaidizi.app.social

import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.onboarding.WorkerProfile
import timber.log.Timber

/**
 * Social Handler — integrates social features into the Orchestrator's
 * intent routing system.
 *
 * Handles social-related intents:
 * - "check leaderboard" → LeaderboardService
 * - "compare with peers" → PeerComparison
 * - "share a tip" → CommunityTips
 * - "community tips" → CommunityTips
 * - "join group" → WhatsAppCommunity
 * - "start challenge" → WhatsAppCommunity
 *
 * @param peerComparison Peer comparison engine
 * @param leaderboardService Weekly leaderboard
 * @param communityTips Community tip sharing
 * @param whatsappCommunity WhatsApp group management
 */
class SocialHandler(
    private val peerComparison: PeerComparison,
    private val leaderboardService: LeaderboardService,
    private val communityTips: CommunityTips,
    private val whatsappCommunity: WhatsAppCommunity
) {
    companion object {
        private const val TAG = "SocialHandler"
    }

    /**
     * Handle a social-related intent.
     *
     * @param intent The social intent type
     * @param profile Worker's profile
     * @param params Additional parameters for the intent
     * @param language Language preference
     * @return SocialResponse with message and data
     */
    suspend fun handleIntent(
        intent: SocialIntent,
        profile: WorkerProfile,
        params: Map<String, String> = emptyMap(),
        language: String = "sw"
    ): SocialResponse {
        Timber.tag(TAG).d("Handling social intent: %s", intent)

        return when (intent) {
            SocialIntent.CHECK_PEER_COMPARISON -> handlePeerComparison(profile, language)
            SocialIntent.CHECK_LEADERBOARD -> handleLeaderboard(profile, language)
            SocialIntent.VIEW_COMMUNITY_TIPS -> handleViewTips(profile, language)
            SocialIntent.SUBMIT_TIP -> handleSubmitTip(profile, params, language)
            SocialIntent.UPVOTE_TIP -> handleUpvoteTip(params, language)
            SocialIntent.JOIN_WHATSAPP_GROUP -> handleJoinGroup(profile, language)
            SocialIntent.START_CHALLENGE -> handleStartChallenge(profile, params, language)
            SocialIntent.VIEW_CHALLENGES -> handleViewChallenges(profile, language)
            SocialIntent.GET_SOCIAL_PROOF -> handleGetSocialProof(profile, language)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTENT HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun handlePeerComparison(
        profile: WorkerProfile,
        language: String
    ): SocialResponse {
        val output = peerComparison.generateComparison(profile, language)

        return if (output.hasComparison) {
            val message = output.messages.firstOrNull()?.message
                ?: if (language == "sw") "Hakuna data ya kulinganisha bado." else "No comparison data yet."
            SocialResponse(
                success = true,
                message = message,
                data = mapOf(
                    "salesPercentile" to output.comparison.salesPercentile.toString(),
                    "profitPercentile" to output.comparison.profitPercentile.toString(),
                    "peerCount" to output.peerMetrics.peerCount.toString()
                )
            )
        } else {
            SocialResponse(
                success = false,
                message = if (language == "sw") {
                    "Hakuna data ya kutosha ya wafanyabiashara wenzako eneo lako bado. Endelea kurekodi mauzo yako!"
                } else {
                    "Not enough peer data in your area yet. Keep recording your sales!"
                }
            )
        }
    }

    private suspend fun handleLeaderboard(
        profile: WorkerProfile,
        language: String
    ): SocialResponse {
        val output = leaderboardService.getLeaderboard(profile, language)

        return if (output.hasData) {
            val message = output.messages.firstOrNull()?.message
                ?: if (language == "sw") "Hakuna data ya leaderboard bado." else "No leaderboard data yet."
            SocialResponse(
                success = true,
                message = message,
                data = mapOf(
                    "myRank" to (output.summary?.myRank?.toString() ?: "0"),
                    "totalParticipants" to (output.summary?.totalParticipants?.toString() ?: "0"),
                    "rankChange" to (output.summary?.rankChange?.toString() ?: "0")
                )
            )
        } else {
            SocialResponse(
                success = false,
                message = if (language == "sw") {
                    "Leaderboard haijapatikana bado. Rekodi mauzo yako ili kushiriki!"
                } else {
                    "Leaderboard not available yet. Record your sales to participate!"
                }
            )
        }
    }

    private suspend fun handleViewTips(
        profile: WorkerProfile,
        language: String
    ): SocialResponse {
        val tips = communityTips.getCommunityTips(profile, 10)

        return if (tips.isNotEmpty()) {
            val typeName = getBusinessTypeLabel(profile.businessType, language)
            val location = profile.location.ifBlank { profile.marketName }
            val message = buildString {
                if (language == "sw") {
                    appendLine("💡 Ushauri kutoka kwa $typeName wengine $location:")
                    appendLine()
                    tips.take(5).forEachIndexed { index, tip ->
                        appendLine("${index + 1}. ${tip.content}")
                        appendLine("   👍 ${tip.upvotes} wamependa")
                    }
                } else {
                    appendLine("💡 Tips from other ${typeName.lowercase()} in $location:")
                    appendLine()
                    tips.take(5).forEachIndexed { index, tip ->
                        appendLine("${index + 1}. ${tip.content}")
                        appendLine("   👍 ${tip.upvotes} upvotes")
                    }
                }
            }
            SocialResponse(
                success = true,
                message = message,
                data = mapOf("tipCount" to tips.size.toString())
            )
        } else {
            SocialResponse(
                success = false,
                message = if (language == "sw") {
                    "Hakuna ushauri bado kutoka kwa wafanyabiashara wenzako. Kuwa wa kwanza kushiriki!"
                } else {
                    "No tips yet from other businesses. Be the first to share!"
                }
            )
        }
    }

    private suspend fun handleSubmitTip(
        profile: WorkerProfile,
        params: Map<String, String>,
        language: String
    ): SocialResponse {
        val content = params["content"] ?: return SocialResponse(
            success = false,
            message = if (language == "sw") "Tafadhali andika ushauri wako." else "Please write your tip."
        )

        val category = params["category"] ?: communityTips.categorizeTip(content)
        val result = communityTips.submitTip(content, profile, category, language)

        return SocialResponse(
            success = result.success,
            message = result.message,
            data = mapOf("tipId" to result.tipId.toString())
        )
    }

    private suspend fun handleUpvoteTip(
        params: Map<String, String>,
        language: String
    ): SocialResponse {
        val tipId = params["tipId"]?.toLongOrNull() ?: return SocialResponse(
            success = false,
            message = if (language == "sw") "Tafadhali chagua ushauri wa kupenda." else "Please select a tip to upvote."
        )

        val result = communityTips.upvoteTip(tipId)

        return SocialResponse(
            success = result.success,
            message = if (result.success) {
                if (language == "sw") "👍 Umeupenda ushauri huo!" else "👍 You upvoted this tip!"
            } else {
                result.message
            }
        )
    }

    private suspend fun handleJoinGroup(
        profile: WorkerProfile,
        language: String
    ): SocialResponse {
        val result = whatsappCommunity.ensureGroupMembership(profile)

        return SocialResponse(
            success = result.success,
            message = result.message,
            data = result.group?.let {
                mapOf(
                    "groupName" to it.groupName,
                    "memberCount" to it.memberCount.toString()
                )
            } ?: emptyMap()
        )
    }

    private suspend fun handleStartChallenge(
        profile: WorkerProfile,
        params: Map<String, String>,
        language: String
    ): SocialResponse {
        val challengeType = params["challengeType"] ?: WhatsAppCommunity.CHALLENGE_SALES_RACE
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        val group = whatsappCommunity.getGroup(location, businessType)
            ?: return SocialResponse(
                success = false,
                message = if (language == "sw") {
                    "Hujaungana na kikundi bado. Jiunge kwanza!"
                } else {
                    "You haven't joined a group yet. Join first!"
                }
            )

        val result = whatsappCommunity.createChallenge(group, challengeType, language)

        return SocialResponse(
            success = result.success,
            message = result.message
        )
    }

    private suspend fun handleViewChallenges(
        profile: WorkerProfile,
        language: String
    ): SocialResponse {
        val challenges = whatsappCommunity.getActiveChallenges()

        return if (challenges.isNotEmpty()) {
            val message = buildString {
                if (language == "sw") {
                    appendLine("🏆 Changamoto za sasa:")
                    challenges.forEach { challenge ->
                        appendLine("• ${challenge.description}")
                        appendLine("  📊 Maendeleo: ${challenge.currentProgress.toInt()}/${challenge.targetValue.toInt()}")
                    }
                } else {
                    appendLine("🏆 Current challenges:")
                    challenges.forEach { challenge ->
                        appendLine("• ${challenge.description}")
                        appendLine("  📊 Progress: ${challenge.currentProgress.toInt()}/${challenge.targetValue.toInt()}")
                    }
                }
            }
            SocialResponse(
                success = true,
                message = message,
                data = mapOf("challengeCount" to challenges.size.toString())
            )
        } else {
            SocialResponse(
                success = false,
                message = if (language == "sw") {
                    "Hakuna changamoto za sasa. Anzisha mpya!"
                } else {
                    "No active challenges. Start a new one!"
                }
            )
        }
    }

    private suspend fun handleGetSocialProof(
        profile: WorkerProfile,
        language: String
    ): SocialResponse {
        val location = profile.location.ifBlank { profile.marketName }
        val businessType = profile.businessType.name

        // Try peer comparison first
        val peerProof = peerComparison.getQuickSocialProof(location, businessType, language)
        if (peerProof != null) {
            return SocialResponse(
                success = true,
                message = peerProof.message,
                data = mapOf("type" to peerProof.type.name)
            )
        }

        // Try leaderboard position
        val leaderboardProof = leaderboardService.getQuickPositionMessage(location, businessType, language)
        if (leaderboardProof != null) {
            return SocialResponse(
                success = true,
                message = leaderboardProof.message,
                data = mapOf("type" to leaderboardProof.type.name)
            )
        }

        // Try community tip
        val tip = communityTips.getBriefingTip(profile, language)
        if (tip != null) {
            return SocialResponse(
                success = true,
                message = tip,
                data = mapOf("type" to "COMMUNITY_TIP")
            )
        }

        return SocialResponse(
            success = false,
            message = if (language == "sw") {
                "Hakuna taarifa za kijamii bado. Endelea kurekodi mauzo yako!"
            } else {
                "No social data yet. Keep recording your sales!"
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun getBusinessTypeLabel(businessType: WorkerType, language: String): String {
        return if (language == "sw") {
            when (businessType) {
                WorkerType.TRADER -> "Wafanyabiashara"
                WorkerType.TRANSPORT -> "Wabebaji"
                WorkerType.FARMER -> "Wakulima"
                WorkerType.SERVICE -> "Watoa huduma"
                WorkerType.MANUFACTURING -> "Watengenezaji"
                WorkerType.DIGITAL -> "Wakala wa kidijitali"
                WorkerType.UNKNOWN -> "Wafanyabiashara"
            }
        } else {
            when (businessType) {
                WorkerType.TRADER -> "Traders"
                WorkerType.TRANSPORT -> "Transporters"
                WorkerType.FARMER -> "Farmers"
                WorkerType.SERVICE -> "Service providers"
                WorkerType.MANUFACTURING -> "Manufacturers"
                WorkerType.DIGITAL -> "Digital agents"
                WorkerType.UNKNOWN -> "Businesses"
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES & ENUMS
// ═══════════════════════════════════════════════════════════════

/**
 * Social intents that can be routed through the Orchestrator.
 */
enum class SocialIntent {
    /** "Linganisha na wengine" — Compare with peers */
    CHECK_PEER_COMPARISON,

    /** "Niko nafasi gani?" — Check leaderboard position */
    CHECK_LEADERBOARD,

    /** "Nione ushauri" — View community tips */
    VIEW_COMMUNITY_TIPS,

    /** "Nataka kushiriki ushauri" — Submit a tip */
    SUBMIT_TIP,

    /** "Penda ushauri huo" — Upvote a tip */
    UPVOTE_TIP,

    /** "Niunge kwenye kikundi" — Join WhatsApp group */
    JOIN_WHATSAPP_GROUP,

    /** "Anzisha challenge" — Start a peer challenge */
    START_CHALLENGE,

    /** "Nione changamoto" — View active challenges */
    VIEW_CHALLENGES,

    /** "Nione taarifa za kijamii" — Get social proof for briefing */
    GET_SOCIAL_PROOF
}

/**
 * Response from a social intent handler.
 */
data class SocialResponse(
    val success: Boolean,
    val message: String,
    val data: Map<String, String> = emptyMap()
)
