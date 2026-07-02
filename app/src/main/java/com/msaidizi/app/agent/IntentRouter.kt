package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import timber.log.Timber

/**
 * Intent Router — classifies user input into intents.
 * This is CODE, not LLM — 0 RAM overhead, instant execution.
 *
 * Uses precompiled regex patterns for Swahili business commands.
 * Handles 90%+ of user input without needing the LLM.
 */
class IntentRouter {

    // === SALE PATTERNS ===
    private val salePatterns = listOf(
        // "Nimeuza mandazi kumi kwa Sh 500"
        Regex("""(?i)(nime?uza|niliuza|nikauza|sold|nauza|nimeuzia)\s+(.+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Mandazi kumi kwa 500"
        Regex("""(?i)(mandazi|maize|unga|sukari|chai|maziwa|mkate|nyanya|viazi)\s+(\d+)\s*(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "10 mandazi kwa 500"
        Regex("""(?i)(\d+)\s+(mandazi|maize|unga|sukari|chai|maziwa|mkate)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Nimeuza nyanya" (without explicit price)
        Regex("""(?i)(nime?uza|sold|nauza)\s+(.+?)(?:\s+(?:kwa|sh|ksh)\s*(\d+(?:\.\d+)?))?"""),
        // Simple: "Sale mandazi 500"
        Regex("""(?i)(sale|sold|uza)\s+(.+?)\s+(\d+(?:\.\d+)?)""")
    )

    // === PURCHASE PATTERNS ===
    private val purchasePatterns = listOf(
        // "Nimenunua unga kwa Sh 200"
        Regex("""(?i)(nimenunua|nilinunua|nimenunulia|nimenunua|bought|nimelog)\s+(.+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // "Nimenunua unga mbili kwa 200"
        Regex("""(?i)(nimenunua|bought)\s+(.+?)\s+(\w+)\s+(kwa|sh|ksh)\s*(\d+(?:\.\d+)?)"""),
        // Simple: "Buy unga 200"
        Regex("""(?i)(buy|purchase|nunua)\s+(.+?)\s+(\d+(?:\.\d+)?)""")
    )

    // === EXPENSE PATTERNS ===
    private val expensePatterns = listOf(
        // "Nimetumia Sh 100 kwa usafiri"
        Regex("""(?i)(nimetumia|nimelipa|nimetoa|spent|paid)\s+(?:sh|ksh|kwa)?\s*(\d+(?:\.\d+)?)\s+(?:kwa|for)\s+(.+)"""),
        // "Usafiri 100"
        Regex("""(?i)(usafiri|rent|kodi|stima|umeme|majani|data|bundle)\s+(\d+(?:\.\d+)?)"""),
        // "Fuel 300" / "Petrol 500"
        Regex("""(?i)(fuel|petrol|diesel|mafuta\s*ya\s*pikipiki)\s+(\d+(?:\.\d+)?)"""),
        // "Nimetumia 200 kwa mafuta"
        Regex("""(?i)(nimetumia|nimelipa)\s+(\d+(?:\.\d+)?)\s+(kwa|for)\s+(mafuta|fuel|petrol|diesel)"""),
        // "Fertilizer 1500" / "Mbolea 800"
        Regex("""(?i)(fertilizer|mbolea|dawa|pesticide|mbegu|seeds)\s+(\d+(?:\.\d+)?)"""),
        // "Nimenunua mbolea kwa 1500"
        Regex("""(?i)(nimenunua|nimelipa)\s+(mbolea|fertilizer|dawa|mbegu|seeds)\s+(kwa|sh|ksh)?\s*(\d+(?:\.\d+)?)"""),
        // "Float 10000" / "Nimeweka float 5000"
        Regex("""(?i)(float|nimeweka\s*float)\s+(\d+(?:\.\d+)?)"""),
        // "SACCO 200" / "Contribution 500"
        Regex("""(?i)(sacco|contribution|mgao|chip\s*in)\s+(\d+(?:\.\d+)?)"""),
        // "Airtime 100" / "Data bundle 250"
        Regex("""(?i)(airtime|data\s*bundle|bundles?|internet)\s+(\d+(?:\.\d+)?)"""),
        // "Spare parts 2000" / "Parts 1500"
        Regex("""(?i)(spare\s*parts?|parts?|vifaa)\s+(\d+(?:\.\d+)?)"""),
        // "Repair 500" / "Service 800"
        Regex("""(?i)(repair|service|matengenezo)\s+(\d+(?:\.\d+)?)"""),
        // "Advert 500" / "Advertisement 1000" / "Boost 200"
        Regex("""(?i)(advert|advertisement|boost|matangazo|tangazo)\s+(\d+(?:\.\d+)?)"""),
        // "Delivery 200" / "Transport 300"
        Regex("""(?i)(delivery|transport|usafirishaji|kubeba)\s+(\d+(?:\.\d+)?)""")
    )

    // === TRANSPORT-SPECIFIC PATTERNS ===
    private val tripPatterns = listOf(
        // "Nimefanya trip 5" / "Trip 10 leo"
        Regex("""(?i)(nimefanya|nimepiga|nimemaliza)\s+(trip|safari|race|gari)\s+(\d+)"""),
        Regex("""(?i)(trip|safari|race)\s+(\d+)"""),
        // "Nimebeba abiria 20"
        Regex("""(?i)(nimebeba|nimeshusha|nimeweka)\s+(abiria|passenger|wateja)\s+(\d+)"""),
        // "Route: Nairobi - Thika"
        Regex("""(?i)(route|njia|safari)\s*:?\s*(.+?)\s*(-|to|→|hadhi)\s*(.+)"""),
        // "Nimepata 1500 kutoka CBD"
        Regex("""(?i)(nimepata|nimechukua|nimelipwa)\s+(\d+(?:\.\d+)?)\s+(kutoka|from)\s+(.+)"""),
        // "Passenger 300"
        Regex("""(?i)(passenger|abiria|mpanda)\s+(\d+(?:\.\d+)?)""")
    )

    // === FARMING-SPECIFIC PATTERNS ===
    private val farmingPatterns = listOf(
        // "Nimevuna mahindi 50kg"
        Regex("""(?i)(nimevuna|nimekuna|nimekata|nimetafuna)\s+(.+)\s+(\d+)\s*(kg|kilo|gunia|mifuko)?"""),
        // "Nimepanda mahindi ekari 2"
        Regex("""(?i)(nimepanda|nimetega|nimeotesha)\s+(.+)\s+(ekari|acre|hectare)\s*(\d+)?"""),
        // "Harvest 200kg"
        Regex("""(?i)(harvest|vunja|kuvuna)\s+(\d+)\s*(kg|kilo)?"""),
        // "Nimenunua mbegu kwa 800"
        Regex("""(?i)(nimenunua|nimelog)\s+(mbegu|seeds|mbolea|fertilizer|dawa)\s+(kwa|sh|ksh)?\s*(\d+(?:\.\d+)?)"""),
        // "Nimepanda mahindi" (without quantity)
        Regex("""(?i)(nimepanda|nimetega|nimeotesha)\s+(.+?)(?:\s+(ekari|acre))?"""),
        // "Crop: maize" / "Mazao: mahindi"
        Regex("""(?i)(crop|mazao|mmea)\s*:?\s*(.+)""")
    )

    // === DIGITAL/GIG-SPECIFIC PATTERNS ===
    private val digitalPatterns = listOf(
        // "Nimefanya transaction 50"
        Regex("""(?i)(nimefanya|nimemaliza)\s+(transaction|tx|shughuli)\s+(\d+)"""),
        // "Commission 1200"
        Regex("""(?i)(commission|komisheni|fee|charge)\s+(\d+(?:\.\d+)?)"""),
        // "Nimepata commission 500"
        Regex("""(?i)(nimepata|nimelipwa)\s+(commission|komisheni)\s+(\d+(?:\.\d+)?)"""),
        // "Deposits 20000" / "Withdrawals 15000"
        Regex("""(?i)(deposit|withdrawal|send|receive|withdraw)\s+(\d+(?:\.\d+)?)"""),
        // "Float balance 50000"
        Regex("""(?i)(float\s*balance|salio\s*la\s*float)\s+(\d+(?:\.\d+)?)"""),
        // "Nimeuza airtime 200"
        Regex("""(?i)(nimeuza|nimetuma)\s+(airtime|bundle|data)\s+(\d+(?:\.\d+)?)?"""),
        // "Nimepost TikTok" / "Nimepost Instagram"
        Regex("""(?i)(nimepost|nimeweka|nimeshare)\s+(tiktok|instagram|facebook|whatsapp)"""),
        // "Orders 10" / "Nimepata order 5"
        Regex("""(?i)(order|oda)\s+(\d+)"""),
        // "Ad spend 500"
        Regex("""(?i)(ad\s*spend|matangazo|boost|promotion)\s+(\d+(?:\.\d+)?)""")
    )

    // === SERVICE-SPECIFIC PATTERNS ===
    private val servicePatterns = listOf(
        // "Nimenyolewa mteja 5" / "Clients 8"
        Regex("""(?i)(nimenyolewa|nimeshonwa|nimeshonewa|nimefanyiwa)\s+(mteja|client|wateja)\s+(\d+)"""),
        Regex("""(?i)(client|mteja|wateja)\s+(\d+)"""),
        // "Nimefundi gari 3"
        Regex("""(?i)(nimefundi|nimefanyia|nimeshonewa)\s+(gari|simu|phone|nguo|nywele)\s+(\d+)"""),
        // "Service ya gari 2000"
        Regex("""(?i)(service\s*ya|kazi\s*ya)\s+(gari|simu|phone|nywele|nguo)\s+(\d+(?:\.\d+)?)"""),
        // "Nimepata 1500 kutoka kwa mteja"
        Regex("""(?i)(nimepata|nimelipwa)\s+(\d+(?:\.\d+)?)\s+(kutoka|from)\s+(kwa\s+mteja|client)""")
    )

    // === QUERY PATTERNS ===
    private val balancePatterns = listOf(
        Regex("""(?i)(salio|balance|pesa|how much|ngapi|nina|niko)"""),
        Regex("""(?i)(nina\s+pesa|pesa\s+yangu|how\s+much\s+do\s+i\s+have)""")
    )

    private val profitPatterns = listOf(
        Regex("""(?i)(faida|profit|loss|hasara|margin|mapato)"""),
        Regex("""(?i)(how\s+much\s+(did\s+i|have\s+i)\s+(made|earned|profit))""")
    )

    private val stockPatterns = listOf(
        Regex("""(?i)(stock|inventory|baki|remaining|imebaki|bado|gani)"""),
        Regex("""(?i)(how\s+much\s+(stock|left|remaining))""")
    )

    // Transport-specific queries
    private val tripQueryPatterns = listOf(
        Regex("""(?i)(trips?|safari|gari|race)\s+(leo|today|jana|yesterday)"""),
        Regex("""(?i)(nimefanya\s+trips?|how\s+many\s+trips?)"""),
        Regex("""(?i)(fare|nauli|bei\s*ya\s*usafiri)"""),
        Regex("""(?i)(fuel\s*cost|gharama\s*ya\s*mafuta)"""),
        Regex("""(?i)(earnings\s*per\s*hour|mapato\s*ya\s*saa)""")
    )

    // Farming-specific queries
    private val harvestQueryPatterns = listOf(
        Regex("""(?i)(harvest|vuna|vunja|mazao)"""),
        Regex("""(?i)(crop|mazao|mmea|shamba)"""),
        Regex("""(?i)(planting\s*season|wakati\s*wa\s*kupanda)"""),
        Regex("""(?i)(yields|mavuno|production)""")
    )

    // Digital/gig queries
    private val digitalQueryPatterns = listOf(
        Regex("""(?i)(commission|komisheni)\s+(leo|today|wiki|week)"""),
        Regex("""(?i)(transactions?|shughuli)\s+(leo|today|jumla)"""),
        Regex("""(?i)(float|salio)"""),
        Regex("""(?i)(ads?\s*spend|matangazo|ROI|return)""")
    )

    // === ADVICE PATTERNS ===
    private val advicePatterns = listOf(
        Regex("""(?i)(nishauri|advise|nifanye|what\s+should|saidia|msaidizi|help|usaidizi)"""),
        Regex("""(?i)(nifanye\s+nini|what\s+can\s+i|how\s+can\s+i)""")
    )

    // === SUMMARY PATTERNS ===
    private val dailySummaryPatterns = listOf(
        Regex("""(?i)(report|summary|jumla|jumlisho)\s+(ya\s+)?leo"""),
        Regex("""(?i)(today|leo)\s+(report|summary|sales|mauzo)"""),
        Regex("""(?i)(daily\s+report|report\s+ya\s+leo)""")
    )

    private val weeklySummaryPatterns = listOf(
        Regex("""(?i)(report|summary)\s+(ya\s+)?wiki"""),
        Regex("""(?i)(weekly|wiki)\s+(report|summary)""")
    )

    // === HELP / GREETING PATTERNS ===
    private val helpPatterns = listOf(
        Regex("""(?i)(help|saidia|msaidizi|nini|unaweza|what\s+can)"""),
        Regex("""(?i)(jinsi|how\s+to|how\s+do)""")
    )

    private val greetingPatterns = listOf(
        Regex("""(?i)^(habari|hi|hello|hey|sasa|niaje|vipi|mambo|salama)"""),
        Regex("""(?i)^(good\s+(morning|afternoon|evening)|siku\s+njema)""")
    )

    // === CORRECTION PATTERNS ===
    private val correctionPatterns = listOf(
        Regex("""(?i)(hapana|siyo|si|wrong|incorrect|badilisha|rekebisha|change|correct)"""),
        Regex("""(?i)(I\s+meant|alisema|kusema)""")
    )

    /**
     * Classify the intent of user input.
     * Returns IntentResult with type, confidence, and extracted data.
     */
    fun classify(text: String): IntentResult {
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            return IntentResult(IntentType.UNKNOWN, 0.0)
        }

        // Try sale patterns first (most common)
        for (pattern in salePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractSaleData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.SALE,
                        confidence = 0.95,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                }
            }
        }

        // Try purchase patterns
        for (pattern in purchasePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractPurchaseData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.PURCHASE,
                        confidence = 0.95,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                }
            }
        }

