package com.msaidizi.app.agent.moe

/**
 * MoE (Mixture-of-Experts) Router for Msaidizi — Swarm 7 Architecture Upgrade.
 *
 * Routes different task types to specialized "expert" models, analogous to
 * how MoE neural networks activate only a subset of parameters per token.
 *
 * In Msaidizi's context, "experts" are different model providers:
 * - TRANSACTION_EXPERT: On-device Qwen 0.5B (fast, free, low-latency)
 * - REASONING_EXPERT: DeepSeek V4 Flash (cheap reasoning at $0.20/1M)
 * - MULTIMODAL_EXPERT: Gemma 4 E2B / LFM2.5-VL (vision + text on-device)
 * - COMPLEX_EXPERT: Claude Haiku (deep financial analysis)
 * - AGENT_EXPERT: Backend Angavu Intelligence (full agent swarm)
 *
 * ## MoE Analogy
 * Just as MoE models have N experts but activate only K per token,
 * Msaidizi has 5 expert types but activates only 1-2 per user request.
 * The router acts as the "gating network" — classifying the input and
 * selecting the best expert(s).
 *
 * ## Routing Table
 * ```
 * Task Type               → Primary Expert        → Fallback
 * ─────────────────────────────────────────────────────────────
 * Transaction recording   → TRANSACTION_EXPERT     → REASONING_EXPERT
 * Balance inquiry         → TRANSACTION_EXPERT     → REASONING_EXPERT
 * Price lookup            → TRANSACTION_EXPERT     → REASONING_EXPERT
 * Goods recognition       → MULTIMODAL_EXPERT      → TRANSACTION_EXPERT
 * Receipt scanning        → MULTIMODAL_EXPERT      → REASONING_EXPERT
 * Credit assessment       → REASONING_EXPERT       → COMPLEX_EXPERT
 * Market forecasting      → REASONING_EXPERT       → COMPLEX_EXPERT
 * Growth planning         → COMPLEX_EXPERT         → AGENT_EXPERT
 * Financial analysis      → REASONING_EXPERT       → COMPLEX_EXPERT
 * Full agent tasks        → AGENT_EXPERT           → COMPLEX_EXPERT
 * ```
 *
 * ## Cost Efficiency
 * - 80% of requests → TRANSACTION_EXPERT ($0.00/request)
 * - 15% of requests → REASONING_EXPERT ($0.001/request)
 * - 5% of requests  → COMPLEX_EXPERT/AGENT_EXPERT ($0.01-0.05/request)
 * Average cost: $0.0015/request ≈ $0.013/user/month
 *
 * Based on: Swarm 7 research — MoE architectures dominate open-source (5/6 major families)
 */
class MoERouter {

    /**
     * Expert types — each maps to a specific model provider tier.
     */
    enum class ExpertType {
        TRANSACTION_EXPERT,   // On-device Qwen 0.5B — fast, free
        REASONING_EXPERT,     // DeepSeek V4 Flash — cheap reasoning
        MULTIMODAL_EXPERT,    // Gemma 4 E2B — vision + text
        COMPLEX_EXPERT,       // Claude Haiku — deep analysis
        AGENT_EXPERT          // Backend Angavu — full agent swarm
    }

    /**
     * Expert profile — metadata about each expert's capabilities and cost.
     */
    data class ExpertProfile(
        val type: ExpertType,
        val providerId: String,
        val modelId: String,
        val costPer1kInput: Double,
        val costPer1kOutput: Double,
        val maxContextTokens: Int,
        val supportsVision: Boolean = false,
        val supportsAudio: Boolean = false,
        val supportsFunctionCalling: Boolean = false,
        val avgLatencyMs: Long = 0,
        val capabilities: List<String> = emptyList()
    )

    /**
     * Routing decision — which expert(s) to activate for a request.
     */
    data class RoutingDecision(
        val primaryExpert: ExpertType,
        val fallbackExpert: ExpertType?,
        val confidence: Double,
        val reasoning: String,
        val estimatedCostMicros: Long = 0
    )

    // ── Expert Registry ────────────────────────────────────────────

