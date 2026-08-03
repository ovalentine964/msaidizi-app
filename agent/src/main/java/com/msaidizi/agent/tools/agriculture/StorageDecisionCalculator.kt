package com.msaidizi.agent.tools.agriculture

import android.content.Context
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * StorageDecisionCalculator — Fix 3: Storage investment ROI calculator for farmers.
 *
 * Problem: Farmers don't know if investing in storage (hermetic bags, metal silos)
 * is worth the cost. They can't calculate ROI of storage vs. selling immediately.
 *
 * Solution: Calculate storage cost vs. spoilage cost vs. price premium.
 * "Hermetic bags cost KES 200 but save KES 5,000 in spoilage. ROI: 2,500%"
 *
 * Voice examples:
 *   "Je, mifuko ya hermetic inafaa?"           → Hermetic bag ROI
 *   "Gharama ya kuhifadhi dhidi ya kuuza"      → Storage vs sell cost comparison
 *   "Nunua mifuko au nihifadhi kawaida?"       → Which storage method is best?
 *   "Bei ya pipa la chuma ni ngapi?"           → Metal silo cost analysis
 */
@Singleton
class StorageDecisionCalculator @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "storage_decision_calculator"
    override val description = "Calculate storage investment ROI. Compares storage cost vs spoilage cost vs price premium. Recommends: store vs sell now."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "calculate",         // Full ROI calculation for storage investment
                "compare_methods",   // Compare all storage methods side by side
                "breakeven",         // How many bags to break even on storage investment
                "recommend",         // Personalized recommendation
                "storage_options"    // List available storage options with costs
            ),
            required = true
        )
        string("product", "Crop/product (mahindi, maharagwe, etc.)", required = false)
        number("quantity_kg", "Total harvest quantity in kg", required = false)
        number("current_price", "Current market price KES/kg", required = false)
        number("future_price", "Expected future price KES/kg (optional, auto-estimated)", required = false)
        string("storage_method", "Storage method: hermetic/silo/bags/open", required = false)
        integer("storage_months", "Planned storage duration in months", required = false)
        number("investment_cost", "Upfront storage investment in KES (e.g., cost of bags)", required = false)
        boolean("voice", "Format for voice output (Swahili)", required = false)
    }

    companion object {
        // Storage method specifications
        data class StorageMethod(
            val id: String,
            val nameSw: String,
            val nameEn: String,
            val costPer90kgBag: Double,     // KES per 90kg bag
            val spoilageRatePerMonth: Double, // % per month
            val durability: String,          // How long the method lasts
            val pros: List<String>,
            val cons: List<String>
        )

        val STORAGE_METHODS = mapOf(
            "hermetic" to StorageMethod(
                id = "hermetic",
                nameSw = "Mifuko ya hermetic (PICS)",
                nameEn = "Hermetic bags (PICS)",
                costPer90kgBag = 200.0,
                spoilageRatePerMonth = 1.0,
                durability = "Miezi 6-12 (inatumika mara 2-3)",
                pros = listOf(
                    "Inazuia wadudu na fangasi bila dawa",
                    "Hasara <2% kwa miezi 6",
                    "Rahisi kutumia — funga tu mfuko",
                    "Inaweza kutumika mara 2-3"
                ),
                cons = listOf(
                    "Inahitaji kufungwa vizuri",
                    "Haifai kwa mazao yenye unyevu mwingi",
                    "Inaweza kuchomwa na panya"
                )
            ),
            "silo" to StorageMethod(
                id = "silo",
                nameSw = "Pipa la chuma",
                nameEn = "Metal silo",
                costPer90kgBag = 1_500.0,  // amortized over 10+ years
                spoilageRatePerMonth = 0.5,
                durability = "Miaka 10+",
                pros = listOf(
                    "Hasara <1% kwa mwaka mzima",
                    "Inazuia wadudu 100%",
                    "Inadumu miaka mingi",
                    "Inaweza kufungwa na kuwekwa kwenye boma"
                ),
                cons = listOf(
                    "Gharama kubwa ya mwanzo (KES 5,000-15,000)",
                    "Inahitaji nafasi kubwa",
                    "Ni nzito — haihamishiki"
                )
            ),
            "bags" to StorageMethod(
                id = "bags",
                nameSw = "Mifuko ya kawaida (PP)",
                nameEn = "Regular polypropylene bags",
                costPer90kgBag = 50.0,
                spoilageRatePerMonth = 5.0,
                durability = "Mara 1-2",
                pros = listOf(
                    "Bei ndogo",
                    "Inapatikana kila mahali",
                    "Rahisi kubeba"
                ),
                cons = listOf(
                    "Hasara kubwa: 15-20% kwa miezi 3",
                    "Haizuii wadudu",
                    "Inahitaji dawa za ziada"
                )
            ),
            "open" to StorageMethod(
                id = "open",
                nameSw = "Kuhifadhi wazi (juu ya sakafu)",
                nameEn = "Open floor storage",
                costPer90kgBag = 0.0,
                spoilageRatePerMonth = 15.0,
                durability = "Mara 1 (haribika haraka)",
                pros = listOf(
                    "Hakuna gharama",
                    "Rahisi — weka tu mahali"
                ),
                cons = listOf(
                    "Hasara kubwa: 25-50% kwa miezi 3",
                    "Wadudu, panya, na mvua",
                    "Mazao yanaweza kuoza haraka"
                )
            )
        )

        // Product-specific spoilage adjustments
        val PRODUCT_SPOILAGE_MULTIPLIER = mapOf(
            "maize" to 1.0,
            "beans" to 0.7,
            "rice" to 0.5,
            "coffee" to 0.3,
            "tea" to 0.2,
            "tomatoes" to 5.0,   // highly perishable
            "potatoes" to 2.0,
            "kale" to 8.0        // very perishable
        )
    }

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "calculate" -> calculateROI(params)
            "compare_methods" -> compareMethods(params)
            "breakeven" -> breakeven(params)
            "recommend" -> recommend(params)
            "storage_options" -> storageOptions(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: calculate — Full ROI calculation
    // ──────────────────────────────────────────────

    private fun calculateROI(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantityKg = params["quantity_kg"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "quantity_kg required", "MISSING_QUANTITY")
        val currentPrice = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required (KES/kg)", "MISSING_PRICE")
        val storageMethod = params["storage_method"] ?: "hermetic"
        val months = params["storage_months"]?.toIntOrNull() ?: 3

        val method = STORAGE_METHODS[storageMethod]
            ?: return ToolResult.error(name, "Unknown storage method: $storageMethod", "INVALID_METHOD")

        val productMultiplier = PRODUCT_SPOILAGE_MULTIPLIER[product] ?: 1.0
        val adjustedSpoilageRate = method.spoilageRatePerMonth * productMultiplier

        // ── Scenario 1: Sell now (no storage) ──
        val sellNowValue = quantityKg * currentPrice

        // ── Scenario 2: Store ──
        // Future price (use seasonal multipliers from HarvestTimingOptimizer)
        val futurePrice = params["future_price"]?.toDoubleOrNull() ?: estimateFuturePrice(product, currentPrice, months)

        // Storage investment cost
        val bagsNeeded = Math.ceil(quantityKg / 90.0).toInt()
        val investmentCost = params["investment_cost"]?.toDoubleOrNull()
            ?: (method.costPer90kgBag * bagsNeeded)

        // Spoilage loss
        val spoilagePct = adjustedSpoilageRate * months
        val spoilageLossKg = quantityKg * (spoilagePct / 100.0)
        val netQuantity = quantityKg - spoilageLossKg

        // Revenue after storage
        val storeRevenue = netQuantity * futurePrice
        val storeNetRevenue = storeRevenue - investmentCost

        // ROI calculation
        val netGain = storeNetRevenue - sellNowValue
        val roi = if (investmentCost > 0) (netGain / investmentCost * 100) else 0.0

        // Spoilage cost (what you'd lose without proper storage)
        val spoilageCostWithoutStorage = quantityKg * (15.0 * productMultiplier / 100.0) * months * currentPrice
        val spoilageCostWithStorage = spoilageLossKg * futurePrice
        val savingsFromStorage = spoilageCostWithoutStorage - spoilageCostWithStorage

        val message = if (voice) {
            buildString {
                appendLine("📊 *Uchunguzi wa Uwekezaji wa Kuhifadhi*")
                appendLine("🌽 $product: ${formatQty(quantityKg)} kg")
                appendLine("📦 Mbinu: ${method.nameSw}")
                appendLine("📅 Muda: miezi $months")
                appendLine()
                appendLine("💰 *Uchambuzi wa Gharama:*")
                appendLine("   Bei ya sasa: KES ${formatPrice(currentPrice)}/kg")
                appendLine("   Bei ya baadaye: KES ${formatPrice(futurePrice)}/kg")
                appendLine("   Gharama ya mifuko: KES ${formatPrice(investmentCost)} ($bagsNeeded mifuko)")
                appendLine("   Kuoza: ${spoilagePct.toInt()}% (${formatQty(spoilageLossKg)} kg)")
                appendLine()
                appendLine("📈 *Matokeo:*")
                appendLine("   Kuuza sasa: KES ${formatPrice(sellNowValue)}")
                appendLine("   Kuhifadhi: KES ${formatPrice(storeNetRevenue)}")
                appendLine("   Faida ya ziada: KES ${formatPrice(netGain)}")
                appendLine()
                appendLine("🎯 *ROI: ${roi.toInt()}%*")
                appendLine()

                if (roi > 100) {
                    appendLine("🏆 *Uwekezaji mzuri sana!*")
                    appendLine("   Kila KES 1 unayowekeza kwenye kuhifadhi inakupa KES ${formatPrice(roi / 100 + 1)} ya faida.")
                    appendLine("   Gharama ya mifuko (KES ${formatPrice(investmentCost)}) inalipwa na faida ya KES ${formatPrice(netGain)}.")
                } else if (roi > 0) {
                    appendLine("✅ *Uwekezaji mzuri.*")
                    appendLine("   Kuhifadhi kunakupa faida ya KES ${formatPrice(netGain)} zaidi ya kuuza sasa.")
                } else {
                    appendLine("⚠️ *Kuhifadhi si faida kwa hali yako.*")
                    appendLine("   Gharama ya kuhifadhi ni kubwa kuliko faida ya bei ya baadaye.")
                    appendLine("   Uza sasa ni bora zaidi.")
                }

                appendLine()
                appendLine("💡 *Ukilinganisha na kuhifadhi wazi:*")
                appendLine("   Kuhifadhi wazi: hasara ~${(15.0 * productMultiplier * months).toInt()}%")
                appendLine("   ${method.nameSw}: hasara ~${spoilagePct.toInt()}%")
                appendLine("   Okoa: KES ${formatPrice(savingsFromStorage)} kwa kupunguza kuoza")
            }
        } else {
            buildString {
                appendLine("Storage Investment ROI — $product:")
                appendLine("Method: ${method.nameEn}")
                appendLine("Investment: KES ${formatPrice(investmentCost)} ($bagsNeeded bags)")
                appendLine("Sell now: KES ${formatPrice(sellNowValue)}")
                appendLine("Store then sell: KES ${formatPrice(storeNetRevenue)}")
                appendLine("Net gain: KES ${formatPrice(netGain)}")
                appendLine("ROI: ${roi.toInt()}%")
                appendLine("Spoilage savings vs open: KES ${formatPrice(savingsFromStorage)}")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "method" to storageMethod,
                "quantity_kg" to quantityKg, "investment_cost" to investmentCost,
                "sell_now_value" to sellNowValue, "store_net_revenue" to storeNetRevenue,
                "net_gain" to netGain, "roi_pct" to roi,
                "spoilage_pct" to spoilagePct, "spoilage_loss_kg" to spoilageLossKg,
                "savings_vs_open" to savingsFromStorage,
                "bags_needed" to bagsNeeded
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_methods — Compare all storage methods
    // ──────────────────────────────────────────────

    private fun compareMethods(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantityKg = params["quantity_kg"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "quantity_kg required", "MISSING_QUANTITY")
        val currentPrice = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required", "MISSING_PRICE")
        val months = params["storage_months"]?.toIntOrNull() ?: 3

        val productMultiplier = PRODUCT_SPOILAGE_MULTIPLIER[product] ?: 1.0
        val futurePrice = estimateFuturePrice(product, currentPrice, months)
        val sellNowValue = quantityKg * currentPrice

        val results = STORAGE_METHODS.values.map { method ->
            val bagsNeeded = Math.ceil(quantityKg / 90.0).toInt()
            val investmentCost = method.costPer90kgBag * bagsNeeded
            val spoilagePct = method.spoilageRatePerMonth * productMultiplier * months
            val spoilageLossKg = quantityKg * (spoilagePct / 100.0)
            val netQuantity = quantityKg - spoilageLossKg
            val revenue = netQuantity * futurePrice - investmentCost
            val netGain = revenue - sellNowValue
            val roi = if (investmentCost > 0) (netGain / investmentCost * 100) else 0.0

            StorageResult(
                method = method,
                investmentCost = investmentCost,
                spoilagePct = spoilagePct,
                spoilageLossKg = spoilageLossKg,
                revenue = revenue,
                netGain = netGain,
                roi = roi,
                bagsNeeded = bagsNeeded
            )
        }.sortedByDescending { it.revenue }

        val best = results.first()
        val message = if (voice) {
            buildString {
                appendLine("📊 *Linganisha Mbinu za Kuhifadhi — $product*")
                appendLine("Kiasi: ${formatQty(quantityKg)} kg | Muda: miezi $months")
                appendLine("Bei ya sasa: KES ${formatPrice(currentPrice)}/kg")
                appendLine("Bei ya baadaye: KES ${formatPrice(futurePrice)}/kg")
                appendLine()
                appendLine("Kuuza sasa: KES ${formatPrice(sellNowValue)}")
                appendLine()
                results.forEachIndexed { i, r ->
                    val emoji = if (i == 0) "🏆" else "  "
                    appendLine("$emoji ${r.method.nameSw}:")
                    appendLine("   Gharama: KES ${formatPrice(r.investmentCost)} | Kuoza: ${r.spoilagePct.toInt()}%")
                    appendLine("   Mapato: KES ${formatPrice(r.revenue)} | ROI: ${r.roi.toInt()}%")
                    if (r.netGain > 0) {
                        appendLine("   ✅ Faida: +KES ${formatPrice(r.netGain)}")
                    } else {
                        appendLine("   ❌ Hasara: KES ${formatPrice(r.netGain)}")
                    }
                    appendLine()
                }
                appendLine("🏆 *Bora: ${best.method.nameSw}*")
                appendLine("   Mapato: KES ${formatPrice(best.revenue)}")
                appendLine("   Faida ya ziada: KES ${formatPrice(best.netGain)}")
            }
        } else {
            buildString {
                appendLine("Storage methods comparison — $product (${formatQty(quantityKg)} kg, $months months):")
                appendLine("Sell now: KES ${formatPrice(sellNowValue)}")
                results.forEach { r ->
                    appendLine("${r.method.nameEn}: KES ${formatPrice(r.revenue)} (${r.spoilagePct.toInt()}% spoilage, ROI ${r.roi.toInt()}%)")
                }
                appendLine("Best: ${best.method.nameEn}")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "sell_now_value" to sellNowValue,
                "methods" to results.map { mapOf(
                    "method" to it.method.id, "investment" to it.investmentCost,
                    "spoilage_pct" to it.spoilagePct, "revenue" to it.revenue,
                    "net_gain" to it.netGain, "roi" to it.roi
                )},
                "best_method" to best.method.id
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: breakeven — How many bags to break even?
    // ──────────────────────────────────────────────

    private fun breakeven(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val currentPrice = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required", "MISSING_PRICE")
        val storageMethod = params["storage_method"] ?: "hermetic"
        val months = params["storage_months"]?.toIntOrNull() ?: 3

        val method = STORAGE_METHODS[storageMethod]
            ?: return ToolResult.error(name, "Unknown method", "INVALID_METHOD")

        val productMultiplier = PRODUCT_SPOILAGE_MULTIPLIER[product] ?: 1.0
        val futurePrice = estimateFuturePrice(product, currentPrice, months)
        val spoilageRate = method.spoilageRatePerMonth * productMultiplier / 100.0

        // Break even: investment = (spoilage_savings + price_premium) per kg × quantity
        // investment = (open_spoilage_cost - method_spoilage_cost) per kg × quantity + (future_price - current_price) × quantity
        val openSpoilageRate = 15.0 * productMultiplier / 100.0
        val spoilageSavingsPerKg = (openSpoilageRate - spoilageRate) * months * futurePrice
        val pricePremiumPerKg = futurePrice - currentPrice
        val benefitPerKg = spoilageSavingsPerKg + pricePremiumPerKg

        // For bags: breakeven = investment_per_bag / benefit_per_kg × 90kg
        val investmentPerBag = method.costPer90kgBag
        val breakevenKg = if (benefitPerKg > 0) investmentPerBag / benefitPerKg * 90.0 else Double.MAX_VALUE
        val breakevenBags = Math.ceil(breakevenKg / 90.0).toInt()

        val message = if (voice) {
            buildString {
                appendLine("⚖️ *Muda wa Kurejesha Uwekezaji — ${method.nameSw}*")
                appendLine("🌽 $product | Muda: miezi $months")
                appendLine()
                appendLine("Gharama ya mfuko 1: KES ${formatPrice(investmentPerBag)}")
                appendLine("Faida kwa kg: KES ${formatPrice(benefitPerKg)}")
                appendLine()
                if (breakevenKg < Double.MAX_VALUE) {
                    appendLine("📏 *Kiasi cha kurejesha uwekezaji:*")
                    appendLine("   Gunia $breakevenBags (${formatQty(breakevenKg)} kg)")
                    appendLine()
                    appendLine("   Ukiwa na gunia ${breakevenBags} au zaidi,")
                    appendLine("   uwekezaji wa ${method.nameSw} unalipia nafsi yake.")
                } else {
                    appendLine("⚠️ Kwa bei ya sasa, ${method.nameSw} hairejeshi uwekezaji.")
                    appendLine("   Fikiria kuuza sasa au tumia mbinu rahisi zaidi.")
                }
            }
        } else {
            buildString {
                appendLine("Breakeven — ${method.nameEn}:")
                appendLine("Investment per bag: KES ${formatPrice(investmentPerBag)}")
                appendLine("Benefit per kg: KES ${formatPrice(benefitPerKg)}")
                appendLine("Breakeven: $breakevenBags bags (${formatQty(breakevenKg)} kg)")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "method" to storageMethod, "investment_per_bag" to investmentPerBag,
                "benefit_per_kg" to benefitPerKg, "breakeven_kg" to breakevenKg,
                "breakeven_bags" to breakevenBags
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: recommend — Personalized recommendation
    // ──────────────────────────────────────────────

    private fun recommend(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantityKg = params["quantity_kg"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "quantity_kg required", "MISSING_QUANTITY")
        val currentPrice = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required", "MISSING_PRICE")
        val months = params["storage_months"]?.toIntOrNull() ?: 3

        val productMultiplier = PRODUCT_SPOILAGE_MULTIPLIER[product] ?: 1.0
        val futurePrice = estimateFuturePrice(product, currentPrice, months)
        val sellNowValue = quantityKg * currentPrice

        // Find best method
        val bestMethod = STORAGE_METHODS.values.maxByOrNull { method ->
            val bagsNeeded = Math.ceil(quantityKg / 90.0).toInt()
            val cost = method.costPer90kgBag * bagsNeeded
            val spoilage = method.spoilageRatePerMonth * productMultiplier * months
            val netQty = quantityKg * (1.0 - spoilage / 100.0)
            netQty * futurePrice - cost
        }!!

        val bagsNeeded = Math.ceil(quantityKg / 90.0).toInt()
        val investmentCost = bestMethod.costPer90kgBag * bagsNeeded
        val spoilagePct = bestMethod.spoilageRatePerMonth * productMultiplier * months
        val netQuantity = quantityKg * (1.0 - spoilagePct / 100.0)
        val storeRevenue = netQuantity * futurePrice - investmentCost
        val netGain = storeRevenue - sellNowValue

        val priceChange = ((futurePrice - currentPrice) / currentPrice * 100).toInt()

        val message = if (voice) {
            buildString {
                appendLine("💡 *Ushauri wa Kuhifadhi — $product*")
                appendLine("Kiasi: ${formatQty(quantityKg)} kg")
                appendLine()
                if (netGain > 0) {
                    appendLine("✅ *Hifadhi kwa ${bestMethod.nameSw}!*")
                    appendLine()
                    appendLine("📊 Sababu:")
                    appendLine("   • Bei itapanda ~$priceChange% ndani ya miezi $months")
                    appendLine("   • Kuoza ni ${spoilagePct.toInt()}% tu na ${bestMethod.nameSw}")
                    appendLine("   • Gharama ya uwekezaji: KES ${formatPrice(investmentCost)}")
                    appendLine("   • Faida ya ziada: KES ${formatPrice(netGain)}")
                    appendLine()
                    appendLine("📦 *Hatua:*")
                    appendLine("   1. Nunua mifuko ${bagsNeeded} ya ${bestMethod.nameSw}")
                    appendLine("   2. Kausha mazao kabisa (unyevu <13%)")
                    appendLine("   3. Funga mifuko vizuri")
                    appendLine("   4. Hifadhi mahali kavu na baridi")
                    appendLine("   5. Uza miezi $months kutoka sasa")
                } else {
                    appendLine("💡 *Uza sasa.*")
                    appendLine()
                    appendLine("Sababu:")
                    if (priceChange < 5) {
                        appendLine("   • Bei haijaongezeka sana (+$priceChange%)")
                    }
                    appendLine("   • Gharama ya kuhifadhi ni kubwa kuliko faida")
                    appendLine("   • Hasara ya kuoza inapunguza faida")
                    appendLine()
                    appendLine("Tumia pesa za mauzo kwa mahitaji ya sasa.")
                }
            }
        } else {
            buildString {
                appendLine("Recommendation — $product:")
                if (netGain > 0) {
                    appendLine("STORE with ${bestMethod.nameEn}")
                    appendLine("Investment: KES ${formatPrice(investmentCost)}")
                    appendLine("Expected gain: KES ${formatPrice(netGain)}")
                } else {
                    appendLine("SELL NOW — storage not profitable")
                    appendLine("Price change too low or spoilage too high")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "recommendation" to if (netGain > 0) "store" else "sell_now",
                "best_method" to bestMethod.id,
                "investment_cost" to investmentCost,
                "net_gain" to netGain,
                "store_revenue" to storeRevenue,
                "sell_now_value" to sellNowValue,
                "bags_needed" to bagsNeeded
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: storage_options — List available options
    // ──────────────────────────────────────────────

    private fun storageOptions(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val message = if (voice) {
            buildString {
                appendLine("📦 *Chaguo za Kuhifadhi Mazao*")
                appendLine()
                STORAGE_METHODS.values.forEach { method ->
                    appendLine("🔹 ${method.nameSw}:")
                    appendLine("   Bei: KES ${formatPrice(method.costPer90kgBag)} kwa gunia (90kg)")
                    appendLine("   Kuoza: ${method.spoilageRatePerMonth.toInt()}%/mwezi")
                    appendLine("   Inadumu: ${method.durability}")
                    appendLine("   Faida: ${method.pros.first()}")
                    appendLine()
                }
                appendLine("💡 *Ushauri:* Mifuko ya hermetic ni bora kwa wakulima wadogo.")
                appendLine("   Bei ndogo (KES 200) + hasara ndogo (<2%) = faida kubwa!")
            }
        } else {
            buildString {
                appendLine("Available storage options:")
                STORAGE_METHODS.values.forEach { method ->
                    appendLine("${method.nameEn}: KES ${formatPrice(method.costPer90kgBag)}/bag, ${method.spoilageRatePerMonth}% spoilage/month")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf("methods" to STORAGE_METHODS.values.map { mapOf(
                "id" to it.id, "name" to it.nameEn, "cost_per_bag" to it.costPer90kgBag,
                "spoilage_rate" to it.spoilageRatePerMonth
            )}),
            message
        )
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun estimateFuturePrice(product: String, currentPrice: Double, monthsAhead: Int): Double {
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val multipliers = HarvestTimingOptimizer.SEASONAL_PRICE_MULTIPLIERS[product]
            ?: HarvestTimingOptimizer.SEASONAL_PRICE_MULTIPLIERS["maize"]!!
        val currentMult = multipliers[currentMonth] ?: 1.0
        val futureMonth = ((currentMonth - 1 + monthsAhead) % 12) + 1
        val futureMult = multipliers[futureMonth] ?: 1.0
        val avgPrice = currentPrice / currentMult
        return avgPrice * futureMult
    }

    private fun normalizeProduct(raw: String): String {
        val aliases = mapOf(
            "mahindi" to "maize", "maize" to "maize",
            "maharagwe" to "beans", "beans" to "beans",
            "nyanya" to "tomatoes", "tomatoes" to "tomatoes",
            "mchele" to "rice", "rice" to "rice",
            "kahawa" to "coffee", "coffee" to "coffee",
            "chai" to "tea", "tea" to "tea"
        )
        return aliases[raw.trim().lowercase()] ?: raw.trim().lowercase()
    }

    private fun formatKES(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) "%,d".format(amount.toLong()) else "%,.0f".format(amount)
    }

    private fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) "%,d".format(price.toLong()) else "%,.0f".format(price)
    }

    private fun formatQty(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) "%,d".format(qty.toLong()) else "%,.1f".format(qty)
    }

    data class StorageResult(
        val method: StorageMethod,
        val investmentCost: Double,
        val spoilagePct: Double,
        val spoilageLossKg: Double,
        val revenue: Double,
        val netGain: Double,
        val roi: Double,
        val bagsNeeded: Int
    )
}
