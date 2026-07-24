package com.msaidizi.app.superagent.flow

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * CustomerTracker — "Wateja wangu ni kina nani?"
 * Tracks customers: new vs returning, average spend, top customers, credit (dlana).
 * Helps workers understand who brings money and who owes money.
 */
class CustomerTracker {

    private val customers = mutableMapOf<String, Customer>()
    private val visits = mutableListOf<CustomerVisit>()

    // ── Data Management ────────────────────────

    fun addCustomer(customer: Customer) {
        customers[customer.id] = customer
    }

    fun recordVisit(customerId: String, amount: Double, products: List<String> = emptyList()) {
        val customer = customers[customerId]
        if (customer != null) {
            val visitCount = customer.visitCount + 1
            val totalSpent = customer.totalSpent + amount
            customers[customerId] = customer.copy(
                lastVisit = LocalDate.now(),
                visitCount = visitCount,
                totalSpent = totalSpent,
                averageSpend = totalSpent / visitCount
            )
        } else {
            // New customer
            customers[customerId] = Customer(
                id = customerId,
                name = "Customer $customerId",
                firstVisit = LocalDate.now(),
                lastVisit = LocalDate.now(),
                totalSpent = amount,
                visitCount = 1,
                isReturning = false,
                averageSpend = amount
            )
        }

        visits.add(CustomerVisit(
            customerId = customerId,
            amount = amount,
            products = products,
            date = LocalDate.now()
        ))
    }

    fun addCredit(customerId: String, amount: Double) {
        val customer = customers[customerId] ?: return
        customers[customerId] = customer.copy(
            outstandingCredit = customer.outstandingCredit + amount
        )
    }

    fun settleCredit(customerId: String, amount: Double) {
        val customer = customers[customerId] ?: return
        customers[customerId] = customer.copy(
            outstandingCredit = (customer.outstandingCredit - amount).coerceAtLeast(0.0)
        )
    }

    fun clearData() {
        customers.clear()
        visits.clear()
    }

    // ── Customer Summary ───────────────────────

    /**
     * Full customer overview for a period.
     * "Wateja wangapi walikuja? Wangapi ni wapya?"
     */
    fun getSummary(period: ReportPeriod, customRange: DateRange? = null): CustomerSummary {
        val range = resolveDateRange(period, customRange)
        val periodVisits = visits.filter { visit ->
            val date = visit.date
            !date.isBefore(range.start) && !date.isAfter(range.end)
        }

        val uniqueCustomerIds = periodVisits.map { it.customerId }.distinct()
        val periodCustomers = uniqueCustomerIds.mapNotNull { customers[it] }

        val newCustomers = periodCustomers.filter {
            !it.firstVisit.isBefore(range.start) && !it.firstVisit.isAfter(range.end)
        }.size

        val returningCustomers = periodCustomers.filter { it.isReturning }.size

        val totalSpent = periodVisits.sumOf { it.amount }
        val averageSpendPerCustomer = if (uniqueCustomerIds.isNotEmpty()) {
            totalSpent / uniqueCustomerIds.size
        } else 0.0

        val averageSpendPerVisit = if (periodVisits.isNotEmpty()) {
            totalSpent / periodVisits.size
        } else 0.0

        val topCustomers = periodCustomers
            .sortedByDescending { customer ->
                periodVisits.filter { it.customerId == customer.id }.sumOf { it.amount }
            }
            .take(10)

        val customersWithCredit = customers.values
            .filter { it.outstandingCredit > 0 }
            .sortedByDescending { it.outstandingCredit }

        val totalOutstandingCredit = customersWithCredit.sumOf { it.outstandingCredit }

        // Retention: how many customers from previous period came back
        val previousRange = getPreviousPeriod(range)
        val previousCustomerIds = visits
            .filter { !it.date.isBefore(previousRange.start) && !it.date.isAfter(previousRange.end) }
            .map { it.customerId }
            .distinct()

        val retainedCustomers = uniqueCustomerIds.filter { it in previousCustomerIds }.size
        val retentionRate = if (previousCustomerIds.isNotEmpty()) {
            (retainedCustomers.toDouble() / previousCustomerIds.size) * 100
        } else 0.0

        return CustomerSummary(
            period = range,
            totalCustomers = uniqueCustomerIds.size,
            newCustomers = newCustomers,
            returningCustomers = returningCustomers,
            averageSpendPerCustomer = averageSpendPerCustomer,
            averageSpendPerVisit = averageSpendPerVisit,
            topCustomers = topCustomers,
            customersWithCredit = customersWithCredit,
            totalOutstandingCredit = totalOutstandingCredit,
            retentionRate = retentionRate
        )
    }

    // ── Top Customers ──────────────────────────

