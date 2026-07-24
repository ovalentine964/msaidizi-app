package com.msaidizi.app.superagent.communication

import timber.log.Timber

/**
 * Report Voice — voice-based financial reports.
 *
 * ## Report Types
 * - **DAILY_SUMMARY** — End-of-day financial summary
 * - **WEEKLY_REPORT** — Weekly performance report
 * - **MONTHLY_REPORT** — Monthly business analysis
 * - **PROFIT_REPORT** — Profit-focused report
 * - **STOCK_REPORT** — Inventory status report
 * - **STREAK_REPORT** — Streak and gamification report
 *
 * ## Design
 * Reports are voice-first, structured for natural speech.
 * Numbers are spelled out for clarity ("mia mbili" not "200").
 * Each report follows: Summary → Details → Insights → Action items.
 *
 * @param voicePersonality For wrapping reports with warmth
 * @param dialectOutput For dialect-specific number formatting
 */
class ReportVoice(
    private val voicePersonality: VoicePersonality,
    private val dialectOutput: DialectOutput
) {
    companion object {
        private const val TAG = "ReportVoice"
    }

    /**
     * Generate a voice report.
     *
     * @param report The report data
     * @param language "sw" or "en"
     * @return Voice-ready report text
     */
    fun generateReport(report: FinancialReport, language: String = "sw"): String {
        val baseText = when (report.type) {
            ReportType.DAILY_SUMMARY -> generateDailySummary(report, language)
            ReportType.WEEKLY_REPORT -> generateWeeklyReport(report, language)
            ReportType.MONTHLY_REPORT -> generateMonthlyReport(report, language)
            ReportType.PROFIT_REPORT -> generateProfitReport(report, language)
            ReportType.STOCK_REPORT -> generateStockReport(report, language)
            ReportType.STREAK_REPORT -> generateStreakReport(report, language)
        }

        return voicePersonality.wrapResponse(baseText, ResponseType.INFORMATION, language = language)
    }

    // ═══════════════ REPORT GENERATORS ═══════════════

    private fun generateDailySummary(report: FinancialReport, language: String): String {
        val sales = report.data["sales"]?.toIntOrNull() ?: 0
        val revenue = report.data["revenue"]?.toDoubleOrNull() ?: 0.0
        val expenses = report.data["expenses"]?.toDoubleOrNull() ?: 0.0
        val profit = report.data["profit"]?.toDoubleOrNull() ?: 0.0

        return if (language == "sw") {
            buildString {
                append("📊 Muhtasari wa leo:\n")
                append("Mauzo: $sales. ")
                append("Mapato: KSh ${dialectOutput.formatNumber(revenue)}. ")
                append("Gharama: KSh ${dialectOutput.formatNumber(expenses)}. ")
                append("Faida: KSh ${dialectOutput.formatNumber(profit)}. ")

                when {
                    profit > revenue * 0.3 -> append("\n\n🚀 Faida nzuri sana! Biashara yako inafanya vizuri!")
                    profit > 0 -> append("\n\n✅ Faida ni chanya. Endelea kuboresha!")
                    else -> append("\n\n💪 Faida ni hasara leo. Angalia gharama zako na bei zako.")
                }
            }
        } else {
            buildString {
                append("📊 Today's summary:\n")
                append("Sales: $sales. ")
                append("Revenue: KSh ${"%.0f".format(revenue)}. ")
                append("Expenses: KSh ${"%.0f".format(expenses)}. ")
                append("Profit: KSh ${"%.0f".format(profit)}. ")

                when {
                    profit > revenue * 0.3 -> append("\n\n🚀 Great profit! Your business is doing well!")
                    profit > 0 -> append("\n\n✅ Profit is positive. Keep improving!")
                    else -> append("\n\n💪 Loss today. Check your costs and prices.")
                }
            }
        }
    }

    private fun generateWeeklyReport(report: FinancialReport, language: String): String {
        val totalSales = report.data["totalSales"]?.toIntOrNull() ?: 0
        val totalRevenue = report.data["totalRevenue"]?.toDoubleOrNull() ?: 0.0
        val totalProfit = report.data["totalProfit"]?.toDoubleOrNull() ?: 0.0
        val avgDailyProfit = report.data["avgDailyProfit"]?.toDoubleOrNull() ?: 0.0
        val bestDay = report.data["bestDay"] ?: ""
        val topItem = report.data["topItem"] ?: ""

        return if (language == "sw") {
            buildString {
                append("📊 Ripoti ya wiki:\n\n")
                append("Jumla ya mauzo: $totalSales. ")
                append("Jumla ya mapato: KSh ${dialectOutput.formatNumber(totalRevenue)}. ")
                append("Jumla ya faida: KSh ${dialectOutput.formatNumber(totalProfit)}. ")
                append("Faida ya wastani kwa siku: KSh ${dialectOutput.formatNumber(avgDailyProfit)}.\n")

                if (bestDay.isNotBlank()) append("\nSiku bora: $bestDay. ")
                if (topItem.isNotBlank()) append("Bidhaa bora: $topItem. ")

                append("\n\n")
                when {
                    totalProfit > avgDailyProfit * 7 * 1.2 -> append("🏆 Wiki nzuri! Faida yako ni juu ya wastani!")
                    totalProfit > 0 -> append("✅ Wiki imeenda vizuri. Endelea kuboresha!")
                    else -> append("💪 Wiki ilikuwa ngumu. Kesho ni mwanzo mpya!")
                }
            }
        } else {
            buildString {
                append("📊 Weekly report:\n\n")
                append("Total sales: $totalSales. ")
                append("Total revenue: KSh ${"%.0f".format(totalRevenue)}. ")
                append("Total profit: KSh ${"%.0f".format(totalProfit)}. ")
                append("Average daily profit: KSh ${"%.0f".format(avgDailyProfit)}.\n")

                if (bestDay.isNotBlank()) append("\nBest day: $bestDay. ")
                if (topItem.isNotBlank()) append("Top item: $topItem. ")

                append("\n\n")
                when {
                    totalProfit > avgDailyProfit * 7 * 1.2 -> append("🏆 Great week! Your profit is above average!")
                    totalProfit > 0 -> append("✅ Good week. Keep improving!")
                    else -> append("💪 Tough week. Tomorrow is a fresh start!")
                }
            }
        }
    }

    private fun generateMonthlyReport(report: FinancialReport, language: String): String {
        val totalSales = report.data["totalSales"]?.toIntOrNull() ?: 0
        val totalProfit = report.data["totalProfit"]?.toDoubleOrNull() ?: 0.0
        val savingsProgress = report.data["savingsProgress"]?.toDoubleOrNull() ?: 0.0
        val savingsGoal = report.data["savingsGoal"]?.toDoubleOrNull() ?: 0.0

        return if (language == "sw") {
            buildString {
                append("📊 Ripoti ya mwezi:\n\n")
                append("Jumla ya mauzo: $totalSales. ")
                append("Jumla ya faida: KSh ${dialectOutput.formatNumber(totalProfit)}.\n\n")

                if (savingsGoal > 0) {
                    val percent = ((savingsProgress / savingsGoal) * 100).toInt()
                    append("🏦 Akiba: KSh ${dialectOutput.formatNumber(savingsProgress)} ")
                    append("kati ya KSh ${dialectOutput.formatNumber(savingsGoal)} ($percent%). ")
                    when {
                        percent >= 100 -> append("🎉 Umefikia lengo la akiba!")
                        percent >= 75 -> append("Karibu sana! Endelea!")
                        else -> append("Endelea kuhifadhi!")
                    }
                }

                append("\n\n")
                when {
                    totalProfit > 0 -> append("✅ Mwezi mzuri! Biashara yako inakua!")
                    else -> append("💪 Mwezi ulikuwa ngumu. Jaribu mbinu mpya!")
                }
            }
        } else {
            buildString {
                append("📊 Monthly report:\n\n")
                append("Total sales: $totalSales. ")
                append("Total profit: KSh ${"%.0f".format(totalProfit)}.\n\n")

                if (savingsGoal > 0) {
                    val percent = ((savingsProgress / savingsGoal) * 100).toInt()
                    append("🏦 Savings: KSh ${"%.0f".format(savingsProgress)} ")
                    append("of KSh ${"%.0f".format(savingsGoal)} ($percent%). ")
                    when {
                        percent >= 100 -> append("🎉 Savings goal reached!")
                        percent >= 75 -> append("Almost there! Keep going!")
                        else -> append("Keep saving!")
                    }
                }

                append("\n\n")
                when {
                    totalProfit > 0 -> append("✅ Good month! Your business is growing!")
                    else -> append("💪 Tough month. Try new strategies!")
                }
            }
        }
    }

    private fun generateProfitReport(report: FinancialReport, language: String): String {
        val todayProfit = report.data["todayProfit"]?.toDoubleOrNull() ?: 0.0
        val weekProfit = report.data["weekProfit"]?.toDoubleOrNull() ?: 0.0
        val avgDaily = report.data["avgDaily"]?.toDoubleOrNull() ?: 0.0

        return if (language == "sw") {
            buildString {
                append("💰 Ripoti ya faida:\n\n")
                append("Leo: KSh ${dialectOutput.formatNumber(todayProfit)}. ")
                append("Wiki hii: KSh ${dialectOutput.formatNumber(weekProfit)}. ")
                append("Wastani wa siku: KSh ${dialectOutput.formatNumber(avgDaily)}.\n\n")

                when {
                    todayProfit > avgDaily * 1.5 -> append("🚀 Leo ni siku nzuri! Faida yako ni kubwa!")
                    todayProfit > avgDaily -> append("📈 Faida yako ni juu ya wastani!")
                    todayProfit > 0 -> append("✅ Faida ni chanya. Endelea!")
                    else -> append("💪 Leo faida ni hasara. Angalia gharama zako.")
                }
            }
        } else {
            buildString {
                append("💰 Profit report:\n\n")
                append("Today: KSh ${"%.0f".format(todayProfit)}. ")
                append("This week: KSh ${"%.0f".format(weekProfit)}. ")
                append("Daily average: KSh ${"%.0f".format(avgDaily)}.\n\n")

                when {
                    todayProfit > avgDaily * 1.5 -> append("🚀 Great day! Profit is high!")
                    todayProfit > avgDaily -> append("📈 Profit is above average!")
                    todayProfit > 0 -> append("✅ Profit is positive. Keep going!")
                    else -> append("💪 Loss today. Check your costs.")
                }
            }
        }
    }

    private fun generateStockReport(report: FinancialReport, language: String): String {
        val items = report.data["items"] ?: ""
        val lowStockItems = report.data["lowStockItems"] ?: ""

        return if (language == "sw") {
            buildString {
                append("📦 Ripoti ya stock:\n\n")
                if (items.isNotBlank()) append("Bidhaa: $items.\n")
                if (lowStockItems.isNotBlank()) {
                    append("⚠️ Bidhaa zinazokaribia kuisha: $lowStockItems. Nunua leo!")
                } else {
                    append("✅ Stock yako iko vizuri!")
                }
            }
        } else {
            buildString {
                append("📦 Stock report:\n\n")
                if (items.isNotBlank()) append("Items: $items.\n")
                if (lowStockItems.isNotBlank()) {
                    append("⚠️ Low stock: $lowStockItems. Buy today!")
                } else {
                    append("✅ Your stock is in good shape!")
                }
            }
        }
    }

    private fun generateStreakReport(report: FinancialReport, language: String): String {
        val currentStreak = report.data["currentStreak"]?.toIntOrNull() ?: 0
        val longestStreak = report.data["longestStreak"]?.toIntOrNull() ?: 0
        val badges = report.data["badges"]?.toIntOrNull() ?: 0

        return if (language == "sw") {
            buildString {
                append("🔥 Ripoti ya streak:\n\n")
                append("Streak ya sasa: siku $currentStreak. ")
                append("Rekodi bora: siku $longestStreak. ")
                append("Badge: $badges.\n\n")

                when {
                    currentStreak >= 30 -> append("👑 Mwezi mzima! Wewe ni mfano wa uthabiti!")
                    currentStreak >= 7 -> append("⚔️ Wiki nzuri! Endelea kulinda streak yako!")
                    currentStreak >= 3 -> append("🛡️ Siku tatu mfululizo! Anza vizuri!")
                    else -> append("Anza streak yako leo! Kila siku ni muhimu!")
                }
            }
        } else {
            buildString {
                append("🔥 Streak report:\n\n")
                append("Current streak: $currentStreak days. ")
                append("Best: $longestStreak days. ")
                append("Badges: $badges.\n\n")

                when {
                    currentStreak >= 30 -> append("👑 Full month! You're a model of consistency!")
                    currentStreak >= 7 -> append("⚔️ Good week! Keep protecting your streak!")
                    currentStreak >= 3 -> append("🛡️ Three days in a row! Good start!")
                    else -> append("Start your streak today! Every day matters!")
                }
            }
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Report types.
 */
enum class ReportType {
    DAILY_SUMMARY, WEEKLY_REPORT, MONTHLY_REPORT,
    PROFIT_REPORT, STOCK_REPORT, STREAK_REPORT
}

/**
 * Financial report data.
 */
data class FinancialReport(
    val type: ReportType,
    val data: Map<String, String> = emptyMap(),
    val period: String = "" // "today", "this_week", "this_month"
)
