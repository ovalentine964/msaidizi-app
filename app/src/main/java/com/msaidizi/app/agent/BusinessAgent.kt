package com.msaidizi.app.agent

import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.*
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt


/**
 * Business Agent — records transactions, tracks inventory, manages business state.
 * Pure code + SQLite — 0 LLM overhead for core operations.
 *
 * ## Economic & Statistical Foundations
 *
 * This agent is driven by Valentine's Economics & Statistics degree:
 *
 * ### ECO 101/201/321 — Microeconomics (Consumer Theory, Production Theory, Cost Analysis)
 * - **Consumer Theory (ECO 101 §1.3):** Models how buyers make choices given budget constraints.
 *   We track price elasticity to understand buyer behavior.
 * - **Production Theory (ECO 201 §1.2):** Q = f(K, L). We estimate marginal products
 *   and returns to scale for informal businesses.
 * - **Cost Analysis (ECO 101 §1.4):** TC = FC + VC. We track average cost, marginal cost,
 *   and break-even points for inventory items.
 * - **Market Structures (ECO 101 §1.5):** We classify market types and analyze
 *   competitive dynamics in informal markets.
 *
 * ### ECO 100/401 — Development Economics (Informal Economy, Structural Transformation)
 * - **Informal Economy Theory (ECO 100 §1.7):** The informal sector is rational entrepreneurial
 *   adaptation, not failure. We design for the actual operating environment of mama mbogas,
 *   jua kali artisans, and boda boda operators.
 * - **Dual Economy (Lewis Model):** We model the transition from traditional to modern
 *   business practices through information and capital access.
 *
 * ### BCB 108 — Business Communication
 * - **7Cs Principle:** All feedback is Clear, Concise, Correct, Complete, Coherent,
 *   Courteous, and Concrete.
 * - **Inverted Pyramid:** Most critical information first (revenue, profit, alerts).
 * - **Multilingual:** Responses adapt to user's language (Swahili, English, Sheng).
 *
 * @see AnalysisAgent for statistical pattern recognition
 * @see AdvisorAgent for economic advice generation
 */
