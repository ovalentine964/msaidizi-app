package com.msaidizi.app.agent.tools

import com.msaidizi.app.agent.LlmEngine
import com.msaidizi.app.data.dao.*
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
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao,
    private val goalDao: GoalDao,
    private val loanDao: LoanDao,
    private val givingDao: GivingDao,
    private val llmEngine: LlmEngine
) {
    private val tools: Map<String, Tool> by lazy {
        buildMap {
            // Financial tools
            put("record_sale", RecordSaleTool(transactionDao, inventoryDao))
            put("record_purchase", RecordPurchaseTool(transactionDao, inventoryDao))
            put("record_expense", RecordExpenseTool(transactionDao))
            put("check_balance", CheckBalanceTool(transactionDao))
            put("check_profit", CheckProfitTool(transactionDao))
            put("check_stock", CheckStockTool(inventoryDao))
            put("daily_summary", DailySummaryTool(transactionDao))
            put("weekly_summary", WeeklySummaryTool(transactionDao))
            put("inventory", InventoryTool(inventoryDao))

            // M-Pesa
            put("mpesa", MpesaTool(RecordSaleTool(transactionDao, inventoryDao)))

            // Goals & Loans
            put("goal", GoalTool(goalDao))
            put("loan", LoanTool(loanDao))
            put("giving", GivingTool(givingDao))
            put("tithe", TitheTool(givingDao))

            // Advice & Education
            put("advice", AdviceTool(llmEngine))
            put("education", EducationTool())

            // Gamification & Community
            put("gamification", GamificationTool())
            put("community", CommunityTool())

            // Domain-specific
            put("transport", TransportTool(transactionDao))
            put("farming", FarmingTool(transactionDao, inventoryDao))
            put("digital", DigitalTool(transactionDao))
            put("service", ServiceTool(transactionDao))
            put("retail", RetailTool(transactionDao, inventoryDao))

            // Market & Credit
            put("market", MarketTool())
            put("credit", CreditTool(transactionDao))
            put("savings", SavingsTool(transactionDao))

            // Utility
            put("greeting", GreetingTool())
            put("help", HelpTool())
            put("correction", CorrectionTool(transactionDao))
            put("receipt", ReceiptTool())
            put("voice", VoiceTool())
            put("briefing", BriefingTool(transactionDao, goalDao, loanDao))
        }
    }

    /**
     * Find the best tool for a given intent.
     * Returns null if no tool matches (will trigger LLM escalation).
     */
    fun findTool(intent: com.msaidizi.app.agent.IntentResult): Tool? {
        return findToolByIntent(intent.type)
    }

    /**
     * Find tool by intent string.
     */
    fun findToolByIntent(intent: String): Tool? {
        // Direct mapping from intent to tool name
        val intentToTool = mapOf(
            "sale" to "record_sale",
            "record_sale" to "record_sale",
            "purchase" to "record_purchase",
            "record_purchase" to "record_purchase",
            "expense" to "record_expense",
            "record_expense" to "record_expense",
            "check_balance" to "check_balance",
            "balance" to "check_balance",
            "profit_query" to "check_profit",
            "check_profit" to "check_profit",
            "stock_query" to "check_stock",
            "check_stock" to "check_stock",
            "daily_summary" to "daily_summary",
            "weekly_summary" to "weekly_summary",
            "advice" to "advice",
            "recommendation" to "advice",
            "ask_advice" to "advice",
            "greeting" to "greeting",
            "help" to "help",
            "correction" to "correction",
            "goal_set" to "goal",
            "goal_check" to "goal",
            "goal_progress" to "goal",
            "goal_report" to "goal",
            "loan_record" to "loan",
            "loan_check" to "loan",
            "loan_repayment" to "loan",
            "loan_report" to "loan",
            "giving" to "giving",
            "tithe" to "tithe",
            "zakat" to "tithe",
            "mpesa" to "mpesa",
            "mpesa_transaction" to "mpesa",
            "transport" to "transport",
            "transport_income" to "transport",
            "transport_expense" to "transport",
            "farming" to "farming",
            "farming_activity" to "farming",
            "farming_input" to "farming",
            "digital" to "digital",
            "digital_income" to "digital",
            "digital_client" to "digital",
            "service" to "service",
            "service_client" to "service",
            "service_job" to "service",
            "retail" to "retail",
            "retail_sales" to "retail",
            "retail_stock" to "retail",
            "credit_score" to "credit",
            "loan_apply" to "credit",
            "savings_check" to "savings",
            "savings_goal" to "savings",
            "price_check" to "market",
            "market_trend" to "market",
            "daily_briefing" to "briefing",
            "morning_report" to "briefing",
            "evening_summary" to "briefing",
            "receipt_scan" to "receipt",
            "rich_habits" to "education",
            "financial_tip" to "education",
            "points" to "gamification",
            "streak" to "gamification"
        )

        val toolName = intentToTool[intent] ?: return null
        return tools[toolName]
    }

    /**
     * Find a tool by name (for planned execution).
     */
    fun findToolByName(name: String): Tool? = tools[name]

    /**
     * Get all registered tools (for diagnostics).
     */
    fun getAllTools(): List<Tool> = tools.values.toList()

    /**
     * Get all tool names (for planning prompts).
     */
    fun getToolNames(): Set<String> = tools.keys
}
