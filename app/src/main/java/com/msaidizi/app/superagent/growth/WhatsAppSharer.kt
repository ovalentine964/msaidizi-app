package com.msaidizi.app.superagent.growth

import timber.log.Timber

/**
 * WhatsApp Sharer — viral sharing through WhatsApp.
 *
 * Workers share business reports, achievements, and referral links
 * via WhatsApp (where informal workers already communicate).
 */
class WhatsAppSharer {
    companion object {
        private const val TAG = "WhatsAppSharer"
    }

    /**
     * Generate a business report share message.
     */
    fun generateReportShare(
        workerName: String,
        weeklyRevenue: Double,
        weeklyProfit: Double,
        profitMargin: Double,
        language: String = "sw"
    ): String {
        return if (language == "sw") {
            "📊 Ripoti ya biashara ya $workerName wiki hii:\n" +
            "💰 Mapato: KES ${"%,.0f".format(weeklyRevenue)}\n" +
            "📈 Faida: KES ${"%,.0f".format(weeklyProfit)} (${profitMargin.toInt()}%)\n" +
            "\nJifunze biashara yako na Msaidizi! Pakua: https://angavu.ai/download"
        } else {
            "📊 $workerName's business report this week:\n" +
            "💰 Revenue: KES ${"%,.0f".format(weeklyRevenue)}\n" +
            "📈 Profit: KES ${"%,.0f".format(weeklyProfit)} (${profitMargin.toInt()}%)\n" +
            "\nLearn your business with Msaidizi! Download: https://angavu.ai/download"
        }
    }

    /**
     * Generate achievement share message.
     */
    fun generateAchievementShare(
        achievement: String,
        language: String = "sw"
    ): String {
        return if (language == "sw") {
            "🏆 Nimepata '$achievement' kwenye Msaidizi! App nzuri ya biashara. Pakua: https://angavu.ai/download"
        } else {
            "🏆 I earned '$achievement' on Msaidizi! Great business app. Download: https://angavu.ai/download"
        }
    }

    /**
     * Generate buying group invite.
     */
    fun generateGroupInvite(
        groupName: String,
        memberCount: Int,
        language: String = "sw"
    ): String {
        return if (language == "sw") {
            "🛒 Jiunge na '$groupName' kwenye Msaidizi! Wanachama $memberCount tayari. " +
            "Nunua pamoja, okoa pesa. Pakua: https://angavu.ai/download"
        } else {
            "🛒 Join '$groupName' on Msaidizi! $memberCount members already. " +
            "Buy together, save money. Download: https://angavu.ai/download"
        }
    }
}
