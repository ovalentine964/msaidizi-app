package com.msaidizi.app.agent.tools

/**
 * MpesaTool — Parse M-Pesa SMS messages and record transactions.
 * M-Pesa sends SMS like: "Confirmed. Ksh500.00 sent to JOHN DOE 0712345678.
 *   on 24/7/26 at 10:30 AM. New M-PESA balance is Ksh1,234.00.
 *   Transaction cost Ksh0.00. ..."
 */
class MpesaTool(
    private val transactionTool: RecordSaleTool
) : Tool {
    override val name = "mpesa"
    override val description = "M-Pesa — Parse M-Pesa SMS and record"
    override val supportedIntents = listOf("mpesa", "mpesa_transaction")
    override val memoryRequiredMB = 5

    // M-Pesa SMS patterns
    private val sendPattern = Regex(
        "Confirmed\\.\\s*Ksh([\\d,]+\\.\\d{2})\\s*sent to\\s+(.+?)\\s+(\\d{10})",
        RegexOption.IGNORE_CASE
    )
    private val receivePattern = Regex(
        "Confirmed\\.\\s*Ksh([\\d,]+\\.\\d{2})\\s*received from\\s+(.+?)\\s+(\\d{10})",
        RegexOption.IGNORE_CASE
    )
    private val payPattern = Regex(
        "Confirmed\\.\\s*Ksh([\\d,]+\\.\\d{2})\\s*paid to\\s+(.+?)",
        RegexOption.IGNORE_CASE
    )
    private val balancePattern = Regex(
        "M-PESA balance is Ksh([\\d,]+\\.\\d{2})",
        RegexOption.IGNORE_CASE
    )
    private val codePattern = Regex("([A-Z0-9]{10})")
    private val costPattern = Regex(
        "Transaction cost Ksh([\\d,]+\\.\\d{2})",
        RegexOption.IGNORE_CASE
    )

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val message = args["message"]?.toString() ?: args["input"]?.toString() ?: ""
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        if (message.isBlank()) {
            return ToolResult(
                text = if (language == "sw") {
                    "📱 Nitumie ujumbe wa M-Pesa nitakao urekebisha. Mfano:\n\"Confirmed. Ksh500.00 sent to JOHN DOE 0712345678...\""
                } else {
                    "📱 Send me the M-Pesa SMS message to parse. Example:\n\"Confirmed. Ksh500.00 sent to JOHN DOE 0712345678...\""
                },
                data = emptyMap(), success = true
            )
        }

        // Parse the SMS
        val transactionType: String
        val amount: Double
        val recipient: String

        when {
            sendPattern.containsMatchIn(message) -> {
                val match = sendPattern.find(message)!!
                amount = match.groupValues[1].replace(",", "").toDouble()
                recipient = match.groupValues[2].trim()
                transactionType = "sent"
            }
            receivePattern.containsMatchIn(message) -> {
                val match = receivePattern.find(message)!!
                amount = match.groupValues[1].replace(",", "").toDouble()
                recipient = match.groupValues[2].trim()
                transactionType = "received"
            }
            payPattern.containsMatchIn(message) -> {
                val match = payPattern.find(message)!!
                amount = match.groupValues[1].replace(",", "").toDouble()
                recipient = match.groupValues[2].trim()
                transactionType = "paid"
            }
            else -> {
                // Try to extract just an amount
                val amountMatch = Regex("Ksh([\\d,]+\\.\\d{2})").find(message)
                if (amountMatch != null) {
                    amount = amountMatch.groupValues[1].replace(",", "").toDouble()
                    recipient = "unknown"
                    transactionType = "unknown"
                } else {
                    return ToolResult(
                        text = if (language == "sw") {
                            "❌ Sijaweza kusoma ujumbe wa M-Pesa. Tafadhali hakiki na urekebishe."
                        } else {
                            "❌ Could not parse M-Pesa message. Please verify and correct."
                        },
                        data = emptyMap(), success = false, errorCode = "PARSE_FAILED"
                    )
                }
            }
        }

        val code = codePattern.find(message)?.value
        val balance = balancePattern.find(message)?.value?.replace(",", "")?.toDoubleOrNull()
        val cost = costPattern.find(message)?.value?.replace(",", "")?.toDoubleOrNull()

        // Determine if this is income or expense
        val isIncome = transactionType == "received"
        val recordType = if (isIncome) "SALE" else "EXPENSE"
        val itemName = when (transactionType) {
            "sent" -> "M-Pesa: Tuma kwa $recipient"
            "received" -> "M-Pesa: Pokea kutoka $recipient"
            "paid" -> "M-Pesa: Lipa $recipient"
            else -> "M-Pesa: $recipient"
        }

        // Record as transaction
        val result = transactionTool.execute(
            mapOf(
                "amount" to amount,
                "item" to itemName,
                "type" to recordType,
                "mpesaCode" to (code ?: ""),
                "workerId" to workerId
            ),
            language
        )

        val balanceMsg = if (balance != null) {
            if (language == "sw") "\nSalio la M-Pesa: KSh ${"%,.0f".format(balance)}"
            else "\nM-Pesa balance: KSh ${"%,.0f".format(balance)}"
        } else ""

        val costMsg = if (cost != null && cost > 0) {
            if (language == "sw") "\nGharama ya muamala: KSh ${"%,.0f".format(cost)}"
            else "\nTransaction cost: KSh ${"%,.0f".format(cost)}"
        } else ""

        return ToolResult(
            text = if (language == "sw") {
                "📱 M-Pesa ${when(transactionType) {
                    "sent" -> "imetumwa"
                    "received" -> "imepokelewa"
                    "paid" -> "imelipwa"
                    else -> "imerekodiwa"
                }}: KSh ${"%,.0f".format(amount)} ${when(transactionType) {
                    "sent" -> "kwa"
                    "received" -> "kutoka"
                    "paid" -> "kwa"
                    else -> "kwa"
                }} $recipient${if (code != null) " ($code)" else ""}$balanceMsg$costMsg"
            } else {
                "📱 M-Pesa ${when(transactionType) {
                    "sent" -> "sent"
                    "received" -> "received"
                    "paid" -> "paid"
                    else -> "recorded"
                }}: KSh ${"%,.0f".format(amount)} ${when(transactionType) {
                    "sent" -> "to"
                    "received" -> "from"
                    "paid" -> "to"
                    else -> "for"
                }} $recipient${if (code != null) " ($code)" else ""}$balanceMsg$costMsg"
            },
            data = mapOf(
                "mpesaType" to transactionType,
                "amount" to amount.toString(),
                "recipient" to recipient,
                "code" to (code ?: ""),
                "balance" to (balance?.toString() ?: ""),
                "cost" to (cost?.toString() ?: "0")
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
