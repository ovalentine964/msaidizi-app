package com.msaidizi.app.superagent.growth

import timber.log.Timber

/**
 * Referral Engine — viral growth through worker invitations.
 *
 * "Invite a friend, both get KES 50 airtime."
 * Workers invite other workers in their market/network.
 * Tracked via phone number referrals.
 *
 * @param contextEngine Worker context for personalization
 */
class ReferralEngine(
    private val contextEngine: Any? = null
) {
    companion object {
        private const val TAG = "ReferralEngine"
        private const val REFERRAL_REWARD_KES = 50
        private const val MAX_REFERRALS_PER_MONTH = 20
    }

    /**
     * Generate a referral link/code for the worker.
     */
    fun generateReferralCode(workerId: String): String {
        val code = "MSD-${workerId.takeLast(6).uppercase()}"
        Timber.d("$TAG: Generated referral code: $code")
        return code
    }

    /**
     * Process a referral when a new worker signs up.
     * Both referrer and referee get rewards.
     */
    fun processReferral(referrerId: String, refereePhone: String): ReferralResult {
        Timber.d("$TAG: Processing referral from $referrerId for $refereePhone")
        return ReferralResult(
            success = true,
            referrerReward = REFERRAL_REWARD_KES,
            refereeReward = REFERRAL_REWARD_KES,
            message = "Both earn KES $REFERRAL_REWARD_KES airtime!",
            messageSw = "Nyote mnopata KES $REFERRAL_REWARD_KES ya hewa!"
        )
    }

    /**
     * Get referral stats for a worker.
     */
    fun getReferralStats(workerId: String): ReferralStats {
        // In production, query database
        return ReferralStats(
            totalReferrals = 0,
            successfulReferrals = 0,
            totalEarned = 0,
            thisMonthReferrals = 0,
            remainingThisMonth = MAX_REFERRALS_PER_MONTH
        )
    }

    /**
     * Generate share message for WhatsApp/SMS.
     */
    fun getShareMessage(workerName: String, referralCode: String, language: String = "sw"): String {
        return if (language == "sw") {
            "Habari! Msaidizi wangu wa biashara umenisaidia sana. Jisajili na code yangu: $referralCode. " +
            "Pata KES $REFERRAL_REWARD_KES ya bure! Pakua: https://angavu.ai/download"
        } else {
            "Hey! Msaidizi business assistant has helped me a lot. Sign up with my code: $referralCode. " +
            "Get KES $REFERRAL_REWARD_KES free! Download: https://angavu.ai/download"
        }
    }
}

data class ReferralResult(
    val success: Boolean,
    val referrerReward: Int,
    val refereeReward: Int,
    val message: String,
    val messageSw: String
)

data class ReferralStats(
    val totalReferrals: Int,
    val successfulReferrals: Int,
    val totalEarned: Int,
    val thisMonthReferrals: Int,
    val remainingThisMonth: Int
)
