package com.msaidizi.app.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import com.msaidizi.app.data.api.MsaidiziApi
import com.msaidizi.app.voice.LlmEngine
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.msaidizi.app.agent.moe.MoERouter
import com.msaidizi.app.agent.moe.ExpertRegistry
import com.msaidizi.app.agent.cost.InferenceCostTracker
import com.msaidizi.app.agent.cost.CostRecord
import com.msaidizi.app.agent.version.ModelVersionManager
import com.msaidizi.app.agent.multimodal.MultimodalPipeline
import com.msaidizi.app.core.util.DeviceCapability
import com.msaidizi.app.loops.ReflexionLoop
import com.msaidizi.app.loops.Critique
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ModelRouter — Hybrid Reasoning Model Router for Msaidizi.
 *
 * Routes inference requests between:
 * - On-device models (llama.cpp NDK, Qwen 0.5B) — offline, free, low-latency
 * - Cloud reasoning (DeepSeek V4 Flash) — cost-efficient reasoning ($0.20/1M)
 * - Cloud premium (GPT-5.4 nano, Claude Opus) — complex financial analysis
 * - Backend proxy (Angavu Intelligence) — full agent capabilities
 *
 * ## Cost Model (per user per month)
 * | Layer             | Queries/Day | Tokens/Query | Monthly Cost |
 * |-------------------|-------------|--------------|--------------|
 * | On-Device (free)  | 40          | 500          | $0.00        |
 * | Cloud Reasoning   | 8           | 2,000        | $0.01        |
 * | Cloud Premium     | 2           | 5,000        | $0.003       |
 * | **Total**         | **50**      | —            | **$0.013**   |
 *
 * ## Routing Strategy
 * - Simple queries (transactions, balance) → On-device (80%)
 * - Reasoning queries (credit, forecasting) → Cloud reasoning (15%)
 * - Complex analysis (growth strategy) → Cloud premium (5%)
 * - Test-time compute scaling: simple → instant, complex → extended thinking
 *
 * ## Fallback Chain
 * on-device → DeepSeek V4 Flash → GPT-5.4 nano → Claude Haiku → backend
 *
 * Based on Swarm 2 & Swarm 7 research
 */