    private val experts = mutableMapOf<ExpertType, ExpertProfile>().apply {
        put(ExpertType.TRANSACTION_EXPERT, ExpertProfile(
            type = ExpertType.TRANSACTION_EXPERT,
            providerId = "on-device",
            modelId = "qwen-0.5b-fl-sw",
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            maxContextTokens = 4096,
            supportsVision = false,
            supportsFunctionCalling = true,
            avgLatencyMs = 200,
            capabilities = listOf(
                "transaction_recording", "balance_inquiry", "price_lookup",
                "simple_qa", "daily_briefing_template"
            )
        ))

        put(ExpertType.REASONING_EXPERT, ExpertProfile(
            type = ExpertType.REASONING_EXPERT,
            providerId = "deepseek-flash",
            modelId = "deepseek-v4-flash",
            costPer1kInput = 0.0002,
            costPer1kOutput = 0.001,
            maxContextTokens = 1_000_000,
            supportsVision = false,
            supportsFunctionCalling = true,
            avgLatencyMs = 2000,
            capabilities = listOf(
                "credit_assessment", "market_forecasting", "risk_assessment",
                "price_analysis", "cash_flow_analysis", "reasoning"
            )
        ))

        put(ExpertType.MULTIMODAL_EXPERT, ExpertProfile(
            type = ExpertType.MULTIMODAL_EXPERT,
            providerId = "on-device-vision",
            modelId = "gemma-4-e2b",  // Or LFM2.5-VL-1.6B
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            maxContextTokens = 4096,
            supportsVision = true,
            supportsAudio = true,
            supportsFunctionCalling = true,
            avgLatencyMs = 500,
            capabilities = listOf(
                "goods_recognition", "receipt_scanning", "inventory_scan",
                "price_comparison", "image_classification", "ocr"
            )
        ))

        put(ExpertType.COMPLEX_EXPERT, ExpertProfile(
            type = ExpertType.COMPLEX_EXPERT,
            providerId = "claude-haiku",
            modelId = "claude-haiku-4.5",
            costPer1kInput = 0.001,
            costPer1kOutput = 0.005,
            maxContextTokens = 200_000,
            supportsVision = true,
            supportsFunctionCalling = true,
            avgLatencyMs = 5000,
            capabilities = listOf(
                "growth_planning", "complex_analysis", "long_context",
                "financial_document_processing", "multi_step_reasoning"
            )
        ))

        put(ExpertType.AGENT_EXPERT, ExpertProfile(
            type = ExpertType.AGENT_EXPERT,
            providerId = "backend",
            modelId = "biashara-agent",
            costPer1kInput = 0.0,
            costPer1kOutput = 0.0,
            maxContextTokens = 32_768,
            supportsVision = false,
            supportsFunctionCalling = true,
            avgLatencyMs = 10000,
            capabilities = listOf(
                "full_agent", "multi_step", "domain_expert",
                "report_generation", "agent_orchestration"
            )
        ))
    }

    // ── Task-to-Expert Routing Table ───────────────────────────────

    private val routingTable: Map<String, Pair<ExpertType, ExpertType?>> = mapOf(
        // Simple transactions — on-device expert
        "TRANSACTION_RECORDING" to (ExpertType.TRANSACTION_EXPERT to ExpertType.REASONING_EXPERT),
        "BALANCE_INQUIRY" to (ExpertType.TRANSACTION_EXPERT to ExpertType.REASONING_EXPERT),
        "PRICE_LOOKUP" to (ExpertType.TRANSACTION_EXPERT to ExpertType.REASONING_EXPERT),

        // Multimodal tasks — vision expert
        "GOODS_RECOGNITION" to (ExpertType.MULTIMODAL_EXPERT to ExpertType.REASONING_EXPERT),
        "RECEIPT_SCANNING" to (ExpertType.MULTIMODAL_EXPERT to ExpertType.REASONING_EXPERT),
        "INVENTORY_SCAN" to (ExpertType.MULTIMODAL_EXPERT to ExpertType.TRANSACTION_EXPERT),
        "PRICE_COMPARISON" to (ExpertType.MULTIMODAL_EXPERT to ExpertType.REASONING_EXPERT),

        // Reasoning tasks — reasoning expert
        "CREDIT_ASSESSMENT" to (ExpertType.REASONING_EXPERT to ExpertType.COMPLEX_EXPERT),
        "MARKET_FORECASTING" to (ExpertType.REASONING_EXPERT to ExpertType.COMPLEX_EXPERT),
        "RISK_ASSESSMENT" to (ExpertType.REASONING_EXPERT to ExpertType.COMPLEX_EXPERT),
        "CASH_FLOW_ALERT" to (ExpertType.TRANSACTION_EXPERT to ExpertType.REASONING_EXPERT),
        "FINANCIAL_ANALYSIS" to (ExpertType.REASONING_EXPERT to ExpertType.COMPLEX_EXPERT),

        // Complex tasks — complex expert
        "GROWTH_PLANNING" to (ExpertType.COMPLEX_EXPERT to ExpertType.AGENT_EXPERT),

        // Agent tasks — backend expert
        "GENERAL" to (ExpertType.TRANSACTION_EXPERT to ExpertType.REASONING_EXPERT),
        "DAILY_BRIEFING" to (ExpertType.TRANSACTION_EXPERT to ExpertType.REASONING_EXPERT)
    )

