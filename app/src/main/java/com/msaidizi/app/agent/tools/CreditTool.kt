package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao

/**
 * CreditTool — Credit scoring and loan readiness (Alama Score).
 * Currently placeholder; will integrate with Alama Score API.
 */
class CreditTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "credit"
    override val description = "Alama — Credit scoring"
    override val supportedIntents = listOf("credit_score", "loan_apply", "loan_status")
    override val memoryRequiredMB = 10

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        // Calculate basic credit indicators from transaction history
        val totalSales = transactionDao.getAllTimeSales(workerId) ?: 0.0
        val transactionCount = transactionDao.getByWorker(workerId).size

        // Simple scoring heuristic (real Alama Score will use ML model)
        val score = when {
            transactionCount >= 100 && totalSales >= 100_000 -> 750
            transactionCount >= 50 && totalSales >= 50_000 -> 650
            transactionCount >= 20 && totalSales >= 10_000 -> 550
            transactionCount >= 5 -> 450
            else -> 350
        }

        val rating = when {
            score >= 700 -> if (language == "sw") "Nzuri sana!" else "Excellent!"
            score >= 600 -> if (language == "sw") "Nzuri" else "Good"
            score >= 500 -> if (language == "sw") "Wastani" else "Fair"
            else -> if (language == "sw") "Inahitaji kuboreshwa" else "Needs improvement"
        }

        return ToolResult(
            text = if (language == "sw") {
                buildString {
                    append("📊 Alama Score yako: $score ($rating)\n\n")
                    append("📈 Viashiria:\n")
                    append("• Miamala: $transactionCount\n")
                    append("• Jumla ya mauzo: KSh ${"%,.0f".format(totalSales)}\n\n")
                    append("💡 Ongeza miamala kuboresha alama yako!")
                }
            } else {
                buildString {
                    append("📊 Your Alama Score: $score ($rating)\n\n")
                    append("📈 Indicators:\n")
                    append("• Transactions: $transactionCount\n")
                    append("• Total sales: KSh ${"%,.0f".format(totalSales)}\n\n")
                    append("💡 Record more transactions to improve your score!")
                }
            },
            data = mapOf(
                "score" to score.toString(),
                "transactionCount" to transactionCount.toString(),
                "totalSales" to totalSales.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
