package com.msaidizi.app.agent.tools

import com.msaidizi.app.agent.IntentResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ToolRegistry — The capability registry for the SuperAgent.
 * 
 * ONE agent, 22 tools. NOT 33 agents.
 * Each tool is a self-contained capability that the cognitive loop can invoke.
 * 
 * Tools replace: TransactionHandler, QueryHandler, AdviceHandler, 
 * GamificationHandler, DomainRouter, and all domain agents.
 * 
 * Design: arch_android.md Section 1.2
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val tools: Set<@JvmSuppressWildcards Tool>
) {
    /**
     * Find the best tool for a given intent.
     * Returns null if no tool matches (will trigger LLM escalation).
     */
    fun findTool(intent: IntentResult): Tool? {
        return tools.find { it.canHandle(intent.type) }
    }
    
    /**
     * Find a tool by name (for planned execution).
     */
    fun findToolByName(name: String): Tool? {
        return tools.find { it.name == name }
    }
    
    /**
     * Get all registered tools (for diagnostics).
     */
    fun getAllTools(): List<Tool> = tools.toList()
}

// ── Financial Tools ──────────────────────────────────────────

/** Record a transaction (sale, purchase, expense) */
class TransactionTool : Tool {
    override val name = "transaction"
    override val description = "Record a business transaction"
    override val supportedIntents = listOf("sale", "purchase", "expense", "record_transaction")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val amount = args["amount"] as? Double ?: return ToolResult(
            text = "Kiasi si sahihi. Tafadhaliambia kiasi.",
            data = emptyMap(), success = false, errorCode = "INVALID_AMOUNT"
        )
        val item = args["item"] as? String ?: "bidhaa"
        val type = args["type"] as? String ?: "sale"
        
        // TODO: Store in Room database
        return ToolResult(
            text = "Imerekodiwa: $type ya $item — KSh ${"%,.0f".format(amount)}",
            data = mapOf("amount" to amount, "item" to item, "type" to type),
            success = true
        )
    }
    
    override fun onLowMemory() {}
}

