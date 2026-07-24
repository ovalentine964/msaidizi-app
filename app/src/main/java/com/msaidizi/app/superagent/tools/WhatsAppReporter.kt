package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class Report(val type: String, val content: String, val recipientPhone: String?)

/**
 * WhatsAppReporter — Generate and send business reports via WhatsApp.
 */
@Singleton
class WhatsAppReporter @Inject constructor() : Tool {

    override val name = "whatsapp_reporter"
    override val description = "Generate and send business reports via WhatsApp"

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "daily"
        return when (action.lowercase()) {
            "daily" -> {
                val revenue = params["revenue"]?.toDoubleOrNull() ?: 0.0
                val expenses = params["expenses"]?.toDoubleOrNull() ?: 0.0
                val profit = params["profit"]?.toDoubleOrNull() ?: (revenue - expenses)
                val topProduct = params["top_product"] ?: "N/A"
                val report = generateDailyReport(revenue, expenses, profit, topProduct)
                ToolResult.success(name, mapOf("report_type" to "daily", "content" to report.content), report.content)
            }
            "weekly" -> {
                val revenue = params["revenue"]?.toDoubleOrNull() ?: 0.0
                val expenses = params["expenses"]?.toDoubleOrNull() ?: 0.0
                val profit = params["profit"]?.toDoubleOrNull() ?: (revenue - expenses)
                val bestDay = params["best_day"] ?: "N/A"
                val report = generateWeeklyReport(revenue, expenses, profit, bestDay)
                ToolResult.success(name, mapOf("report_type" to "weekly", "content" to report.content), report.content)
            }
            "send" -> {
                val content = params["content"] ?: return ToolResult.error(name, "Report content required", "MISSING_CONTENT")
                val phone = params["phone"]
                val report = Report("custom", content, phone)
                sendViaWhatsApp(report)
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

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
        return ToolResult.success(name, mapOf("status" to "sent", "type" to report.type), "Report sent via WhatsApp: ${report.type}")
    }
}
