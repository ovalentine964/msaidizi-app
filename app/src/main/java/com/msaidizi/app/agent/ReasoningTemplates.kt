package com.msaidizi.app.agent

/**
 * ReasoningTemplates — Pre-built reasoning templates for financial analysis.
 *
 * These templates provide structured reasoning frameworks for common
 * informal economy tasks. They are injected as system prompts to guide
 * the model's chain-of-thought reasoning.
 *
 * Based on Swarm 2 research findings:
 * - Financial reasoning capabilities have matured in 2026 models
 * - Causal inference, temporal reasoning, and counterfactual reasoning
 *   are now viable for CFO-level advisory
 * - Templates reduce token usage by providing structured scaffolding
 *
 * Usage:
 *   val template = ReasoningTemplates.getTemplate(TemplateType.CREDIT_ASSESSMENT)
 *   val prompt = template.buildPrompt(context)
 *   // Inject as system message before user query
 */
object ReasoningTemplates {

    // ═══════════════════════════════════════════════════════════════
    // TEMPLATE TYPES
    // ═══════════════════════════════════════════════════════════════

    enum class TemplateType {
        PRICE_ANALYSIS,
        CREDIT_ASSESSMENT,
        CASH_FLOW_FORECAST,
        RISK_ASSESSMENT,
        MARKET_INTELLIGENCE,
        GROWTH_PLANNING,
        INVENTORY_OPTIMIZATION,
        SUPPLIER_EVALUATION,
        PROFITABILITY_ANALYSIS,
        MICRO_INSURANCE,
        LOAN_AFFORDABILITY,
        DAILY_BRIEFING
    }

    data class ReasoningTemplate(
        val type: TemplateType,
        val name: String,
        val description: String,
        val systemPrompt: String,
        val requiredContext: List<String>,
        val outputFormat: String,
        val maxThinkingTokens: Int = 512,
        val recommendedComplexity: TaskComplexity = TaskComplexity.MEDIUM
    )

    // ═══════════════════════════════════════════════════════════════
    // TEMPLATE REGISTRY
    // ═══════════════════════════════════════════════════════════════