/** Query balance, profit, expenses */
class QueryTool : Tool {
    override val name = "query"
    override val description = "Query financial information"
    override val supportedIntents = listOf("balance", "profit", "expenses", "revenue", "query_sales")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        // TODO: Query Room database
        return ToolResult(
            text = "Data bado haijapatikana. Rekodi mauzo yako kwanza.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

/** Get daily/weekly/monthly summaries */
class SummaryTool : Tool {
    override val name = "summary"
    override val description = "Get business summaries"
    override val supportedIntents = listOf("daily_summary", "weekly_summary", "monthly_summary")
    override val memoryRequiredMB = 10
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        // TODO: Generate summary from Room database
        return ToolResult(
            text = "Muhtasari bado haupatikani.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Inventory Tools ──────────────────────────────────────────

/** Check and update inventory */
class InventoryTool : Tool {
    override val name = "inventory"
    override val description = "Manage business inventory"
    override val supportedIntents = listOf("stock_check", "stock_update", "low_stock", "inventory_bulk")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val action = args["action"] as? String ?: "check"
        val item = args["item"] as? String ?: "bidhaa"
        
        return when (action) {
            "check" -> ToolResult(
                text = "Stock ya $item: bado haijarekodiwa.",
                data = mapOf("item" to item, "quantity" to 0),
                success = true
            )
            "update" -> ToolResult(
                text = "Stock ya $item imebadilishwa.",
                data = mapOf("item" to item),
                success = true
            )
            else -> ToolResult(text = "Amri si sahihi.", data = emptyMap(), success = false)
        }
    }
    
    override fun onLowMemory() {}
}

// ── Market Tools ─────────────────────────────────────────────

/** Get market prices and trends */
class MarketTool : Tool {
    override val name = "market"
    override val description = "Market intelligence and pricing"
    override val supportedIntents = listOf("price_check", "market_trend", "compare_analysis")
    override val memoryRequiredMB = 10
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val item = args["item"] as? String ?: "bidhaa"
        
        // TODO: Query Soko Pulse API or cached data
        return ToolResult(
            text = "Bei za soko za $item: bado hazijapatikana.",
            data = mapOf("item" to item),
            success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Credit Tools ─────────────────────────────────────────────

/** Check Alama Score and credit readiness */
class CreditTool : Tool {
    override val name = "credit"
    override val description = "Credit scoring and loan information"
    override val supportedIntents = listOf("credit_score", "loan_apply", "loan_status")
    override val memoryRequiredMB = 10
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Alama Score yako bado haijakokotolewa. Endelea kurekodi miamala.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Savings Tools ────────────────────────────────────────────

/** Track savings goals and progress */
class SavingsTool : Tool {
    override val name = "savings"
    override val description = "Savings goals and tracking"
    override val supportedIntents = listOf("savings_check", "savings_goal", "savings_update")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Malengo ya akiba: bado hayajawekwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Advice Tools ─────────────────────────────────────────────

/** Provide business advice */
class AdviceTool : Tool {
    override val name = "advice"
    override val description = "Business advice and recommendations"
    override val supportedIntents = listOf("advice", "recommendation", "help")
    override val memoryRequiredMB = 15
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Ushauri: Rekodi kila miamala yako kila siku.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Gamification Tools ───────────────────────────────────────

/** Track points, streaks, badges */
class GamificationTool : Tool {
    override val name = "gamification"
    override val description = "Points, streaks, and rewards"
    override val supportedIntents = listOf("points", "streak", "badge", "level")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Pointi zako: bado hazijahesabiwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Tithe Tools ──────────────────────────────────────────────

/** Track tithes and charitable giving */
class TitheTool : Tool {
    override val name = "tithe"
    override val description = "Tithe and charitable giving tracking"
    override val supportedIntents = listOf("tithe", "giving", "zakat")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Zakat/Toleo: bado halijarekodiwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── M-Pesa Tools ─────────────────────────────────────────────

/** Handle M-Pesa transactions */
class MpesaTool : Tool {
    override val name = "mpesa"
    override val description = "M-Pesa transaction parsing and recording"
    override val supportedIntents = listOf("mpesa_transaction", "mpesa_balance", "mpesa_send")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        val message = args["message"] as? String ?: ""
        
        // TODO: Parse M-Pesa SMS
        return ToolResult(
            text = "M-Pesa: bado haijaunganishwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Voice Tools ──────────────────────────────────────────────

/** Handle voice input/output */
class VoiceTool : Tool {
    override val name = "voice"
    override val description = "Voice input and output"
    override val supportedIntents = listOf("voice_input", "voice_output", "speak", "listen")
    override val memoryRequiredMB = 40
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Sauti: tayari.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Goal Tools ───────────────────────────────────────────────

/** Track financial goals */
class GoalTool : Tool {
    override val name = "goal"
    override val description = "Financial goal tracking"
    override val supportedIntents = listOf("goal_set", "goal_check", "goal_progress")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Malengo: bado hayajawekwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Loan Tools ───────────────────────────────────────────────

/** Track loans and repayments */
class LoanTool : Tool {
    override val name = "loan"
    override val description = "Loan tracking and repayment"
    override val supportedIntents = listOf("loan_check", "loan_repayment", "loan_history")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Mikopo: bado haijarekodiwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Briefing Tools ───────────────────────────────────────────

/** Generate daily/weekly briefings */
class BriefingTool : Tool {
    override val name = "briefing"
    override val description = "Daily business briefings"
    override val supportedIntents = listOf("daily_briefing", "morning_report", "evening_summary")
    override val memoryRequiredMB = 15
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Muhtasari wa leo: bado haujapatikana.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Education Tools ──────────────────────────────────────────

/** Financial literacy and Rich Habits */
class EducationTool : Tool {
    override val name = "education"
    override val description = "Financial literacy and business education"
    override val supportedIntents = listOf("rich_habits", "mindset", "financial_tip")
    override val memoryRequiredMB = 10
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Tabia ya tajiri: Rekodi kila miamala yako.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Receipt Tools ────────────────────────────────────────────

/** Scan and process receipts */
class ReceiptTool : Tool {
    override val name = "receipt"
    override val description = "Receipt scanning and OCR"
    override val supportedIntents = listOf("receipt_scan", "receipt_process")
    override val memoryRequiredMB = 50
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Skani risiti: bado haijaunganishwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Community Tools ──────────────────────────────────────────

/** Community features and peer comparison */
class CommunityTool : Tool {
    override val name = "community"
    override val description = "Community features and peer comparison"
    override val supportedIntents = listOf("peer_compare", "community_tip", "leaderboard")
    override val memoryRequiredMB = 10
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Jamii: bado haijaunganishwa.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

// ── Domain-Specific Tools ────────────────────────────────────

/** Transport-specific (boda-boda, matatu) */
class TransportTool : Tool {
    override val name = "transport"
    override val description = "Transport business intelligence"
    override val supportedIntents = listOf("transport_income", "transport_fuel", "transport_route")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Usafiri: bado haijapatikana.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

/** Farming-specific */
class FarmingTool : Tool {
    override val name = "farming"
    override val description = "Agricultural business intelligence"
    override val supportedIntents = listOf("farming_harvest", "farming_input", "farming_market")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Kilimo: bado haijapatikana.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

/** Digital services (freelance, content) */
class DigitalTool : Tool {
    override val name = "digital"
    override val description = "Digital worker intelligence"
    override val supportedIntents = listOf("digital_income", "digital_client", "digital_project")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Kazi ya kidijitali: bado haijapatikana.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

/** Service business (salon, mechanic) */
class ServiceTool : Tool {
    override val name = "service"
    override val description = "Service business intelligence"
    override val supportedIntents = listOf("service_client", "service_pricing", "service_schedule")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Huduma: bado haijapatikana.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}

/** Retail shop (duka) */
class RetailTool : Tool {
    override val name = "retail"
    override val description = "Retail shop intelligence"
    override val supportedIntents = listOf("retail_sales", "retail_stock", "retail_customer")
    override val memoryRequiredMB = 5
    
    override suspend fun execute(args: Map<String, Any>, language: String): ToolResult {
        return ToolResult(
            text = "Duka: bado haijapatikana.",
            data = emptyMap(), success = true
        )
    }
    
    override fun onLowMemory() {}
}
