package com.msaidizi.app.agent

import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import timber.log.Timber

/**
 * Intent Router — classifies user input into intents.
 * This is CODE, not LLM — 0 RAM overhead, instant execution.
 *
 * Loads patterns from IntentPatternConfig (assets/intent_patterns.json).
 * Supports OTA updates: backend can push new patterns without app update.
 * Handles 90%+ of user input without needing the LLM.
 *
 * Migration note: All 800+ lines of hardcoded regex have been extracted
 * to intent_patterns.json for maintainability and OTA updatability.
 */
class IntentRouter(private val config: IntentPatternConfig) {

    // ═══ Lazy-loaded config data ═══
    private val shengToStandard: Map<String, String> by lazy { config.getShengVocabulary() }
    private val shengAmounts: Map<String, Int> by lazy { config.getShengAmounts() }
    private val givingTypeKeywords: Map<String, String> by lazy { config.getGivingTypeKeywords() }

    /**
     * Classify the intent of user input.
     * Returns IntentResult with type, confidence, and extracted data.
     */
    fun classify(text: String): IntentResult {
        val cleaned = text.trim()
        if (cleaned.isBlank()) {
            return IntentResult(IntentType.UNKNOWN, 0.0)
        }

        // ═══ STEP 0: Sheng/Dialect Normalization ═══
        val normalized = normalizeSheng(cleaned)
        val hasSheng = normalized != cleaned.lowercase().trim()

        // ═══ STEP 1: Try intents in priority order ═══
        val intentKeys = config.getIntentKeysByPriority()

        for (intentKey in intentKeys) {
            val intentConfig = config.getConfig().intents[intentKey] ?: continue
            val patterns = config.getPatternsForIntent(intentKey)

            // Sheng-specific patterns only run on original text when Sheng detected
            if (intentConfig.isSheng && !hasSheng) continue

            // Determine which text to match against
            val matchText = if (intentConfig.isSheng) cleaned else cleaned

            for (pattern in patterns) {
                val match = pattern.find(matchText) ?: continue
                val result = processMatch(intentKey, intentConfig, matchText, match, cleaned, normalized)
                if (result != null) {
                    Timber.d("Intent matched: $intentKey (%.2f)".format(result.confidence))
                    return result
                }
            }
        }

        // ═══ STEP 2: Try query-style patterns on normalized text ═══
        val queryResult = tryQueryPatterns(normalized, cleaned)
        if (queryResult.intent != IntentType.UNKNOWN) {
            return queryResult
        }

        // Fallback: unknown intent
        return IntentResult(
            intent = IntentType.UNKNOWN,
            confidence = 0.0,
            needsLLM = true
        )
    }

