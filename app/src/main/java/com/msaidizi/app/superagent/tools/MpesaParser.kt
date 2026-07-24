package com.msaidizi.app.superagent.tools
import javax.inject.Inject
data class MpesaTransaction(val amount: Double, val sender: String, val type: String, val reference: String)
class MpesaParser @Inject constructor() {
    private val patterns = listOf(
        Regex("Ksh([\\d,.]+) received from (.+?) ") to "received",
        Regex("Ksh([\\d,.]+) sent to (.+?) ") to "sent"
    )
    fun parse(sms: String): ToolResult {
        for ((pattern, type) in patterns) {
            pattern.find(sms)?.let {
                val amount = it.groupValues[1].replace(",", "").toDouble()
                return ToolResult.Success(MpesaTransaction(amount, it.groupValues[2], type, sms.take(20)).toString())
            }
        }
        return ToolResult.Error("Could not parse M-Pesa SMS")
    }
}
