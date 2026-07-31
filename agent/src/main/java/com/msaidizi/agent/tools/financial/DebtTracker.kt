package com.msaidizi.agent.tools.financial

import com.msaidizi.core.database.CustomerDao
import com.msaidizi.core.database.DebtDao
import com.msaidizi.core.database.DebtRepaymentDao
import com.msaidizi.core.util.DateTimeUtil
import com.msaidizi.core.model.CustomerEntity
import com.msaidizi.core.model.DebtEntity
import com.msaidizi.core.model.DebtRepaymentEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * DebtTracker — Eliminates information asymmetry for informal workers.
 *
 * Workers lose thousands to bad debts because they give credit on trust
 * with no tracking. This tool makes debt management as simple as recording
 * a sale — voice-first, dead simple, always on.
 *
 * Features:
 *  1. Record debts via natural voice input ("Customer John owes KES 500")
 *  2. Track partial and full repayments
 *  3. Generate WhatsApp/SMS reminders for overdue debts
 *  4. Aging analysis: 30/60/90 day buckets
 *  5. Credit decisions based on customer history
 *  6. Full debt ledger and summaries
 *
 * Supports bilingual voice parsing (Kiswahili + English).
 */
@Singleton
class DebtTracker @Inject constructor(
    private val debtDao: DebtDao,
    private val repaymentDao: DebtRepaymentDao,
    private val customerDao: CustomerDao,
    private val gson: com.google.gson.Gson
) : Tool {

    override val name = "debt_tracker"
    override val description = "Track debts, record repayments, send reminders, aging analysis, and credit decisions. Voice: 'Customer John owes KES 500'"

    override val argsSchema = argSchema {
        enum("action", "Debt action to perform",
            listOf(
                "record",       // Record a new debt
                "repay",        // Record a repayment
                "remind",       // Generate reminder message for a customer
                "aging",        // 30/60/90 day aging report
                "credit_check", // Should I give credit to this customer?
                "list",         // List all active debts
                "customer",     // View debts for a specific customer
                "summary",      // Overall debt summary
                "write_off"     // Mark a debt as written off (bad debt)
            ), required = false)
        string("customer", "Customer name (supports voice input: 'John', 'Mama Njeri')", required = false)
        number("amount", "Debt amount in KES", required = false)
        string("product", "Product or service sold on credit", required = false)
        string("phone", "Customer phone number for WhatsApp/SMS reminders", required = false)
        integer("due_days", "Days until payment is due (default: 30)", required = false)
        string("notes", "Additional notes about the debt", required = false)
        string("payment_method", "Payment method: cash, mpesa, other", required = false)
        string("voice_input", "Raw voice text to parse (e.g. 'Customer John ananidai KES 500')", required = false)
        integer("debt_id", "Specific debt ID for repayments or actions", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "list"

        // Voice input parsing — if voice_input is provided, parse it first
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!)
        }

        return when (action.lowercase()) {
            "record" -> recordDebt(params)
            "repay" -> recordRepayment(params)
            "remind" -> generateReminder(params)
            "aging" -> getAgingReport()
            "credit_check" -> creditDecision(params)
            "list" -> listActiveDebts()
            "customer" -> viewCustomerDebts(params)
            "summary" -> debtSummary()
            "write_off" -> writeOffDebt(params)
            else -> ToolResult.error(name, "Unknown action: $action. Use: record, repay, remind, aging, credit_check, list, customer, summary, write_off", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // 1. RECORD DEBT
    // ──────────────────────────────────────────────

    /**
     * Record a new debt. Customer name + amount are required.
     * If customer doesn't exist in the database, creates them.
     * Optionally sets a due date (default: 30 days from now).
     */
    private suspend fun recordDebt(params: Map<String, String>): ToolResult {
        return try {
            val customerName = params["customer"]?.trim()
            val amountStr = params["amount"]
            val product = params["product"]?.trim() ?: "goods/services"
            val phone = params["phone"]?.trim()
            val dueDays = params["due_days"]?.toIntOrNull() ?: 30
            val notes = params["notes"]?.trim()

            // Validate required fields
            if (customerName.isNullOrBlank()) {
                return ToolResult.error(name, "Customer name is required. Example: customer='John'", "MISSING_CUSTOMER")
            }
            val amount = amountStr?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                return ToolResult.error(name, "Amount must be a positive number in KES. Example: amount=500", "INVALID_AMOUNT")
            }

            // Calculate due date
            val now = System.currentTimeMillis()
            val dueDate = now + TimeUnit.DAYS.toMillis(dueDays.toLong())

            // Create debt record
            val debt = DebtEntity(
                customerName = customerName,
                customerPhone = phone,
                amount = amount,
                outstandingBalance = amount,
                product = product,
                notes = notes,
                status = "active",
                dueDate = dueDate,
                createdAt = now,
                updatedAt = now
            )

            val debtId = debtDao.insert(debt)

            // Also ensure customer exists in the customer table
            val existingCustomers = customerDao.search(customerName).first()
            val exactMatch = existingCustomers.find {
                it.name.equals(customerName, ignoreCase = true)
            }
            if (exactMatch == null) {
                customerDao.insert(
                    CustomerEntity(
                        name = customerName,
                        phone = phone,
                        creditBalance = amount,
                        createdAt = now
                    )
                )
            } else {
                customerDao.addCredit(exactMatch.id, amount)
            }

            val dueDateStr = DateTimeUtil.formatDate(dueDate)

            val message = buildString {
                appendLine("📝 *Debt Recorded*")
                appendLine()
                appendLine("👤 Customer: $customerName")
                appendLine("💰 Amount: KES ${"%,.0f".format(amount)}")
                appendLine("📦 Product: $product")
                if (!phone.isNullOrBlank()) appendLine("📱 Phone: $phone")
                appendLine("📅 Due: $dueDateStr ($dueDays days)")
                if (!notes.isNullOrBlank()) appendLine("📌 Notes: $notes")
                appendLine()
                appendLine("Debt #$debtId saved. I'll track this for you! 💪")
            }

            Timber.d("Debt recorded: #$debtId — $customerName owes KES $amount for $product")
            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "debt_id" to debtId,
                    "customer" to customerName,
                    "amount" to amount,
                    "due_date" to dueDateStr
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record debt")
            ToolResult.error(name, "Failed to record debt: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 2. TRACK REPAYMENTS
    // ──────────────────────────────────────────────

    /**
     * Record a repayment against a debt. Supports partial payments.
     * If repayment covers full outstanding balance, debt is marked as settled.
     */
    private suspend fun recordRepayment(params: Map<String, String>): ToolResult {
        return try {
            val debtId = params["debt_id"]?.toLongOrNull()
            val amountStr = params["amount"]
            val paymentMethod = params["payment_method"]?.trim() ?: "cash"
            val notes = params["notes"]?.trim()

            if (debtId == null) {
                return ToolResult.error(name, "Debt ID is required. Use 'list' to see active debts.", "MISSING_DEBT_ID")
            }
            val amount = amountStr?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                return ToolResult.error(name, "Repayment amount must be a positive number in KES.", "INVALID_AMOUNT")
            }

            val debt = debtDao.getById(debtId)
                ?: return ToolResult.error(name, "Debt #$debtId not found.", "DEBT_NOT_FOUND")

            if (debt.status == "settled") {
                return ToolResult.error(name, "Debt #$debtId is already fully settled.", "ALREADY_SETTLED")
            }

            if (amount > debt.outstandingBalance) {
                return ToolResult.error(
                    name,
                    "Repayment KES ${"%,.0f".format(amount)} exceeds outstanding balance KES ${"%,.0f".format(debt.outstandingBalance)}. Max you can collect: KES ${"%,.0f".format(debt.outstandingBalance)}.",
                    "OVERPAYMENT"
                )
            }

            // Record the repayment
            val repayment = DebtRepaymentEntity(
                debtId = debtId,
                amount = amount,
                paymentMethod = paymentMethod,
                notes = notes,
                timestamp = System.currentTimeMillis()
            )
            repaymentDao.insert(repayment)

            // Update outstanding balance
            val newBalance = debt.outstandingBalance - amount
            debtDao.updateBalance(debtId, newBalance)

            // Mark as settled if fully paid
            val isFullyPaid = newBalance <= 0.0
            if (isFullyPaid) {
                debtDao.updateStatus(debtId, "settled")
            }

            // Update customer credit balance
            val existingCustomers = customerDao.search(debt.customerName).first()
            val exactMatch = existingCustomers.find {
                it.name.equals(debt.customerName, ignoreCase = true)
            }
            exactMatch?.let { customerDao.reduceCredit(it.id, amount) }

            // Get total repayment history
            val totalRepaid = repaymentDao.getTotalRepaid(debtId) ?: 0.0
            val repaymentCount = repaymentDao.getRepaymentCount(debtId)

            val message = buildString {
                if (isFullyPaid) {
                    appendLine("✅ *Debt Settled!*")
                    appendLine()
                    appendLine("👤 ${debt.customerName} has fully paid!")
                    appendLine("💰 Total paid: KES ${"%,.0f".format(debt.amount)}")
                    appendLine("📊 Payments: $repaymentCount")
                } else {
                    appendLine("💵 *Repayment Recorded*")
                    appendLine()
                    appendLine("👤 Customer: ${debt.customerName}")
                    appendLine("💵 Paid: KES ${"%,.0f".format(amount)} ($paymentMethod)")
                    appendLine("💰 Remaining: KES ${"%,.0f".format(newBalance)}")
                    appendLine("📊 Total repaid: KES ${"%,.0f".format(totalRepaid)} of KES ${"%,.0f".format(debt.amount)}")
                }
            }

            Timber.d("Repayment recorded: debt #$debtId — KES $amount paid, remaining KES $newBalance")
            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "debt_id" to debtId,
                    "paid" to amount,
                    "remaining" to newBalance,
                    "settled" to isFullyPaid
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record repayment")
            ToolResult.error(name, "Failed to record repayment: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. SEND REMINDERS
    // ──────────────────────────────────────────────

    /**
     * Generate a WhatsApp/SMS reminder message for an overdue customer.
     * The message is ready to copy-paste into WhatsApp or send via SMS.
     * Supports both English and Kiswahili templates.
     */
    private suspend fun generateReminder(params: Map<String, String>): ToolResult {
        return try {
            val customerName = params["customer"]?.trim()

            if (customerName.isNullOrBlank()) {
                // Show all overdue debts as reminders
                return generateOverdueReminders()
            }

            val debts = debtDao.getActiveByCustomer(customerName).first()
            if (debts.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "✅ No active debts for $customerName. They're all clear!"
                )
            }

            val totalOwed = debts.sumOf { it.outstandingBalance }
            val overdueDebts = debts.filter {
                it.dueDate != null && it.dueDate < System.currentTimeMillis()
            }

            val now = System.currentTimeMillis()
            val message = buildString {
                if (overdueDebts.isNotEmpty()) {
                    appendLine("🔴 *Overdue Debt Reminder*")
                    appendLine()
                    appendLine("Send this to $customerName:")
                    appendLine()
                    appendLine("---")
                    appendLine("Habari $customerName,")
                    appendLine()
                    appendLine("Nakukumbusha kuhusu deni lako:")
                    overdueDebts.forEach { debt ->
                        val daysOverdue = TimeUnit.MILLISECONDS.toDays(now - (debt.dueDate ?: now)).toInt()
                        appendLine("• ${debt.product}: KES ${"%,.0f".format(debt.outstandingBalance)} (overdue ${daysOverdue}d)")
                    }
                    appendLine()
                    appendLine("Jumla: KES ${"%,.0f".format(overdueDebts.sumOf { it.outstandingBalance })}")
                    appendLine("Tafadhali lipa mapema iwezekanavyo. Asante! 🙏")
                    appendLine("---")
                }

                if (debts.size > overdueDebts.size) {
                    val upcomingDebts = debts.filter { it !in overdueDebts }
                    if (upcomingDebts.isNotEmpty()) {
                        appendLine()
                        appendLine("📋 *Upcoming payments (not yet overdue):*")
                        upcomingDebts.forEach { debt ->
                            val dueStr = debt.dueDate?.let { DateTimeUtil.formatDate(it) } ?: "No due date"
                            appendLine("• ${debt.product}: KES ${"%,.0f".format(debt.outstandingBalance)} — due $dueStr")
                        }
                    }
                }

                appendLine()
                appendLine("📊 Total outstanding for $customerName: KES ${"%,.0f".format(totalOwed)}")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "customer" to customerName,
                    "total_outstanding" to totalOwed,
                    "overdue_count" to overdueDebts.size,
                    "total_debts" to debts.size
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate reminder")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Generate reminders for ALL overdue debts — useful for a daily check.
     */
    private suspend fun generateOverdueReminders(): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val overdueDebts = debtDao.getDebtsPastDue(now).first()

            if (overdueDebts.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "🎉 *No overdue debts!* All your customers are on track. Great job!"
                )
            }

            val totalOverdue = overdueDebts.sumOf { it.outstandingBalance }
            val groupedByCustomer = overdueDebts.groupBy { it.customerName }

            val message = buildString {
                appendLine("🔴 *Overdue Debts — ${DateTimeUtil.today()}*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                groupedByCustomer.forEach { (customer, debts) ->
                    val customerTotal = debts.sumOf { it.outstandingBalance }
                    val maxOverdue = debts.maxOf {
                        TimeUnit.MILLISECONDS.toDays(now - (it.dueDate ?: now)).toInt()
                    }
                    appendLine("👤 *$customer* — KES ${"%,.0f".format(customerTotal)} (${maxOverdue}d overdue)")
                    debts.forEach { debt ->
                        appendLine("   • ${debt.product}: KES ${"%,.0f".format(debt.outstandingBalance)}")
                    }
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💰 Total overdue: KES ${"%,.0f".format(totalOverdue)}")
                appendLine("👥 Debtors: ${groupedByCustomer.size}")
                appendLine()
                appendLine("💡 Say 'remind [name]' to get a ready-to-send WhatsApp message")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "total_overdue" to totalOverdue,
                    "debtor_count" to groupedByCustomer.size,
                    "debt_count" to overdueDebts.size
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate overdue reminders")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 4. AGING ANALYSIS
    // ──────────────────────────────────────────────

    /**
     * Generate a 30/60/90 day aging report for all outstanding debts.
     * Critical for understanding how long money has been owed.
     */
    private suspend fun getAgingReport(): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val buckets = debtDao.getAgingBuckets(now).first()
            val totalOutstanding = debtDao.getTotalOutstanding().first() ?: 0.0
            val activeCount = debtDao.getActiveDebtCount().first()
            val debtorCount = debtDao.getUniqueDebtorsCount().first()

            // Organize buckets
            val bucketMap = buckets.associateBy { it.bucket }
            val current = bucketMap["current"]
            val days30 = bucketMap["30_days"]
            val days60 = bucketMap["60_days"]
            val days90Plus = bucketMap["90_plus"]

            val message = buildString {
                appendLine("📊 *Debt Aging Report*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("🟢 *Current (not yet due):*")
                appendLine("   ${current?.debtCount ?: 0} debts — KES ${"%,.0f".format(current?.totalAmount ?: 0.0)}")
                appendLine()
                appendLine("🟡 *1-30 days overdue:*")
                appendLine("   ${days30?.debtCount ?: 0} debts — KES ${"%,.0f".format(days30?.totalAmount ?: 0.0)}")
                appendLine()
                appendLine("🟠 *31-60 days overdue:*")
                appendLine("   ${days60?.debtCount ?: 0} debts — KES ${"%,.0f".format(days60?.totalAmount ?: 0.0)}")
                appendLine()
                appendLine("🔴 *60+ days overdue:*")
                appendLine("   ${days90Plus?.debtCount ?: 0} debts — KES ${"%,.0f".format(days90Plus?.totalAmount ?: 0.0)}")
                appendLine()
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💰 Total outstanding: KES ${"%,.0f".format(totalOutstanding)}")
                appendLine("📋 Active debts: $activeCount")
                appendLine("👥 Unique debtors: $debtorCount")

                // Risk assessment
                val riskyAmount = (days60?.totalAmount ?: 0.0) + (days90Plus?.totalAmount ?: 0.0)
                if (riskyAmount > 0 && totalOutstanding > 0) {
                    val riskPercent = (riskyAmount / totalOutstanding * 100)
                    appendLine()
                    when {
                        riskPercent > 50 -> appendLine("🚨 HIGH RISK: ${"%.0f".format(riskPercent)}% of debt is 60+ days overdue. Act fast!")
                        riskPercent > 25 -> appendLine("⚠️ MEDIUM RISK: ${"%.0f".format(riskPercent)}% of debt is 60+ days overdue.")
                        else -> appendLine("✅ LOW RISK: Only ${"%.0f".format(riskPercent)}% is 60+ days overdue.")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "total_outstanding" to totalOutstanding,
                    "current" to (current?.totalAmount ?: 0.0),
                    "days_30" to (days30?.totalAmount ?: 0.0),
                    "days_60" to (days60?.totalAmount ?: 0.0),
                    "days_90_plus" to (days90Plus?.totalAmount ?: 0.0),
                    "active_debts" to activeCount,
                    "unique_debtors" to debtorCount
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate aging report")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. CREDIT DECISIONS
    // ──────────────────────────────────────────────

    /**
     * Should the user give credit to this customer?
     * Analyzes past debt history: repayment speed, completion rate, current exposure.
     * Returns a clear recommendation with reasoning.
     */
    private suspend fun creditDecision(params: Map<String, String>): ToolResult {
        return try {
            val customerName = params["customer"]?.trim()
            if (customerName.isNullOrBlank()) {
                return ToolResult.error(name, "Customer name is required for credit check. Example: customer='John'", "MISSING_CUSTOMER")
            }

            // Get all debts for this customer (including settled)
            val allDebts = debtDao.getAllByCustomer(customerName).first()
            val activeDebts = allDebts.filter { it.status == "active" }
            val settledDebts = allDebts.filter { it.status == "settled" }
            val writtenOffDebts = allDebts.filter { it.status == "written_off" }

            // New customer — no history
            if (allDebts.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    data = mapOf("recommendation" to "cautious", "reason" to "no_history"),
                    message = buildString {
                        appendLine("🔍 *Credit Check: $customerName*")
                        appendLine()
                        appendLine("📋 No debt history found.")
                        appendLine()
                        appendLine("💡 *Recommendation: START SMALL*")
                        appendLine("This is a new customer with no track record.")
                        appendLine()
                        appendLine("Tips:")
                        appendLine("• Start with a small amount (KES 200-500)")
                        appendLine("• Set a short due date (7-14 days)")
                        appendLine("• Get their phone number")
                        appendLine("• Build trust before larger amounts")
                    }
                )
            }

            // Calculate metrics
            val totalDebts = allDebts.size
            val totalAmount = allDebts.sumOf { it.amount }
            val totalOutstanding = activeDebts.sumOf { it.outstandingBalance }
            val totalRepaid = totalAmount - totalOutstanding - writtenOffDebts.sumOf { it.outstandingBalance }
            val completionRate = if (totalAmount > 0) ((totalAmount - totalOutstanding - writtenOffDebts.sumOf { it.outstandingBalance }) / totalAmount * 100) else 0.0

            // Check repayment speed
            var totalRepaymentDays = 0L
            var repaymentCount = 0
            var latePayments = 0
            var onTimePayments = 0

            for (debt in settledDebts) {
                val repayments = repaymentDao.getByDebtOnce(debt.id)
                if (repayments.isNotEmpty()) {
                    val lastPayment = repayments.first().timestamp // most recent
                    val daysToSettle = TimeUnit.MILLISECONDS.toDays(lastPayment - debt.createdAt)
                    totalRepaymentDays += daysToSettle
                    repaymentCount++

                    if (debt.dueDate != null && lastPayment > debt.dueDate) {
                        latePayments++
                    } else {
                        onTimePayments++
                    }
                }
            }

            val avgRepaymentDays = if (repaymentCount > 0) totalRepaymentDays.toDouble() / repaymentCount else null

            // Check current overdue exposure
            val now = System.currentTimeMillis()
            val overdueActive = activeDebts.filter {
                it.dueDate != null && it.dueDate < now && it.outstandingBalance > 0
            }
            val overdueAmount = overdueActive.sumOf { it.outstandingBalance }

            // Calculate credit score (simple heuristic)
            var score = 50 // neutral starting point

            // Positive factors
            if (completionRate >= 90) score += 20
            else if (completionRate >= 70) score += 10
            else if (completionRate >= 50) score += 5
            else score -= 15

            if (onTimePayments > latePayments) score += 15
            else if (latePayments > onTimePayments) score -= 15

            if (avgRepaymentDays != null && avgRepaymentDays <= 14) score += 10
            else if (avgRepaymentDays != null && avgRepaymentDays <= 30) score += 5
            else if (avgRepaymentDays != null && avgRepaymentDays > 60) score -= 10

            if (writtenOffDebts.isNotEmpty()) score -= 20
            if (overdueAmount > 0) score -= 15

            // Determine recommendation
            val recommendation = when {
                score >= 75 -> "approved"
                score >= 50 -> "cautious"
                score >= 30 -> "limited"
                else -> "declined"
            }

            val message = buildString {
                appendLine("🔍 *Credit Check: $customerName*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 *History:*")
                appendLine("   Total debts: $totalDebts")
                appendLine("   Settled: ${settledDebts.size}")
                appendLine("   Active: ${activeDebts.size}")
                if (writtenOffDebts.isNotEmpty()) appendLine("   Written off: ${writtenOffDebts.size}")
                appendLine()
                appendLine("💰 *Financial:*")
                appendLine("   Total borrowed: KES ${"%,.0f".format(totalAmount)}")
                appendLine("   Total repaid: KES ${"%,.0f".format(totalRepaid)}")
                appendLine("   Currently owes: KES ${"%,.0f".format(totalOutstanding)}")
                if (overdueAmount > 0) appendLine("   ⚠️ Overdue: KES ${"%,.0f".format(overdueAmount)}")
                appendLine()
                appendLine("📈 *Performance:*")
                appendLine("   Completion rate: ${"%.0f".format(completionRate)}%")
                if (avgRepaymentDays != null) {
                    appendLine("   Avg repayment: ${avgRepaymentDays.toInt()} days")
                }
                if (onTimePayments + latePayments > 0) {
                    appendLine("   On-time: $onTimePayments / Late: $latePayments")
                }
                appendLine()

                when (recommendation) {
                    "approved" -> {
                        appendLine("✅ *Recommendation: APPROVE*")
                        appendLine("This customer has a strong repayment history.")
                        appendLine("Credit score: $score/100")
                    }
                    "cautious" -> {
                        appendLine("🟡 *Recommendation: APPROVE WITH CAUTION*")
                        appendLine("Decent history but some risk. Consider:")
                        appendLine("• Smaller amount than usual")
                        appendLine("• Shorter due date")
                        appendLine("• Credit score: $score/100")
                    }
                    "limited" -> {
                        appendLine("🟠 *Recommendation: LIMITED CREDIT ONLY*")
                        appendLine("Mixed history. If you give credit:")
                        appendLine("• Keep amount very small")
                        appendLine("• Collect existing debts first")
                        appendLine("• Credit score: $score/100")
                    }
                    "declined" -> {
                        appendLine("🔴 *Recommendation: DO NOT GIVE CREDIT*")
                        appendLine("Poor repayment history. Collect existing debts first.")
                        if (writtenOffDebts.isNotEmpty()) {
                            appendLine("⚠️ Has ${writtenOffDebts.size} written-off debts")
                        }
                        appendLine("Credit score: $score/100")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "customer" to customerName,
                    "recommendation" to recommendation,
                    "credit_score" to score,
                    "completion_rate" to completionRate,
                    "total_outstanding" to totalOutstanding,
                    "overdue_amount" to overdueAmount
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate credit decision")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 6. VOICE INPUT PARSING
    // ──────────────────────────────────────────────

    /**
     * Parse natural language voice input into a debt record.
     * Supports bilingual patterns (Kiswahili + English):
     *
     * English patterns:
     *  - "Customer John owes KES 500"
     *  - "John owes me 500 for tomatoes"
     *  - "Record debt: Mama Njeri, 1000, charcoal"
     *
     * Kiswahili patterns:
     *  - "Customer John ananidai KES 500"
     *  - "John anadaiwa 500 kwa mboga"
     *  - "Mama Njeri ananidai elfu moja"
     *
     * Also handles shorthand:
     *  - "John 500"
     *  - "Njeri 1000 charcoal"
     */
    private suspend fun parseVoiceInput(voiceInput: String): ToolResult {
        return try {
            val input = voiceInput.trim()
            Timber.d("Parsing voice debt input: '$input'")

            // Extract amount — look for KES/amount patterns
            val amount = extractAmount(input)
            if (amount == null) {
                return ToolResult.error(
                    name,
                    "I couldn't find an amount in: '$input'\nTry: 'Customer John owes 500' or 'John ananidai 1000'",
                    "PARSE_ERROR"
                )
            }

            // Extract customer name
            val customerName = extractCustomerName(input, amount)
            if (customerName.isNullOrBlank()) {
                return ToolResult.error(
                    name,
                    "I couldn't find the customer name in: '$input'\nTry: 'Customer John owes 500'",
                    "PARSE_ERROR"
                )
            }

            // Extract product (optional)
            val product = extractProduct(input, customerName, amount)

            // Record the debt
            val params = mutableMapOf(
                "customer" to customerName,
                "amount" to amount.toString()
            )
            if (!product.isNullOrBlank()) params["product"] = product

            return recordDebt(params)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse voice input")
            ToolResult.error(name, "Couldn't understand: '$voiceInput'. Try: 'Customer John owes 500'", "PARSE_ERROR")
        }
    }

    /**
     * Extract a numeric amount from voice text.
     * Handles: "500", "KES 500", "elfu moja" (1000), "5,000", etc.
     */
    private fun extractAmount(input: String): Double? {
        // Pattern 1: Explicit KES amount
        val kesPattern = Regex("""(?:KES|kes|ksh|Ksh)\s*(\d[\d,]*(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        kesPattern.find(input)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()
        }

        // Pattern 2: "elfu" (thousands in Kiswahili)
        val elfuPattern = Regex("""elfu\s+(\d+)""", RegexOption.IGNORE_CASE)
        elfuPattern.find(input)?.let {
            return it.groupValues[1].toDoubleOrNull()?.times(1000)
        }

        // Pattern 3: Standalone number near debt keywords
        val debtKeywords = listOf("owes", "owe", "ananidai", "anadaiwa", "deni", "debt", "credit")
        val hasKeyword = debtKeywords.any { input.contains(it, ignoreCase = true) }
        if (hasKeyword) {
            val numberPattern = Regex("""(\d[\d,]*(?:\.\d+)?)""")
            numberPattern.find(input)?.let {
                return it.groupValues[1].replace(",", "").toDoubleOrNull()
            }
        }

        // Pattern 4: Last resort — any number in the string
        val anyNumber = Regex("""(\d[\d,]*(?:\.\d+)?)""")
        anyNumber.find(input)?.let {
            return it.groupValues[1].replace(",", "").toDoubleOrNull()
        }

        return null
    }

    /**
     * Extract customer name from voice text.
     * Looks for patterns like "Customer X", "X owes", "X ananidai", etc.
     */
    private fun extractCustomerName(input: String, amount: Double): String? {
        // Pattern 1: "Customer [Name]" or "customer [name]"
        val customerPrefix = Regex("""[Cc]ustomer\s+([A-Za-zÀ-ÿ\u00C0-\u024F]+(?:\s+[A-Za-zÀ-ÿ\u00C0-\u024F]+)?)""", RegexOption.IGNORE_CASE)
        customerPrefix.find(input)?.let {
            val name = it.groupValues[1].trim()
            if (!isAmountOrKeyword(name)) return name
        }

        // Pattern 2: "[Name] owes" or "[Name] ananidai" or "[Name] anadaiwa"
        val owesPattern = Regex("""([A-Za-zÀ-ÿ\u00C0-\u024F]+(?:\s+[A-Za-zÀ-ÿ\u00C0-\u024F]+)?)\s+(?:owes?|ananidai|anadaiwa|anadai)""", RegexOption.IGNORE_CASE)
        owesPattern.find(input)?.let {
            val name = it.groupValues[1].trim()
            if (!isAmountOrKeyword(name)) return name
        }

        // Pattern 3: "[Name] [amount]" (shorthand)
        val amountStr = amount.toLong().toString()
        val shorthand = Regex("""([A-Za-zÀ-ÿ\u00C0-\u024F]+(?:\s+[A-Za-zÀ-ÿ\u00C0-\u024F]+)?)\s+(?:KES\s*)?$amountStr""", RegexOption.IGNORE_CASE)
        shorthand.find(input)?.let {
            val name = it.groupValues[1].trim()
            if (!isAmountOrKeyword(name)) return name
        }

        // Pattern 4: First word that looks like a name
        val words = input.split(Regex("""\s+"""))
        for (word in words) {
            val clean = word.replace(Regex("""[,.;:!?]"""), "")
            if (clean.matches(Regex("""[A-Za-zÀ-ÿ\u00C0-\u024F]{2,}""")) && !isAmountOrKeyword(clean)) {
                return clean
            }
        }

        return null
    }

    /**
     * Check if a word is a number or common keyword (not a name).
     */
    private fun isAmountOrKeyword(word: String): Boolean {
        val lower = word.lowercase()
        val keywords = setOf(
            "customer", "owes", "owe", "owed", "ananidai", "anadaiwa", "anadai",
            "deni", "debt", "credit", "for", "kwa", "ya", "za", "the", "na",
            "kes", "ksh", "elfu", "moja", "mbili", "tatu", "nne", "tano",
            "record", "add", "new", "please", "tafadhali"
        )
        if (lower in keywords) return true
        if (lower.toDoubleOrNull() != null) return true
        return false
    }

    /**
     * Extract product name from voice text.
     * Looks for patterns like "for [product]", "kwa [product]", or trailing words after amount.
     */
    private fun extractProduct(input: String, customerName: String, amount: Double): String? {
        // Pattern 1: "for [product]" or "kwa [product]"
        val forPattern = Regex("""(?:for|kwa)\s+([A-Za-zÀ-ÿ\u00C0-\u024F]+(?:\s+[A-Za-zÀ-ÿ\u00C0-\u024F]+)?)""", RegexOption.IGNORE_CASE)
        forPattern.find(input)?.let {
            return it.groupValues[1].trim()
        }

        // Pattern 2: Trailing words after amount that aren't keywords
        val amountStr = amount.toLong().toString()
        val afterAmount = Regex("""$amountStr\s*(?:KES|kes|ksh|Ksh)?\s*(.+)""", RegexOption.IGNORE_CASE)
        afterAmount.find(input)?.let {
            val trailing = it.groupValues[1].trim()
            if (trailing.isNotBlank() && !isAmountOrKeyword(trailing.split(" ").first())) {
                return trailing
            }
        }

        return null
    }

    // ──────────────────────────────────────────────
    // LIST & VIEW
    // ──────────────────────────────────────────────

    /**
     * List all active debts, sorted by outstanding balance.
     */
    private suspend fun listActiveDebts(): ToolResult {
        return try {
            val debts = debtDao.getActiveDebts().first()
            val totalOutstanding = debtDao.getTotalOutstanding().first() ?: 0.0

            if (debts.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "🎉 *No active debts!* Everyone has paid. Well done! 💪"
                )
            }

            val now = System.currentTimeMillis()
            val message = buildString {
                appendLine("📋 *Active Debts*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                debts.forEach { debt ->
                    val statusEmoji = when {
                        debt.dueDate == null -> "📋"
                        debt.dueDate < now -> "🔴"
                        debt.dueDate < now + TimeUnit.DAYS.toMillis(7) -> "🟡"
                        else -> "🟢"
                    }
                    val dueStr = debt.dueDate?.let { " — due ${DateTimeUtil.formatDate(it)}" } ?: ""
                    appendLine("$statusEmoji #${debt.id} ${debt.customerName}")
                    appendLine("   KES ${"%,.0f".format(debt.outstandingBalance)} of ${"%,.0f".format(debt.amount)} — ${debt.product}$dueStr")
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💰 Total outstanding: KES ${"%,.0f".format(totalOutstanding)}")
                appendLine("📋 ${debts.size} active debts")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "count" to debts.size,
                    "total_outstanding" to totalOutstanding
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to list debts")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * View all debts for a specific customer.
     */
    private suspend fun viewCustomerDebts(params: Map<String, String>): ToolResult {
        return try {
            val customerName = params["customer"]?.trim()
            if (customerName.isNullOrBlank()) {
                return ToolResult.error(name, "Customer name is required. Example: customer='John'", "MISSING_CUSTOMER")
            }

            val activeDebts = debtDao.getActiveByCustomer(customerName).first()
            val allDebts = debtDao.getAllByCustomer(customerName).first()
            val totalOwed = activeDebts.sumOf { it.outstandingBalance }
            val totalHistorical = allDebts.sumOf { it.amount }

            if (allDebts.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "No debt records found for $customerName."
                )
            }

            val message = buildString {
                appendLine("👤 *Debt History: $customerName*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                if (activeDebts.isNotEmpty()) {
                    appendLine("🔴 *Active debts:*")
                    activeDebts.forEach { debt ->
                        val dueStr = debt.dueDate?.let { " — due ${DateTimeUtil.formatDate(it)}" } ?: ""
                        appendLine("   #${debt.id} ${debt.product}: KES ${"%,.0f".format(debt.outstandingBalance)} remaining$dueStr")
                    }
                    appendLine()
                }

                val settledDebts = allDebts.filter { it.status == "settled" }
                if (settledDebts.isNotEmpty()) {
                    appendLine("✅ *Settled debts:*")
                    settledDebts.take(5).forEach { debt ->
                        appendLine("   #${debt.id} ${debt.product}: KES ${"%,.0f".format(debt.amount)} — settled")
                    }
                    if (settledDebts.size > 5) {
                        appendLine("   ... and ${settledDebts.size - 5} more")
                    }
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💰 Currently owes: KES ${"%,.0f".format(totalOwed)}")
                appendLine("📊 Total historical: KES ${"%,.0f".format(totalHistorical)}")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "customer" to customerName,
                    "active_count" to activeDebts.size,
                    "total_owed" to totalOwed,
                    "total_historical" to totalHistorical
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to view customer debts")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Overall debt summary — total exposure, top debtors, recent activity.
     */
    private suspend fun debtSummary(): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val todayStart = DateTimeUtil.startOfDay()
            val weekStart = DateTimeUtil.startOfWeek()

            val totalOutstanding = debtDao.getTotalOutstanding().first() ?: 0.0
            val totalOverdue = debtDao.getTotalOverdue(now).first() ?: 0.0
            val activeCount = debtDao.getActiveDebtCount().first()
            val debtorCount = debtDao.getUniqueDebtorsCount().first()
            val topDebtors = debtDao.getTopDebtors(5).first()

            // This week's activity
            val debtsCreatedToday = debtDao.getDebtsCreatedBetween(todayStart, now).first() ?: 0.0
            val debtsCreatedWeek = debtDao.getDebtsCreatedBetween(weekStart, now).first() ?: 0.0
            val repaidToday = repaymentDao.getTotalRepaymentsBetween(todayStart, now).first() ?: 0.0
            val repaidWeek = repaymentDao.getTotalRepaymentsBetween(weekStart, now).first() ?: 0.0

            val message = buildString {
                appendLine("📊 *Debt Summary — ${DateTimeUtil.today()}*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("💰 *Outstanding:* KES ${"%,.0f".format(totalOutstanding)}")
                appendLine("🔴 *Overdue:* KES ${"%,.0f".format(totalOverdue)}")
                appendLine("📋 *Active debts:* $activeCount")
                appendLine("👥 *Debtors:* $debtorCount")
                appendLine()
                appendLine("📅 *This week:*")
                appendLine("   New debts: KES ${"%,.0f".format(debtsCreatedWeek)}")
                appendLine("   Repayments: KES ${"%,.0f".format(repaidWeek)}")
                appendLine()
                appendLine("📅 *Today:*")
                appendLine("   New debts: KES ${"%,.0f".format(debtsCreatedToday)}")
                appendLine("   Repayments: KES ${"%,.0f".format(repaidToday)}")

                if (topDebtors.isNotEmpty()) {
                    appendLine()
                    appendLine("🏆 *Top debtors:*")
                    topDebtors.forEachIndexed { i, debt ->
                        appendLine("   ${i + 1}. ${debt.customerName}: KES ${"%,.0f".format(debt.outstandingBalance)}")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "total_outstanding" to totalOutstanding,
                    "total_overdue" to totalOverdue,
                    "active_debts" to activeCount,
                    "unique_debtors" to debtorCount,
                    "repaid_this_week" to repaidWeek
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate debt summary")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // WRITE OFF
    // ──────────────────────────────────────────────

    /**
     * Mark a debt as written off (bad debt).
     * This means the worker has accepted they won't collect this money.
     * Important for accurate financial records.
     */
    private suspend fun writeOffDebt(params: Map<String, String>): ToolResult {
        return try {
            val debtId = params["debt_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "Debt ID is required. Use 'list' to see active debts.", "MISSING_DEBT_ID")

            val debt = debtDao.getById(debtId)
                ?: return ToolResult.error(name, "Debt #$debtId not found.", "DEBT_NOT_FOUND")

            if (debt.status == "written_off") {
                return ToolResult.error(name, "Debt #$debtId is already written off.", "ALREADY_WRITTEN_OFF")
            }

            debtDao.updateStatus(debtId, "written_off")

            // Update customer credit balance
            val existingCustomers = customerDao.search(debt.customerName).first()
            val exactMatch = existingCustomers.find {
                it.name.equals(debt.customerName, ignoreCase = true)
            }
            exactMatch?.let { customerDao.reduceCredit(it.id, debt.outstandingBalance) }

            val message = buildString {
                appendLine("📝 *Debt Written Off*")
                appendLine()
                appendLine("👤 Customer: ${debt.customerName}")
                appendLine("💰 Amount: KES ${"%,.0f".format(debt.outstandingBalance)}")
                appendLine("📦 Product: ${debt.product}")
                appendLine()
                appendLine("This debt has been marked as uncollectable.")
                appendLine("It will still appear in your financial records.")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "debt_id" to debtId,
                    "customer" to debt.customerName,
                    "written_off_amount" to debt.outstandingBalance
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to write off debt")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }
}