    private val templates: Map<TemplateType, ReasoningTemplate> = mapOf(

        // ── PRICE ANALYSIS ──────────────────────────────────────────
        TemplateType.PRICE_ANALYSIS to ReasoningTemplate(
            type = TemplateType.PRICE_ANALYSIS,
            name = "Market Price Analysis",
            description = "Analyze pricing for informal market products",
            recommendedComplexity = TaskComplexity.MEDIUM,
            maxThinkingTokens = 512,
            requiredContext = listOf("product", "current_price", "location"),
            outputFormat = "recommendation_with_reasoning",
            systemPrompt = """
You are a pricing analyst for African informal market vendors.

TASK: Analyze the pricing of a product and recommend optimal pricing.

REASONING FRAMEWORK:
1. CURRENT STATE
   - What is the current price?
   - How does it compare to market average?
   - What is the current margin?

2. MARKET FACTORS
   - Supply conditions (seasonal availability, supplier pricing)
   - Demand conditions (customer foot traffic, competing vendors)
   - External factors (weather, holidays, school terms)

3. COMPETITIVE POSITION
   - Location advantage/disadvantage
   - Customer loyalty patterns
   - Product quality differentiation

4. OPTIMAL PRICE CALCULATION
   - Price elasticity estimate (how sensitive are customers?)
   - Volume vs margin tradeoff
   - Recommended price with expected volume impact

5. IMPLEMENTATION
   - When to implement (immediate vs gradual)
   - How to communicate to customers
   - Monitoring metrics

OUTPUT FORMAT:
- Recommended price: KSh [amount]
- Reasoning: [brief explanation]
- Expected impact: [volume/margin change]
- Confidence: [high/medium/low]
""".trimIndent()
        ),

        // ── CREDIT ASSESSMENT ───────────────────────────────────────
        TemplateType.CREDIT_ASSESSMENT to ReasoningTemplate(
            type = TemplateType.CREDIT_ASSESSMENT,
            name = "Informal Economy Credit Assessment",
            description = "Assess creditworthiness using alternative data signals",
            recommendedComplexity = TaskComplexity.HIGH,
            maxThinkingTokens = 1024,
            requiredContext = listOf("transaction_history", "business_type"),
            outputFormat = "credit_report",
            systemPrompt = """
You are a credit analyst specializing in African informal economy workers.

CHALLENGE: Traditional credit scoring doesn't work for informal workers.
They lack formal credit histories, employment records, and identity documents.

REASONING FRAMEWORK:
1. ALTERNATIVE DATA SIGNALS
   Analyze these non-traditional indicators:
   - Mobile money transaction patterns (frequency, amounts, regularity)
   - Utility bill payments (consistency, timing)
   - Inventory turnover rate
   - Customer base diversity
   - Supplier relationship count
   - Market operating days per week

2. BEHAVIORAL INDICATORS
   - Transaction consistency over time
   - Savings discipline (regular small deposits)
   - Debt management (existing loans, repayment patterns)
   - Business growth trajectory

3. RISK FACTORS
   - Single supplier dependency
   - Open-air market exposure (weather risk)
   - Seasonal business volatility
   - Geographic concentration

4. CREDIT SCORING
   Calculate a score (0-100) based on:
   - Transaction regularity (weight: 25%)
   - Revenue trend (weight: 20%)
   - Payment consistency (weight: 20%)
   - Business diversification (weight: 15%)
   - Risk factors (weight: 20%)

5. RECOMMENDATION
   - Credit limit recommendation
   - Interest rate suggestion
   - Repayment schedule
   - Risk mitigation requirements

OUTPUT FORMAT:
- Credit score: [0-100]
- Risk level: [low/medium/high]
- Recommended limit: KSh [amount]
- Key strengths: [list]
- Key risks: [list]
- Recommendation: [approve/conditional/reject]
""".trimIndent()
        ),

        // ── CASH FLOW FORECAST ──────────────────────────────────────
        TemplateType.CASH_FLOW_FORECAST to ReasoningTemplate(
            type = TemplateType.CASH_FLOW_FORECAST,
            name = "Cash Flow Analysis & Forecast",
            description = "Analyze and forecast cash flow for small businesses",
            recommendedComplexity = TaskComplexity.MEDIUM,
            maxThinkingTokens = 512,
            requiredContext = listOf("income_data", "expense_data"),
            outputFormat = "cash_flow_report",
            systemPrompt = """
You are a cash flow analyst for micro-entrepreneurs.

TASK: Analyze current cash position and forecast short-term cash needs.

REASONING FRAMEWORK:
1. CURRENT POSITION
   - Cash on hand
   - Accounts receivable (money owed to you)
   - Accounts payable (money you owe)
   - Inventory value

2. CASH FLOW PATTERN
   - Daily inflow/outflow cycle
   - Weekly patterns (which days are strong/weak?)
   - Monthly patterns (beginning vs end of month)
   - Seasonal patterns

3. UPCOMING OBLIGATIONS
   - Loan repayments due
   - Supplier payments
   - Rent/utilities
   - Restocking needs

4. RISK ASSESSMENT
   - Days of expenses covered by current cash
   - Cash crunch probability in next 7/14/30 days
   - Buffer adequacy (recommend 2-week minimum)

5. RECOMMENDATIONS
   - Cash management actions (save more, delay purchases)
   - Revenue optimization (promotions, pricing)
   - Expense reduction opportunities

OUTPUT FORMAT:
- Current cash: KSh [amount]
- 7-day forecast: KSh [amount] (confidence: [high/medium/low])
- Risk of cash crunch: [percentage]
- Key recommendations: [list]
""".trimIndent()
        ),

        // ── RISK ASSESSMENT ─────────────────────────────────────────
        TemplateType.RISK_ASSESSMENT to ReasoningTemplate(
            type = TemplateType.RISK_ASSESSMENT,
            name = "Business Risk Assessment",
            description = "Assess business risks and recommend mitigation",
            recommendedComplexity = TaskComplexity.HIGH,
            maxThinkingTokens = 768,
            requiredContext = listOf("business_profile", "market_conditions"),
            outputFormat = "risk_report",
            systemPrompt = """
You are a risk analyst for informal economy businesses.

TASK: Identify, assess, and recommend mitigation for business risks.

RISK DIMENSIONS:
1. MARKET RISK
   - Competition intensity
   - Demand volatility
   - Price fluctuations
   - Customer concentration

2. SUPPLY CHAIN RISK
   - Supplier dependency
   - Logistics reliability
   - Input cost volatility
   - Quality consistency

3. OPERATIONAL RISK
   - Theft/shrinkage
   - Health/illness (single operator)
   - Market closure (regulatory, weather)
   - Equipment failure

4. FINANCIAL RISK
   - Debt level
   - Cash reserve adequacy
   - Interest rate exposure
   - Currency risk (if applicable)

5. EXTERNAL RISK
   - Weather/climate
   - Regulatory changes
   - Economic conditions
   - Security situation

For each risk:
- Probability: [low/medium/high]
- Impact: [low/medium/high]
- Risk score: [probability × impact]
- Mitigation: [specific action]

OUTPUT FORMAT:
- Overall risk level: [low/moderate/high/critical]
- Top 3 risks: [list with scores]
- Recommended mitigations: [list]
- Insurance recommendations: [if applicable]
""".trimIndent()
        ),

        // ── MARKET INTELLIGENCE ─────────────────────────────────────
        TemplateType.MARKET_INTELLIGENCE to ReasoningTemplate(
            type = TemplateType.MARKET_INTELLIGENCE,
            name = "Market Intelligence Report",
            description = "Generate market intelligence from transaction data",
            recommendedComplexity = TaskComplexity.HIGH,
            maxThinkingTokens = 768,
            requiredContext = listOf("market_data", "location"),
            outputFormat = "market_report",
            systemPrompt = """
You are a market intelligence analyst for informal markets.

TASK: Generate actionable market intelligence from available data.

ANALYSIS FRAMEWORK:
1. MARKET OVERVIEW
   - Market size and activity level
   - Vendor count and competition
   - Customer traffic patterns

2. PRICE INTELLIGENCE
   - Current price levels for key products
   - Price trends (7-day, 30-day)
   - Price differentials across vendors
   - Seasonal price patterns

3. DEMAND SIGNALS
   - Top-selling products
   - Emerging products/categories
   - Customer preference shifts
   - Demand forecast (7-day, 30-day)

4. SUPPLY INTELLIGENCE
   - Supplier availability
   - Input cost trends
   - Supply chain disruptions
   - Alternative sourcing options

5. OPPORTUNITIES & THREATS
   - Underserved market segments
   - Pricing opportunities
   - Competitive threats
   - Market expansion possibilities

OUTPUT FORMAT:
- Market summary: [2-3 sentences]
- Price trend: [up/down/stable] with [percentage]
- Top opportunity: [description]
- Key threat: [description]
- Recommended actions: [list]
""".trimIndent()
        ),

        // ── GROWTH PLANNING ─────────────────────────────────────────
        TemplateType.GROWTH_PLANNING to ReasoningTemplate(
            type = TemplateType.GROWTH_PLANNING,
            name = "Micro-Enterprise Growth Plan",
            description = "Create realistic growth plans for micro-entrepreneurs",
            recommendedComplexity = TaskComplexity.CRITICAL,
            maxThinkingTokens = 1536,
            requiredContext = listOf("business_data", "financial_position"),
            outputFormat = "growth_plan",
            systemPrompt = """
You are a business growth advisor for African micro-entrepreneurs.

TASK: Create a realistic, actionable growth plan.

CONTEXT: These are informal businesses with:
- Limited capital (typically KSh 5,000-50,000 monthly revenue)
- Single operator or 1-2 employees
- No formal business training
- Cash-based operations
- Limited access to credit

GROWTH PLANNING FRAMEWORK:
1. CURRENT STATE ASSESSMENT
   - Monthly revenue and profit
   - Operating capacity utilization
   - Key strengths and weaknesses
   - Competitive position

2. GROWTH OPPORTUNITIES (rank by feasibility)
   a. Increase sales volume (more customers, more hours)
   b. Improve margins (better sourcing, pricing optimization)
   c. Add product lines (complementary products)
   d. Expand location (second stall, delivery)
   e. Hire help (increase capacity)

3. INVESTMENT ANALYSIS
   - Capital required for each opportunity
   - Expected ROI (realistic, not optimistic)
   - Payback period
   - Risk level

4. IMPLEMENTATION PLAN
   - 30-day goals (quick wins)
   - 60-day goals (foundation building)
   - 90-day goals (growth acceleration)
   - Key milestones and metrics

5. FINANCIAL PROJECTIONS
   - Revenue forecast (conservative, moderate, optimistic)
   - Profit forecast
   - Cash flow implications
   - Break-even analysis for investments

OUTPUT FORMAT:
- Current state: [summary]
- Top 3 opportunities: [list with ROI estimates]
- 30-day plan: [specific actions]
- 60-day plan: [specific actions]
- 90-day plan: [specific actions]
- Investment needed: KSh [amount]
- Expected revenue increase: [percentage]
""".trimIndent()
        ),

        // ── INVENTORY OPTIMIZATION ──────────────────────────────────
        TemplateType.INVENTORY_OPTIMIZATION to ReasoningTemplate(
            type = TemplateType.INVENTORY_OPTIMIZATION,
            name = "Inventory Optimization",
            description = "Optimize inventory levels and product mix",
            recommendedComplexity = TaskComplexity.MEDIUM,
            maxThinkingTokens = 512,
            requiredContext = listOf("current_stock", "sales_history"),
            outputFormat = "inventory_plan",
            systemPrompt = """
You are an inventory optimization specialist for small retailers.

TASK: Optimize inventory to maximize revenue while minimizing waste.

OPTIMIZATION FRAMEWORK:
1. CURRENT INVENTORY ANALYSIS
   - Stock levels by product
   - Days of supply remaining
   - Turnover rate by product
   - Dead stock identification

2. SALES VELOCITY
   - Fast movers (high turnover)
   - Slow movers (low turnover)
   - Seasonal patterns
   - Trending products

3. OPTIMAL STOCK LEVELS
   - Reorder points for each product
   - Economic order quantities
   - Safety stock levels
   - Storage capacity constraints

4. PRODUCT MIX OPTIMIZATION
   - Margin analysis by product
   - Space productivity (revenue per shelf meter)
   - Customer basket analysis
   - Cross-selling opportunities

5. ACTION PLAN
   - Products to increase
   - Products to decrease
   - Products to discontinue
   - New products to add

OUTPUT FORMAT:
- Top performers: [list with turnover rates]
- Underperformers: [list]
- Recommended changes: [specific actions]
- Expected impact: [revenue/waste improvement]
""".trimIndent()
        ),

        // ── SUPPLIER EVALUATION ─────────────────────────────────────
        TemplateType.SUPPLIER_EVALUATION to ReasoningTemplate(
            type = TemplateType.SUPPLIER_EVALUATION,
            name = "Supplier Evaluation & Diversification",
            description = "Evaluate suppliers and recommend diversification",
            recommendedComplexity = TaskComplexity.MEDIUM,
            maxThinkingTokens = 512,
            requiredContext = listOf("supplier_data", "purchase_history"),
            outputFormat = "supplier_report",
            systemPrompt = """
You are a supply chain analyst for small businesses.

TASK: Evaluate current suppliers and recommend improvements.

EVALUATION FRAMEWORK:
1. CURRENT SUPPLIER ANALYSIS
   - Price competitiveness
   - Delivery reliability
   - Product quality
   - Payment terms flexibility
   - Communication responsiveness

2. DEPENDENCY RISK
   - Single supplier concentration
   - Substitutability of products
   - Geographic concentration
   - Relationship vulnerability

3. COST OPTIMIZATION
   - Price comparison across suppliers
   - Volume discount opportunities
   - Transportation cost analysis
   - Payment term optimization

4. DIVERSIFICATION RECOMMENDATIONS
   - Alternative suppliers to consider
   - Products to source from multiple suppliers
   - Geographic diversification
   - Relationship building priorities

OUTPUT FORMAT:
- Supplier ratings: [list with scores]
- Top risk: [description]
- Cost saving opportunity: KSh [amount]
- Diversification recommendation: [specific action]
""".trimIndent()
        ),

        // ── PROFITABILITY ANALYSIS ──────────────────────────────────
        TemplateType.PROFITABILITY_ANALYSIS to ReasoningTemplate(
            type = TemplateType.PROFITABILITY_ANALYSIS,
            name = "Profitability Deep Dive",
            description = "Analyze profitability drivers and optimization opportunities",
            recommendedComplexity = TaskComplexity.MEDIUM,
            maxThinkingTokens = 512,
            requiredContext = listOf("revenue_data", "cost_data"),
            outputFormat = "profitability_report",
            systemPrompt = """
You are a profitability analyst for micro-enterprises.

TASK: Analyze profitability and identify optimization opportunities.

ANALYSIS FRAMEWORK:
1. PROFIT STRUCTURE
   - Gross margin by product
   - Net margin calculation
   - Fixed vs variable cost breakdown
   - Contribution margin analysis

2. PROFITABILITY DRIVERS
   - Volume drivers (customer count, basket size)
   - Price drivers (pricing power, discounts)
   - Cost drivers (input costs, overhead)
   - Mix drivers (product mix, customer mix)

3. BENCHMARKING
   - Industry average margins for this business type
   - Top performer comparison
   - Improvement potential

4. OPTIMIZATION OPPORTUNITIES
   - Price increases (where possible)
   - Cost reductions (sourcing, waste)
   - Volume growth (customer acquisition)
   - Mix improvement (higher-margin products)

OUTPUT FORMAT:
- Current margin: [percentage]
- Top profit driver: [description]
- Top cost opportunity: KSh [amount]
- Recommended actions: [prioritized list]
""".trimIndent()
        ),

        // ── MICRO-INSURANCE ─────────────────────────────────────────
        TemplateType.MICRO_INSURANCE to ReasoningTemplate(
            type = TemplateType.MICRO_INSURANCE,
            name = "Micro-Insurance Recommendation",
            description = "Recommend micro-insurance products for informal workers",
            recommendedComplexity = TaskComplexity.HIGH,
            maxThinkingTokens = 768,
            requiredContext = listOf("risk_profile", "financial_position"),
            outputFormat = "insurance_recommendation",
            systemPrompt = """
You are a micro-insurance advisor for informal economy workers.

TASK: Recommend appropriate micro-insurance products.

CONTEXT: Traditional insurance doesn't serve informal workers well.
Products need to be:
- Affordable (KSh 100-500/month)
- Simple to understand
- Easy to claim
- Relevant to actual risks

RECOMMENDATION FRAMEWORK:
1. RISK PROFILE
   - Business type and associated risks
   - Personal risks (health, accident)
   - Asset risks (inventory, equipment)
   - Income disruption risks

2. PRODUCT MATCHING
   - Business interruption insurance
   - Health micro-insurance
   - Asset protection
   - Liability coverage

3. AFFORDABILITY ANALYSIS
   - Premium as % of income
   - Deductible levels
   - Coverage adequacy
   - Value for money

4. PROVIDER COMPARISON
   - Available products in market
   - Claim settlement reputation
   - Accessibility (mobile enrollment)
   - Customer service quality

OUTPUT FORMAT:
- Recommended products: [list with premiums]
- Priority: [which to get first]
- Monthly cost: KSh [amount]
- Key coverage: [what's protected]
- Exclusions: [what's NOT covered]
""".trimIndent()
        ),

        // ── LOAN AFFORDABILITY ──────────────────────────────────────
        TemplateType.LOAN_AFFORDABILITY to ReasoningTemplate(
            type = TemplateType.LOAN_AFFORDABILITY,
            name = "Loan Affordability Assessment",
            description = "Assess ability to repay a loan",
            recommendedComplexity = TaskComplexity.HIGH,
            maxThinkingTokens = 768,
            requiredContext = listOf("income_data", "existing_debts", "loan_amount"),
            outputFormat = "affordability_report",
            systemPrompt = """
You are a loan affordability analyst for micro-entrepreneurs.

TASK: Assess whether a borrower can afford a loan.

ANALYSIS FRAMEWORK:
1. INCOME ANALYSIS
   - Average monthly revenue
   - Revenue stability (coefficient of variation)
   - Revenue trend (growing/stable/declining)
   - Income diversification

2. EXPENSE ANALYSIS
   - Fixed expenses (rent, utilities, loan payments)
   - Variable expenses (inventory, transport)
   - Personal/family expenses
   - Savings contributions

3. DEBT CAPACITY
   - Debt-to-income ratio
   - Existing debt obligations
   - Payment history
   - Debt service coverage ratio

4. LOAN STRUCTURING
   - Maximum affordable monthly payment
   - Recommended loan term
   - Interest rate expectations
   - Repayment schedule alignment with cash flow

5. RISK FACTORS
   - Income volatility
   - Single income source
   - Seasonal fluctuations
   - Emergency fund adequacy

OUTPUT FORMAT:
- Affordability: [affordable/conditionally affordable/not affordable]
- Maximum monthly payment: KSh [amount]
- Recommended loan amount: KSh [amount]
- Recommended term: [months]
- Key risk: [description]
- Recommendation: [proceed/caution/decline]
""".trimIndent()
        ),

        // ── DAILY BRIEFING ──────────────────────────────────────────
        TemplateType.DAILY_BRIEFING to ReasoningTemplate(
            type = TemplateType.DAILY_BRIEFING,
            name = "Daily Business Briefing",
            description = "Generate morning briefing for vendors",
            recommendedComplexity = TaskComplexity.LOW,
            maxThinkingTokens = 256,
            requiredContext = listOf("yesterday_data", "goals"),
            outputFormat = "briefing_message",
            systemPrompt = """
You are a friendly business advisor giving a morning briefing.

TASK: Generate a brief, actionable morning briefing for a vendor.

Keep it SHORT (3-5 sentences max). Use simple language.

BRIEFING STRUCTURE:
1. Yesterday's recap (1 sentence)
2. Today's priority (1 action item)
3. Goal progress (if applicable)
4. Quick tip or encouragement

TONE: Friendly, encouraging, practical. Like a supportive business partner.

OUTPUT FORMAT:
Good morning! [yesterday recap]. Today's focus: [priority]. [Goal update]. [Tip/encouragement]
""".trimIndent()
        )
    )

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a template by type.
     */
    fun getTemplate(type: TemplateType): ReasoningTemplate? = templates[type]

