package com.msaidizi.app.superagent.tools
import javax.inject.Inject

data class Report(val type: String, val content: String, val recipientPhone: String?)

class WhatsAppReporter @Inject constructor() {
    fun generateDailyReport(revenue: Double, expenses: Double, profit: Double, topProduct: String): Report {
        val content = buildString {
            appendLine("📊 *Msaidizi CFO — Daily Report*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("💰 Revenue: KES ${"%,.0f".format(revenue)}")
            appendLine("📉 Expenses: KES ${"%,.0f".format(expenses)}")
            appendLine("✅ Profit: KES ${"%,.0f".format(profit)}")
            appendLine("🏆 Top: $topProduct")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("Keep going! 💪")
        }
        return Report("daily", content, null)
    }

    fun generateWeeklyReport(totalRevenue: Double, totalExpenses: Double, totalProfit: Double, bestDay: String): Report {
        val content = buildString {
            appendLine("📊 *Msaidizi CFO — Weekly Report*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("💰 Total Revenue: KES ${"%,.0f".format(totalRevenue)}")
            appendLine("📉 Total Expenses: KES ${"%,.0f".format(totalExpenses)}")
            appendLine("✅ Total Profit: KES ${"%,.0f".format(totalProfit)}")
            appendLine("🏆 Best Day: $bestDay")
            appendLine("━━━━━━━━━━━━━━━━")
        }
        return Report("weekly", content, null)
    }

    fun sendViaWhatsApp(report: Report): ToolResult {
        // In production, integrate with WhatsApp Business API
        return ToolResult.Success("Report sent: ${report.type}")
    }
}
