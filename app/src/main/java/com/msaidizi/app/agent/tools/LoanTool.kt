package com.msaidizi.app.agent.tools

import com.msaidizi.app.data.dao.LoanDao
import com.msaidizi.app.data.entity.LoanEntity

/**
 * LoanTool — Track loans and repayments.
 */
class LoanTool(
    private val loanDao: LoanDao
) : Tool {
    override val name = "loan"
    override val description = "Mikopo — Track loans and repayments"
    override val supportedIntents = listOf("loan_record", "loan_check", "loan_repayment", "loan_report")
    override val memoryRequiredMB = 5

    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val action = args["action"]?.toString()
            ?: if (args.containsKey("amount")) "record" else "check"

        return when (action) {
            "record" -> recordLoan(args, language)
            "repay" -> recordRepayment(args, language)
            "check" -> checkLoans(args, language)
            else -> checkLoans(args, language)
        }
    }

    private suspend fun recordLoan(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult(
                text = if (language == "sw") "Mkopo ni pesa ngapi?" else "How much is the loan?",
                data = emptyMap(), success = false, errorCode = "MISSING_AMOUNT"
            )

        val lender = args["lender"]?.toString() ?: args["item"]?.toString() ?: "mkopaji"
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")

        val loan = LoanEntity(
            lender = lender,
            amount = amount,
            remainingAmount = amount,
            interestRate = (args["interest"] as? Number)?.toDouble() ?: 0.0,
            workerId = workerId
        )
        val id = loanDao.insert(loan)

        return ToolResult(
            text = if (language == "sw") {
                "💳 Mkopo umerekodiwa: KSh ${"%,.0f".format(amount)} kutoka $lender."
            } else {
                "💳 Loan recorded: KSh ${"%,.0f".format(amount)} from $lender."
            },
            data = mapOf("loanId" to id.toString(), "amount" to amount.toString(), "lender" to lender),
            success = true
        )
    }

    private suspend fun recordRepayment(args: Map<String, Any>, language: String): ToolResult {
        val amount = (args["amount"] as? Number)?.toDouble()
            ?: args["amount"]?.toString()?.toDoubleOrNull()
            ?: return ToolResult(
                text = if (language == "sw") "Umelipa pesa ngapi?" else "How much did you repay?",
                data = emptyMap(), success = false, errorCode = "MISSING_AMOUNT"
            )

        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val activeLoans = loanDao.getActiveLoans(workerId)

        if (activeLoans.isEmpty()) {
            return ToolResult(
                text = if (language == "sw") "Huna mkopo wowote unaosubiri." else "You have no outstanding loans.",
                data = emptyMap(), success = true
            )
        }

        // Apply to first active loan (or specific one if lender matches)
        val targetLoan = args["lender"]?.toString()?.let { lender ->
            activeLoans.find { it.lender.contains(lender, ignoreCase = true) }
        } ?: activeLoans.first()

        loanDao.recordRepayment(targetLoan.id, amount)
        val updatedLoan = loanDao.getById(targetLoan.id)

        val remaining = updatedLoan?.remainingAmount ?: 0.0
        val paidOff = remaining <= 0

        if (paidOff) {
            loanDao.markPaid(targetLoan.id)
        }

        return ToolResult(
            text = if (language == "sw") {
                if (paidOff) "✅ Mkopo wa ${targetLoan.lender} umelipwa kabisa! Hongera! 🎉"
                else "✅ Umelipa KSh ${"%,.0f".format(amount)} kwa ${targetLoan.lender}. Baki: KSh ${"%,.0f".format(remaining)}"
            } else {
                if (paidOff) "✅ Loan from ${targetLoan.lender} fully paid off! Congratulations! 🎉"
                else "✅ Paid KSh ${"%,.0f".format(amount)} to ${targetLoan.lender}. Remaining: KSh ${"%,.0f".format(remaining)}"
            },
            data = mapOf(
                "loanId" to targetLoan.id.toString(),
                "paid" to amount.toString(),
                "remaining" to remaining.toString(),
                "paidOff" to paidOff.toString()
            ),
            success = true
        )
    }

    private suspend fun checkLoans(args: Map<String, Any>, language: String): ToolResult {
        val workerId = args["workerId"]?.toString() ?: throw IllegalStateException("workerId must be provided by SuperAgent")
        val activeLoans = loanDao.getActiveLoans(workerId)
        val totalOutstanding = loanDao.getTotalOutstanding(workerId) ?: 0.0

        if (activeLoans.isEmpty()) {
            return ToolResult(
                text = if (language == "sw") {
                    "💳 Huna mikopo inayosubiri. Hongera! 🎉"
                } else {
                    "💳 No outstanding loans. Congratulations! 🎉"
                },
                data = emptyMap(), success = true
            )
        }

        val loansText = activeLoans.joinToString("\n") { loan ->
            val progress = if (loan.amount > 0) {
                (((loan.amount - loan.remainingAmount) / loan.amount) * 100).toInt()
            } else 100
            if (language == "sw") {
                "💳 ${loan.lender}: Baki KSh ${"%,.0f".format(loan.remainingAmount)} / ${"%,.0f".format(loan.amount)} ($progress% imelipwa)"
            } else {
                "💳 ${loan.lender}: Remaining KSh ${"%,.0f".format(loan.remainingAmount)} / ${"%,.0f".format(loan.amount)} ($progress% paid)"
            }
        }

        return ToolResult(
            text = if (language == "sw") {
                "📋 Mikopo yako:\n$loansText\n\n💰 Jumla ya deni: KSh ${"%,.0f".format(totalOutstanding)}"
            } else {
                "📋 Your loans:\n$loansText\n\n💰 Total outstanding: KSh ${"%,.0f".format(totalOutstanding)}"
            },
            data = mapOf(
                "activeLoans" to activeLoans.size.toString(),
                "totalOutstanding" to totalOutstanding.toString()
            ),
            success = true
        )
    }

    override fun onLowMemory() {}
}