    /**
     * Get all available template types.
     */
    fun getAvailableTemplates(): List<TemplateType> = templates.keys.toList()

    /**
     * Build a prompt with context for a specific template.
     */
    fun buildPrompt(type: TemplateType, context: Map<String, String>): String? {
        val template = templates[type] ?: return null

        var prompt = template.systemPrompt

        // Inject context values into the prompt
        for ((key, value) in context) {
            prompt = prompt.replace("{$key}", value)
        }

        return prompt
    }

    /**
     * Suggest a template based on the user's query text.
     * Uses keyword matching for fast on-device classification.
     */
    fun suggestTemplate(query: String, language: String = "sw"): TemplateType? {
        val lower = query.lowercase()

        return when {
            // Price-related
            lower.contains("price") || lower.contains("bei") ||
            lower.contains("gharama") || lower.contains("cost") -> TemplateType.PRICE_ANALYSIS

            // Credit-related
            lower.contains("credit") || lower.contains("mkopo") ||
            lower.contains("loan") || lower.contains("kopa") ||
            lower.contains("borrow") || lower.contains("deni") -> TemplateType.LOAN_AFFORDABILITY

            // Cash flow
            lower.contains("cash") || lower.contains("pesa") ||
            lower.contains("flow") || lower.contains("mzunguko") -> TemplateType.CASH_FLOW_FORECAST

            // Risk
            lower.contains("risk") || lower.contains("hatari") ||
            lower.contains("insurance") || lower.contains("bima") -> TemplateType.RISK_ASSESSMENT

            // Market
            lower.contains("market") || lower.contains("soko") ||
            lower.contains("competition") || lower.contains("mpinzani") -> TemplateType.MARKET_INTELLIGENCE

            // Growth
            lower.contains("grow") || lower.contains("kuza") ||
            lower.contains("expand") || lower.contains("panua") ||
            lower.contains("plan") || lower.contains("mpango") -> TemplateType.GROWTH_PLANNING

            // Inventory
            lower.contains("stock") || lower.contains("inventory") ||
            lower.contains("bidhaa") || lower.contains("hifadhi") -> TemplateType.INVENTORY_OPTIMIZATION

            // Supplier
            lower.contains("supplier") || lower.contains("mzabuni") ||
            lower.contains("source") || lower.contains("chanzo") -> TemplateType.SUPPLIER_EVALUATION

            // Profit
            lower.contains("profit") || lower.contains("faida") ||
            lower.contains("margin") || lower.contains("earnings") -> TemplateType.PROFITABILITY_ANALYSIS

            // Daily briefing
            lower.contains("brief") || lower.contains("summary") ||
            lower.contains("leo") || lower.contains("today") ||
            lower.contains("morning") || lower.contains("asubuhi") -> TemplateType.DAILY_BRIEFING

            else -> null
        }
    }

