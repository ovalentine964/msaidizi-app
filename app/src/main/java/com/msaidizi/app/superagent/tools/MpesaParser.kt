package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class MpesaTransaction(val amount: Double, val sender: String, val type: String, val reference: String)

/**
 * MpesaParser — Parse M-Pesa SMS messages into structured transaction data.
 */
@Singleton
class MpesaParser @Inject constructor() : Tool {

    override val name = "mpesa_parser"
    override val description = "Parse M-Pesa SMS messages into structured transaction data"

    private val patterns = listOf(
        Regex("Ksh([\\d,.]+) received from (.+?) ") to "received",
        Regex("Ksh([\\d,.]+) sent to (.+?) ") to "sent"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "parse"
        return when (action.lowercase()) {
            "parse" -> {
                val sms = params["sms"]
                    ?: return ToolResult.error(name, "SMS text required", "MISSING_SMS")
                parse(sms)
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun parse(sms: String): ToolResult {
        for ((pattern, type) in patterns) {
            pattern.find(sms)?.let {
                val amount = it.groupValues[1].replace(",", "").toDouble()
                val transaction = MpesaTransaction(amount, it.groupValues[2], type, sms.take(20))
                return ToolResult.success(
                    name,
                    mapOf("amount" to amount, "sender" to transaction.sender, "type" to type, "reference" to transaction.reference),
                    "M-Pesa $type: Ksh ${"%,.0f".format(amount)} from ${transaction.sender}"
                )
            }
        }
        return ToolResult.error(name, "Could not parse M-Pesa SMS", "PARSE_ERROR")
    }
}