    /**
     * Process a regex match into an IntentResult.
     * Handles intent-specific data extraction.
     */
    private fun processMatch(
        intentKey: String,
        intentConfig: IntentPatternConfig.IntentConfig,
        matchText: String,
        match: MatchResult,
        originalText: String,
        normalizedText: String
    ): IntentResult? {
        val groups = match.groupValues

        return when {
            // ── SALE intents ──
            intentKey.startsWith("SALE") -> {
                val data = extractSaleData(matchText, match)
                if (data != null) {
                    IntentResult(
                        intent = IntentType.SALE,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                } else null
            }

            // ── PURCHASE intents ──
            intentKey.startsWith("PURCHASE") -> {
                val data = extractPurchaseData(matchText, match)
                if (data != null) {
                    IntentResult(
                        intent = IntentType.PURCHASE,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to data.item,
                            "quantity" to data.quantity.toString(),
                            "amount" to data.amount.toString(),
                            "unitPrice" to data.unitPrice.toString()
                        )
                    )
                } else null
            }

            // ── EXPENSE intents ──
            intentKey.startsWith("EXPENSE") -> {
                val data = extractExpenseData(matchText, match)
                if (data != null) {
                    IntentResult(
                        intent = IntentType.EXPENSE,
                        confidence = intentConfig.confidence,
                        extractedData = data
                    )
                } else null
            }

            // ── TRANSPORT_TRIP ──
            intentKey == "TRANSPORT_TRIP" -> {
                when {
                    groups.size >= 3 && groups.last { it.isNotBlank() }.toIntOrNull() != null -> {
                        val lastNum = groups.last { it.isNotBlank() }
                        IntentResult(
                            intent = IntentType.TRANSPORT_TRIP,
                            confidence = intentConfig.confidence,
                            extractedData = mapOf(
                                "tripCount" to lastNum,
                                "item" to "trip"
                            )
                        )
                    }
                    groups.size >= 3 && groups.last { it.isNotBlank() }.toDoubleOrNull() != null -> {
                        IntentResult(
                            intent = IntentType.TRANSPORT_TRIP,
                            confidence = intentConfig.confidence,
                            extractedData = mapOf(
                                "item" to "passenger",
                                "amount" to groups.last { it.isNotBlank() }
                            )
                        )
                    }
                    else -> null
                }
            }

            // ── FARMING_ACTIVITY / FARMING_INPUT ──
            intentKey.startsWith("FARMING") -> {
                val intentType = if (intentKey == "FARMING_INPUT") IntentType.FARMING_INPUT else IntentType.FARMING_ACTIVITY
                when {
                    groups.size >= 4 && groups[3].toIntOrNull() != null -> IntentResult(
                        intent = intentType,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "quantity" to groups[3],
                            "activity" to if (intentKey == "FARMING_INPUT") "input" else "harvest"
                        )
                    )
                    groups.size >= 3 -> IntentResult(
                        intent = intentType,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "activity" to "plant"
                        )
                    )
                    else -> null
                }
            }