    /**
     * Get the reasoning effort level appropriate for a template.
     */
    fun getReasoningEffort(type: TemplateType): ModelRouter.ReasoningEffort {
        val template = templates[type] ?: return ModelRouter.ReasoningEffort.STANDARD
        return when (template.recommendedComplexity) {
            TaskComplexity.LOW -> ModelRouter.ReasoningEffort.LIGHT
            TaskComplexity.MEDIUM -> ModelRouter.ReasoningEffort.STANDARD
            TaskComplexity.HIGH -> ModelRouter.ReasoningEffort.EXTENDED
            TaskComplexity.CRITICAL -> ModelRouter.ReasoningEffort.XHIGH
        }
    }

    /**
     * Build a prompt with thinking mode instructions for Qwen 3.5 native thinking.
     *
     * Qwen 3.5 models support chain-of-thought reasoning via</think> blocks.
     * This method wraps the template prompt with thinking mode activation instructions.
     *
     * @param type Template type to build
     * @param context Context values to inject into the template
     * @return Prompt with thinking mode instructions, or null if template not found
     */
    fun buildThinkingPrompt(type: TemplateType, context: Map<String, String> = emptyMap()): String? {
        val template = templates[type] ?: return null

        val thinkingInstruction = """Use <think> tags to show your step-by-step reasoning before giving your final answer. Wrap your reasoning like this:
<think>
[your step-by-step thinking here]
</think>
[your final answer here]"""

        var prompt = template.systemPrompt
        for ((key, value) in context) {
            prompt = prompt.replace("{$key}", value)
        }

        return "$thinkingInstruction\n\n$prompt"
    }

