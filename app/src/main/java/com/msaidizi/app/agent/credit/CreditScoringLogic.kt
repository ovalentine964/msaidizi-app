package com.msaidizi.app.agent.credit

/**
 * Credit scoring and financial analysis logic extracted from ModelRouter.
 *
 * Encapsulates financial reasoning templates, credit assessment scoring,
 * and risk classification for informal economy workers.
 */
object CreditScoringLogic {

    /**
     * Financial reasoning templates for common informal economy analysis tasks.
     * Previously embedded inline in ModelRouter.getFinancialTemplatePrompt().
     */
    enum class FinancialTemplate(val displayName: String, val requiredComplexity: String) {
        PRICE_ANALYSIS("Price Analysis", "MEDIUM"),
        CREDIT_ASSESSMENT("Credit Assessment", "HIGH"),
        CASH_FLOW_ANALYSIS("Cash Flow Analysis", "MEDIUM"),
        RISK_ASSESSMENT("Risk Assessment", "HIGH"),
        MARKET_FORECAST("Market Forecast", "HIGH"),
        GROWTH_PLANNING("Growth Planning", "CRITICAL"),
        DAILY_BRIEFING("Daily Briefing", "LOW"),
        INVENTORY_OPTIMIZATION("Inventory Optimization", "MEDIUM"),
        SUPPLIER_ANALYSIS("Supplier Analysis", "MEDIUM"),
        PROFITABILITY_ANALYSIS("Profitability Analysis", "MEDIUM")
    }

    /**
     * Get the reasoning template prompt for a financial analysis task.
     */
    fun getTemplatePrompt(template: FinancialTemplate, context: Map<String, String> = emptyMap()): String {
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

    /**
     * Classify the risk level of a credit assessment based on signals.
     */
    fun classifyCreditRisk(creditScore: Int): String {
        return when {
            creditScore >= 80 -> "LOW"
            creditScore >= 60 -> "MEDIUM"
            creditScore >= 40 -> "HIGH"
            else -> "VERY_HIGH"
        }
    }

    /**
     * Calculate a basic credit score from transaction signals.
     * Returns a score from 0-100.
     */
    fun calculateCreditScore(
        transactionConsistency: Double,   // 0.0-1.0
        revenueTrend: Double,             // -1.0 to 1.0 (negative = declining)
        cashFlowStability: Double,        // 0.0-1.0
        customerDiversity: Double,        // 0.0-1.0
        inventoryTurnover: Double         // 0.0-1.0
    ): Int {
        val score = (
            transactionConsistency * 30 +
            (revenueTrend.coerceIn(0.0, 1.0)) * 25 +
            cashFlowStability * 20 +
            customerDiversity * 15 +
            inventoryTurnover * 10
        )
        return score.toInt().coerceIn(0, 100)
    }
}