    /**
     * Who spends the most?
     * "Wateja wangu bora ni kina nani?"
     */
    fun getTopCustomers(period: ReportPeriod, limit: Int = 10, customRange: DateRange? = null): List<TopCustomer> {
        val range = resolveDateRange(period, customRange)
        val periodVisits = visits.filter { !it.date.isBefore(range.start) && !it.date.isAfter(range.end) }

        return periodVisits
            .groupBy { it.customerId }
            .map { (customerId, customerVisits) ->
                val customer = customers[customerId]
                TopCustomer(
                    customerId = customerId,
                    name = customer?.name ?: "Customer $customerId",
                    totalSpent = customerVisits.sumOf { it.amount },
                    visitCount = customerVisits.size,
                    averageSpend = customerVisits.sumOf { it.amount } / customerVisits.size,
                    outstandingCredit = customer?.outstandingCredit ?: 0.0
                )
            }
            .sortedByDescending { it.totalSpent }
            .take(limit)
    }

    // ── Credit (Dlana) Tracking ────────────────

    /**
     * Who owes money? How much?
     * "Nani ananidai? Dlana ni ngapi?"
     */
    fun getCreditReport(): CreditReport {
        val withCredit = customers.values
            .filter { it.outstandingCredit > 0 }
            .sortedByDescending { it.outstandingCredit }

        val totalCredit = withCredit.sumOf { it.outstandingCredit }
        val totalRevenue = customers.values.sumOf { it.totalSpent }
        val creditPercent = if (totalRevenue > 0) (totalCredit / totalRevenue) * 100 else 0.0

        val severity = when {
            creditPercent > 30 -> CreditSeverity.CRITICAL
            creditPercent > 15 -> CreditSeverity.HIGH
            creditPercent > 5 -> CreditSeverity.MODERATE
            else -> CreditSeverity.LOW
        }

        return CreditReport(
            totalOutstanding = totalCredit,
            customersWithCredit = withCredit.size,
            creditPercentOfRevenue = creditPercent,
            severity = severity,
            topDebtors = withCredit.take(5).map { customer ->
                Debtor(
                    name = customer.name,
                    phone = customer.phone,
                    amountOwed = customer.outstandingCredit,
                    daysSinceLastVisit = ChronoUnit.DAYS.between(customer.lastVisit, LocalDate.now()).toInt()
                )
            },
            messageEn = when (severity) {
                CreditSeverity.CRITICAL -> "⚠️ Dlana ni kubwa sana! KES ${"%,.0f".format(totalCredit)} haijalipwa."
                CreditSeverity.HIGH -> "Kuna dlana nyingi: KES ${"%,.0f".format(totalCredit)}. Fuatilia malipo."
                CreditSeverity.MODERATE -> "Dlana ni wastani: KES ${"%,.0f".format(totalCredit)}."
                CreditSeverity.LOW -> "Dlana ni kidogo — vizuri!"
            },
            messageSw = when (severity) {
                CreditSeverity.CRITICAL -> "Dlana ni kubwa! Wateja wanakudai KES ${"%,.0f".format(totalCredit)}. Fanya kitu!"
                CreditSeverity.HIGH -> "Dlana ni nyingi: KES ${"%,.0f".format(totalCredit)}. Wafuate wakulipe."
                CreditSeverity.MODERATE -> "Dlana ni wastani: KES ${"%,.0f".format(totalCredit)}."
                CreditSeverity.LOW -> "Dlana ni kidogo — wateja wanalipa vizuri!"
            }
        )
    }

    // ── New vs Returning ───────────────────────

    /**
     * Ratio of new to returning customers.
     * "Wateja wapya dhidi ya wanaorudi?"
     */
    fun getNewVsReturning(period: ReportPeriod, customRange: DateRange? = null): NewVsReturning {
        val summary = getSummary(period, customRange)
        val total = summary.totalCustomers

        return NewVsReturning(
            newCustomers = summary.newCustomers,
            returningCustomers = summary.returningCustomers,
            newPercent = if (total > 0) (summary.newCustomers.toDouble() / total) * 100 else 0.0,
            returningPercent = if (total > 0) (summary.returningCustomers.toDouble() / total) * 100 else 0.0,
            retentionRate = summary.retentionRate,
            messageEn = buildString {
                append("${summary.newCustomers} new, ${summary.returningCustomers} returning. ")
                append("Retention: ${"%.0f".format(summary.retentionRate)}%.")
            },
            messageSw = buildString {
                append("Wateja wapya ${summary.newCustomers}, wanaorudi ${summary.returningCustomers}. ")
                append("Kiwango cha kurudi: asilimia ${"%.0f".format(summary.retentionRate)}.")
            }
        )
    }

    // ── Voice Summary ──────────────────────────

    fun getVoiceSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        val creditReport = getCreditReport()

        val periodName = getPeriodNameSw(period)

