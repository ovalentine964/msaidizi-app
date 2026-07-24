package com.msaidizi.app.superagent.growth

import timber.log.Timber

/**
 * Daily Briefing Engine — morning insights to retain workers.
 *
 * "Good morning! Yesterday you made KES 2,300. Here's your tip."
 * Delivered via voice every morning to keep workers engaged.
 */
class DailyBriefingEngine {
    companion object {
        private const val TAG = "DailyBriefingEngine"
    }

    /**
     * Generate morning briefing for a worker.
     */
    fun generateBriefing(
        workerName: String,
        yesterdayRevenue: Double,
        yesterdayProfit: Double,
        currentStreak: Int,
        language: String = "sw"
    ): Briefing {
        val streakMsg = if (currentStreak > 0) {
            if (language == "sw") " Streak yako ni siku $currentStreak!" else " Your streak is $currentStreak days!"
        } else ""

        val tip = getDailyTip(language)

        val text = if (language == "sw") {
            "Habari za asubuhi $workerName! " +
            "Jana ulipata KES ${"%,.0f".format(yesterdayRevenue)}, faida KES ${"%,.0f".format(yesterdayProfit)}. " +
            "$streakMsg $tip"
        } else {
            "Good morning $workerName! " +
            "Yesterday you made KES ${"%,.0f".format(yesterdayRevenue)}, profit KES ${"%,.0f".format(yesterdayProfit)}. " +
            "$streakMsg $tip"
        }

        return Briefing(
            text = text,
            shouldSpeak = true,
            revenue = yesterdayRevenue,
            profit = yesterdayProfit,
            streak = currentStreak
        )
    }

    private fun getDailyTip(language: String): String {
        val tipsSw = listOf(
            "Jaribu kununua bidaa za jioni — bei ni ya chini zaidi.",
            "Andika kila mauzo — hii itasaidia biashara yako kukua.",
            "Angalia bidaa zako — ni zipi zinazouzwa zaidi?",
            "Okoa pesa kidogo kila siku — itakusaidia baadaye.",
            "Angalia bei za wauzaji wengine — usiuze bei ya chini sana."
        )
        val tipsEn = listOf(
            "Try buying stock in the evening — prices are lower then.",
            "Record every sale — this helps your business grow.",
            "Check your inventory — which products sell fastest?",
            "Save a little every day — it will help you later.",
            "Check other vendors' prices — don't sell too cheap."
        )
        return if (language == "sw") tipsSw.random() else tipsEn.random()
    }
}

data class Briefing(
    val text: String,
    val shouldSpeak: Boolean,
    val revenue: Double,
    val profit: Double,
    val streak: Int
)