        // Try expense patterns
        for (pattern in expensePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val data = extractExpenseData(cleaned, match)
                if (data != null) {
                    return IntentResult(
                        intent = IntentType.EXPENSE,
                        confidence = 0.90,
                        extractedData = data
                    )
                }
            }
        }

        // === TRANSPORT-SPECIFIC PATTERNS ===
        for (pattern in tripPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Trip count: "Nimefanya trip 5"
                    groups.size >= 3 && groups[2].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.TRANSPORT_TRIP,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "tripCount" to groups[2],
                            "item" to "trip"
                        )
                    )
                    // Passenger fare: "Passenger 300"
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.TRANSPORT_TRIP,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "item" to "passenger",
                            "amount" to groups[2]
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // === FARMING-SPECIFIC PATTERNS ===
        for (pattern in farmingPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Harvest with quantity: "Nimevuna mahindi 50kg"
                    groups.size >= 4 && groups[3].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.FARMING_ACTIVITY,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "quantity" to groups[3],
                            "activity" to "harvest"
                        )
                    )
                    // Planting with area: "Nimepanda mahindi ekari 2"
                    groups.size >= 4 && groups[3].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.FARMING_ACTIVITY,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "area" to groups[3],
                            "activity" to "plant"
                        )
                    )
                    // Input purchase: "Nimenunua mbegu kwa 800"
                    groups.size >= 5 && groups[4].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.FARMING_INPUT,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "amount" to groups[4]
                        )
                    )
                    // Simple planting: "Nimepanda mahindi"
                    groups.size >= 3 -> IntentResult(
                        intent = IntentType.FARMING_ACTIVITY,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "activity" to "plant"
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // === DIGITAL/GIG-SPECIFIC PATTERNS ===
        for (pattern in digitalPatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Transaction count: "Nimefanya transaction 50"
                    groups.size >= 3 && groups[2].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.DIGITAL_TRANSACTION,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "transactionCount" to groups[2],
                            "item" to "transaction"
                        )
                    )
                    // Commission/amount: "Commission 1200"
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.DIGITAL_COMMISSION,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[1].lowercase(),
                            "amount" to groups[2]
                        )
                    )
                    // Deposit/withdrawal: "Deposits 20000"
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.DIGITAL_TRANSACTION,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "item" to groups[1].lowercase(),
                            "amount" to groups[2]
                        )
                    )
                    // Social media post: "Nimepost TikTok"
                    groups.size >= 2 -> IntentResult(
                        intent = IntentType.DIGITAL_TRANSACTION,
                        confidence = 0.80,
                        extractedData = mapOf(
                            "item" to "post",
                            "platform" to groups.last().lowercase()
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // === SERVICE-SPECIFIC PATTERNS ===
        for (pattern in servicePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val groups = match.groupValues
                return when {
                    // Client count: "Clients 8"
                    groups.size >= 3 && groups[2].toIntOrNull() != null -> IntentResult(
                        intent = IntentType.SERVICE_CLIENT,
                        confidence = 0.85,
                        extractedData = mapOf(
                            "clientCount" to groups[2],
                            "item" to "client"
                        )
                    )
                    // Service with amount: "Service ya gari 2000"
                    groups.size >= 4 && groups[3].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.SERVICE_JOB,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "amount" to groups[3]
                        )
                    )
                    // Payment from client: "Nimepata 1500 kutoka kwa mteja"
                    groups.size >= 4 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = IntentType.SERVICE_JOB,
                        confidence = 0.90,
                        extractedData = mapOf(
                            "item" to "service",
                            "amount" to groups[2]
                        )
                    )
                    else -> null
                } ?: continue
            }
        }

        // Try query patterns
        if (profitPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.PROFIT_QUERY,
                confidence = 0.90
            )
        }

        if (balancePatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.CHECK_BALANCE,
                confidence = 0.90
            )
        }

        if (stockPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.STOCK_QUERY,
                confidence = 0.85,
                extractedData = mapOf("item" to (SwahiliParser.extractItemName(cleaned) ?: ""))
            )
        }

        // Transport-specific queries
        if (tripQueryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.TRANSPORT_TRIP,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "trip_info")
            )
        }

        // Farming-specific queries
        if (harvestQueryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.FARMING_ACTIVITY,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "harvest_info")
            )
        }

        // Digital/gig queries
        if (digitalQueryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.DIGITAL_COMMISSION,
                confidence = 0.80,
                extractedData = mapOf("queryType" to "digital_info")
            )
        }

        // Try summary patterns
        if (dailySummaryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.DAILY_SUMMARY,
                confidence = 0.90
            )
        }

        if (weeklySummaryPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.WEEKLY_SUMMARY,
                confidence = 0.85
            )
        }

        // Try advice patterns
        if (advicePatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.ASK_ADVICE,
                confidence = 0.85,
                needsLLM = true
            )
        }

        // Try greeting patterns
        if (greetingPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.GREETING,
                confidence = 0.95
            )
        }

        // Try help patterns
        if (helpPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.HELP,
                confidence = 0.80
            )
        }

        // Try correction patterns
        if (correctionPatterns.any { it.containsMatchIn(cleaned) }) {
            return IntentResult(
                intent = IntentType.CORRECTION,
                confidence = 0.80,
                needsLLM = true
            )
        }

        // Fallback: unknown intent
        return IntentResult(
            intent = IntentType.UNKNOWN,
            confidence = 0.0,
            needsLLM = true
        )
    }

    /**
     * Extract sale data from the matched text.
     */
    private fun extractSaleData(text: String, match: MatchResult): SaleData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null

        return SaleData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Extract purchase data from the matched text.
     */
    private fun extractPurchaseData(text: String, match: MatchResult): PurchaseData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null

        return PurchaseData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Extract expense data from the matched text.
     */
    private fun extractExpenseData(text: String, match: MatchResult): Map<String, String>? {
        val amount = SwahiliParser.extractPrice(text) ?: return null
        val category = SwahiliParser.extractItemName(text) ?: "other"

        return mapOf(
            "category" to category,
            "amount" to amount.toString()
        )
    }
}
