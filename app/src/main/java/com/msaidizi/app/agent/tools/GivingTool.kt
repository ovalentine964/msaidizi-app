package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.GivingDao
import com.msaidizi.app.data.entity.GivingEntity

/**
 * GivingTool — Track tithes, zakat, and charitable giving.
 */
class GivingTool(
    private val givingDao: GivingDao
) : Tool {
    override val name = "giving"
    override val description = "Toleo/Sadaka — Track charitable giving"
    override val supportedIntents = listOf("giving", "tithe", "zakat")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult(
                text = if (language == "sw") "Umetoa pesa ngapi? Sema: Nimetoa sadaka 200"
                else "How much did you give? Say: Gave tithe 200",
                data = emptyMap(), success = false, errorCode = "MISSING_AMOUNT"
            )

        val type = args["type"]?.toString() ?: args["item"]?.toString() ?: "sadaka"
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        val giving = GivingEntity(
            type = type,
            amount = amount,
            recipient = args["recipient"]?.toString() ?: "",
            workerId = workerId
        )
        givingDao.insert(giving)

        // Calculate total giving this month
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        val monthlyTotal = givingDao.getTotalGiving(workerId, monthStart, System.currentTimeMillis()) ?: 0.0

        return ToolResult(
            text = if (language == "sw") {
                "🤲 $type imerekodiwa: KSh ${"%,.0f".format(amount)}. Jumla ya mwezi huu: KSh ${"%,.0f".format(monthlyTotal)}. Mungu awabariki! 🙏"
            } else {
                "🤲 $type recorded: KSh ${"%,.0f".format(amount)}. This month's total: KSh ${"%,.0f".format(monthlyTotal)}. God bless! 🙏"
            },
            data = mapOf(
                "type" to type,
                "amount" to amount.toString(),
                "monthlyTotal" to monthlyTotal.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