        return buildString {
            append("Wateja $periodName: ${summary.totalCustomers}. ")
            append("Wapya: ${summary.newCustomers}. ")
            append("Wanaorudi: ${summary.returningCustomers}. ")
            append("Wastani wa matumizi: KES ${"%,.0f".format(summary.averageSpendPerCustomer)}. ")
            append("Kiwango cha kurudi: asilimia ${"%.0f".format(summary.retentionRate)}. ")
            if (creditReport.totalOutstanding > 0) {
                append("Dlana: KES ${"%,.0f".format(creditReport.totalOutstanding)}. ")
            }
            summary.topCustomers.firstOrNull()?.let { top ->
                append("Mteja bora: ${top.name} (KES ${"%,.0f".format(top.totalSpent)}).")
            }
        }
    }

    fun getEnglishSummary(period: ReportPeriod, customRange: DateRange? = null): String {
        val summary = getSummary(period, customRange)
        return buildString {
            append("Customers ${getPeriodNameEn(period)}: ${summary.totalCustomers} total ")
            append("(${summary.newCustomers} new, ${summary.returningCustomers} returning). ")
            append("Avg spend: KES ${"%,.0f".format(summary.averageSpendPerCustomer)}. ")
            append("Retention: ${"%.0f".format(summary.retentionRate)}%. ")
            append("Outstanding credit: KES ${"%,.0f".format(summary.totalOutstandingCredit)}.")
        }
    }

    // ── Helpers ────────────────────────────────

    private fun resolveDateRange(period: ReportPeriod, custom: DateRange?): DateRange {
        return when (period) {
            ReportPeriod.TODAY -> DateRange.today()
            ReportPeriod.YESTERDAY -> {
                val yesterday = LocalDate.now().minusDays(1)
                DateRange(yesterday, yesterday)
            }
            ReportPeriod.THIS_WEEK -> DateRange.thisWeek()
            ReportPeriod.LAST_WEEK -> {
                val end = LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong())
                val start = end.minusDays(6)
                DateRange(start, end)
            }
            ReportPeriod.THIS_MONTH -> DateRange.thisMonth()
            ReportPeriod.LAST_MONTH -> {
                val firstOfThisMonth = LocalDate.now().withDayOfMonth(1)
                val end = firstOfThisMonth.minusDays(1)
                val start = end.withDayOfMonth(1)
                DateRange(start, end)
            }
            ReportPeriod.CUSTOM -> custom ?: DateRange.today()
        }
    }

    private fun getPreviousPeriod(range: DateRange): DateRange {
        val days = range.days
        return DateRange(
            start = range.start.minusDays(days),
            end = range.start.minusDays(1)
        )
    }

    private fun getPeriodNameSw(period: ReportPeriod): String = when (period) {
        ReportPeriod.TODAY -> "ya leo"
        ReportPeriod.YESTERDAY -> "ya jana"
        ReportPeriod.THIS_WEEK -> "ya wiki hii"
        ReportPeriod.LAST_WEEK -> "ya wiki iliyopita"
        ReportPeriod.THIS_MONTH -> "ya mwezi huu"
        ReportPeriod.LAST_MONTH -> "ya mwezi uliopita"
        ReportPeriod.CUSTOM -> "ya kipindi hiki"
    }

    private fun getPeriodNameEn(period: ReportPeriod): String = when (period) {
        ReportPeriod.TODAY -> "today"
        ReportPeriod.YESTERDAY -> "yesterday"
        ReportPeriod.THIS_WEEK -> "this week"
        ReportPeriod.LAST_WEEK -> "last week"
        ReportPeriod.THIS_MONTH -> "this month"
        ReportPeriod.LAST_MONTH -> "last month"
        ReportPeriod.CUSTOM -> "this period"
    }
}

// ── Supporting data classes ──────────────────

private data class CustomerVisit(
    val customerId: String,
    val amount: Double,
    val products: List<String>,
    val date: LocalDate
)

data class TopCustomer(
    val customerId: String,
    val name: String,
    val totalSpent: Double,
    val visitCount: Int,
    val averageSpend: Double,
    val outstandingCredit: Double
)

data class CreditReport(
    val totalOutstanding: Double,
    val customersWithCredit: Int,
    val creditPercentOfRevenue: Double,
    val severity: CreditSeverity,
    val topDebtors: List<Debtor>,
    val messageEn: String,
    val messageSw: String
)

data class Debtor(
    val name: String,
    val phone: String?,
    val amountOwed: Double,
    val daysSinceLastVisit: Int
)

enum class CreditSeverity {
    LOW, MODERATE, HIGH, CRITICAL
}

data class NewVsReturning(
    val newCustomers: Int,
    val returningCustomers: Int,
    val newPercent: Double,
    val returningPercent: Double,
    val retentionRate: Double,
    val messageEn: String,
    val messageSw: String
)
