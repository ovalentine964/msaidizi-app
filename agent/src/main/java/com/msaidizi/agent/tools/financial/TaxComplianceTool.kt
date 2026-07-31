package com.msaidizi.agent.tools.financial

import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.database.ExpenseDao
import com.msaidizi.core.database.ServiceTransactionDao
import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

// ════════════════════════════════════════════════════════════
// TAX COMPLIANCE TOOL — TOT Calculator, VAT Threshold Monitor
// ════════════════════════════════════════════════════════════
// Kenya informal sector tax obligations:
// - Turnover Tax (TOT): 1% of gross revenue for KES 1M-25M businesses
// - VAT registration: mandatory above KES 5M annual revenue
// - NHIF/NSSF: employer obligations for archetypes with employees
//
// This tool helps workers understand and prepare for tax compliance.
// ════════════════════════════════════════════════════════════

/**
 * Tax assessment result.
 */
data class TaxAssessment(
    val annualRevenue: Double,
    val monthlyRevenue: Double,
    val totApplicable: Boolean,
    val totAmount: Double,
    val vatThresholdProximity: Double,  // percentage of KES 5M threshold
    val vatRegistrationRequired: Boolean,
    val nhifApplicable: Boolean,
    val nssfApplicable: Boolean,
    val estimatedMonthlyTax: Double,
    val nextFilingDate: String,
    val warnings: List<String>,
    val warningsSwahili: List<String>
)

/**
 * TaxComplianceTool — TOT calculator, VAT threshold monitor, tax calendar.
 *
 * Kenya's informal workers face increasing tax pressure. This tool:
 * 1. Calculates Turnover Tax (TOT) at 1% for KES 1M-25M businesses
 * 2. Monitors VAT registration threshold (KES 5M)
 * 3. Tracks NHIF/NSSF obligations
 * 4. Provides tax calendar with voice reminders
 * 5. Generates proof-of-income for tax purposes
 */