class BusinessAgent(
    private val transactionDao: TransactionDao,
    private val inventoryDao: InventoryDao
) {

    // ═══════════════════════════════════════════════════════════════
    // ECO 101 §1.3 — CONSUMER THEORY: Track buyer behavior patterns
    // ═══════════════════════════════════════════════════════════════

    /**
     * Estimate price elasticity of demand for an item using log-log OLS regression.
     *
     * **ECO 101 §1.2 / ECO 201 §1.1:** Price Elasticity of Demand (PED) measures
     * how quantity demanded responds to price changes:
     *   PED = %ΔQ / %ΔP
     *
     * We estimate the constant-elasticity demand function:
     *   ln(Q) = α + β·ln(P) + ε
     * where β = price elasticity.
     *
     * **STA 241:** The OLS estimator β̂ = Cov(lnP, lnQ) / Var(lnP)
     * is BLUE under Gauss-Markov assumptions.
     *
     * @param item The product name
     * @param days Number of days of history to use
     * @return Estimated price elasticity (negative for normal goods),
     *         or null if insufficient data (< 5 observations)
     */
    suspend fun estimatePriceElasticity(item: String, days: Int = 30): Double? {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val salesHistory = transactionDao.getItemSalesHistory(item, startEpoch)

        // STA 341: Need minimum observations for reliable estimation
        if (salesHistory.size < 5) return null

        // Filter to valid (positive price and quantity) observations
        val data = salesHistory
            .filter { it.quantity > 0 && it.totalAmount > 0 }
            .map { tx ->
                val unitPrice = tx.totalAmount / tx.quantity
                Pair(ln(unitPrice), ln(tx.quantity))
            }

        if (data.size < 3) return null

        // OLS regression: ln(Q) = α + β·ln(P)
        // β̂ = Cov(lnP, lnQ) / Var(lnP)
        val n = data.size
        val sumX = data.sumOf { it.first }
        val sumY = data.sumOf { it.second }
        val sumXY = data.sumOf { it.first * it.second }
        val sumX2 = data.sumOf { it.first * it.first }

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return null

        val beta = (n * sumXY - sumX * sumY) / denominator

        Timber.d("Price elasticity for %s: β=%.3f (n=%d)", item, beta, data.size)
        return beta
    }

    /**
     * Classify the elasticity type for an item.
     *
     * **ECO 101 §1.2:** Elasticity classification:
     * - |PED| > 1: Elastic (luxury, many substitutes)
     * - |PED| < 1: Inelastic (necessity, few substitutes)
     * - |PED| = 1: Unit elastic
     *
     * For informal markets, staple foods (unga, mchele) are typically inelastic
     * while non-essentials (clothes, electronics) are elastic.
     */
    suspend fun classifyElasticity(item: String): ElasticityClassification {
        val elasticity = estimatePriceElasticity(item) ?: return ElasticityClassification.UNKNOWN
        val absElasticity = abs(elasticity)

        return when {
            absElasticity > 1.5 -> ElasticityClassification.HIGHLY_ELASTIC
            absElasticity > 1.0 -> ElasticityClassification.ELASTIC
            absElasticity > 0.5 -> ElasticityClassification.INELASTIC
            else -> ElasticityClassification.HIGHLY_INELASTIC
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 201 §1.2 — PRODUCTION THEORY: Track input-output relationships
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate the marginal cost of producing/selling one more unit of an item.
     *
     * **ECO 101 §1.4 / ECO 201 §1.2:** Marginal Cost (MC) = ΔTC / ΔQ
     * The change in total cost from producing one additional unit.
     *
     * For informal traders, this approximates the cost of restocking one more unit,
     * including purchase price, transport, and spoilage risk.
     *
     * @param item The product name
     * @return Marginal cost in KSh, or null if no cost data
     */
    suspend fun calculateMarginalCost(item: String): Double? {
        val avgCost = inventoryDao.getAverageCost(item)
        if (avgCost <= 0) return null

        // ECO 101 §1.4: MC ≈ AVC for small changes (when FC is spread over many units)
        // For informal traders, MC is approximately the unit purchase cost
        // adjusted for spoilage and handling
        val spoilageRate = estimateSpoilageRate(item)
        val adjustedCost = avgCost * (1 + spoilageRate)

        Timber.d("Marginal cost for %s: KSh %.0f (spoilage %.1f%%)",
            item, adjustedCost, spoilageRate * 100)
        return adjustedCost
    }

    /**
     * Estimate the spoilage/wastage rate for a product category.
     *
     * **ECO 101 §1.4:** In production theory, wastage is a form of inefficiency
     * that shifts the cost curve upward. Different product categories have
     * different spoilage rates based on perishability.
     *
     * Based on development economics research on informal food markets in Kenya:
     * - Fresh produce: 8-15% spoilage
     * - Grains: 2-5%
     * - Protein: 10-20%
     * - Prepared food: 15-25%
     * - Non-food: < 1%
     */
    private fun estimateSpoilageRate(item: String): Double {
        val category = classifyItem(item)
        return when (category) {
            "produce" -> 0.12    // Fresh vegetables spoil quickly in Kenya's heat
            "grains" -> 0.03     // Dry goods last longer
            "protein" -> 0.15    // Meat, fish, eggs are highly perishable
            "prepared_food" -> 0.20  // Mandazi, chapati must sell same day
            "cooking" -> 0.02    // Oil, sugar, salt are shelf-stable
            "household" -> 0.01  // Non-food items rarely spoil
            "transport" -> 0.00  // Services don't spoil
            "agriculture" -> 0.08 // Post-harvest losses for farm produce
            "digital" -> 0.00    // Digital transactions don't spoil
            "service" -> 0.00    // Services don't spoil
            "manufacturing" -> 0.02 // Some material waste
            else -> 0.05         // Default estimate
        }
    }

    /**
     * Calculate break-even point for an item.
     *
     * **ECO 101 §1.4:** Break-even occurs where Total Revenue = Total Cost:
     *   P × Q = FC + VC × Q
     *   Q_breakeven = FC / (P - VC)
     *
     * For informal traders, FC is typically rent + equipment; VC is purchase cost per unit.
     * This tells the trader how many units they must sell to cover costs.
     *
     * @param item The product name
     * @param sellingPrice Price per unit
     * @param dailyFixedCost Daily fixed costs (rent, transport, etc.)
     * @return Break-even quantity, or null if cost data insufficient
     */
    suspend fun calculateBreakEven(
        item: String,
        sellingPrice: Double,
        dailyFixedCost: Double = 0.0
    ): Int? {
        val avgCost = inventoryDao.getAverageCost(item)
        if (avgCost <= 0 || sellingPrice <= avgCost) return null

        val contributionMargin = sellingPrice - avgCost
        if (contributionMargin <= 0) return null

        val breakEvenQty = (dailyFixedCost / contributionMargin).toInt() + 1
        Timber.d("Break-even for %s: %d units at KSh %.0f (margin KSh %.0f)",
            item, breakEvenQty, sellingPrice, contributionMargin)
        return breakEvenQty
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 101 §1.5 — MARKET STRUCTURES: Analyze competitive dynamics
    // ═══════════════════════════════════════════════════════════════

    /**
     * Analyze the market structure for an item based on pricing patterns.
     *
     * **ECO 101 §1.5:** Market structures range from perfect competition
     * (many sellers, homogeneous products, price takers) to monopoly
     * (single seller, price maker).
     *
     * In Kenya's informal economy:
     * - Mama mboga (vegetables): Monopolistic competition (many sellers, differentiation)
     * - Jua kali workshops: Oligopoly (few large workshops for specific products)
     * - Mobile money (M-Pesa): Near-monopoly (network effects)
     *
     * We infer market structure from price dispersion:
     * - Low dispersion → competitive market (prices converge)
     * - High dispersion → differentiated/segmented market
     *
     * @param item The product name
     * @param days Number of days to analyze
     * @return Market structure analysis
     */
    suspend fun analyzeMarketStructure(item: String, days: Int = 30): MarketStructureAnalysis? {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val salesHistory = transactionDao.getItemSalesHistory(item, startEpoch)
        if (salesHistory.size < 5) return null

        val prices = salesHistory
            .filter { it.quantity > 0 }
            .map { it.totalAmount / it.quantity }

        if (prices.size < 3) return null

        val mean = prices.average()
        val variance = prices.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        val cv = if (mean > 0) stdDev / mean else 0.0  // Coefficient of variation

        // ECO 101 §1.5: Price dispersion indicates market structure
        val structure = when {
            cv < 0.05 -> MarketType.NEAR_PERFECT_COMPETITION
            cv < 0.15 -> MarketType.COMPETITIVE
            cv < 0.30 -> MarketType.MONOPOLISTIC_COMPETITION
            cv < 0.50 -> MarketType.OLIGOPOLY
            else -> MarketType.DIFFERENTIATED
        }

        return MarketStructureAnalysis(
            item = item,
            meanPrice = mean,
            priceStdDev = stdDev,
            coefficientOfVariation = cv,
            marketType = structure,
            observationCount = prices.size
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 100/401 — DEVELOPMENT ECONOMICS: Informal economy awareness
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate business formalization indicators.
     *
     * **ECO 100 §1.7 / ECO 401 §1.1.9:** The informal economy is not a failure
     * but rational entrepreneurial adaptation (Hart, 1973; De Soto, 1989).
     *
     * We track indicators that signal a business's transition from informal
     * to more structured operations — without requiring formal registration:
     * - Transaction regularity (daily recording)
     * - Record keeping consistency
     * - Inventory management sophistication
     * - Financial separation (personal vs. business)
     *
     * These align with Sen's Capability Approach: expanding what a business
     * can do and be, through information and organization.
     */
    suspend fun calculateFormalizationScore(): FormalizationScore {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(30)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val txCount = transactionDao.getTransactionCount(startEpoch, endEpoch)
        val dailyTotals = transactionDao.getDailySalesTotals(startEpoch)

        // Transaction regularity: what fraction of days had transactions?
        val activeDays = dailyTotals.size
        val regularity = activeDays / 30.0

        // Record keeping: average transactions per active day
        val avgTxsPerDay = if (activeDays > 0) txCount.toDouble() / activeDays else 0.0
        val recordKeeping = (avgTxsPerDay / 5.0).coerceIn(0.0, 1.0) // 5 txs/day = max score

        // Product diversity: number of distinct items
        val topItems = transactionDao.getTopSellingItems(startEpoch, endEpoch, 50)
        val diversity = (topItems.size / 10.0).coerceIn(0.0, 1.0) // 10 items = max score

        // Overall formalization score (0-100)
        val score = (regularity * 40 + recordKeeping * 30 + diversity * 30).coerceIn(0.0, 100.0)

        return FormalizationScore(
            totalScore = score,
            regularityScore = regularity * 100,
            recordKeepingScore = recordKeeping * 100,
            diversityScore = diversity * 100,
            activeDays = activeDays,
            totalTransactions = txCount,
            distinctProducts = topItems.size
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // BCB 108 — BUSINESS COMMUNICATION: Structured, accessible reporting
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a structured business report following BCB 108 principles.
     *
     * **BCB 108 §1.2:** Business writing follows the 7Cs:
     * - Clear: No jargon, simple language
     * - Concise: Key metrics only
     * - Correct: Accurate calculations
     * - Complete: All relevant metrics included
     * - Coherent: Logical flow (revenue → costs → profit → alerts)
     * - Courteous: Positive framing where possible
     * - Concrete: Numbers, not vague statements
     *
     * **BCB 108 §1.1 (Inverted Pyramid):** Most important information first.
     * A mama mboga checking her phone for 10 seconds should see profit first.
     *
     * @param date The date to report on
     * @param language Output language ("sw" for Swahili, "en" for English)
     * @return Structured business report
     */
    suspend fun generateBusinessReport(
        date: LocalDate = LocalDate.now(),
        language: String = "sw"
    ): BusinessReport {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val sales = transactionDao.getSalesTotal(startOfDay, endOfDay)
        val purchases = transactionDao.getPurchasesTotal(startOfDay, endOfDay)
        val expenses = transactionDao.getExpensesTotal(startOfDay, endOfDay)
        val profit = sales - purchases - expenses
        val count = transactionDao.getTransactionCount(startOfDay, endOfDay)
        val margin = if (sales > 0) (profit / sales * 100) else 0.0

        // ECO 201 §1.2: Calculate key business ratios
        val costToRevenueRatio = if (sales > 0) ((purchases + expenses) / sales) else 0.0

        // BCB 108: Generate human-readable summary
        val summary = if (language == "sw") {
            buildString {
                appendLine("📊 Ripoti ya Biashara — ${date}")
                appendLine()
                appendLine("💰 Mauzo: KSh ${"%.0f".format(sales)}")
                appendLine("🛒 Manunuzi: KSh ${"%.0f".format(purchases)}")
                appendLine("📋 Matumizi: KSh ${"%.0f".format(expenses)}")
                appendLine("📈 Faida: KSh ${"%.0f".format(profit)}")
                appendLine("📊 Margin: ${margin.toInt()}%")
                appendLine("📋 Shughuli: $count")
                if (costToRevenueRatio > 0.8) {
                    appendLine("⚠️ Gharama ni kubwa ikilinganishwa na mauzo")
                }
            }
        } else {
            buildString {
                appendLine("📊 Business Report — ${date}")
                appendLine()
                appendLine("💰 Sales: KSh ${"%.0f".format(sales)}")
                appendLine("🛒 Purchases: KSh ${"%.0f".format(purchases)}")
                appendLine("📋 Expenses: KSh ${"%.0f".format(expenses)}")
                appendLine("📈 Profit: KSh ${"%.0f".format(profit)}")
                appendLine("📊 Margin: ${margin.toInt()}%")
                appendLine("📋 Transactions: $count")
                if (costToRevenueRatio > 0.8) {
                    appendLine("⚠️ Costs are high relative to sales")
                }
            }
        }

        return BusinessReport(
            date = date.toString(),
            sales = sales,
            purchases = purchases,
            expenses = expenses,
            profit = profit,
            margin = margin,
            transactionCount = count,
            costToRevenueRatio = costToRevenueRatio,
            summary = summary
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CORE TRANSACTION RECORDING (Enhanced with economic context)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a sale transaction.
     * Automatically updates inventory and checks restock thresholds.
     *
     * **ECO 101 §1.4:** Each sale updates the cost basis and profit margin,
     * enabling real-time production theory analysis.
     */
    suspend fun recordSale(
        item: String,
        quantity: Double,
        amount: Double,
        language: String = "sw",
        confidence: Float = 1.0f
    ): Transaction {
        val unitPrice = if (quantity > 0) amount / quantity else amount
        val costBasis = inventoryDao.getAverageCost(item) * quantity

        val transaction = Transaction(
            type = TransactionType.SALE,
            item = item,
            category = classifyItem(item),
            quantity = quantity,
            unitPrice = unitPrice,
            totalAmount = amount,
            costBasis = costBasis,
            language = language,
            confidence = confidence
        )

        val id = transactionDao.insert(transaction)

        // Update inventory
        inventoryDao.decrementStock(item, quantity)

        // Check restock threshold
        checkRestockAlert(item)

        Timber.d("Recorded sale: %s x%.0f = KSh %.0f (id=%d)", item, quantity, amount, id)
        return transaction.copy(id = id)
    }

    /**
     * Record a purchase transaction.
     * Updates inventory and rolling average cost.
     *
     * **ECO 201 §1.2:** The weighted average cost method approximates
     * the firm's cost function in the short run:
     *   AVC = (old_stock × old_avg + new_qty × new_price) / total
     */
    suspend fun recordPurchase(
        item: String,
        quantity: Double,
        amount: Double,
        language: String = "sw",
        confidence: Float = 1.0f
    ): Transaction {
        val unitPrice = if (quantity > 0) amount / quantity else amount

        val transaction = Transaction(
            type = TransactionType.PURCHASE,
            item = item,
            category = classifyItem(item),
            quantity = quantity,
            unitPrice = unitPrice,
            totalAmount = amount,
            language = language,
            confidence = confidence
        )

        val id = transactionDao.insert(transaction)

        // Update inventory with new rolling average cost
        val existingItem = inventoryDao.getItem(item)
        val newAvgCost = if (existingItem != null && existingItem.currentStock > 0) {
            // Weighted average: (old_stock * old_avg + new_qty * new_price) / total
            val totalCost = (existingItem.currentStock * existingItem.avgCost) + amount
            val totalQty = existingItem.currentStock + quantity
            if (totalQty > 0) totalCost / totalQty else unitPrice
        } else {
            unitPrice
        }

        if (existingItem != null) {
            inventoryDao.incrementStock(item, quantity, newAvgCost)
        } else {
            inventoryDao.upsert(InventoryItem(
                item = item,
                category = classifyItem(item),
                currentStock = quantity,
                avgCost = unitPrice
            ))
        }

        Timber.d("Recorded purchase: %s x%.0f = KSh %.0f (id=%d)", item, quantity, amount, id)
        return transaction.copy(id = id)
    }

    /**
     * Record an expense transaction.
     */
    suspend fun recordExpense(
        category: String,
        amount: Double,
        notes: String = "",
        language: String = "sw"
    ): Transaction {
        val transaction = Transaction(
            type = TransactionType.EXPENSE,
            item = category,
            category = category,
            totalAmount = amount,
            notes = notes,
            language = language
        )

        val id = transactionDao.insert(transaction)
        Timber.d("Recorded expense: %s = KSh %.0f (id=%d)", category, amount, id)
        return transaction.copy(id = id)
    }

    // ═══════════════════════════════════════════════════════════════
    // TRANSPORT-SPECIFIC: Trip and fare recording
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a transport trip with fare and optional route data.
     *
     * **ECO 201 §1.2 (Transport):** Trip revenue = fare × passengers.
     * Key metrics: earnings per hour, fuel cost ratio, trips per day.
     *
     * @param fare Fare amount per trip/passenger
     * @param tripCount Number of trips (default 1)
     * @param route Route description (e.g., "CBD - Thika")
     * @param passengers Number of passengers (for matatu)
     * @param language Language of input
     */
    suspend fun recordTrip(
        fare: Double,
        tripCount: Int = 1,
        route: String = "",
        passengers: Int = 1,
        language: String = "sw"
    ): Transaction {
        val totalAmount = fare * tripCount
        val notes = buildString {
            if (route.isNotBlank()) append("route=$route")
            if (passengers > 1) append(", passengers=$passengers")
            append(", trips=$tripCount")
        }

        val transaction = Transaction(
            type = TransactionType.SALE,
            item = "trip",
            category = "transport",
            quantity = tripCount.toDouble(),
            unitPrice = fare,
            totalAmount = totalAmount,
            notes = notes,
            language = language
        )

        val id = transactionDao.insert(transaction)
        Timber.d("Recorded transport: %d trips × KSh %.0f = KSh %.0f (id=%d)",
            tripCount, fare, totalAmount, id)
        return transaction.copy(id = id)
    }

    /**
     * Record fuel expense for transport workers.
     *
     * @param amount Fuel cost
     * @param fuelType Type of fuel (petrol, diesel)
     * @param language Language of input
     */
    suspend fun recordFuelExpense(
        amount: Double,
        fuelType: String = "petrol",
        language: String = "sw"
    ): Transaction {
        val transaction = Transaction(
            type = TransactionType.EXPENSE,
            item = "fuel_$fuelType",
            category = "transport",
            totalAmount = amount,
            notes = "fuel_type=$fuelType",
            language = language
        )

        val id = transactionDao.insert(transaction)
        Timber.d("Recorded fuel: %s = KSh %.0f (id=%d)", fuelType, amount, id)
        return transaction.copy(id = id)
    }

    // ═══════════════════════════════════════════════════════════════
    // AGRICULTURE-SPECIFIC: Planting, harvest, input recording
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a farming activity (planting, harvesting, input purchase).
     *
     * **ECO 100 §1.7 / ECO 401:** Agriculture is the backbone of Kenya's
     * informal economy. Tracking planting→harvest cycles enables yield
     * optimization and post-harvest loss reduction.
     *
     * @param activity Type of activity: "plant", "harvest", "input"
     * @param crop Crop name (e.g., "mahindi", "nyanya")
     * @param quantity Quantity (kg for harvest, units for inputs)
     * @param area Area in acres (for planting)
     * @param amount Cost (for inputs) or revenue (for harvest sales)
     * @param language Language of input
     */
    suspend fun recordFarmingActivity(
        activity: String,
        crop: String,
        quantity: Double = 0.0,
        area: Double = 0.0,
        amount: Double = 0.0,
        language: String = "sw"
    ): Transaction {
        val type = when (activity) {
            "harvest" -> TransactionType.SALE
            "input" -> TransactionType.PURCHASE
            else -> TransactionType.OTHER
        }

        val notes = buildString {
            append("activity=$activity")
            if (area > 0) append(", area=${area}acres")
            if (quantity > 0) append(", quantity=${quantity}kg")
        }

        val transaction = Transaction(
            type = type,
            item = crop,
            category = "agriculture",
            quantity = quantity,
            totalAmount = amount,
            notes = notes,
            language = language
        )

        val id = transactionDao.insert(transaction)
        Timber.d("Recorded farming: %s %s (qty=%.0f, area=%.1f, KSh %.0f, id=%d)",
            activity, crop, quantity, area, amount, id)
        return transaction.copy(id = id)
    }

    // ═══════════════════════════════════════════════════════════════
    // DIGITAL/GIG-SPECIFIC: Commission, transaction volume
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a digital/gig transaction (commission, deposit, withdrawal).
     *
     * @param transactionType Type: "commission", "deposit", "withdrawal", "sale"
     * @param amount Amount in KSh
     * @param volume Number of transactions (for M-Pesa agents)
     * @param platform Platform (mpesa", "tiktok", "instagram")
     * @param language Language of input
     */
    suspend fun recordDigitalTransaction(
        transactionType: String,
        amount: Double,
        volume: Int = 1,
        platform: String = "mpesa",
        language: String = "sw"
    ): Transaction {
        val type = when (transactionType) {
            "commission" -> TransactionType.SALE
            "deposit" -> TransactionType.DEPOSIT
            "withdrawal" -> TransactionType.WITHDRAWAL
            "sale" -> TransactionType.SALE
            else -> TransactionType.OTHER
        }

        val transaction = Transaction(
            type = type,
            item = "${platform}_$transactionType",
            category = "digital",
            quantity = volume.toDouble(),
            unitPrice = if (volume > 0) amount / volume else amount,
            totalAmount = amount,
            paymentMethod = platform,
            notes = "platform=$platform, volume=$volume",
            language = language
        )

        val id = transactionDao.insert(transaction)
        Timber.d("Recorded digital: %s %s KSh %.0f (vol=%d, id=%d)",
            platform, transactionType, amount, volume, id)
        return transaction.copy(id = id)
    }

    // ═══════════════════════════════════════════════════════════════
    // SERVICE-SPECIFIC: Client and job recording
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a service job (haircut, repair, laundry, etc.).
     *
     * @param serviceType Type of service
     * @param amount Amount charged
     * @param clientCount Number of clients served
     * @param partsCost Cost of parts/materials (for mechanics, etc.)
     * @param language Language of input
     */
    suspend fun recordServiceJob(
        serviceType: String,
        amount: Double,
        clientCount: Int = 1,
        partsCost: Double = 0.0,
        language: String = "sw"
    ): Transaction {
        val laborRevenue = amount - partsCost
        val notes = buildString {
            append("service=$serviceType")
            if (clientCount > 1) append(", clients=$clientCount")
            if (partsCost > 0) append(", parts_cost=$partsCost")
            append(", labor_revenue=$laborRevenue")
        }

        val transaction = Transaction(
            type = TransactionType.SALE,
            item = serviceType,
            category = "service",
            quantity = clientCount.toDouble(),
            unitPrice = if (clientCount > 0) amount / clientCount else amount,
            totalAmount = amount,
            costBasis = partsCost,
            notes = notes,
            language = language
        )

        val id = transactionDao.insert(transaction)
        Timber.d("Recorded service: %s KSh %.0f (clients=%d, parts=KSh %.0f, id=%d)",
            serviceType, amount, clientCount, partsCost, id)
        return transaction.copy(id = id)
    }

    // ═══════════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════════

    // Get today's profit (sales - purchases - expenses).
    suspend fun getDailyProfit(date: LocalDate = LocalDate.now()): Double {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getProfit(startOfDay, endOfDay)
    }

    // Get today's sales total.
    suspend fun getDailySales(date: LocalDate = LocalDate.now()): Double {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getSalesTotal(startOfDay, endOfDay)
    }

    // Get today's purchases total.
    suspend fun getDailyPurchases(date: LocalDate = LocalDate.now()): Double {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getPurchasesTotal(startOfDay, endOfDay)
    }

    /**
     * Get cash flow for a period.
     *
     * **ECO 201 §1.2:** Cash flow analysis tracks the firm's liquidity position:
     *   Net Cash Flow = Inflow (sales) - Outflow (purchases + expenses)
     */
    suspend fun getCashFlow(days: Int = 7): CashFlow {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())

        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val sales = transactionDao.getSalesTotal(startEpoch, endEpoch)
        val purchases = transactionDao.getPurchasesTotal(startEpoch, endEpoch)
        val expenses = transactionDao.getExpensesTotal(startEpoch, endEpoch)

        return CashFlow(
            inflow = sales,
            outflow = purchases + expenses,
            net = sales - purchases - expenses,
            period = "${startDate} to ${endDate}"
        )
    }

    // Get current balance (total sales - total purchases - total expenses).
    suspend fun getBalance(): Double {
        val now = System.currentTimeMillis() / 1000
        return transactionDao.getProfit(0, now)
    }

    // Record a transaction (generic).
    suspend fun recordTransaction(transaction: Transaction) {
        transactionDao.insert(transaction)
    }

    // Get transaction count for today.
    suspend fun getDailyTransactionCount(date: LocalDate = LocalDate.now()): Int {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return transactionDao.getTransactionCount(startOfDay, endOfDay)
    }

    // Get top selling items.
    suspend fun getTopSellingItems(days: Int = 7, limit: Int = 5): List<ItemRanking> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        return transactionDao.getTopSellingItems(startEpoch, endEpoch, limit).map { tuple ->
            ItemRanking(
                item = tuple.item,
                totalQuantity = tuple.totalQty,
                totalRevenue = tuple.totalRev,
                transactionCount = tuple.txCount
            )
        }
    }

    // Get items needing restock.
    suspend fun getRestockAlerts(): List<RestockAlert> {
        return inventoryDao.getItemsNeedingRestock().map { item ->
            RestockAlert(
                item = item.item,
                currentStock = item.currentStock,
                threshold = item.restockThreshold,
                avgCost = item.avgCost,
                daysUntilStockout = calculateDaysUntilStockout(item)
            )
        }
    }

    // Generate daily summary.
    suspend fun generateDailySummary(date: LocalDate = LocalDate.now()): DailySummary {
        val startOfDay = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endOfDay = date.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val sales = transactionDao.getSalesTotal(startOfDay, endOfDay)
        val purchases = transactionDao.getPurchasesTotal(startOfDay, endOfDay)
        val expenses = transactionDao.getExpensesTotal(startOfDay, endOfDay)
        val profit = sales - purchases - expenses
        val count = transactionDao.getTransactionCount(startOfDay, endOfDay)
        val topItems = transactionDao.getTopSellingItems(startOfDay, endOfDay, 5)

        val summary = DailySummary(
            date = date.toString(),
            totalSales = sales,
            totalPurchases = purchases,
            totalExpenses = expenses,
            profit = profit,
            topItems = topItems.joinToString(",") { "${it.item}:${it.totalRev}" },
            transactionCount = count
        )

        return summary
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    // Check if an item needs restocking and log alert.
    private suspend fun checkRestockAlert(item: String) {
        val inventoryItem = inventoryDao.getItem(item) ?: return
        if (inventoryItem.currentStock <= inventoryItem.restockThreshold) {
            Timber.w("RESTOCK ALERT: %s has %.0f remaining (threshold: %.0f)",
                item, inventoryItem.currentStock, inventoryItem.restockThreshold)
        }
    }

    /**
     * Calculate days until stockout based on sales velocity.
     *
     * **STA 244:** Uses simple exponential smoothing on daily sales
     * to forecast depletion rate.
     */
    private suspend fun calculateDaysUntilStockout(item: InventoryItem): Int {
        if (item.currentStock <= 0) return 0

        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(7)
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val salesHistory = transactionDao.getItemSalesHistory(item.item, startEpoch)
        if (salesHistory.isEmpty()) return -1

        val totalSold = salesHistory.sumOf { it.quantity }
        val dailyRate = totalSold / 7.0

        return if (dailyRate > 0) {
            (item.currentStock / dailyRate).toInt()
        } else {
            -1
        }
    }

    /**
     * Auto-classify an item into a category.
     *
     * **ECO 100 / ECO 401:** Classification reflects Kenya's informal economy
     * structure — food dominates (60%+ of informal trade), followed by
     * household goods, transport, and services.
     */
    private fun classifyItem(item: String): String {
        val lower = item.lowercase()
        return when {
            // Fresh produce
            lower.contains("nyanya") || lower.contains("viazi") ||
            lower.contains("vitunguu") || lower.contains("karoti") ||
            lower.contains("sukuma") || lower.contains("mboga") ||
            lower.contains("matunda") || lower.contains("embe") ||
            lower.contains("ndizi") || lower.contains("machungwa") -> "produce"

            // Grains and staples
            lower.contains("unga") || lower.contains("mchele") ||
            lower.contains("mahindi") || lower.contains("maharagwe") ||
            lower.contains("dengu") || lower.contains("ngano") -> "grains"

            // Protein
            lower.contains("nyama") || lower.contains("kuku") ||
            lower.contains("samaki") || lower.contains("mayai") ||
            lower.contains("maziwa") -> "protein"

            // Cooking essentials
            lower.contains("mafuta") || lower.contains("sukari") ||
            lower.contains("chumvi") || lower.contains("chai") -> "cooking"

            // Prepared food
            lower.contains("mandazi") || lower.contains("chapati") ||
            lower.contains("mkate") || lower.contains("ugali") ||
            lower.contains("nyama choma") || lower.contains("chipsi") -> "prepared_food"

            // Household items
            lower.contains("sabuni") || lower.contains("dawa") ||
            lower.contains("pampers") || lower.contains("mshumaa") ||
            lower.contains("toothpaste") -> "household"

            // Transport-related
            lower.contains("usafiri") || lower.contains("fare") ||
            lower.contains("trip") || lower.contains("abiria") ||
            lower.contains("pikipiki") || lower.contains("matatu") ||
            lower.contains("boda") || lower.contains("nduthi") ||
            lower.contains("passenger") || lower.contains("delivery") -> "transport"

            // Agriculture/Farming
            lower.contains("mbegu") || lower.contains("mbolea") ||
            lower.contains("fertilizer") || lower.contains("dawa ya mashamba") ||
            lower.contains("pesticide") || lower.contains("harvest") ||
            lower.contains("mazao") || lower.contains("shamba") ||
            lower.contains("ekari") || lower.contains("crop") -> "agriculture"

            // Digital/Gig economy
            lower.contains("commission") || lower.contains("komisheni") ||
            lower.contains("float") || lower.contains("airtime") ||
            lower.contains("bundle") || lower.contains("data") ||
            lower.contains("deposit") || lower.contains("withdrawal") ||
            lower.contains("transaction") || lower.contains("boost") ||
            lower.contains("matangazo") || lower.contains("advert") -> "digital"

            // Services
            lower.contains("kunyolewa") || lower.contains("salon") ||
            lower.contains("haircut") || lower.contains("fundi") ||
            lower.contains("mechanic") || lower.contains("repair") ||
            lower.contains("service") || lower.contains("ushona") ||
            lower.contains("tailor") || lower.contains("laundry") ||
            lower.contains("fua") -> "service"

            // Manufacturing/Artisan
            lower.contains("chuma") || lower.contains("mbao") ||
            lower.contains("furniture") || lower.contains("meza") ||
            lower.contains("kiti") || lower.contains("bed") ||
            lower.contains("welding") || lower.contains("fabric") ||
            lower.contains("nguo") || lower.contains("brick") ||
            lower.contains("tofali") -> "manufacturing"

            // General expenses
            lower.contains("rent") || lower.contains("kodi") ||
            lower.contains("stima") || lower.contains("umeme") -> "expense"

            else -> "other"
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES — Economic analysis results
// ═══════════════════════════════════════════════════════════════

/**
 * **ECO 101 §1.2:** Price elasticity classification.
 * Informs pricing strategy and demand forecasting.
 */
enum class ElasticityClassification {
    // |PED| > 1.5 — Small price changes cause large demand shifts
    HIGHLY_ELASTIC,
    // |PED| 1.0-1.5 — Demand is price-sensitive
    ELASTIC,
    // |PED| 0.5-1.0 — Demand is somewhat price-insensitive
    INELASTIC,
    // |PED| < 0.5 — Necessities; demand barely changes with price
    HIGHLY_INELASTIC,
    // Insufficient data to classify
    UNKNOWN
}

/**
 * **ECO 101 §1.5:** Market structure analysis based on price dispersion.
 */
data class MarketStructureAnalysis(
    val item: String,
    val meanPrice: Double,
    val priceStdDev: Double,
    val coefficientOfVariation: Double,
    val marketType: MarketType,
    val observationCount: Int
)

/**
 * **ECO 101 §1.5:** Market types observed in informal economies.
 */
enum class MarketType {
    NEAR_PERFECT_COMPETITION,  // CV < 5%: many sellers, homogeneous product
    COMPETITIVE,                // CV 5-15%: competitive with minor differentiation
    MONOPOLISTIC_COMPETITION,   // CV 15-30%: differentiated products
    OLIGOPOLY,                  // CV 30-50%: few dominant sellers
    DIFFERENTIATED              // CV > 50%: highly segmented market
}

/**
 * **ECO 401 §1.1.9:** Business formalization score.
 * Tracks the transition from informal to structured operations.
 */
data class FormalizationScore(
    val totalScore: Double,
    val regularityScore: Double,
    val recordKeepingScore: Double,
    val diversityScore: Double,
    val activeDays: Int,
    val totalTransactions: Int,
    val distinctProducts: Int
)

/**
 * **BCB 108 §1.2:** Structured business report following communication best practices.
 */
data class BusinessReport(
    val date: String,
    val sales: Double,
    val purchases: Double,
    val expenses: Double,
    val profit: Double,
    val margin: Double,
    val transactionCount: Int,
    val costToRevenueRatio: Double,
    val summary: String
)