class ModelRouter(
    private val context: Context,
    private val config: RouterConfig = RouterConfig(),
    private val llmEngine: LlmEngine? = null,
    private val apiClient: MsaidiziApi? = null
) {

    // ── Emerging Architecture Components (Swarm 7) ──
    private val moeRouter = MoERouter()
    private val expertRegistry = ExpertRegistry()
    private val costTracker = InferenceCostTracker()
    private val modelVersionManager = ModelVersionManager(context)
    private val multimodalPipeline = MultimodalPipeline(context, this)
    private val reflexionLoop = ReflexionLoop()

    // ═══════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════

    data class RouterConfig(
        val cacheSize: Int = 100,
        val maxPromptTokens: Int = 2048,
        val compressionThreshold: Int = 1500,
        val cloudTimeoutMs: Long = 15_000,
        val onDeviceTimeoutMs: Long = 10_000,
        val enableCache: Boolean = true,
        val preferOnDevice: Boolean = true,
        // Cost controls
        val monthlyBudgetMicros: Long = 13_000L, // $0.013 in micro-dollars
        val dailyBudgetMicros: Long = 433L,       // $0.013/30 per day
        val alertThresholdPct: Float = 0.8f,       // Alert at 80% budget
        // Reasoning controls
        val enableReasoningChains: Boolean = true,
        val maxReasoningDepth: Int = 5,
        val enableTestTimeCompute: Boolean = true,
        // MoE routing controls (Swarm 7)
        val enableMoERouting: Boolean = true,
        // Reflexion controls
        val enableReflexion: Boolean = true,
        val reflexionQualityThreshold: Double = 0.7,
        val reflexionMaxRetries: Int = 2,
        // Multimodal controls
        val enableMultimodal: Boolean = true,
        // Model versioning
        val preferredModelVersion: String = "auto"  // "auto", "qwen-0.5b", "qwen3.5-0.8b"
    )

    // ═══════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════

    data class InferenceRequest(
        val requestId: String,
        val messages: List<Map<String, String>>,
        val model: String? = null,
        val maxTokens: Int = 512,
        val temperature: Float = 0.7f,
        val taskComplexity: TaskComplexity = TaskComplexity.MEDIUM,
        val taskType: TaskType = TaskType.GENERAL,
        val userId: String? = null,
        val reasoningEffort: ReasoningEffort = ReasoningEffort.STANDARD,
        val financialTemplate: FinancialTemplate? = null
    )

    data class InferenceResponse(
        val requestId: String,
        val providerId: String,
        val modelUsed: String,
        val content: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val latencyMs: Long,
        val fromCache: Boolean = false,
        val compressionInfo: Map<String, Any> = emptyMap(),
        val reasoningChain: ReasoningChain? = null,
        val costMicros: Long = 0
    )

    /**
     * Task complexity levels for routing decisions.
     * Maps to the Swarm 2 finding: simple → on-device, complex → cloud.
     */
    enum class TaskComplexity { LOW, MEDIUM, HIGH, CRITICAL }

    /**
     * Task types for financial reasoning template matching.
     */
    enum class TaskType {
        GENERAL,
        TRANSACTION_RECORDING,   // Simple → on-device
        BALANCE_INQUIRY,         // Simple → on-device
        PRICE_LOOKUP,            // Simple → on-device
        CASH_FLOW_ALERT,         // Simple on-device, complex → cloud
        CREDIT_ASSESSMENT,       // Cloud reasoning primary
        MARKET_FORECASTING,      // Cloud reasoning primary
        RISK_ASSESSMENT,         // Cloud reasoning primary
        GROWTH_PLANNING,         // Cloud premium primary
        DAILY_BRIEFING,          // Template on-device, content from cloud
        FINANCIAL_ANALYSIS,      // Cloud reasoning primary
        // Multimodal task types (Swarm 7)
        GOODS_RECOGNITION,       // Multimodal → vision expert
        RECEIPT_SCANNING,        // Multimodal → vision expert
        INVENTORY_SCAN,          // Multimodal → vision expert
        PRICE_COMPARISON         // Multimodal + reasoning
    }

    /**
     * Reasoning effort levels — test-time compute scaling.
     * Simple queries: instant (no thinking tokens)
     * Medium queries: short thinking (1-2 seconds)
     * Complex queries: extended thinking (5-10 seconds)
     */
    enum class ReasoningEffort(val maxThinkingTokens: Int) {
        NONE(0),        // Instant response, no thinking
        LIGHT(256),     // Quick reasoning
        STANDARD(512),  // Normal reasoning
        EXTENDED(1024), // Deep reasoning
        XHIGH(2048)     // Maximum reasoning (complex financial analysis)
    }

    enum class ProviderType { ON_DEVICE, CLOUD_REASONING, CLOUD_PREMIUM, BACKEND }

    /**
     * Provider definition with cost and capability metadata.
     * Costs are per 1M tokens (from Swarm 2 research).
     */
    data class Provider(
        val id: String,
        val type: ProviderType,
        val displayName: String,
        val models: List<String>,
        val costPer1kInput: Double = 0.0,
        val costPer1kOutput: Double = 0.0,
        val maxContextTokens: Int = 4096,
        val priority: Int = 100,
        val capabilities: List<String> = emptyList(),
        var isAvailable: Boolean = true,
        var consecutiveFailures: Int = 0,
        var avgLatencyMs: Long = 0,
        val totalRequests: AtomicLong = AtomicLong(0),
        val totalFailures: AtomicLong = AtomicLong(0)
    )

    // ═══════════════════════════════════════════════════════════════
    // REASONING CHAIN — For auditability and learning
    // ═══════════════════════════════════════════════════════════════

    /**
     * A reasoning chain stores the step-by-step reasoning process
     * for auditability and few-shot learning.
     *
     * Based on Swarm 2 finding: reasoning chains enable:
     * - Auditability of financial decisions
     * - Learning from successful reasoning patterns
     * - Debugging when reasoning goes wrong
     */
    data class ReasoningChain(
        val chainId: String,
        val requestId: String,
        val steps: MutableList<ReasoningStep> = mutableListOf(),
        val templateUsed: FinancialTemplate? = null,
        val modelUsed: String = "",
        val totalThinkingTokens: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
        var completedAt: Long = 0L,
        var success: Boolean = false
    ) {
        fun addStep(step: ReasoningStep) {
            steps.add(step)
        }

        fun complete(success: Boolean) {
            this.success = success
            this.completedAt = System.currentTimeMillis()
        }

        fun toMap(): Map<String, Any> = mapOf(
            "chain_id" to chainId,
            "request_id" to requestId,
            "steps" to steps.map { it.toMap() },
            "template" to (templateUsed?.name ?: "none"),
            "model" to modelUsed,
            "thinking_tokens" to totalThinkingTokens,
            "duration_ms" to (completedAt - startedAt),
            "success" to success
        )
    }

    data class ReasoningStep(
        val stepNumber: Int,
        val type: StepType,
        val content: String,
        val confidence: Double = 1.0,
        val tokenCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        enum class StepType { OBSERVE, THINK, ACT, REFLECT, TEMPLATE_INJECT }
        fun toMap(): Map<String, Any> = mapOf(
            "step" to stepNumber, "type" to type.name,
            "content" to content, "confidence" to confidence
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // FINANCIAL REASONING TEMPLATES
    // Pre-built reasoning templates for common informal economy tasks
    // ═══════════════════════════════════════════════════════════════

    enum class FinancialTemplate(val displayName: String, val requiredComplexity: TaskComplexity) {
        PRICE_ANALYSIS("Price Analysis", TaskComplexity.MEDIUM),
        CREDIT_ASSESSMENT("Credit Assessment", TaskComplexity.HIGH),
        CASH_FLOW_ANALYSIS("Cash Flow Analysis", TaskComplexity.MEDIUM),
        RISK_ASSESSMENT("Risk Assessment", TaskComplexity.HIGH),
        MARKET_FORECAST("Market Forecast", TaskComplexity.HIGH),
        GROWTH_PLANNING("Growth Planning", TaskComplexity.CRITICAL),
        DAILY_BRIEFING("Daily Briefing", TaskComplexity.LOW),
        INVENTORY_OPTIMIZATION("Inventory Optimization", TaskComplexity.MEDIUM),
        SUPPLIER_ANALYSIS("Supplier Analysis", TaskComplexity.MEDIUM),
        PROFITABILITY_ANALYSIS("Profitability Analysis", TaskComplexity.MEDIUM)
    }

    /**
     * Get the reasoning template prompt for a financial analysis task.
     * These templates guide the model's reasoning for common informal economy tasks.
     */
    fun getFinancialTemplatePrompt(template: FinancialTemplate, context: Map<String, String> = emptyMap()): String {
        return when (template) {
            FinancialTemplate.PRICE_ANALYSIS -> """
                You are analyzing pricing for an informal market vendor.
                
                Data: ${context["data"] ?: "No data provided"}
                Product: ${context["product"] ?: "Unknown"}
                
                Think step by step:
                1. What is the current price and how does it compare to market average?
                2. What factors affect this price (season, supply, demand, competition)?
                3. What is the optimal price considering customer loyalty and location?
                4. What is the expected impact on sales volume?
                
                Provide a clear recommendation with reasoning.
            """.trimIndent()

            FinancialTemplate.CREDIT_ASSESSMENT -> """
                You are assessing creditworthiness for an informal economy worker.
                
                Transaction history: ${context["history"] ?: "No history"}
                Business type: ${context["business_type"] ?: "Unknown"}
                
                Analyze these signals:
                1. Transaction consistency (frequency, regularity)
                2. Revenue trend (growing, stable, declining)
                3. Cash flow patterns (seasonal, cyclical)
                4. Risk factors (single supplier, open-air market, seasonal business)
                
                Alternative data signals to consider:
                - Mobile money transaction patterns
                - Utility bill payment consistency
                - Inventory turnover rate
                - Customer base diversity
                
                Provide credit score (0-100), risk level, and recommendation.
            """.trimIndent()

            FinancialTemplate.CASH_FLOW_ANALYSIS -> """
                You are analyzing cash flow for a small business.
                
                Income data: ${context["income"] ?: "No data"}
                Expense data: ${context["expenses"] ?: "No data"}
                Period: ${context["period"] ?: "This week"}
                
                Analyze:
                1. Net cash flow (income - expenses)
                2. Cash flow timing (when money comes in vs goes out)
                3. Safety buffer (days of expenses covered)
                4. Upcoming obligations (loan payments, restocking)
                
                Identify any cash crunch risks and suggest mitigation.
            """.trimIndent()

            FinancialTemplate.RISK_ASSESSMENT -> """
                You are assessing business risk for an informal vendor.
                
                Business profile: ${context["profile"] ?: "Unknown"}
                Market conditions: ${context["market"] ?: "Unknown"}
                
                Evaluate these risk dimensions:
                1. Market risk (competition, demand volatility)
                2. Supply chain risk (single supplier, logistics)
                3. Weather/environmental risk (open-air market, seasonal)
                4. Financial risk (debt level, cash reserves)
                5. Operational risk (theft, illness, market closure)
                
                For each risk, assess probability and impact.
                Suggest micro-insurance or mitigation strategies.
            """.trimIndent()

            FinancialTemplate.MARKET_FORECAST -> """
                You are forecasting market conditions for a vendor.
                
                Historical data: ${context["history"] ?: "No data"}
                Market type: ${context["market_type"] ?: "Local market"}
                
                Consider:
                1. Seasonal patterns (holidays, rainy season, school terms)
                2. Supply chain trends (input costs, availability)
                3. Demand patterns (customer behavior, competition)
                4. External factors (economic conditions, regulations)
                
                Provide 7-day and 30-day price/demand forecast with confidence levels.
            """.trimIndent()

            FinancialTemplate.GROWTH_PLANNING -> """
                You are creating a growth plan for a micro-entrepreneur.
                
                Current business: ${context["business"] ?: "Unknown"}
                Financial position: ${context["financials"] ?: "Unknown"}
                Goals: ${context["goals"] ?: "Not specified"}
                
                Create a realistic growth plan:
                1. Current state assessment (revenue, margins, capacity)
                2. Growth opportunities (new products, new locations, hiring)
                3. Investment requirements (capital, time, skills)
                4. Risk-adjusted ROI projections
                5. Milestone timeline (30/60/90 day goals)
                
                Be realistic for informal economy context.
            """.trimIndent()

            FinancialTemplate.DAILY_BRIEFING -> """
                Generate a morning briefing for a vendor.
                
                Yesterday's data: ${context["yesterday"] ?: "No data"}
                Current goals: ${context["goals"] ?: "No goals set"}
                Weather: ${context["weather"] ?: "Unknown"}
                
                Include:
                1. Yesterday's performance summary
                2. Today's action items (restock, pricing, marketing)
                3. Goal progress update
                4. Market insight or tip of the day
                5. Motivational message
                
                Keep it brief and actionable. Use simple language.
            """.trimIndent()

            FinancialTemplate.INVENTORY_OPTIMIZATION -> """
                You are optimizing inventory for a vendor.
                
                Current stock: ${context["stock"] ?: "Unknown"}
                Sales data: ${context["sales"] ?: "No data"}
                Storage capacity: ${context["capacity"] ?: "Unknown"}
                
                Optimize:
                1. Which items to restock (turnover rate analysis)
                2. Optimal order quantities (avoid stockouts and waste)
                3. Product mix recommendations (margin vs volume)
                4. Timing of purchases (price cycles, supplier schedules)
            """.trimIndent()

            FinancialTemplate.SUPPLIER_ANALYSIS -> """
                You are analyzing suppliers for a vendor.
                
                Current suppliers: ${context["suppliers"] ?: "Unknown"}
                Purchase history: ${context["purchases"] ?: "No data"}
                
                Evaluate:
                1. Price competitiveness across suppliers
                2. Reliability (delivery consistency, quality)
                3. Payment terms and flexibility
                4. Risk of single-supplier dependency
                5. Recommendations for diversification
            """.trimIndent()

            FinancialTemplate.PROFITABILITY_ANALYSIS -> """
                You are analyzing profitability for a business.
                
                Revenue data: ${context["revenue"] ?: "No data"}
                Cost data: ${context["costs"] ?: "No data"}
                Time period: ${context["period"] ?: "This month"}
                
                Calculate and explain:
                1. Gross margin per product
                2. Net profit margin
                3. Most and least profitable items
                4. Break-even analysis
                5. Recommendations to improve profitability
            """.trimIndent()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROVIDER REGISTRY — Based on Swarm 2 cost/performance data
    // ═══════════════════════════════════════════════════════════════

    private val providers = ConcurrentHashMap<String, Provider>().apply {
        // Layer 1: On-Device (free, instant, private)
        put("on-device", Provider(
            id = "on-device",
            type = ProviderType.ON_DEVICE,
            displayName = "On-Device Qwen3.5-0.8B (llama.cpp)",
            models = listOf("qwen3.5-0.8b", "qwen3-1.7b", "phi-2"),
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            maxContextTokens = 4096,
            priority = 10,
            capabilities = listOf(
                "transaction_recording", "balance_inquiry", "price_lookup",
                "cash_flow_alert", "daily_briefing_template", "simple_qa"
            )
        ))
        // Layer 2: Cloud Reasoning (cost-efficient, $0.20/1M input)
        put("deepseek-flash", Provider(
            id = "deepseek-flash",
            type = ProviderType.CLOUD_REASONING,
            displayName = "DeepSeek V4 Flash",
            models = listOf("deepseek-v4-flash", "deepseek-chat"),
            costPer1kInput = 0.0002,   // $0.20/1M = $0.0002/1k
            costPer1kOutput = 0.001,    // $1.00/1M = $0.001/1k
            maxContextTokens = 1_000_000,
            priority = 20,
            capabilities = listOf(
                "credit_assessment", "market_forecasting", "risk_assessment",
                "price_analysis", "cash_flow_analysis", "reasoning"
            )
        ))
        // Layer 2 alt: GPT-5.4 nano (same price tier)
        put("gpt-nano", Provider(
            id = "gpt-nano",
            type = ProviderType.CLOUD_REASONING,
            displayName = "GPT-5.4 nano",
            models = listOf("gpt-5.4-nano"),
            costPer1kInput = 0.0002,    // $0.20/1M
            costPer1kOutput = 0.00125,  // $1.25/1M
            maxContextTokens = 128_000,
            priority = 25,
            capabilities = listOf(
                "credit_assessment", "market_forecasting", "risk_assessment",
                "reasoning", "financial_analysis"
            )
        ))
        // Layer 3: Cloud Premium (complex financial analysis)
        put("claude-haiku", Provider(
            id = "claude-haiku",
            type = ProviderType.CLOUD_PREMIUM,
            displayName = "Claude Haiku 4.5",
            models = listOf("claude-haiku-4.5"),
            costPer1kInput = 0.001,     // $1.00/1M
            costPer1kOutput = 0.005,    // $5.00/1M
            maxContextTokens = 200_000,
            priority = 30,
            capabilities = listOf(
                "growth_planning", "complex_analysis", "long_context",
                "financial_document_processing"
            )
        ))
        // Backend: Angavu Intelligence — AfriqueQwen-14B for African language support
        put("backend", Provider(
            id = "backend",
            type = ProviderType.BACKEND,
            displayName = "Angavu Backend (AfriqueQwen-14B)",
            models = listOf("AfriqueQwen-14B", "biashara-agent"),
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            maxContextTokens = 32_768,
            priority = 35,
            capabilities = listOf(
                "full_agent", "multi_step", "domain_expert", "report_generation"
            )
        ))
        // On-Device Vision: Gemma 4 E2B (multimodal, free)
        put("on-device-vision", Provider(
            id = "on-device-vision",
            type = ProviderType.ON_DEVICE,
            displayName = "On-Device Gemma 4 E2B (Vision)",
            models = listOf("gemma-4-e2b", "lfm2.5-vl-1.6b"),
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            maxContextTokens = 4096,
            priority = 12,
            capabilities = listOf(
                "goods_recognition", "receipt_scanning", "inventory_scan",
                "price_comparison", "ocr", "image_classification"
            )
        ))
    } — Task type → optimal provider chain
    // Based on Swarm 2 research: 80% on-device, 15% cloud reasoning, 5% premium
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // ROUTING TABLE — Task type → optimal provider chain
    // Based on Swarm 2 research: 80% on-device, 15% cloud reasoning, 5% premium
    // ═══════════════════════════════════════════════════════════════

    private val taskRoutingTable: Map<TaskType, List<String>> = mapOf(
        TaskType.TRANSACTION_RECORDING to listOf("on-device"),
        TaskType.BALANCE_INQUIRY to listOf("on-device"),
        TaskType.PRICE_LOOKUP to listOf("on-device"),
        TaskType.CASH_FLOW_ALERT to listOf("on-device", "deepseek-flash"),
        TaskType.DAILY_BRIEFING to listOf("on-device", "deepseek-flash"),
        TaskType.CREDIT_ASSESSMENT to listOf("deepseek-flash", "gpt-nano", "claude-haiku"),
        TaskType.MARKET_FORECASTING to listOf("deepseek-flash", "gpt-nano", "claude-haiku"),
        TaskType.RISK_ASSESSMENT to listOf("deepseek-flash", "gpt-nano", "claude-haiku"),
        TaskType.FINANCIAL_ANALYSIS to listOf("deepseek-flash", "gpt-nano"),
        TaskType.GROWTH_PLANNING to listOf("claude-haiku", "deepseek-flash", "gpt-nano"),
        // Multimodal routing (Swarm 7)
        TaskType.GOODS_RECOGNITION to listOf("on-device-vision", "deepseek-flash", "on-device"),
        TaskType.RECEIPT_SCANNING to listOf("on-device-vision", "deepseek-flash", "on-device"),
        TaskType.INVENTORY_SCAN to listOf("on-device-vision", "on-device", "deepseek-flash"),
        TaskType.PRICE_COMPARISON to listOf("on-device-vision", "deepseek-flash", "gpt-nano"),
        TaskType.GENERAL to listOf("on-device", "deepseek-flash", "gpt-nano", "backend")
    )

    // ═══════════════════════════════════════════════════════════════
    // RESULT CACHE
    // ═══════════════════════════════════════════════════════════════

    private val resultCache = LruCache<String, InferenceResponse>(config.cacheSize)

    // ═══════════════════════════════════════════════════════════════
    // USAGE & COST TRACKING — $0.013/user/month model
    // ═══════════════════════════════════════════════════════════════

    private val totalRequests = AtomicLong(0)
    private val totalTokensIn = AtomicLong(0)
    private val totalTokensOut = AtomicLong(0)
    private val totalCostMicros = AtomicLong(0)
    private val requestLog = mutableListOf<RequestLogEntry>()

    /** Per-user monthly cost tracking (keyed by userId) */
    private val userMonthlyCost = ConcurrentHashMap<String, AtomicLong>()
    private val userDailyCost = ConcurrentHashMap<String, AtomicLong>()
    private var currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH)
    private var currentDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    data class RequestLogEntry(
        val requestId: String,
        val providerId: String,
        val model: String,
        val taskType: TaskType,
        val inputTokens: Int,
        val outputTokens: Int,
        val latencyMs: Long,
        val fromCache: Boolean,
        val costMicros: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class CostBudgetStatus(
        val userId: String,
        val monthlyUsedMicros: Long,
        val monthlyBudgetMicros: Long,
        val dailyUsedMicros: Long,
        val dailyBudgetMicros: Long,
        val monthlyPctUsed: Float,
        val isOverBudget: Boolean,
        val isNearBudget: Boolean
    )

    // ═══════════════════════════════════════════════════════════════
    // REASONING CHAIN STORAGE
    // ═══════════════════════════════════════════════════════════════

    private val reasoningChains = LruCache<String, ReasoningChain>(50)

    // ═══════════════════════════════════════════════════════════════
    // MAIN INFERENCE METHOD
    // ═══════════════════════════════════════════════════════════════

    /**
     * Route an inference request to the optimal provider.
     *
     * Routing logic:
     * 1. Check cache
     * 2. Classify task complexity and type
     * 3. Check user budget
     * 4. Build provider chain from routing table
     * 5. Apply fallback logic
     * 6. Track costs and reasoning chains
     */
    suspend fun infer(request: InferenceRequest): InferenceResponse = withContext(Dispatchers.IO) {
        totalRequests.incrementAndGet()

        // 1. Check cache
        if (config.enableCache) {
            val cacheKey = computeCacheKey(request)
            resultCache.get(cacheKey)?.let { cached ->
                return@withContext cached.copy(fromCache = true)
            }
        }

        // 2. Auto-classify task complexity if not set
        val effectiveComplexity = classifyTaskComplexity(request)
        val effectiveTaskType = request.taskType

        // 3. Check user budget
        val userId = request.userId ?: "anonymous"
        val budgetStatus = checkBudget(userId)
        if (budgetStatus.isOverBudget) {
            // Force on-device when over budget
            return@withContext inferOnDevice(request, effectiveComplexity)
                ?: throw BudgetExceededException(
                    "Monthly budget exceeded for user $userId. " +
                    "Used: $${budgetStatus.monthlyUsedMicros / 1_000_000.0}, " +
                    "Budget: $${budgetStatus.monthlyBudgetMicros / 1_000_000.0}"
                )
        }

        // 4. Compress if needed
        val messages = if (estimateTokens(request.messages) > config.compressionThreshold) {
            compressMessages(request.messages)
        } else {
            request.messages
        }

        // 5. MoE routing — select best expert for this task type (Swarm 7)
        val moeDecision = if (config.enableMoERouting) {
            moeRouter.route(
                taskType = effectiveTaskType.name,
                hasImageInput = request.messages.any { it["content"]?.startsWith("[IMAGE]") == true },
                isOnline = isNetworkAvailable(),
                isOverBudget = budgetStatus.isOverBudget,
                isLowRamDevice = DeviceCapability.getTier(context) == DeviceCapability.PerformanceTier.LOW
            )
        } else null

        // 6. Build provider chain (with MoE override if applicable)
        val chain = if (moeDecision != null && config.enableMoERouting) {
            // Use MoE-selected expert as primary, with standard fallback
            val moeProviderId = moeRouter.getExpert(moeDecision.primaryExpert)?.providerId
            if (moeProviderId != null) {
                listOf(moeProviderId) + buildSmartFallbackChain(effectiveTaskType, effectiveComplexity, budgetStatus)
                    .filter { it != moeProviderId }
            } else {
                buildSmartFallbackChain(effectiveTaskType, effectiveComplexity, budgetStatus)
            }
        } else {
            buildSmartFallbackChain(effectiveTaskType, effectiveComplexity, budgetStatus)
        }

        // 6. Initialize reasoning chain if enabled
        val reasoningChain = if (config.enableReasoningChains) {
            ReasoningChain(
                chainId = java.util.UUID.randomUUID().toString().take(12),
                requestId = request.requestId,
                templateUsed = request.financialTemplate,
                modelUsed = ""
            )
        } else null

        // 7. Inject financial template if provided
        val enhancedMessages = if (request.financialTemplate != null) {
            val templatePrompt = getFinancialTemplatePrompt(request.financialTemplate)
            reasoningChain?.addStep(ReasoningStep(
                stepNumber = 0,
                type = ReasoningStep.StepType.TEMPLATE_INJECT,
                content = "Injected ${request.financialTemplate.displayName} template",
                confidence = 1.0
            ))
            listOf(mapOf("role" to "system", "content" to templatePrompt)) + messages
        } else messages

        // 8. Try each provider in chain
        var lastException: Exception? = null
        for (providerId in chain) {
            val provider = providers[providerId] ?: continue
            if (!provider.isAvailable) continue

            try {
                reasoningChain?.addStep(ReasoningStep(
                    stepNumber = reasoningChain?.steps?.size ?: 0,
                    type = ReasoningStep.StepType.THINK,
                    content = "Attempting provider: ${provider.displayName}",
                    confidence = 0.8
                ))

                val startTime = System.currentTimeMillis()
                val response = callProvider(
                    provider,
                    request.copy(messages = enhancedMessages),
                    effectiveComplexity
                )
                val latencyMs = System.currentTimeMillis() - startTime

                provider.totalRequests.incrementAndGet()
                provider.consecutiveFailures = 0
                provider.avgLatencyMs = (provider.avgLatencyMs + latencyMs) / 2

                val costMicros = calculateCost(provider, response.inputTokens, response.outputTokens)

                val result = response.copy(
                    latencyMs = latencyMs,
                    reasoningChain = reasoningChain,
                    costMicros = costMicros
                )

                // Track usage — legacy tracker
                trackUsage(userId, result.inputTokens, result.outputTokens, costMicros, effectiveTaskType)

                // Track usage — Swarm 7 cost tracker (detailed per-call attribution)
                costTracker.record(
                    providerId = provider.id,
                    modelId = result.modelUsed,
                    inputTokens = result.inputTokens,
                    outputTokens = result.outputTokens,
                    costMicros = costMicros,
                    taskType = effectiveTaskType.name,
                    userId = userId,
                    latencyMs = latencyMs,
                    fromCache = false
                )

                // Record expert health for MoE routing
                val expertType = moeRouter.getAllExperts().values
                    .firstOrNull { it.providerId == provider.id }?.type
                if (expertType != null) {
                    expertRegistry.recordSuccess(expertType, latencyMs)
                }

                // Complete reasoning chain
                reasoningChain?.let {
                    it.modelUsed = result.modelUsed
                    it.addStep(ReasoningStep(
                        stepNumber = it.steps.size,
                        type = ReasoningStep.StepType.ACT,
                        content = "Response generated (${result.outputTokens} tokens)",
                        confidence = 0.9
                    ))
                    it.complete(true)
                    reasoningChains.put(it.chainId, it)
                }

                // Cache result
                if (config.enableCache && result.content.isNotEmpty()) {
                    val cacheKey = computeCacheKey(request)
                    resultCache.put(cacheKey, result)
                }

                // Reflexion — self-critique and retry (Swarm 7)
                if (config.enableReflexion && result.content.isNotBlank()) {
                    val reflexionResult = reflexionLoop.execute(
                        task = "Inference for ${effectiveTaskType.name}",
                        qualityThreshold = config.reflexionQualityThreshold,
                        maxRetries = config.reflexionMaxRetries,
                        critiqueFn = { content: String ->
                            reflexionLoop.critiqueResponse(
                                response = content,
                                expectedLanguage = "sw",
                                minLength = 10,
                                maxLength = 2000
                            )
                        },
                        executeFn = { previousCritique: Critique? ->
                            // On retry, inject critique as feedback
                            if (previousCritique != null && previousCritique.shouldRetry) {
                                val feedbackMessages = enhancedMessages + listOf(
                                    mapOf("role" to "system",
                                        "content" to "Previous response had issues: ${previousCritique.issues.joinToString(\"; \")}. Suggestions: ${previousCritique.suggestions.joinToString(\"; \")}. Improve the response.")
                                )
                                callProvider(provider, request.copy(messages = feedbackMessages), effectiveComplexity)
                            } else {
                                result.content
                            }
                        }
                    )
                    if (reflexionResult.success && reflexionResult.result is String && reflexionResult.result != result.content) {
                        // Use the reflexion-improved response
                        return@withContext result.copy(
                            content = reflexionResult.result as String,
                            compressionInfo = result.compressionInfo + mapOf(
                                "reflexion_attempts" to reflexionResult.attempts,
                                "reflexion_score" to reflexionResult.finalScore
                            )
                        )
                    }
                }

                return@withContext result

            } catch (e: Exception) {
                lastException = e
                provider.totalFailures.incrementAndGet()
                provider.consecutiveFailures++
                if (provider.consecutiveFailures >= 3) {
                    provider.isAvailable = false
                }

                reasoningChain?.addStep(ReasoningStep(
                    stepNumber = reasoningChain?.steps?.size ?: 0,
                    type = ReasoningStep.StepType.REFLECT,
                    content = "Provider ${provider.id} failed: ${e.message}",
                    confidence = 0.0
                ))
            }
        }

        reasoningChain?.complete(false)
        reasoningChain?.let { reasoningChains.put(it.chainId, it) }

        throw lastException ?: IllegalStateException("No providers available")
    }

    // ═══════════════════════════════════════════════════════════════
    // SMART ROUTING — Task-aware provider chain building
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify task complexity based on request content.
     * Uses heuristics to avoid needing an LLM just to classify.
     */
    private fun classifyTaskComplexity(request: InferenceRequest): TaskComplexity {
        // If explicitly set, use it
        if (request.taskComplexity != TaskComplexity.MEDIUM) return request.taskComplexity

        // Auto-classify based on task type
        return when (request.taskType) {
            TaskType.TRANSACTION_RECORDING,
            TaskType.BALANCE_INQUIRY,
            TaskType.PRICE_LOOKUP,
            TaskType.DAILY_BRIEFING -> TaskComplexity.LOW

            TaskType.CASH_FLOW_ALERT,
            TaskType.FINANCIAL_ANALYSIS,
            TaskType.INVENTORY_OPTIMIZATION,
            TaskType.SUPPLIER_ANALYSIS,
            TaskType.PROFITABILITY_ANALYSIS,
            TaskType.PRICE_ANALYSIS -> TaskComplexity.MEDIUM

            TaskType.CREDIT_ASSESSMENT,
            TaskType.MARKET_FORECASTING,
            TaskType.RISK_ASSESSMENT -> TaskComplexity.HIGH

            TaskType.GROWTH_PLANNING -> TaskComplexity.CRITICAL

            // Multimodal tasks (Swarm 7) — vision processing is MEDIUM complexity
            TaskType.GOODS_RECOGNITION,
            TaskType.RECEIPT_SCANNING,
            TaskType.INVENTORY_SCAN -> TaskComplexity.MEDIUM

            TaskType.PRICE_COMPARISON -> TaskComplexity.HIGH

            TaskType.GENERAL -> {
                // Heuristic: longer messages → more complex
                val totalTokens = estimateTokens(request.messages)
                when {
                    totalTokens < 100 -> TaskComplexity.LOW
                    totalTokens < 500 -> TaskComplexity.MEDIUM
                    else -> TaskComplexity.HIGH
                }
            }
        }
    }

    /**
     * Build a smart fallback chain based on task type, complexity, and budget.
     *
     * Routing table maps task types to preferred provider order.
     * Budget constraints may force downgrade to cheaper providers.
     */
    private fun buildSmartFallbackChain(
        taskType: TaskType,
        complexity: TaskComplexity,
        budgetStatus: CostBudgetStatus
    ): List<String> {
        val isOnline = isNetworkAvailable()

        // Offline: only on-device
        if (!isOnline) return listOf("on-device")

        // Get preferred chain from routing table
        val preferredChain = taskRoutingTable[taskType]
            ?: taskRoutingTable[TaskType.GENERAL]!!

        // If near budget, prefer cheaper providers
        val chain = if (budgetStatus.isNearBudget) {
            preferredChain.sortedBy { id ->
                providers[id]?.costPer1kInput ?: Double.MAX_VALUE
            }
        } else {
            preferredChain
        }

        // Always append backend as last resort
        return if ("backend" in chain) chain else chain + "backend"
    }

    // ═══════════════════════════════════════════════════════════════
    // PROVIDER CALLS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun callProvider(
        provider: Provider,
        request: InferenceRequest,
        complexity: TaskComplexity
    ): InferenceResponse {
        val model = request.model ?: provider.models.firstOrNull() ?: "default"
        val inputTokens = estimateTokens(request.messages)

        val content = when (provider.type) {
            ProviderType.ON_DEVICE -> callOnDevice(provider, request)
            ProviderType.CLOUD_REASONING, ProviderType.CLOUD_PREMIUM -> callCloud(provider, request)
            ProviderType.BACKEND -> callBackend(provider, request)
        }

        val outputTokens = estimateTokens(listOf(mapOf("content" to content)))

        return InferenceResponse(
            requestId = request.requestId,
            providerId = provider.id,
            modelUsed = model,
            content = content,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = 0 // Set by caller
        )
    }

    private suspend fun callOnDevice(provider: Provider, request: InferenceRequest): String {
        val engine = llmEngine ?: throw IllegalStateException("On-device LLM not available")
        if (!engine.isModelLoaded()) throw IllegalStateException("On-device model not loaded")

        val prompt = buildPromptFromMessages(request.messages)

        // Apply test-time compute scaling
        val maxTokens = if (config.enableTestTimeCompute) {
            when (request.reasoningEffort) {
                ReasoningEffort.NONE -> request.maxTokens
                ReasoningEffort.LIGHT -> request.maxTokens + 256
                ReasoningEffort.STANDARD -> request.maxTokens + 512
                ReasoningEffort.EXTENDED -> request.maxTokens + 1024
                ReasoningEffort.XHIGH -> request.maxTokens + 2048
            }
        } else request.maxTokens

        return engine.generate(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = request.temperature
        )
    }

    private suspend fun callCloud(provider: Provider, request: InferenceRequest): String {
        val api = apiClient ?: throw IllegalStateException("API client not configured")
        val prompt = buildPromptFromMessages(request.messages)

        try {
            val response = api.aiChat(
                com.msaidizi.app.data.model.AiChatRequest(
                    message = prompt,
                    model = provider.models.first(),
                    maxTokens = request.maxTokens,
                    temperature = request.temperature
                )
            )
            if (response.isSuccessful) {
                return response.body()?.reply ?: ""
            } else {
                throw Exception("Cloud API error: ${response.code()}")
            }
        } catch (e: Exception) {
            throw Exception("Cloud inference failed: ${e.message}")
        }
    }

    private suspend fun callBackend(provider: Provider, request: InferenceRequest): String {
        val api = apiClient ?: throw IllegalStateException("API client not configured")
        val prompt = buildPromptFromMessages(request.messages)

        try {
            val response = api.aiChat(
                com.msaidizi.app.data.model.AiChatRequest(
                    message = prompt,
                    model = provider.models.first(),
                    maxTokens = request.maxTokens,
                    temperature = request.temperature
                )
            )
            if (response.isSuccessful) {
                return response.body()?.reply ?: ""
            } else {
                throw Exception("Backend API error: ${response.code()}")
            }
        } catch (e: Exception) {
            throw Exception("Backend inference failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // COST TRACKING & BUDGET MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    private fun calculateCost(provider: Provider, inputTokens: Int, outputTokens: Int): Long {
        return ((inputTokens * provider.costPer1kInput / 1000.0) +
                (outputTokens * provider.costPer1kOutput / 1000.0) * 1_000_000).toLong()
    }

    private fun trackUsage(
        userId: String,
        inputTokens: Int,
        outputTokens: Int,
        costMicros: Long,
        taskType: TaskType
    ) {
        totalTokensIn.addAndGet(inputTokens.toLong())
        totalTokensOut.addAndGet(outputTokens.toLong())
        totalCostMicros.addAndGet(costMicros)

        // Per-user tracking
        userMonthlyCost.getOrPut(userId) { AtomicLong(0) }.addAndGet(costMicros)
        userDailyCost.getOrPut(userId) { AtomicLong(0) }.addAndGet(costMicros)

        // Log entry
        synchronized(requestLog) {
            requestLog.add(RequestLogEntry(
                requestId = java.util.UUID.randomUUID().toString().take(8),
                providerId = "",
                model = "",
                taskType = taskType,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                latencyMs = 0,
                fromCache = false,
                costMicros = costMicros
            ))
            if (requestLog.size > 500) requestLog.removeAt(0)
        }

        // Reset daily/monthly counters if needed
        resetCountersIfNeeded()
    }

    private fun checkBudget(userId: String): CostBudgetStatus {
        resetCountersIfNeeded()

        val monthlyUsed = userMonthlyCost[userId]?.get() ?: 0L
        val dailyUsed = userDailyCost[userId]?.get() ?: 0L

        return CostBudgetStatus(
            userId = userId,
            monthlyUsedMicros = monthlyUsed,
            monthlyBudgetMicros = config.monthlyBudgetMicros,
            dailyUsedMicros = dailyUsed,
            dailyBudgetMicros = config.dailyBudgetMicros,
            monthlyPctUsed = monthlyUsed.toFloat() / config.monthlyBudgetMicros,
            isOverBudget = monthlyUsed >= config.monthlyBudgetMicros,
            isNearBudget = monthlyUsed >= (config.monthlyBudgetMicros * config.alertThresholdPct).toLong()
        )
    }

    private fun resetCountersIfNeeded() {
        val cal = Calendar.getInstance()
        val nowMonth = cal.get(Calendar.MONTH)
        val nowDay = cal.get(Calendar.DAY_OF_YEAR)

        if (nowMonth != currentMonth) {
            currentMonth = nowMonth
            userMonthlyCost.values.forEach { it.set(0) }
        }
        if (nowDay != currentDay) {
            currentDay = nowDay
            userDailyCost.values.forEach { it.set(0) }
        }
    }

    private suspend fun inferOnDevice(
        request: InferenceRequest,
        complexity: TaskComplexity
    ): InferenceResponse? {
        val provider = providers["on-device"] ?: return null
        if (!provider.isAvailable) return null

        return try {
            val startTime = System.currentTimeMillis()
            val response = callProvider(provider, request, complexity)
            val latencyMs = System.currentTimeMillis() - startTime
            response.copy(latencyMs = latencyMs, costMicros = 0)
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════

    private fun buildPromptFromMessages(messages: List<Map<String, String>>): String {
        return messages.joinToString("\n") { msg ->
            val role = msg["role"] ?: "user"
            val content = msg["content"] ?: ""
            when (role) {
                "system" -> "System: $content"
                "assistant" -> "Assistant: $content"
                else -> "User: $content"
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun estimateTokens(messages: List<Map<String, String>>): Int {
        var total = 0
        for (msg in messages) {
            total += 4
            total += (msg["role"]?.length ?: 0) / 4
            total += (msg["content"]?.length ?: 0) / 4
        }
        return maxOf(1, total)
    }

    private fun compressMessages(messages: List<Map<String, String>>): List<Map<String, String>> {
        if (messages.size <= 4) return messages

        val system = messages.filter { it["role"] == "system" }
        val nonSystem = messages.filter { it["role"] != "system" }
        val recent = nonSystem.takeLast(4)
        val older = nonSystem.dropLast(4)

        val summary = older.joinToString("\n") { msg ->
            val content = msg["content"]?.take(100) ?: ""
            "[${msg["role"]}]: $content"
        }

        val summaryMsg = mapOf("role" to "system", "content" to "Previous context:\n$summary")
        return system + listOf(summaryMsg) + recent
    }

    private fun computeCacheKey(request: InferenceRequest): String {
        val content = request.messages.joinToString("|") { "${it["role"]}:${it["content"]}" }
        val hash = MessageDigest.getInstance("MD5")
            .digest("$content:${request.model}:${request.maxTokens}:${request.temperature}".toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // STATS & MONITORING
    // ═══════════════════════════════════════════════════════════════

    fun getStats(): Map<String, Any> {
        val providerStats = providers.mapValues { (_, p) ->
            mapOf(
                "totalRequests" to p.totalRequests.get(),
                "totalFailures" to p.totalFailures.get(),
                "isAvailable" to p.isAvailable,
                "consecutiveFailures" to p.consecutiveFailures,
                "avgLatencyMs" to p.avgLatencyMs,
                "costPer1kInput" to p.costPer1kInput,
                "costPer1kOutput" to p.costPer1kOutput
            )
        }
        return mapOf(
            "totalRequests" to totalRequests.get(),
            "totalTokensInput" to totalTokensIn.get(),
            "totalTokensOutput" to totalTokensOut.get(),
            "totalCostMicros" to totalCostMicros.get(),
            "totalCostDollars" to totalCostMicros.get() / 1_000_000.0,
            "cacheSize" to resultCache.size(),
            "reasoningChainsStored" to reasoningChains.size(),
            "providers" to providerStats,
            "isOnline" to isNetworkAvailable(),
            // Swarm 7 metrics
            "moe_routing" to moeRouter.getStats(),
            "cost_tracking" to costTracker.getStats(),
            "model_version" to modelVersionManager.getUpgradePathInfo(),
            "expert_health" to expertRegistry.getAllHealth().mapKeys { it.key.name },
            "reflexion_avg_score" to reflexionLoop.getAverageScore()
        )
    }

    fun getUserCostStatus(userId: String): CostBudgetStatus = checkBudget(userId)

    fun getReasoningChain(chainId: String): ReasoningChain? = reasoningChains.get(chainId)

    fun getRecentReasoningChains(n: Int = 10): List<Map<String, Any>> {
        // LruCache doesn't expose ordered entries, so we use a snapshot
        return emptyList() // TODO: implement with a bounded deque
    }

    fun getProviderHealth(): List<Map<String, Any>> {
        return providers.values.map { p ->
            mapOf(
                "id" to p.id,
                "type" to p.type.name,
                "displayName" to p.displayName,
                "isAvailable" to p.isAvailable,
                "totalRequests" to p.totalRequests.get(),
                "totalFailures" to p.totalFailures.get(),
                "consecutiveFailures" to p.consecutiveFailures,
                "avgLatencyMs" to p.avgLatencyMs,
                "models" to p.models,
                "capabilities" to p.capabilities,
                "costPer1kInput" to p.costPer1kInput,
                "costPer1kOutput" to p.costPer1kOutput
            )
        }
    }

    fun clearCache() {
        resultCache.evictAll()
    }

    fun resetProvider(providerId: String) {
        providers[providerId]?.let {
            it.isAvailable = true
            it.consecutiveFailures = 0
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SWARM 7 — EMERGING ARCHITECTURE ACCESSORS
    // ═══════════════════════════════════════════════════════════════

    /** Get MoE routing statistics. */
    fun getMoEStats(): Map<String, Any> = moeRouter.getStats()

    /** Get detailed cost tracking data. */
    fun getCostTrackerStats(): Map<String, Any> = costTracker.getStats()

    /** Get recent cost records for analysis. */
    fun getRecentCostRecords(n: Int = 20): List<CostRecord> = costTracker.getRecentRecords(n)

    /** Get user's cost status from the detailed tracker. */
    fun getDetailedUserCost(userId: String) = costTracker.getUserCost(userId)

    /** Get per-task cost breakdown. */
    fun getTaskCostBreakdown(): Map<String, Map<String, Any>> = costTracker.getTaskCostBreakdown()

    /** Get model version status and upgrade info. */
    fun getModelVersionStatus() = modelVersionManager.getModelStatus()

    /** Get model upgrade path info. */
    fun getModelUpgradePath(): Map<String, Any> = modelVersionManager.getUpgradePathInfo()

    /** Check if a model upgrade is available. */
    fun isModelUpgradeAvailable(): Pair<Boolean, ModelVersionManager.ModelVersion?> =
        modelVersionManager.isUpgradeAvailable()

    /** Get the multimodal pipeline for image processing. */
    fun getMultimodalPipeline(): MultimodalPipeline = multimodalPipeline

    /** Get expert registry health data. */
    fun getExpertHealth(): Map<MoERouter.ExpertType, ExpertRegistry.ExpertHealth> =
        expertRegistry.getAllHealth()

    /** Get reflexion loop critique history. */
    fun getReflexionHistory(n: Int = 10) = reflexionLoop.getCritiqueHistory(n)

    /** Get average reflexion quality score. */
    fun getReflexionAverageScore(): Double = reflexionLoop.getAverageScore()

    // ═══════════════════════════════════════════════════════════════
    // EXCEPTIONS
    // ═══════════════════════════════════════════════════════════════

    class BudgetExceededException(message: String) : Exception(message)
}