@Singleton
class TaxComplianceTool @Inject constructor(
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val serviceTransactionDao: ServiceTransactionDao,
    private val dailySummaryDao: DailySummaryDao
) : Tool {

    override val name = "tax_compliance"
    override val description = "Tax compliance for informal workers: TOT calculator (1% of revenue), " +
            "VAT threshold monitor (KES 5M), NHIF/NSSF calculator, tax calendar. " +
            "Helps workers understand their tax obligations without jargon."

    override val argsSchema = argSchema {
        enum("action", "Tax action to perform",
            listOf("assess", "tot_calculator", "vat_monitor", "tax_calendar",
                "annual_estimate", "filing_reminder"), required = false)
        number("annual_revenue", "Annual revenue for manual calculation (KES)", required = false)
        number("monthly_revenue", "Monthly revenue for manual calculation (KES)", required = false)
        integer("employee_count", "Number of employees (for NHIF/NSSF)", required = false, default = "0")
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "assess"
        return when (action.lowercase()) {
            "assess" -> fullAssessment(params)
            "tot_calculator" -> totCalculator(params)
            "vat_monitor" -> vatMonitor(params)
            "tax_calendar" -> taxCalendar(params)
            "annual_estimate" -> annualEstimate(params)
            "filing_reminder" -> filingReminder(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  FULL TAX ASSESSMENT
    // ════════════════════════════════════════════════════════════

    private suspend fun fullAssessment(params: Map<String, String>): ToolResult {
        return try {
            val assessment = computeAssessment(params)

            val report = buildString {
                appendLine("🏛️ TATHMINI YA KODI — Msaidizi")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                // Revenue summary
                appendLine("💰 MAPATO YAKO:")
                appendLine("   Mwezi: KES ${"%,.0f".format(assessment.monthlyRevenue)}")
                appendLine("   Mwaka (makadirio): KES ${"%,.0f".format(assessment.annualRevenue)}")
                appendLine()

                // TOT assessment
                appendLine("📋 TURNOVER TAX (TOT):")
                if (assessment.totApplicable) {
                    appendLine("   🔴 UNAPASWA KULIPA TOT!")
                    appendLine("   Kodi: 1% ya mauzo = KES ${"%,.0f".format(assessment.totAmount)}/mwaka")
                    appendLine("   Hiyo ni KES ${"%,.0f".format(assessment.totAmount / 12)}/mwezi")
                } else if (assessment.annualRevenue >= 800_000) {
                    appendLine("   🟡 Karibu na kikomo cha TOT (KES 1M)")
                    appendLine("   Mapato yako: KES ${"%,.0f".format(assessment.annualRevenue)}")
                    appendLine("   Ukifika KES 1M, utalipa 1% ya mauzo.")
                } else {
                    appendLine("   🟢 Haulipi TOT — mapato yako ni chini ya KES 1M/mwaka")
                }
                appendLine()

                // VAT threshold
                appendLine("📊 VAT THRESHOLD:")
                val vatPct = assessment.vatThresholdProximity
                when {
                    assessment.vatRegistrationRequired -> {
                        appendLine("   🔴 UNAPASWA KUJISAJILI VAT!")
                        appendLine("   Mapato yako yamezidi KES 5M/mwaka.")
                    }
                    vatPct > 80 -> {
                        appendLine("   🟡 Karibu sana! ${"%.0f".format(vatPct)}% ya kikomo cha VAT")
                        appendLine("   Ukizidi KES 5M, lazima ujisajili VAT.")
                    }
                    vatPct > 50 -> {
                        appendLine("   🟡 ${"%.0f".format(vatPct)}% ya kikomo cha VAT (KES 5M)")
                    }
                    else -> {
                        appendLine("   🟢 ${"%.0f".format(vatPct)}% ya kikomo — bado mbali")
                    }
                }
                appendLine()

                // NHIF/NSSF
                if (assessment.nhifApplicable || assessment.nssfApplicable) {
                    appendLine("👥 NHIF/NSSF:")
                    if (assessment.nhifApplicable) appendLine("   • NHIF: Inahitajika kwa wafanyakazi")
                    if (assessment.nssfApplicable) appendLine("   • NSSF: Inahitajika kwa wafanyakazi")
                    appendLine()
                }

                // Total estimated tax
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 KODI JUMLA (makadirio):")
                appendLine("   KES ${"%,.0f".format(assessment.estimatedMonthlyTax)}/mwezi")
                appendLine("   KES ${"%,.0f".format(assessment.estimatedMonthlyTax * 12)}/mwaka")
                appendLine("   Hiyo ni ${"%.1f".format(if (assessment.monthlyRevenue > 0) assessment.estimatedMonthlyTax / assessment.monthlyRevenue * 100 else 0.0)}% ya mapato yako")
                appendLine()

                // Warnings
                if (assessment.warningsSwahili.isNotEmpty()) {
                    appendLine("⚠️ TAHADHARI:")
                    assessment.warningsSwahili.forEach { w ->
                        appendLine("   • $w")
                    }
                    appendLine()
                }

                // Next filing
                appendLine("📅 FILING INAYOFUATA: ${assessment.nextFilingDate}")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "annualRevenue" to assessment.annualRevenue,
                    "monthlyRevenue" to assessment.monthlyRevenue,
                    "totApplicable" to assessment.totApplicable,
                    "totAmount" to assessment.totAmount,
                    "vatThresholdProximity" to assessment.vatThresholdProximity,
                    "vatRegistrationRequired" to assessment.vatRegistrationRequired,
                    "estimatedMonthlyTax" to assessment.estimatedMonthlyTax,
                    "nextFilingDate" to assessment.nextFilingDate
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute tax assessment")
            ToolResult.error(name, "Imeshindwa: ${e.message}", "DB_ERROR")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TOT CALCULATOR
    // ════════════════════════════════════════════════════════════

    private suspend fun totCalculator(params: Map<String, String>): ToolResult {
        // Use provided revenue or estimate from data
        val monthlyRevenue = params["monthly_revenue"]?.toDoubleOrNull()
            ?: estimateMonthlyRevenue()
        val annualRevenue = params["annual_revenue"]?.toDoubleOrNull()
            ?: (monthlyRevenue * 12)

        val totApplicable = annualRevenue in 1_000_000.0..25_000_000.0
        val totRate = 0.01 // 1%
        val totAmount = if (totApplicable) annualRevenue * totRate else 0.0
        val monthlyTot = totAmount / 12

        val report = buildString {
            appendLine("📋 KIKOKOTOO CHA TOT (Turnover Tax)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("Mapato ya mwezi: KES ${"%,.0f".format(monthlyRevenue)}")
            appendLine("Mapato ya mwaka: KES ${"%,.0f".format(annualRevenue)}")
            appendLine()

            if (totApplicable) {
                appendLine("✅ TOT inatumika: 1% ya mauzo")
                appendLine("   KODI: KES ${"%,.0f".format(totAmount)}/mwaka")
                appendLine("   KES ${"%,.0f".format(monthlyTot)}/mwezi")
                appendLine()
                appendLine("💡 TOT ni rahisi — hauna hesabu ngapi. Lipa 1% ya mauzo yako.")
            } else if (annualRevenue < 1_000_000) {
                val remaining = 1_000_000 - annualRevenue
                appendLine("🟢 Haulipi TOT — mapato yako ni chini ya KES 1M/mwaka")
                appendLine("   Unahitaji KES ${"%,.0f".format(remaining)} zaidi kufikia kikomo.")
            } else {
                appendLine("🔴 Mapato yako yamezidi KES 25M — TOT haifai.")
                appendLine("   Unahitaji kujisajili kama business ya kawaida (income tax).")
            }

            appendLine()
            appendLine("📅 TOT inalipwa kila mwezi, kabla ya tarehe 20.")
        }

        return ToolResult.success(
            name,
            data = mapOf(
                "annualRevenue" to annualRevenue,
                "totApplicable" to totApplicable,
                "totAmount" to totAmount,
                "monthlyTot" to monthlyTot
            ),
            message = report
        )
    }

    // ════════════════════════════════════════════════════════════
    //  VAT THRESHOLD MONITOR
    // ════════════════════════════════════════════════════════════

    private suspend fun vatMonitor(params: Map<String, String>): ToolResult {
        val monthlyRevenue = params["monthly_revenue"]?.toDoubleOrNull()
            ?: estimateMonthlyRevenue()
        val annualRevenue = params["annual_revenue"]?.toDoubleOrNull()
            ?: (monthlyRevenue * 12)

        val vatThreshold = 5_000_000.0 // KES 5M annual
        val proximity = (annualRevenue / vatThreshold * 100).coerceAtMost(100.0)
        val required = annualRevenue >= vatThreshold
        val remaining = (vatThreshold - annualRevenue).coerceAtLeast(0.0)

        val report = buildString {
            appendLine("📊 VAT THRESHOLD MONITOR")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("Kikomo cha VAT: KES ${"%,.0f".format(vatThreshold)}/mwaka")
            appendLine("Mapato yako: KES ${"%,.0f".format(annualRevenue)}/mwaka")
            appendLine("Ukaribu: ${"%.1f".format(proximity)}%")
            appendLine()

            // Visual progress bar
            val barLength = 20
            val filled = (proximity / 100 * barLength).toInt().coerceIn(0, barLength)
            val bar = "█".repeat(filled) + "░".repeat(barLength - filled)
            appendLine("   [$bar] ${"%.0f".format(proximity)}%")
            appendLine()

            when {
                required -> {
                    appendLine("🔴 UNAPASWA KUJISAJILI VAT!")
                    appendLine("   Mapato yako yamezidi KES 5M/mwaka.")
                    appendLine("   VAT ni 16% ya mauzo — unaongeza bei kwa wateja.")
                    appendLine("   Jisajili kwa KRA iTax: itax.kra.go.ke")
                }
                proximity > 80 -> {
                    appendLine("🟡 KARIBU SANA! Umefika ${"%.0f".format(proximity)}% ya kikomo.")
                    appendLine("   Unahitaji KES ${"%,.0f".format(remaining)} tu zaidi.")
                    appendLine("   Jitayarishe kwa kujisajili VAT mapema.")
                }
                proximity > 50 -> {
                    appendLine("🟡 Umefika ${"%.0f".format(proximity)}% ya kikomo.")
                    appendLine("   Bado una nafasi: KES ${"%,.0f".format(remaining)}.")
                }
                else -> {
                    appendLine("🟢 Bado mbali — ${"%.0f".format(proximity)}% tu ya kikomo.")
                    appendLine("   Relax — haulipi VAT bado.")
                }
            }

            appendLine()
            appendLine("💡 VAT ni 16% ya mauzo. Unaweza kuongeza bei kidogo kwa wateja.")
        }

        return ToolResult.success(
            name,
            data = mapOf(
                "vatThreshold" to vatThreshold,
                "annualRevenue" to annualRevenue,
                "proximity" to proximity,
                "required" to required,
                "remaining" to remaining
            ),
            message = report
        )
    }

    // ════════════════════════════════════════════════════════════
    //  TAX CALENDAR
    // ════════════════════════════════════════════════════════════

    private fun taxCalendar(params: Map<String, String>): ToolResult {
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH) + 1
        val currentYear = calendar.get(Calendar.YEAR)

        val report = buildString {
            appendLine("📅 RATIBA YA KODI — Kenya $currentYear")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("📋 TURNOVER TAX (TOT):")
            appendLine("   • Inalipwa kila mwezi")
            appendLine("   • Tarehe ya mwisho: 20 ya kila mwezi")
            appendLine("   • Lipa kupitia KRA iTax portal")
            appendLine()
            appendLine("📊 VAT:")
            appendLine("   • Inalipwa kila mwezi")
            appendLine("   • Tarehe ya mwisho: 20 ya mwezi unaofuata")
            appendLine("   • Lipa kupitia KRA iTax portal")
            appendLine()
            appendLine("💰 INCOME TAX:")
            appendLine("   • Makadirio: kila mwezi (tarehe 20)")
            appendLine("   • Final: kabla ya 30 Juni ya mwaka ujao")
            appendLine()
            appendLine("👥 NHIF/NSSF:")
            appendLine("   • NHIF: tarehe 9 ya kila mwezi")
            appendLine("   • NSSF: tarehe 15 ya kila mwezi")
            appendLine()

            // Next filing dates
            appendLine("📅 TAREHE ZINAZOFUATA:")
            val nextTOT = "20 ya mwezi huu"
            appendLine("   • TOT: $nextTOT")
            appendLine("   • VAT: 20 ya mwezi unaofuata")
            appendLine("   • NHIF: 9 ya mwezi unaofuata")
            appendLine("   • NSSF: 15 ya mwezi unaofuata")
        }

        return ToolResult.success(name, message = report)
    }

    // ════════════════════════════════════════════════════════════
    //  ANNUAL ESTIMATE
    // ════════════════════════════════════════════════════════════

    private suspend fun annualEstimate(params: Map<String, String>): ToolResult {
        val assessment = computeAssessment(params)

        val report = buildString {
            appendLine("📊 MAKADIRIO YA KODI YA MWAKA")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Mapato: KES ${"%,.0f".format(assessment.annualRevenue)}")
            appendLine("TOT: KES ${"%,.0f".format(assessment.totAmount)}")
            appendLine("NHIF: ~KES 6,000-72,000 (kulingana na level)")
            appendLine("NSSF: ~KES 4,800 (Tier I + II)")
            appendLine()
            appendLine("JUMLA: ~KES ${"%,.0f".format(assessment.totAmount + 12_000)}/mwaka")
            appendLine("Kwa mwezi: ~KES ${"%,.0f".format((assessment.totAmount + 12_000) / 12)}")
        }

        return ToolResult.success(name, message = report)
    }

    // ════════════════════════════════════════════════════════════
    //  FILING REMINDER
    // ════════════════════════════════════════════════════════════

    private fun filingReminder(params: Map<String, String>): ToolResult {
        val report = buildString {
            appendLine("🔔 KUMBUKUMBU ZA KODI")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("Kumbuka kulipa kodi zako kwa wakati!")
            appendLine()
            appendLine("📋 TOT: Lipa kabla ya 20 ya kila mwezi")
            appendLine("📊 VAT: Lipa kabla ya 20 ya mwezi unaofuata")
            appendLine("👥 NHIF: Lipa kabla ya 9 ya kila mwezi")
            appendLine("👥 NSSF: Lipa kabla ya 15 ya kila mwezi")
            appendLine()
            appendLine("💡 Duka la KRA: itax.kra.go.ke")
            appendLine("💡 Unahitaji KRA PIN kujisajili.")
        }

        return ToolResult.success(name, message = report)
    }

    // ════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════

    private suspend fun computeAssessment(params: Map<String, String>): TaxAssessment {
        val monthlyRevenue = params["monthly_revenue"]?.toDoubleOrNull()
            ?: estimateMonthlyRevenue()
        val annualRevenue = params["annual_revenue"]?.toDoubleOrNull()
            ?: (monthlyRevenue * 12)
        val employeeCount = params["employee_count"]?.toIntOrNull() ?: 0

        // TOT: 1% for KES 1M-25M annual revenue
        val totApplicable = annualRevenue in 1_000_000.0..25_000_000.0
        val totAmount = if (totApplicable) annualRevenue * 0.01 else 0.0

        // VAT: mandatory above KES 5M
        val vatThreshold = 5_000_000.0
        val vatProximity = (annualRevenue / vatThreshold * 100).coerceAtMost(100.0)
        val vatRequired = annualRevenue >= vatThreshold

        // NHIF/NSSF
        val nhifApplicable = employeeCount > 0
        val nssfApplicable = employeeCount > 0

        // Total estimated monthly tax
        var monthlyTax = totAmount / 12
        if (nhifApplicable) monthlyTax += 500 * employeeCount // approximate NHIF
        if (nssfApplicable) monthlyTax += 400 * employeeCount // approximate NSSF

        // Warnings
        val warnings = mutableListOf<String>()
        val warningsSw = mutableListOf<String>()
        if (annualRevenue > 800_000 && !totApplicable) {
            warnings.add("Approaching TOT threshold — KES ${"%,.0f".format(1_000_000 - annualRevenue)} remaining")
            warningsSw.add("Karibu na kikomo cha TOT — KES ${"%,.0f".format(1_000_000 - annualRevenue)} imebaki")
        }
        if (vatProximity > 80) {
            warnings.add("Approaching VAT registration — ${"%.0f".format(vatProximity)}%")
            warningsSw.add("Karibu kujisajili VAT — ${"%.0f".format(vatProximity)}%")
        }

        // Next filing date
        val cal = Calendar.getInstance()
        val nextFiling = "20 ya mwezi unaofuata"

        return TaxAssessment(
            annualRevenue = annualRevenue,
            monthlyRevenue = monthlyRevenue,
            totApplicable = totApplicable,
            totAmount = totAmount,
            vatThresholdProximity = vatProximity,
            vatRegistrationRequired = vatRequired,
            nhifApplicable = nhifApplicable,
            nssfApplicable = nssfApplicable,
            estimatedMonthlyTax = monthlyTax,
            nextFilingDate = nextFiling,
            warnings = warnings,
            warningsSwahili = warningsSw
        )
    }

    private suspend fun estimateMonthlyRevenue(): Double {
        return try {
            val now = System.currentTimeMillis()
            val thirtyDaysAgo = now - 30 * 86_400_000L
            val sales = saleDao.getTotalSalesBetween(thirtyDaysAgo, now).first() ?: 0.0
            val services = serviceTransactionDao.getTotalRevenueBetween(thirtyDaysAgo, now).first() ?: 0.0
            sales + services
        } catch (e: Exception) {
            0.0
        }
    }
}
