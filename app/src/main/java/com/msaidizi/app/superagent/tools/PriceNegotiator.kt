package com.msaidizi.app.superagent.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow

/**
 * PriceNegotiator — Voice-based price negotiation assistant.
 *
 * Empowers informal workers to negotiate from a position of knowledge.
 * When a mama mboga hears "tomatoes ni 80 per kilo," she speaks to Msaidizi
 * and gets an instant counter-offer recommendation based on real market data,
 * supplier history, and negotiation strategy.
 *
 * Actions:
 *   negotiate       – Worker states quoted price; returns optimal counter-offer
 *   counter_offer   – Calculates optimal counter-offer from fair price data
 *   walk_away_price – Determines the maximum price worth paying
 *   history         – Shows past negotiation outcomes for a product
 *   tips            – Negotiation tips tailored to context (product/wage/fare)
 *
 * Voice trigger examples (Swahili):
 *   "Bei ya nyanya ni 80, nifanye nini?"
 *   "Nimpee bei gani?"
 *   "Bei ya juu kabisa ni ngapi?"
 *   "Niliongea bei ya nyanya jana"
 *   "Nipe ushauri wa kujadiliana"
 */
@Singleton
class PriceNegotiator @Inject constructor() : Tool {

    override val name = "price_negotiator"
    override val description =
        "Voice-based price negotiation assistant. Suggests optimal counter-offers " +
        "from market data, tracks negotiation history, and provides Swahili negotiation tips."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf("negotiate", "counter_offer", "walk_away_price", "history", "tips"),
            required = true
        )
        string("product", "Product name (e.g. nyanya, sukuma wiki)", required = false)
        number("quoted_price", "Price quoted by supplier/buyer in KES", required = false)
        number("quantity", "Quantity in units (default 1)", required = false)
        string("unit", "Unit of measurement (kg, bunch, piece, litre)", required = false, default = "kg")
        string("market", "Market or supplier name", required = false)
        string("context", "Negotiation context: wholesale, retail, wage, fare", required = false, default = "wholesale")
        string("outcome", "Final outcome when logging: accepted, rejected, partial, walked_away", required = false)
        number("final_price", "Final agreed price when logging outcome", required = false)
    }

    // ── In-memory fallback (used when no DB context is available) ──────────
    private val fairPrices = mutableMapOf<String, FairPriceRange>()
    private val negotiationLog = mutableListOf<NegotiationRecord>()

    // ── Public API ─────────────────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required. Use: negotiate, counter_offer, walk_away_price, history, tips", "MISSING_ACTION")

        return when (action.lowercase()) {
            "negotiate" -> handleNegotiate(params)
            "counter_offer" -> handleCounterOffer(params)
            "walk_away_price" -> handleWalkAwayPrice(params)
            "history" -> handleHistory(params)
            "tips" -> handleTips(params)
            else -> ToolResult.error(name, "Unknown action: $action. Use: negotiate, counter_offer, walk_away_price, history, tips", "INVALID_ACTION")
        }
    }

    // ── negotiate ──────────────────────────────────────────────────────────
    // Worker says: "Bei ya nyanya ni 80, nifanye nini?"
    // Returns: fair price range, counter-offer, negotiation script, savings estimate

    private fun handleNegotiate(params: Map<String, String>): ToolResult {
        val product = params["product"]
            ?: return ToolResult.error(name, "Product name required. E.g. 'nyanya', 'sukuma wiki'", "MISSING_PRODUCT")
        val quotedPrice = params["quoted_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Quoted price required in KES. E.g. '80'", "MISSING_PRICE")
        val quantity = params["quantity"]?.toDoubleOrNull() ?: 1.0
        val unit = params["unit"] ?: "kg"
        val market = params["market"] ?: ""
        val context = params["context"] ?: "wholesale"

        val fairPrice = lookupFairPrice(product, market, context)
            ?: return suggestNoDataResponse(product, quotedPrice, unit)

        val counterPrice = calculateCounterOffer(quotedPrice, fairPrice, context)
        val walkAway = calculateWalkAwayPrice(fairPrice, context)
        val savingsPerUnit = quotedPrice - counterPrice
        val totalSavings = savingsPerUnit * quantity
        val script = generateNegotiationScript(product, counterPrice, unit, market)

        // Log this negotiation request
        logNegotiation(product, quotedPrice, counterPrice, quantity, unit, market, context)

        val confidenceLabel = when {
            fairPrice.confidence >= 0.7 -> "ya juu"
            fairPrice.confidence >= 0.4 -> "ya kati"
            else -> "ya chini"
        }

        val response = buildString {
            appendLine("Bei ya sasa ya $product${if (market.isNotEmpty()) " $market" else ""}: " +
                "${formatKes(fairPrice.min)} hadi ${formatKes(fairPrice.max)} kwa $unit.")
            appendLine()
            appendLine("Pendekezo: Nunua kwa ${formatKes(counterPrice)} kwa $unit.")
            if (quantity > 1) {
                appendLine("Kwa $unit ${formatQty(quantity)}: utaokoa ${formatKes(totalSavings)}.")
            }
            appendLine()
            appendLine("Bei ya juu kabisa usiyoze: ${formatKes(walkAway)} kwa $unit.")
            appendLine()
            appendLine("Mwambie: \"$script\"")
            appendLine()
            appendLine("Ujasiri wa bei: $confidenceLabel.")
            if (savingsPerUnit <= 0) {
                appendLine("Bei iliyopendekezwa ni sawa na au zaidi ya bei ya soko — bei uliyopewa ni nzuri!")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product,
                "quoted_price" to quotedPrice,
                "fair_min" to fairPrice.min,
                "fair_max" to fairPrice.max,
                "counter_offer" to counterPrice,
                "walk_away_price" to walkAway,
                "savings_per_unit" to savingsPerUnit,
                "total_savings" to totalSavings,
                "unit" to unit,
                "confidence" to fairPrice.confidence,
                "script" to script
            ),
            response.trim()
        )
    }

    // ── counter_offer ──────────────────────────────────────────────────────
    // Calculates the optimal counter-offer price

    private fun handleCounterOffer(params: Map<String, String>): ToolResult {
        val product = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val quotedPrice = params["quoted_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Quoted price required in KES", "MISSING_PRICE")
        val market = params["market"] ?: ""
        val context = params["context"] ?: "wholesale"

        val fairPrice = lookupFairPrice(product, market, context)
            ?: return suggestNoDataResponse(product, quotedPrice, params["unit"] ?: "kg")

        val counter = calculateCounterOffer(quotedPrice, fairPrice, context)
        val diff = quotedPrice - counter
        val diffPct = if (quotedPrice > 0) ((diff / quotedPrice) * 100).toInt() else 0

        val response = if (diff > 0) {
            "Mpee bei ya ${formatKes(counter)} kwa ${params["unit"] ?: "kg"}. " +
                "Hii ni punguza la ${formatKes(diff)} ($diffPct%) kutoka ${formatKes(quotedPrice)}. " +
                "Bei ya soko: ${formatKes(fairPrice.min)}–${formatKes(fairPrice.max)}."
        } else {
            "Bei ya ${formatKes(quotedPrice)} ni nzuri — iko chini ya bei ya soko " +
                "(${formatKes(fairPrice.min)}–${formatKes(fairPrice.max)}). Kubali!"
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product,
                "quoted_price" to quotedPrice,
                "counter_offer" to counter,
                "fair_min" to fairPrice.min,
                "fair_max" to fairPrice.max,
                "savings" to diff
            ),
            response
        )
    }

    // ── walk_away_price ────────────────────────────────────────────────────
    // The maximum price worth paying before walking away

    private fun handleWalkAwayPrice(params: Map<String, String>): ToolResult {
        val product = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val market = params["market"] ?: ""
        val context = params["context"] ?: "wholesale"
        val unit = params["unit"] ?: "kg"

        val fairPrice = lookupFairPrice(product, market, context)
            ?: return ToolResult.error(
                name,
                "Sina bei ya soko ya $product. Tafadhaliambia bei ya soko kwanza.",
                "NO_PRICE_DATA"
            )

        val walkAway = calculateWalkAwayPrice(fairPrice, context)

        val response = buildString {
            appendLine("Bei ya juu kabisa ya $product: ${formatKes(walkAway)} kwa $unit.")
            appendLine()
            appendLine("Bei ya soko: ${formatKes(fairPrice.min)}–${formatKes(fairPrice.max)} kwa $unit.")
            appendLine()
            appendLine("Ukipewa zaidi ya ${formatKes(walkAway)}, " +
                "enda sokoni jirani au mwingine — utapata bei bora.")
            appendLine()
            appendLine("Mwambie: \"Ninunue kwa ${formatKes(walkAway)}, au nitafuta mwingine.\"")
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product,
                "walk_away_price" to walkAway,
                "fair_min" to fairPrice.min,
                "fair_max" to fairPrice.max,
                "unit" to unit
            ),
            response.trim()
        )
    }

    // ── history ────────────────────────────────────────────────────────────
    // Shows past negotiation outcomes for a product

    private fun handleHistory(params: Map<String, String>): ToolResult {
        val product = params["product"]

        val records = if (product != null) {
            negotiationLog.filter { it.product.equals(product, ignoreCase = true) }
        } else {
            negotiationLog.takeLast(20)
        }

        if (records.isEmpty()) {
            val msg = if (product != null) {
                "Hakuna historia ya mazungumzo ya $product bado."
            } else {
                "Hakuna historia ya mazungumzo bado. Anza kwa kusema bei uliyopewa!"
            }
            return ToolResult.success(name, emptyMap<String, Any>(), msg)
        }

        val response = buildString {
            appendLine("Historia ya mazungumzo${if (product != null) " ya $product" else ""}:")
            appendLine()
            records.takeLast(10).forEach { r ->
                val outcome = when (r.outcome) {
                    "accepted" -> "✓"
                    "rejected" -> "✗"
                    "walked_away" -> "→"
                    else -> "•"
                }
                val savings = r.quotedPrice - (r.finalPrice ?: r.counterPrice)
                appendLine("$outcome ${r.product}: ${formatKes(r.quotedPrice)} → " +
                    "${formatKes(r.finalPrice ?: r.counterPrice)} " +
                    "(okoa ${formatKes(savings * r.quantity)}${if (r.quantity > 1) " kwa ${formatQty(r.quantity)} ${r.unit}" else ""})")
            }
            appendLine()
            val totalSaved = records.sumOf { (it.quotedPrice - (it.finalPrice ?: it.counterPrice)) * it.quantity }
            appendLine("Jumla uliyo okoa: ${formatKes(totalSaved)}")
        }

        return ToolResult.success(
            name,
            mapOf(
                "record_count" to records.size,
                "total_saved" to records.sumOf { (it.quotedPrice - (it.finalPrice ?: it.counterPrice)) * it.quantity }
            ),
            response.trim()
        )
    }

    // ── tips ───────────────────────────────────────────────────────────────
    // Negotiation tips tailored to context

    private fun handleTips(params: Map<String, String>): ToolResult {
        val context = params["context"] ?: "wholesale"
        val product = params["product"]

        val tips = when (context.lowercase()) {
            "wage" -> wageTips
            "fare" -> fareTips
            else -> productTips
        }

        val response = buildString {
            appendLine("Ushauri wa kujadiliana${if (product != null) " kwa $product" else ""}:")
            appendLine()
            tips.forEachIndexed { i, tip ->
                appendLine("${i + 1}. $tip")
            }
            appendLine()
            appendLine("Kumbuka: Daima kuwa na heshima. Mazungumzo mazuri yanajenga uhusiano wa muda mrefu.")
        }

        return ToolResult.success(name, mapOf("context" to context, "tip_count" to tips.size), response.trim())
    }

    // ── Fair Price Lookup ──────────────────────────────────────────────────

    private fun lookupFairPrice(product: String, market: String, context: String): FairPriceRange? {
        // Check in-memory cache first
        val key = "${product.lowercase()}_${market.lowercase()}_${context.lowercase()}"
        fairPrices[key]?.let { return it }

        // Try without market qualifier
        val productKey = "${product.lowercase()}__${context.lowercase()}"
        fairPrices[productKey]?.let { return it }

        // Try product-only key
        fairPrices[product.lowercase()]?.let { return it }

        // Seed with common Kenyan market prices if nothing found
        return seedPrices[product.lowercase()]
    }

    /**
     * Update fair price data (called from MarketPriceBroadcaster or manual entry).
     */
    fun updateFairPrice(product: String, market: String, min: Double, max: Double,
                        unit: String = "kg", confidence: Double = 0.5) {
        val key = "${product.lowercase()}_${market.lowercase()}_wholesale"
        fairPrices[key] = FairPriceRange(min, max, unit, confidence)
        // Also store without market for general lookup
        fairPrices["${product.lowercase()}__wholesale"] = FairPriceRange(min, max, unit, confidence)
    }

    // ── Negotiation Calculations ───────────────────────────────────────────

    /**
     * Calculate optimal counter-offer.
     * Strategy: aim for the lower end of fair range, adjusted by context.
     */
    private fun calculateCounterOffer(quotedPrice: Double, fair: FairPriceRange, context: String): Double {
        val target = when (context.lowercase()) {
            "wage" -> fair.max  // For wages, aim higher (worker perspective)
            "fare" -> (fair.min + fair.max) / 2  // Fares: aim for middle
            "retail" -> fair.min + (fair.max - fair.min) * 0.3  // Retail: lean low
            else -> fair.min + (fair.max - fair.min) * 0.25  // Wholesale: aim low
        }
        // Counter-offer should never exceed the quoted price
        return minOf(target, quotedPrice).let { Math.round(it / 5.0) * 5.0 } // Round to nearest 5
    }

    /**
     * Walk-away price: the ceiling before it's not worth buying.
     * Typically fair max + 10% tolerance.
     */
    private fun calculateWalkAwayPrice(fair: FairPriceRange, context: String): Double {
        val base = when (context.lowercase()) {
            "wage" -> fair.min * 0.85  // For wages, floor is 85% of min fair
            else -> fair.max * 1.10     // For purchases, ceiling is 110% of max fair
        }
        return Math.round(base / 5.0) * 5.0  // Round to nearest 5
    }

    // ── Negotiation Script Generation ──────────────────────────────────────

    private fun generateNegotiationScript(product: String, counterPrice: Double, unit: String, market: String): String {
        return "Ninunue $product kwa ${formatKes(counterPrice)} kwa $unit, au nitafuta mwingine."
    }

    // ── Logging ────────────────────────────────────────────────────────────

    private fun logNegotiation(product: String, quotedPrice: Double, counterPrice: Double,
                               quantity: Double, unit: String, market: String, context: String) {
        negotiationLog.add(
            NegotiationRecord(
                product = product,
                quotedPrice = quotedPrice,
                counterPrice = counterPrice,
                finalPrice = null,
                quantity = quantity,
                unit = unit,
                market = market,
                context = context,
                outcome = "pending"
            )
        )
    }

    /**
     * Log the final outcome of a negotiation (called after worker confirms).
     */
    fun logOutcome(product: String, quotedPrice: Double, finalPrice: Double,
                   quantity: Double, unit: String, outcome: String) {
        negotiationLog.add(
            NegotiationRecord(
                product = product,
                quotedPrice = quotedPrice,
                counterPrice = finalPrice,
                finalPrice = finalPrice,
                quantity = quantity,
                unit = unit,
                market = "",
                context = "wholesale",
                outcome = outcome
            )
        )
    }

    // ── No-Data Response ───────────────────────────────────────────────────

    private fun suggestNoDataResponse(product: String, quotedPrice: Double, unit: String): ToolResult {
        val response = buildString {
            appendLine("Sina bei ya soko ya $product bado.")
            appendLine()
            appendLine("Bei uliyopewa ni ${formatKes(quotedPrice)} kwa $unit.")
            appendLine()
            appendLine("Tafadhaliambia bei ya soko ili nikusaidie kujadiliana. " +
                "Sema: \"Bei ya soko ya $product ni [bei]\"")
            appendLine()
            appendLine("Ushauri wa haraka:")
            appendLine("• Uliza wauzaji wengine bei yao kabla ya kununua")
            appendLine("• Anza na nusu ya bei uliyopewa kama kujadiliana")
            appendLine("• Usiogope kuondoka — mara nyingi watakupigia simu")
        }

        return ToolResult.success(
            name,
            mapOf("product" to product, "quoted_price" to quotedPrice, "has_data" to false),
            response.trim()
        )
    }

    // ── Formatting Helpers ─────────────────────────────────────────────────

    private fun formatKes(amount: Double): String = "KES ${"%,.0f".format(amount)}"

    private fun formatQty(qty: Double): String =
        if (qty == qty.toLong().toDouble()) qty.toLong().toString() else "%,.1f".format(qty)

    // ── Academic Formula Methods (ECO 101/321, MAT 121) ─────────────────────

    /**
     * Price Elasticity of Demand (PED) via log-log regression.
     * PED = %ΔQ / %ΔP, estimated as slope of ln(Q) on ln(P).
     * Returns negative value (law of demand); magnitude > 1 = elastic.
     *
     * @param priceHistory List of historical prices
     * @param quantityHistory Corresponding quantities sold
     * @return Estimated PED (typically negative)
     */
    fun calculateElasticity(
        priceHistory: List<Double>,
        quantityHistory: List<Double>
    ): Double {
        require(priceHistory.size == quantityHistory.size) { "Price and quantity lists must be same length" }
        require(priceHistory.size >= 2) { "Need at least 2 data points" }

        val lnP = priceHistory.map { ln(it) }
        val lnQ = quantityHistory.map { ln(it) }
        val meanLnP = lnP.average()
        val meanLnQ = lnQ.average()

        val numerator = lnP.zip(lnQ).sumOf { (p, q) -> (p - meanLnP) * (q - meanLnQ) }
        val denominator = lnP.sumOf { (it - meanLnP).pow(2) }

        return if (denominator > 1e-10) numerator / denominator else -1.0
    }

    /**
     * Simple PED from two data points.
     * PED = (ΔQ/Q̄) / (ΔP/P̄)
     */
    fun calculateElasticity(
        currentPrice: Double, currentQty: Double,
        newPrice: Double, newQty: Double
    ): Double {
        val avgPrice = (currentPrice + newPrice) / 2.0
        val avgQty = (currentQty + newQty) / 2.0
        if (avgPrice == 0.0 || avgQty == 0.0) return 0.0
        val pctChangeQty = (newQty - currentQty) / avgQty
        val pctChangePrice = (newPrice - currentPrice) / avgPrice
        return if (pctChangePrice != 0.0) pctChangeQty / pctChangePrice else 0.0
    }

    /**
     * Nash Bargaining Solution.
     * Finds the price that maximizes: (sellerSurplus)^α × (buyerSurplus)^(1-α)
     * where sellerSurplus = price - sellerMin, buyerSurplus = buyerMax - price.
     *
     * @param sellerMin Seller's reservation price (minimum acceptable)
     * @param buyerMax Buyer's maximum willingness to pay
     * @param sellerPower α ∈ [0,1] — seller's bargaining power (0.5 = equal)
     * @return Optimal price from Nash bargaining
     */
    fun nashBargaining(
        sellerMin: Double,
        buyerMax: Double,
        sellerPower: Double = 0.5
    ): Double {
        require(buyerMax > sellerMin) { "No zone of agreement: buyerMax must exceed sellerMin" }
        require(sellerPower in 0.0..1.0) { "sellerPower must be between 0 and 1" }

        val totalSurplus = buyerMax - sellerMin
        // Nash solution: price = sellerMin + α × (buyerMax - sellerMin)
        return sellerMin + totalSurplus * sellerPower
    }

    /**
     * Optimal (profit-maximizing) price for linear demand.
     * Given demand Q = a - b·P, the monopoly optimal price is:
     *   P* = (a + b·MC) / (2b)  =  (a - MC) / (2b) + MC  ... but the standard
     *   MR = MC derivation for inverse demand P = (a - Q)/b gives:
     *   P* = (a + b·MC) / (2b)
     * Wait — let's use the clean form: if demand is Q = a - bP, then
     * TR = P·Q = P(a - bP), MR = a - 2bP, set MR = MC → P* = (a - MC)/(2b)
     *
     * @param marginalCost MC (constant marginal cost)
     * @param demandIntercept a in Q = a - bP
     * @param demandSlope b in Q = a - bP (positive)
     * @return Profit-maximizing price P*
     */
    fun optimalPrice(
        marginalCost: Double,
        demandIntercept: Double,
        demandSlope: Double
    ): Double {
        require(demandSlope > 0) { "Demand slope must be positive" }
        return (demandIntercept - marginalCost) / (2.0 * demandSlope)
    }

    // ── Data Classes ───────────────────────────────────────────────────────

    data class FairPriceRange(
        val min: Double,
        val max: Double,
        val unit: String = "kg",
        val confidence: Double = 0.5
    )

    data class NegotiationRecord(
        val product: String,
        val quotedPrice: Double,
        val counterPrice: Double,
        val finalPrice: Double?,
        val quantity: Double,
        val unit: String,
        val market: String,
        val context: String,
        val outcome: String  // 'pending', 'accepted', 'rejected', 'partial', 'walked_away'
    )

    // ── Seed Data: Common Kenyan Market Prices (KES) ───────────────────────

    companion object {
        private val seedPrices = mapOf(
            "nyanya" to FairPriceRange(50.0, 80.0, "kg", 0.6),
            "sukuma wiki" to FairPriceRange(15.0, 25.0, "bunch", 0.6),
            "vitunguu" to FairPriceRange(40.0, 60.0, "kg", 0.5),
            "karoti" to FairPriceRange(40.0, 60.0, "kg", 0.5),
            "ndizi" to FairPriceRange(30.0, 50.0, "kg", 0.5),
            "machungwa" to FairPriceRange(10.0, 20.0, "piece", 0.5),
            "maembe" to FairPriceRange(20.0, 40.0, "piece", 0.5),
            "avocado" to FairPriceRange(20.0, 35.0, "piece", 0.5),
            "nyama" to FairPriceRange(350.0, 500.0, "kg", 0.5),
            "samaki" to FairPriceRange(200.0, 400.0, "kg", 0.5),
            "mchele" to FairPriceRange(120.0, 180.0, "kg", 0.5),
            "unga" to FairPriceRange(100.0, 150.0, "kg", 0.5),
            "sukari" to FairPriceRange(120.0, 160.0, "kg", 0.5),
            "mafuta" to FairPriceRange(200.0, 300.0, "litre", 0.5),
            "chai" to FairPriceRange(80.0, 120.0, "kg", 0.5),
            "pilau masala" to FairPriceRange(50.0, 80.0, "packet", 0.4),
            "hoho" to FairPriceRange(20.0, 40.0, "piece", 0.5),
            "spinachi" to FairPriceRange(15.0, 30.0, "bunch", 0.4),
            "kunde" to FairPriceRange(15.0, 25.0, "bunch", 0.4),
            "managu" to FairPriceRange(15.0, 25.0, "bunch", 0.4)
        )

        /** Negotiation tips for product (wholesale/retail) purchases. */
        private val productTips = listOf(
            "Daima uliza bei ya kwanza kabla ya kusema bei yako — fahamu soko kwanza.",
            "Anza na bei ya chini zaidi (30-40% chini ya bei uliyopewa) na uongeze pole pole.",
            "Sema \"Ninunue kwa bei gani ukinipatia kilo tano?\" — bei ya jumla ni ya chini zaidi.",
            "Ondoka ukisema \"Sawa, nitafuta mwingine\" — mara nyingi watakupigia simu.",
            "Nunua mapema asubuhi — wauzaji wanataka kuuza haraka kabla ya jua kali.",
            "Ukijenga uhusiano na muuzaji mmoja, utapata bei nzuri kila wiki.",
            "Linganisha bei katika soko 2-3 kabla ya kununua — usinunue sokoni la kwanza.",
            "Bei ya mwisho wa siku ni ya chini zaidi — saa kuuza ni saa nne jioni.",
            "Sema \"Nimepata bei ya [bei] kwa mwingine\" — hii inasukuma bei chini.",
            "Usinunue ukikimbilia — utalipa bei ya juu. Pumzika, ujadiliane."
        )

        /** Negotiation tips for wage negotiations (mjengo, casual labor). */
        private val wageTips = listOf(
            "Fahamu bei ya soko kabla ya kukubali kazi — uliza wafanyakazi wengine.",
            "Sema \"Kazi hii inastahili [bei] kwa sababu ya [sababu]\" — toa sababu za msingi.",
            "Ondoa bei ya juu kwa kusema \"Ninaweza kufanya kazi kwa [bei], lakini ni bora zaidi.\"",
            "Uliza maswali: \"Kazi hii itachukua siku ngapi?\" — bei ya siku ni tofauti na bei ya mradi.",
            "Jenga sifa yako — mfanyakazi mzuri anapata bei bora kila mara.",
            "Usikubali bei ya chini sana — bora kukata kazi kuliko kufanya kazi bure.",
            "Wakati wa mvua, bei ya mjengo inapanda — tumia wakati huu vizuri.",
            "Kubaliana bei kabla ya kuanza kazi, si baada — andika ikiwezekana."
        )

        /** Negotiation tips for fare negotiations (boda boda, tuk-tuk, mkokoteni). */
        private val fareTips = listOf(
            "Uliza bei kabla ya kupanda — usipande kwanza kisha ujadiliane.",
            "Fahamu umbali — kwa kila kilomita, bei ya boda boda ni KES 10-15.",
            "Sema \"Ninapenda kulipa [bei]\" badala ya \"Bei ni ngapi?\" — wewe ndiye unaanzisha.",
            "Ikiwa ni safari ya kila siku, jadiliana bei ya wiki — ni ya chini zaidi.",
            "Subiri dakika 2-3 — dereva mwingine atakuja na bei bora.",
            "Sema \"Nimepata bei ya [bei] kwa dereva mwingine\" — ushindani wa bei.",
            "Safari za asubuhi ni za bei ya juu — safari za mchana ni za bei ya chini.",
            "Ukijenga uhusiano na dereva mmoja, utapata bei nzuri kila siku."
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// SQLite Helper for PriceNegotiator persistence
// ────────────────────────────────────────────────────────────────────────────

/**
 * SQLite helper for persisting fair prices, negotiation history,
 * and wage/fare benchmarks.
 *
 * Schema matches the design doc's SQL definitions.
 */
class PriceNegotiatorDbHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "price_negotiator.db"
        private const val DB_VERSION = 1

        // ── fair_prices table ──
        const val TABLE_FAIR_PRICES = "fair_prices"
        const val COL_ID = "id"
        const val COL_PRODUCT_NAME = "product_name"
        const val COL_MARKET_NAME = "market_name"
        const val COL_COUNTY = "county"
        const val COL_WHOLESALE_MIN = "wholesale_price_min"
        const val COL_WHOLESALE_MAX = "wholesale_price_max"
        const val COL_RETAIL_MIN = "retail_price_min"
        const val COL_RETAIL_MAX = "retail_price_max"
        const val COL_UNIT = "unit"
        const val COL_DATE_RECORDED = "date_recorded"
        const val COL_SOURCE = "source"
        const val COL_CONFIDENCE = "confidence"
        const val COL_REPORT_COUNT = "report_count"
        const val COL_CREATED_AT = "created_at"
        const val COL_UPDATED_AT = "updated_at"

        // ── negotiations table ──
        const val TABLE_NEGOTIATIONS = "negotiations"
        const val COL_WORKER_ID = "worker_id"
        const val COL_QUOTED_PRICE = "quoted_price"
        const val COL_COUNTER_PRICE = "counter_price"
        const val COL_FINAL_PRICE = "final_price"
        const val COL_QUANTITY = "quantity"
        const val COL_CONTEXT = "context"
        const val COL_SUPPLIER_NAME = "supplier_name"
        const val COL_OUTCOME = "negotiation_outcome"
        const val COL_SAVINGS = "savings"

        // ── wage_fare_benchmarks table ──
        const val TABLE_WAGE_FARE = "wage_fare_benchmarks"
        const val COL_CATEGORY = "category"
        const val COL_ROUTE_OR_SITE = "route_or_site"
        const val COL_FAIR_RATE_MIN = "fair_rate_min"
        const val COL_FAIR_RATE_MAX = "fair_rate_max"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_FAIR_PRICES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PRODUCT_NAME TEXT NOT NULL,
                $COL_MARKET_NAME TEXT NOT NULL,
                $COL_COUNTY TEXT NOT NULL,
                $COL_WHOLESALE_MIN REAL,
                $COL_WHOLESALE_MAX REAL,
                $COL_RETAIL_MIN REAL,
                $COL_RETAIL_MAX REAL,
                $COL_UNIT TEXT DEFAULT 'kg',
                $COL_DATE_RECORDED TEXT NOT NULL,
                $COL_SOURCE TEXT,
                $COL_CONFIDENCE REAL DEFAULT 0.5,
                $COL_REPORT_COUNT INTEGER DEFAULT 1,
                $COL_CREATED_AT TEXT DEFAULT (datetime('now')),
                $COL_UPDATED_AT TEXT DEFAULT (datetime('now')),
                UNIQUE($COL_PRODUCT_NAME, $COL_MARKET_NAME, $COL_DATE_RECORDED, $COL_SOURCE)
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_fair_prices_product ON $TABLE_FAIR_PRICES($COL_PRODUCT_NAME, $COL_DATE_RECORDED)")
        db.execSQL("CREATE INDEX idx_fair_prices_market ON $TABLE_FAIR_PRICES($COL_MARKET_NAME, $COL_DATE_RECORDED)")

        db.execSQL("""
            CREATE TABLE $TABLE_NEGOTIATIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_WORKER_ID TEXT NOT NULL,
                $COL_PRODUCT_NAME TEXT NOT NULL,
                $COL_QUOTED_PRICE REAL NOT NULL,
                $COL_COUNTER_PRICE REAL,
                $COL_FINAL_PRICE REAL,
                $COL_QUANTITY REAL,
                $COL_UNIT TEXT DEFAULT 'kg',
                $COL_MARKET_NAME TEXT,
                $COL_SUPPLIER_NAME TEXT,
                $COL_OUTCOME TEXT,
                $COL_SAVINGS REAL,
                $COL_CONTEXT TEXT,
                $COL_CREATED_AT TEXT DEFAULT (datetime('now'))
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_negotiations_worker ON $TABLE_NEGOTIATIONS($COL_WORKER_ID, $COL_CREATED_AT)")
        db.execSQL("CREATE INDEX idx_negotiations_product ON $TABLE_NEGOTIATIONS($COL_PRODUCT_NAME, $COL_MARKET_NAME)")

        db.execSQL("""
            CREATE TABLE $TABLE_WAGE_FARE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_ROUTE_OR_SITE TEXT,
                $COL_FAIR_RATE_MIN REAL,
                $COL_FAIR_RATE_MAX REAL,
                $COL_UNIT TEXT,
                $COL_COUNTY TEXT,
                $COL_DATE_RECORDED TEXT NOT NULL,
                $COL_REPORT_COUNT INTEGER DEFAULT 1,
                $COL_CREATED_AT TEXT DEFAULT (datetime('now')),
                $COL_UPDATED_AT TEXT DEFAULT (datetime('now'))
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_wage_fare ON $TABLE_WAGE_FARE($COL_CATEGORY, $COL_COUNTY, $COL_DATE_RECORDED)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_WAGE_FARE")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NEGOTIATIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FAIR_PRICES")
        onCreate(db)
    }

    // ── Fair Price CRUD ────────────────────────────────────────────────────

    fun insertFairPrice(
        product: String, market: String, county: String,
        wholesaleMin: Double?, wholesaleMax: Double?,
        retailMin: Double?, retailMax: Double?,
        unit: String = "kg", source: String = "manual", confidence: Double = 0.5
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PRODUCT_NAME, product.lowercase())
            put(COL_MARKET_NAME, market.lowercase())
            put(COL_COUNTY, county.lowercase())
            put(COL_WHOLESALE_MIN, wholesaleMin)
            put(COL_WHOLESALE_MAX, wholesaleMax)
            put(COL_RETAIL_MIN, retailMin)
            put(COL_RETAIL_MAX, retailMax)
            put(COL_UNIT, unit)
            put(COL_DATE_RECORDED, java.time.LocalDate.now().toString())
            put(COL_SOURCE, source)
            put(COL_CONFIDENCE, confidence)
        }
        return db.insertWithOnConflict(TABLE_FAIR_PRICES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun queryFairPrice(product: String, market: String = "", context: String = "wholesale"): PriceNegotiator.FairPriceRange? {
        val db = readableDatabase
        val today = java.time.LocalDate.now().toString()
        val selection = if (market.isNotEmpty()) {
            "$COL_PRODUCT_NAME = ? AND $COL_MARKET_NAME = ? AND $COL_DATE_RECORDED >= ?"
        } else {
            "$COL_PRODUCT_NAME = ? AND $COL_DATE_RECORDED >= ?"
        }
        val selectionArgs = if (market.isNotEmpty()) {
            arrayOf(product.lowercase(), market.lowercase(), today)
        } else {
            arrayOf(product.lowercase(), today)
        }

        val cursor = db.query(
            TABLE_FAIR_PRICES, null, selection, selectionArgs,
            null, null, "$COL_CONFIDENCE DESC, $COL_REPORT_COUNT DESC", "1"
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val isWholesale = context == "wholesale"
                val min = it.getDouble(it.getColumnIndexOrThrow(if (isWholesale) COL_WHOLESALE_MIN else COL_RETAIL_MIN))
                val max = it.getDouble(it.getColumnIndexOrThrow(if (isWholesale) COL_WHOLESALE_MAX else COL_RETAIL_MAX))
                val unit = it.getString(it.getColumnIndexOrThrow(COL_UNIT)) ?: "kg"
                val conf = it.getDouble(it.getColumnIndexOrThrow(COL_CONFIDENCE))
                if (min > 0 && max > 0) {
                    PriceNegotiator.FairPriceRange(min, max, unit, conf)
                } else null
            } else null
        }
    }

    // ── Negotiation CRUD ───────────────────────────────────────────────────

    fun insertNegotiation(
        workerId: String, product: String, quotedPrice: Double,
        counterPrice: Double?, finalPrice: Double?,
        quantity: Double?, unit: String, market: String?,
        supplier: String?, outcome: String?, savings: Double?, context: String
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_WORKER_ID, workerId)
            put(COL_PRODUCT_NAME, product.lowercase())
            put(COL_QUOTED_PRICE, quotedPrice)
            put(COL_COUNTER_PRICE, counterPrice)
            put(COL_FINAL_PRICE, finalPrice)
            put(COL_QUANTITY, quantity)
            put(COL_UNIT, unit)
            put(COL_MARKET_NAME, market)
            put(COL_SUPPLIER_NAME, supplier)
            put(COL_OUTCOME, outcome)
            put(COL_SAVINGS, savings)
            put(COL_CONTEXT, context)
        }
        return db.insert(TABLE_NEGOTIATIONS, null, values)
    }

    fun queryNegotiationHistory(product: String? = null, workerId: String? = null, limit: Int = 20): List<Map<String, Any?>> {
        val db = readableDatabase
        val selection = mutableListOf<String>()
        val args = mutableListOf<String>()
        product?.let { selection.add("$COL_PRODUCT_NAME = ?"); args.add(it.lowercase()) }
        workerId?.let { selection.add("$COL_WORKER_ID = ?"); args.add(it) }

        val cursor = db.query(
            TABLE_NEGOTIATIONS, null,
            if (selection.isNotEmpty()) selection.joinToString(" AND ") else null,
            if (args.isNotEmpty()) args.toTypedArray() else null,
            null, null, "$COL_CREATED_AT DESC", limit.toString()
        )

        val results = mutableListOf<Map<String, Any?>>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    mapOf(
                        "product" to it.getString(it.getColumnIndexOrThrow(COL_PRODUCT_NAME)),
                        "quoted_price" to it.getDouble(it.getColumnIndexOrThrow(COL_QUOTED_PRICE)),
                        "counter_price" to it.getDouble(it.getColumnIndexOrThrow(COL_COUNTER_PRICE)),
                        "final_price" to if (!it.isNull(it.getColumnIndexOrThrow(COL_FINAL_PRICE)))
                            it.getDouble(it.getColumnIndexOrThrow(COL_FINAL_PRICE)) else null,
                        "quantity" to it.getDouble(it.getColumnIndexOrThrow(COL_QUANTITY)),
                        "unit" to it.getString(it.getColumnIndexOrThrow(COL_UNIT)),
                        "market" to it.getString(it.getColumnIndexOrThrow(COL_MARKET_NAME)),
                        "outcome" to it.getString(it.getColumnIndexOrThrow(COL_OUTCOME)),
                        "savings" to it.getDouble(it.getColumnIndexOrThrow(COL_SAVINGS)),
                        "context" to it.getString(it.getColumnIndexOrThrow(COL_CONTEXT)),
                        "created_at" to it.getString(it.getColumnIndexOrThrow(COL_CREATED_AT))
                    )
                )
            }
        }
        return results
    }

    // ── Wage/Fare Benchmarks ───────────────────────────────────────────────

    fun insertWageFareBenchmark(
        category: String, routeOrSite: String?, fairMin: Double, fairMax: Double,
        unit: String?, county: String?
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CATEGORY, category)
            put(COL_ROUTE_OR_SITE, routeOrSite)
            put(COL_FAIR_RATE_MIN, fairMin)
            put(COL_FAIR_RATE_MAX, fairMax)
            put(COL_UNIT, unit)
            put(COL_COUNTY, county)
            put(COL_DATE_RECORDED, java.time.LocalDate.now().toString())
        }
        return db.insert(TABLE_WAGE_FARE, null, values)
    }

    fun queryWageFareBenchmark(category: String, county: String? = null): PriceNegotiator.FairPriceRange? {
        val db = readableDatabase
        val today = java.time.LocalDate.now().toString()
        val selection = if (county != null) {
            "$COL_CATEGORY = ? AND $COL_COUNTY = ? AND $COL_DATE_RECORDED >= ?"
        } else {
            "$COL_CATEGORY = ? AND $COL_DATE_RECORDED >= ?"
        }
        val args = if (county != null) {
            arrayOf(category, county.lowercase(), today)
        } else {
            arrayOf(category, today)
        }

        val cursor = db.query(
            TABLE_WAGE_FARE, null, selection, args,
            null, null, "$COL_REPORT_COUNT DESC", "1"
        )

        return cursor.use {
            if (it.moveToFirst()) {
                val min = it.getDouble(it.getColumnIndexOrThrow(COL_FAIR_RATE_MIN))
                val max = it.getDouble(it.getColumnIndexOrThrow(COL_FAIR_RATE_MAX))
                val unit = it.getString(it.getColumnIndexOrThrow(COL_UNIT)) ?: "per_day"
                if (min > 0 && max > 0) PriceNegotiator.FairPriceRange(min, max, unit, 0.6)
                else null
            } else null
        }
    }
}