            // ── DIGITAL intents ──
            intentKey.startsWith("DIGITAL") -> {
                val intentType = if (intentKey == "DIGITAL_COMMISSION") IntentType.DIGITAL_COMMISSION else IntentType.DIGITAL_TRANSACTION
                when {
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = intentType,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to groups[1].lowercase(),
                            "amount" to groups[2]
                        )
                    )
                    groups.size >= 2 -> IntentResult(
                        intent = intentType,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to "post",
                            "platform" to groups.last().lowercase()
                        )
                    )
                    else -> null
                }
            }

            // ── SERVICE intents ──
            intentKey.startsWith("SERVICE") -> {
                val intentType = if (intentKey == "SERVICE_CLIENT") IntentType.SERVICE_CLIENT else IntentType.SERVICE_JOB
                when {
                    groups.size >= 3 && groups[2].toIntOrNull() != null -> IntentResult(
                        intent = intentType,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "clientCount" to groups[2],
                            "item" to "client"
                        )
                    )
                    groups.size >= 4 && groups[3].toDoubleOrNull() != null -> IntentResult(
                        intent = intentType,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to groups[2].trim(),
                            "amount" to groups[3]
                        )
                    )
                    groups.size >= 3 && groups[2].toDoubleOrNull() != null -> IntentResult(
                        intent = intentType,
                        confidence = intentConfig.confidence,
                        extractedData = mapOf(
                            "item" to "service",
                            "amount" to groups[2]
                        )
                    )
                    else -> null
                }
            }

            // ── GIVING intents ──
            intentKey == "GIVING_RECORD" -> {
                val amount = groups.lastOrNull { it.toDoubleOrNull() != null } ?: return null
                val givingKeyword = groups.getOrNull(2)?.lowercase() ?: "sadaka"
                val type = resolveGivingType(givingKeyword)
                IntentResult(
                    intent = IntentType.GIVING_RECORD,
                    confidence = intentConfig.confidence,
                    extractedData = mapOf(
                        "amount" to amount,
                        "givingType" to type,
                        "rawText" to originalText
                    )
                )
            }

            intentKey == "GIVING_QUERY" -> IntentResult(
                intent = IntentType.GIVING_QUERY,
                confidence = intentConfig.confidence,
                extractedData = mapOf("rawText" to originalText)
            )

            intentKey == "GIVING_GOAL" -> {
                val amount = groups.lastOrNull { it.toDoubleOrNull() != null } ?: "0"
                IntentResult(
                    intent = IntentType.GIVING_GOAL,
                    confidence = intentConfig.confidence,
                    extractedData = mapOf("amount" to amount, "rawText" to originalText)
                )
            }

            // ── RECEIPT SCAN intent ──
            intentKey == "RECEIPT_SCAN" -> IntentResult(
                intent = IntentType.RECEIPT_SCAN,
                confidence = intentConfig.confidence,
                extractedData = mapOf("rawText" to originalText)
            )

            // ── GOAL intents ──
            intentKey == "GOAL_CREATE" -> {
                val amount = groups.lastOrNull { it.replace(",", "").toDoubleOrNull() != null }
                    ?.replace(",", "") ?: "0"
                val description = groups.getOrNull(groups.size - 2) ?: groups.lastOrNull() ?: ""
                IntentResult(
                    intent = IntentType.GOAL_CREATE,
                    confidence = intentConfig.confidence,
                    extractedData = mapOf(
                        "description" to description.trim(),
                        "amount" to amount,
                        "rawText" to originalText
                    )
                )
            }

            intentKey == "GOAL_PROGRESS" -> {
                val percentOrAmount = groups.firstOrNull {
                    it.toDoubleOrNull() != null || it.toIntOrNull() != null
                } ?: "0"
                val isPercent = originalText.contains("%")
                IntentResult(
                    intent = IntentType.GOAL_PROGRESS,
                    confidence = intentConfig.confidence,
                    extractedData = mapOf(
                        "value" to percentOrAmount,
                        "isPercent" to isPercent.toString(),
                        "rawText" to originalText
                    )
                )
            }

            intentKey == "GOAL_ADJUST" -> {
                val amount = groups.lastOrNull { it.replace(",", "").toDoubleOrNull() != null }
                    ?.replace(",", "") ?: "0"
                IntentResult(
                    intent = IntentType.GOAL_ADJUST,
                    confidence = intentConfig.confidence,
                    extractedData = mapOf("amount" to amount, "rawText" to originalText)
                )
            }

            intentKey == "GOAL_REPORT" -> IntentResult(
                intent = IntentType.GOAL_REPORT,
                confidence = intentConfig.confidence,
                extractedData = mapOf("rawText" to originalText)
            )

            intentKey == "GOAL_TIME_FORECAST" -> IntentResult(
                intent = IntentType.GOAL_TIME_FORECAST,
                confidence = intentConfig.confidence,
                extractedData = mapOf("rawText" to originalText)
            )

            intentKey == "GOAL_ENCOURAGEMENT" -> IntentResult(
                intent = IntentType.GOAL_ENCOURAGEMENT,
                confidence = intentConfig.confidence,
                extractedData = mapOf("rawText" to originalText)
            )

            // ── LOAN intents ──
            intentKey == "LOAN_RECORD" -> {
                val amount = groups.lastOrNull { it.replace(",", "").toDoubleOrNull() != null } ?: "0"
                val lender = groups.lastOrNull {
                    it.isNotBlank() && it.replace(",", "").toDoubleOrNull() == null && it.length > 2
                } ?: ""
                IntentResult(
                    intent = IntentType.LOAN_RECORD,
                    confidence = intentConfig.confidence,
                    extractedData = mapOf(
                        "amount" to amount.replace(",", ""),
                        "lender" to lender
                    )
                )
            }

            intentKey == "LOAN_QUERY" -> IntentResult(
                intent = IntentType.LOAN_QUERY,
                confidence = intentConfig.confidence
            )

            intentKey == "LOAN_REPORT" -> IntentResult(
                intent = IntentType.LOAN_REPORT,
                confidence = intentConfig.confidence
            )

            intentKey == "LOAN_DEADLINE" -> IntentResult(
                intent = IntentType.LOAN_DEADLINE,
                confidence = intentConfig.confidence
            )

            // ── Simple keyword-match intents ──
            intentKey == "CHECK_BALANCE" -> IntentResult(
                intent = IntentType.CHECK_BALANCE,
                confidence = intentConfig.confidence
            )

            intentKey == "PROFIT_QUERY" -> IntentResult(
                intent = IntentType.PROFIT_QUERY,
                confidence = intentConfig.confidence
            )

            intentKey == "STOCK_QUERY" -> IntentResult(
                intent = IntentType.STOCK_QUERY,
                confidence = intentConfig.confidence,
                extractedData = mapOf("item" to (SwahiliParser.extractItemName(normalizedText) ?: ""))
            )

            intentKey == "DAILY_SUMMARY" -> IntentResult(
                intent = IntentType.DAILY_SUMMARY,
                confidence = intentConfig.confidence
            )

            intentKey == "WEEKLY_SUMMARY" -> IntentResult(
                intent = IntentType.WEEKLY_SUMMARY,
                confidence = intentConfig.confidence
            )

            intentKey == "ASK_ADVICE" -> IntentResult(
                intent = IntentType.ASK_ADVICE,
                confidence = intentConfig.confidence,
                needsLLM = true
            )

            intentKey == "GREETING" -> IntentResult(
                intent = IntentType.GREETING,
                confidence = intentConfig.confidence
            )

            intentKey == "HELP" -> IntentResult(
                intent = IntentType.HELP,
                confidence = intentConfig.confidence
            )

            intentKey == "CORRECTION" -> IntentResult(
                intent = IntentType.CORRECTION,
                confidence = intentConfig.confidence,
                needsLLM = true
            )

            else -> null
        }
    }

    /**
     * Try query patterns on normalized text (fallback for simple keyword matches).
     */
    private fun tryQueryPatterns(normalized: String, original: String): IntentResult {
        // Query-style intents (transport, farming, digital queries)
        for (queryKey in listOf(
            "TRANSPORT_QUERY", "FARMING_QUERY", "DIGITAL_QUERY"
        )) {
            val patterns = config.getPatternsForIntent(queryKey)
            val intentConfig = config.getConfig().intents[queryKey] ?: continue
            if (patterns.any { it.containsMatchIn(normalized) }) {
                val intentType = when (queryKey) {
                    "TRANSPORT_QUERY" -> IntentType.TRANSPORT_TRIP
                    "FARMING_QUERY" -> IntentType.FARMING_ACTIVITY
                    "DIGITAL_QUERY" -> IntentType.DIGITAL_COMMISSION
                    else -> IntentType.UNKNOWN
                }
                return IntentResult(
                    intent = intentType,
                    confidence = intentConfig.confidence,
                    extractedData = mapOf("queryType" to queryKey.lowercase())
                )
            }
        }

        return IntentResult(intent = IntentType.UNKNOWN, confidence = 0.0, needsLLM = true)
    }

    // ═══ Sheng Normalization ═══

    private fun normalizeSheng(text: String): String {
        var normalized = text.lowercase().trim()
        for ((sheng, standard) in shengToStandard) {
            normalized = normalized.replace(sheng, standard)
        }
        return normalized
    }

    // ═══ Data Extraction Helpers ═══

    private fun extractSaleData(text: String, match: MatchResult): SaleData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null
        return SaleData(item = item, quantity = quantity, amount = amount)
    }

    private fun extractPurchaseData(text: String, match: MatchResult): PurchaseData? {
        val item = SwahiliParser.extractItemName(text) ?: return null
        val quantity = SwahiliParser.extractQuantity(text)
        val amount = SwahiliParser.extractPrice(text) ?: return null
        return PurchaseData(item = item, quantity = quantity, amount = amount)
    }

    private fun extractExpenseData(text: String, match: MatchResult): Map<String, String>? {
        val amount = SwahiliParser.extractPrice(text) ?: return null
        val category = SwahiliParser.extractItemName(text) ?: "other"
        return mapOf("category" to category, "amount" to amount.toString())
    }

    private fun resolveGivingType(keyword: String): String {
        for ((key, type) in givingTypeKeywords) {
            if (keyword.contains(key)) return type
        }
        return "OFFERING"
    }
}
