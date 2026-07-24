package com.msaidizi.app.superagent.growth

import timber.log.Timber

/**
 * Viral Loop Manager — track and optimize the growth flywheel.
 *
 * Tracks: invites sent → installs → active users → more invites
 * K-factor = invites per user × conversion rate
 * K > 1 = viral growth
 */
class ViralLoopManager {
    companion object {
        private const val TAG = "ViralLoopManager"
    }

    /**
     * Calculate viral coefficient (K-factor).
     * K > 1 means viral growth (each user brings >1 new user).
     */
    fun calculateKFactor(
        totalUsers: Int,
        totalInvites: Int,
        successfulInstalls: Int
    ): ViralMetrics {
        if (totalUsers == 0) return ViralMetrics()

        val invitesPerUser = totalInvites.toDouble() / totalUsers
        val conversionRate = if (totalInvites > 0) successfulInstalls.toDouble() / totalInvites else 0.0
        val kFactor = invitesPerUser * conversionRate

        val growthType = when {
            kFactor >= 1.0 -> "viral" // Self-sustaining growth
            kFactor >= 0.5 -> "organic" // Growing but needs push
            else -> "paid" // Need paid acquisition
        }

        Timber.d("$TAG: K-factor = $kFactor ($growthType)")

        return ViralMetrics(
            kFactor = kFactor,
            invitesPerUser = invitesPerUser,
            conversionRate = conversionRate,
            growthType = growthType,
            totalUsers = totalUsers,
            totalInvites = totalInvites,
            successfulInstalls = successfulInstalls
        )
    }

    /**
     * Track a referral event.
     */
    fun trackReferral(referrerId: String, refereePhone: String, channel: String) {
        Timber.d("$TAG: Referral tracked — $referrerId → $refereePhone via $channel")
        // In production: save to database, update metrics
    }

    /**
     * Get growth recommendations based on K-factor.
     */
    fun getRecommendations(kFactor: Double, language: String = "sw"): List<String> {
        return if (language == "sw") {
            when {
                kFactor >= 1.0 -> listOf("Ukuaji wako ni mzuri! Endelea na hivi.")
                kFactor >= 0.5 -> listOf(
                    "Ongeza zawadi za rufaa — fanya watu wapate zaidi.",
                    "Shiriki ripoti za biashara WhatsApp — wengine wataona.",
                    "Ongeza mgao wa kijamii — onyesha watumiaji wengine."
                )
                else -> listOf(
                    "Anza na marafiki 5 wanaoaminika.",
                    "Shiriki ripoti yako ya wiki WhatsApp.",
                    "Ondoka sokoni ukisema kuhusu Msaidizi."
                )
            }
        } else {
            when {
                kFactor >= 1.0 -> listOf("Your growth is great! Keep going.")
                kFactor >= 0.5 -> listOf(
                    "Increase referral rewards — make people want to invite.",
                    "Share business reports on WhatsApp — others will see.",
                    "Add social proof — show other users."
                )
                else -> listOf(
                    "Start with 5 trusted friends.",
                    "Share your weekly report on WhatsApp.",
                    "Tell people about Msaidizi at the market."
                )
            }
        }
    }
}

data class ViralMetrics(
    val kFactor: Double = 0.0,
    val invitesPerUser: Double = 0.0,
    val conversionRate: Double = 0.0,
    val growthType: String = "paid",
    val totalUsers: Int = 0,
    val totalInvites: Int = 0,
    val successfulInstalls: Int = 0
)