    /**
     * Route a task to the best expert based on task type and context.
     *
     * The gating logic considers:
     * 1. Task type (primary signal)
     * 2. Whether input contains images (multimodal routing)
     * 3. Network availability (offline → on-device only)
     * 4. Budget constraints (over budget → cheaper expert)
     * 5. Device capability (low RAM → avoid heavy models)
     */
    fun route(
        taskType: String,
        hasImageInput: Boolean = false,
        isOnline: Boolean = true,
        isOverBudget: Boolean = false,
        isLowRamDevice: Boolean = false
    ): RoutingDecision {
        // Multimodal override: if input has images, prefer multimodal expert
        if (hasImageInput) {
            val multimodalExpert = experts[ExpertType.MULTIMODAL_EXPERT]!!
            return RoutingDecision(
                primaryExpert = ExpertType.MULTIMODAL_EXPERT,
                fallbackExpert = if (isOnline) ExpertType.REASONING_EXPERT else null,
                confidence = 0.95,
                reasoning = "Image input detected → routing to multimodal expert",
                estimatedCostMicros = 0  // On-device = free
            )
        }

        // Offline override: only on-device expert available
        if (!isOnline) {
            return RoutingDecision(
                primaryExpert = ExpertType.TRANSACTION_EXPERT,
                fallbackExpert = null,
                confidence = 0.90,
                reasoning = "Offline mode → on-device expert only",
                estimatedCostMicros = 0
            )
        }

        // Budget override: prefer cheaper experts
        if (isOverBudget) {
            return RoutingDecision(
                primaryExpert = ExpertType.TRANSACTION_EXPERT,
                fallbackExpert = ExpertType.REASONING_EXPERT,
                confidence = 0.85,
                reasoning = "Over budget → forced on-device routing",
                estimatedCostMicros = 0
            )
        }

        // Low RAM override: avoid heavy models
        if (isLowRamDevice) {
            // Skip multimodal expert on low RAM devices
            val (primary, fallback) = routingTable[taskType]
                ?: routingTable["GENERAL"]!!
            val adjustedPrimary = if (primary == ExpertType.MULTIMODAL_EXPERT) {
                ExpertType.TRANSACTION_EXPERT
            } else primary
            return RoutingDecision(
                primaryExpert = adjustedPrimary,
                fallbackExpert = fallback,
                confidence = 0.80,
                reasoning = "Low RAM device → avoiding heavy models",
                estimatedCostMicros = estimateCost(adjustedPrimary, 500, 200)
            )
        }

        // Standard routing via table
        val (primary, fallback) = routingTable[taskType]
            ?: routingTable["GENERAL"]!!

        val costEstimate = estimateCost(primary, 500, 200)

        return RoutingDecision(
            primaryExpert = primary,
            fallbackExpert = fallback,
            confidence = 0.90,
            reasoning = "MoE routing: $taskType → ${primary.name}",
            estimatedCostMicros = costEstimate
        )
    }

    /**
     * Get expert profile for a given expert type.
     */
    fun getExpert(type: ExpertType): ExpertProfile? = experts[type]

    /**
     * Get all registered experts.
     */
    fun getAllExperts(): Map<ExpertType, ExpertProfile> = experts.toMap()

    /**
     * Register a custom expert (for dynamic model upgrades).
     */
    fun registerExpert(type: ExpertType, profile: ExpertProfile) {
        experts[type] = profile
    }

    /**
     * Estimate cost in micro-dollars for a given expert and token counts.
     */
    private fun estimateCost(expert: ExpertType, inputTokens: Int, outputTokens: Int): Long {
        val profile = experts[expert] ?: return 0L
        return ((inputTokens * profile.costPer1kInput / 1000.0) +
                (outputTokens * profile.costPer1kOutput / 1000.0) * 1_000_000).toLong()
    }

    /**
     * Get routing statistics for monitoring.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "expert_count" to experts.size,
            "routing_table_size" to routingTable.size,
            "experts" to experts.map { (type, profile) ->
                mapOf(
                    "type" to type.name,
                    "provider" to profile.providerId,
                    "model" to profile.modelId,
                    "cost_per_1k_input" to profile.costPer1kInput,
                    "supports_vision" to profile.supportsVision,
                    "capabilities" to profile.capabilities
                )
            }
        )
    }
}