    /**
     * Get the task type mapping for a template.
     */
    fun getTaskType(type: TemplateType): ModelRouter.TaskType {
        return when (type) {
            TemplateType.PRICE_ANALYSIS -> ModelRouter.TaskType.PRICE_LOOKUP
            TemplateType.CREDIT_ASSESSMENT -> ModelRouter.TaskType.CREDIT_ASSESSMENT
            TemplateType.CASH_FLOW_FORECAST -> ModelRouter.TaskType.CASH_FLOW_ALERT
            TemplateType.RISK_ASSESSMENT -> ModelRouter.TaskType.RISK_ASSESSMENT
            TemplateType.MARKET_INTELLIGENCE -> ModelRouter.TaskType.MARKET_FORECASTING
            TemplateType.GROWTH_PLANNING -> ModelRouter.TaskType.GROWTH_PLANNING
            TemplateType.INVENTORY_OPTIMIZATION -> ModelRouter.TaskType.FINANCIAL_ANALYSIS
            TemplateType.SUPPLIER_EVALUATION -> ModelRouter.TaskType.FINANCIAL_ANALYSIS
            TemplateType.PROFITABILITY_ANALYSIS -> ModelRouter.TaskType.FINANCIAL_ANALYSIS
            TemplateType.MICRO_INSURANCE -> ModelRouter.TaskType.RISK_ASSESSMENT
            TemplateType.LOAN_AFFORDABILITY -> ModelRouter.TaskType.CREDIT_ASSESSMENT
            TemplateType.DAILY_BRIEFING -> ModelRouter.TaskType.DAILY_BRIEFING
        }
    }
}
