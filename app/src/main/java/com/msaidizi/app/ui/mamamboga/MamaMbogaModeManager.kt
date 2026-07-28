package com.msaidizi.app.ui.mamamboga

import com.msaidizi.app.model.BusinessType
import com.msaidizi.app.model.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MamaMbogaModeManager — Controls UI simplification for mama mboga segment.
 *
 * Research shows mama mbogas need 5 tools, not 52.
 * This manager filters the tool registry and navigation to expose only
 * the tools that matter for vegetable vendors.
 *
 * Core 5 tools:
 *   1. RecordTransaction — record sales/expenses (voice or tap)
 *   2. MarketPriceLookup — check wholesale prices
 *   3. CashFlowPredict — 7-day cash flow forecast
 *   4. DailySummary — today's profit/loss
 *   5. AlamaScore — credit readiness score
 *
 * Everything else is hidden behind "Zaidi" (More) menu.
 */
@Singleton
class MamaMbogaModeManager @Inject constructor() {

    /**
     * Tools visible in simplified mode (mama mboga).
     * 5 core tools only — everything else goes to "More" menu.
     */
    val coreTools = setOf(
        "record_transaction",   // Record sales, expenses, purchases
        "market_price_lookup",  // Check wholesale market prices
        "cashflow_predict",     // 7-day cash flow forecast
        "daily_summary",        // Today's profit/loss
        "alama_score"           // Credit readiness score
    )

    /**
     * Bottom navigation tabs for simplified mode.
     * 5 screens: Home, Record, Market, Credit, Reports
     */
    val coreScreens = listOf(
        SimplifiedTab.HOME,
        SimplifiedTab.RECORD,
        SimplifiedTab.MARKET,
        SimplifiedTab.CREDIT,
        SimplifiedTab.REPORTS
    )

    /**
     * Check if the current user profile qualifies for simplified mode.
     * Mama mboga, mama lishe, and similar food-trade businesses get simplified UI.
     */
    fun isSimplifiedMode(profile: UserProfileEntity?): Boolean {
        if (profile == null) return true // Default to simplified for new users
        val businessType = try {
            BusinessType.valueOf(profile.businessProfile)
        } catch (e: Exception) {
            // If business profile is JSON or unrecognized, check keywords
            val lower = profile.businessProfile.lowercase()
            return lower.contains("mama_mboga") || lower.contains("mama mboga") ||
                   lower.contains("vegetable") || lower.contains("mama_lishe") ||
                   lower.contains("food vendor")
        }
        return businessType in simplifiedBusinessTypes
    }

    /**
     * Business types that get the simplified UI.
     */
    private val simplifiedBusinessTypes = setOf(
        BusinessType.MAMA_MBOGA,
        BusinessType.MAMA_LISHE,
        BusinessType.CHAPATI_SELLER,
        BusinessType.WATER_SELLER,
        BusinessType.MACHINGA
    )

    /**
     * Filter a list of tools to only show core tools in simplified mode.
     * Returns tool names that should be visible.
     */
    fun filterTools(allToolNames: List<String>, simplified: Boolean): List<String> {
        if (!simplified) return allToolNames
        return allToolNames.filter { it in coreTools }
    }

    /**
     * Get tools for the "More" (Zaidi) menu in simplified mode.
     * These are all non-core tools grouped by category.
     */
    fun getMoreMenuTools(allToolNames: List<String>): List<MoreMenuGroup> {
        val moreTools = allToolNames.filter { it !in coreTools }
        return moreTools.groupBy { categorizeTool(it) }
            .map { (category, tools) ->
                MoreMenuGroup(
                    category = category,
                    categorySw = categoryTranslations[category] ?: category,
                    tools = tools
                )
            }
            .sortedBy { it.category }
    }

    private fun categorizeTool(toolName: String): String {
        return when (toolName) {
            "inventory_tracker", "auto_restock", "restock_predictor", "spoilage_tracker" -> "Inventory"
            "chama_manager", "debt_tracker", "loan_comparison", "insurance_matcher" -> "Finance"
            "customer_insights", "customer_matcher", "rating_system" -> "Customers"
            "bulk_order_coordinator", "market_pooling", "supplier_matcher" -> "Procurement"
            "gamification_engine", "goal_tracker" -> "Motivation"
            "whatsapp_reporter", "proof_of_income" -> "Reports"
            "voice_pipeline", "code_switch_handler", "language_detector" -> "Voice"
            "job_matcher", "booking_scheduler" -> "Services"
            "harvest_tracker", "yield_predictor", "post_harvest_loss_tracker" -> "Agriculture"
            "produce_price_tracker", "price_negotiator", "pricing_advisor" -> "Pricing"
            "competitor_tracker", "customer_insights" -> "Intelligence"
            else -> "Other"
        }
    }

    private val categoryTranslations = mapOf(
        "Inventory" to "Hifadhi ya Bidhaa",
        "Finance" to "Fedha",
        "Customers" to "Wateja",
        "Procurement" to "Ununuzi",
        "Motivation" to "Motisha",
        "Reports" to "Ripoti",
        "Voice" to "Sauti",
        "Services" to "Huduma",
        "Agriculture" to "Kilimo",
        "Pricing" to "Bei",
        "Intelligence" to "Upelelezi",
        "Other" to "Nyingine"
    )
}

/**
 * Simplified bottom navigation tabs.
 */
enum class SimplifiedTab(
    val route: String,
    val iconSw: String,
    val labelSw: String,
    val labelEn: String
) {
    HOME("home", "🏠", "Nyumbani", "Home"),
    RECORD("record", "➕", "Rekodi", "Record"),
    MARKET("market", "🛒", "Soko", "Market"),
    CREDIT("credit", "💳", "Mkopo", "Credit"),
    REPORTS("reports_simplified", "📊", "Ripoti", "Reports")
}

/**
 * A group of tools for the "More" menu.
 */
data class MoreMenuGroup(
    val category: String,
    val categorySw: String,
    val tools: List<String>
)
