package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.TransactionDao

/**
 * CorrectionTool — Handle user corrections to previous transactions.
 */
class CorrectionTool(
    private val transactionDao: TransactionDao
) : Tool {
    override val name = "correction"
    override val description = "Sahihisha — Correct previous transaction"
    override val supportedIntents = listOf("correction")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val lastTransaction = transactionDao.getLastTransaction(workerId)

        if (lastTransaction == null) {
            return ToolResult(
                text = if (language == "sw") {
                    "Hakuna miamala ya hivi karibuni ya kurekebisha."
                } else {
                    "No recent transaction to correct."
                },
                data = emptyMap(), success = true
            )
        }

        // Check if there's a new amount to correct with
        val newAmount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
        val newItem = args["item"]?.toString()

        if (newAmount != null || newItem != null) {
            // Apply correction
            val updated = lastTransaction.copy(
                amount = newAmount ?: lastTransaction.amount,
                item = newItem ?: lastTransaction.item,
                unitPrice = if (newAmount != null && lastTransaction.quantity > 0) {
                    newAmount / lastTransaction.quantity
                } else lastTransaction.unitPrice
            )
            transactionDao.update(updated)

            // Determine correction type for L1→L3 signal
            val correctionType = when {
                newAmount != null && newItem != null -> "amount_and_item"
                newAmount != null -> "amount"
                newItem != null -> "item"
                else -> "other"
            }

            return ToolResult(
                text = if (language == "sw") {
                    "✅ Nimesahihisha: ${updated.item} — KSh ${"%,.0f".format(updated.amount)}. Asante kwa kunisaidia kujifunza!"
                } else {
                    "✅ Corrected: ${updated.item} — KSh ${"%,.0f".format(updated.amount)}. Thanks for helping me learn!"
                },
                data = mapOf(
                    "transactionId" to updated.id.toString(),
                    "oldAmount" to lastTransaction.amount.toString(),
                    "newAmount" to updated.amount.toString(),
                    "oldItem" to lastTransaction.item,
                    "newItem" to updated.item,
                    // L1→L3 correction signal metadata
                    "correction_applied" to "true",
                    "correction_type" to correctionType
                ),
                success = true
            )
        }

        // Ask what to correct
        return ToolResult(
            text = if (language == "sw") {
                "📝 Miamala ya mwisho: ${lastTransaction.item} — KSh ${"%,.0f".format(lastTransaction.amount)}\n\nNini sahihi? Sema:\n• \"Sio 500, ni 550\" — Rekebisha bei\n• \"Sio nyanya, ni vitunguu\" — Rekebisha bidhaa"
            } else {
                "📝 Last transaction: ${lastTransaction.item} — KSh ${"%,.0f".format(lastTransaction.amount)}\n\nWhat's wrong? Say:\n• \"Not 500, it's 550\" — Correct price\n• \"Not tomatoes, it's onions\" — Correct item"
            },
            data = mapOf(
                "lastTransactionId" to lastTransaction.id.toString(),
                "lastItem" to lastTransaction.item,
                "lastAmount" to lastTransaction.amount.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
