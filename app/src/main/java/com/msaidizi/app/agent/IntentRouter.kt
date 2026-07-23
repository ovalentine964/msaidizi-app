package com.msaidizi.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * IntentRouter — Regex-based intent classification.
 * 
 * Handles 90% of inputs without LLM. Zero RAM. Sub-millisecond.
 * Supports Swahili, Sheng, and English patterns.
 * 
 * Design: arch_android.md Section 6.2
 */
@Singleton
class IntentRouter @Inject constructor() {

    data class Classification(
        val intent: String,
        val confidence: Double,
        val entities: Map<String, String> = emptyMap(),
        val isSheng: Boolean = false
    )

    // ── Intent Patterns ──────────────────────────────────────────

    private val intentPatterns: List<IntentPattern> = listOf(
        // ═══ SALES ═══
        IntentPattern(
            intent = "sale",
            patterns = listOf(
                Regex("(?i)(nimeuza|nimeuzia|nimesell|sold|sale|mauzo|nimemaliza)"),
                Regex("(?i)(imeuzwa|imeuzika|tumemaliza)"),
                Regex("(?i)(nauza|ninauza|selling|nina sell)"),
                Regex("(?i)(record.*sale|rekodi.*mauzo|andika.*mauzo)")
            ),
            amountPattern = Regex("(?:kwa|for|ya|KSh|ksh|kes|sh)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)"),
            itemPattern = Regex("(?:nimeuza|nimesold|sold|ninauza|selling)\\s+(.+?)(?:\\s+(?:kwa|for|ya|KSh)|$)")
        ),

        // ═══ PURCHASES ═══
        IntentPattern(
            intent = "purchase",
            patterns = listOf(
                Regex("(?i)(nimenunua|nimbuy|bought|purchase|nimenunuliwa)"),
                Regex("(?i)(nimetafuta|nimepata.*bei|got.*for)"),
                Regex("(?i)(record.*purchase|rekodi.*manunuzi)")
            ),
            amountPattern = Regex("(?:kwa|for|ya|KSh|ksh|kes)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)"),
            itemPattern = Regex("(?:nimenunua|nimebuy|bought)\\s+(.+?)(?:\\s+(?:kwa|for|ya|KSh)|$)")
        ),

        // ═══ EXPENSES ═══
        IntentPattern(
            intent = "expense",
            patterns = listOf(
                Regex("(?i)(nimetumia|nimespend|spent|expense|gharama|matumizi)"),
                Regex("(?i)(nimelipia|paid|nalipa|nimetoa)"),
                Regex("(?i)(record.*expense|rekodi.*gharama|andika.*matumizi)")
            ),
            amountPattern = Regex("(?:kwa|for|ya|KSh|ksh|kes)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)")
        ),

        // ═══ BALANCE CHECK ═══
        IntentPattern(
            intent = "check_balance",
            patterns = listOf(
                Regex("(?i)(balance|salio|pesa\\s*zangu|how much.*have|nina.*pesa)"),
                Regex("(?i)(angalia.*salio|check.*balance|onyesha.*pesa)"),
                Regex("(?i)(profit|faida|hasara|mapato|income)")
            )
        ),

        // ═══ PROFIT QUERY ═══
        IntentPattern(
            intent = "profit_query",
            patterns = listOf(
                Regex("(?i)(profit|faida|hasara|mapato|earning|nimetengeneza)"),
                Regex("(?i)(how much.*profit|kiasi.*faida|jinsi.*mapato)"),
                Regex("(?i)(gani.*profit|gani.*faida)")
            )
        ),

        // ═══ STOCK CHECK ═══
        IntentPattern(
            intent = "stock_query",
            patterns = listOf(
                Regex("(?i)(stock|inventory|bidhaa|stash|kiasi.*bidhaa)"),
                Regex("(?i)(angalia.*stock|check.*stock|how much.*stock)"),
                Regex("(?i)(imebaki|remaining|baki|imebakia)")
            ),
            itemPattern = Regex("(?:stock|inventory|stash|bidhaa)\\s+(?:ya\\s+)?(.+?)(?:\\s*$)")
        ),

        // ═══ DAILY SUMMARY ═══
        IntentPattern(
            intent = "daily_summary",
            patterns = listOf(
                Regex("(?i)(daily.*summary|muhtasari.*leo|leo.*nimefanya|today.*summary)"),
                Regex("(?i)(report.*leo|leo.*report|mwisho.*leo|leo.*mwisho)"),
                Regex("(?i)(jumla.*leo|leo.*jumla|total.*today)")
            )
        ),

        // ═══ WEEKLY SUMMARY ═══
        IntentPattern(
            intent = "weekly_summary",
            patterns = listOf(
                Regex("(?i)(weekly.*summary|muhtasari.*wiki|wiki.*nimefanya|week.*summary)"),
                Regex("(?i)(report.*wiki|wiki.*report|this week|wiki.*hii)")
            )
        ),

        // ═══ ADVICE ═══
        IntentPattern(
            intent = "advice",
            patterns = listOf(
                Regex("(?i)(ushauri|advice|nifanye|nini.*fanya|what.*do|suggest|pendekeza)"),
                Regex("(?i)(saidia|help.*me|nisaidie|how.*improve|jinsi.*boresha)"),
                Regex("(?i)(niko.*wapi|where.*stand|hali.*biashara)")
            )
        ),

        // ═══ GREETING ═══
        IntentPattern(
            intent = "greeting",
            patterns = listOf(
                Regex("^(?i)(habari|hujambo|jambo|salama|mambo|niaje|sasa|vipi|hey|hi|hello|good morning|good evening)"),
                Regex("(?i)(habari.*asubuhi|habari.*jioni|nzuri|poa)")
            )
        ),

        // ═══ HELP ═══
        IntentPattern(
            intent = "help",
            patterns = listOf(
                Regex("(?i)(help|msaada|saidia|nini.*weza|what.*can.*do|jinsi.*tumia)"),
                Regex("(?i)(commands|amri|menu|options|chaguo)")
            )
        ),

        // ═══ GOAL SET ═══
        IntentPattern(
            intent = "goal_set",
            patterns = listOf(
                Regex("(?i)(goal|lengo|target|nataka.*kufikia|want.*reach|weka.*lengo|set.*goal)"),
                Regex("(?i)(nahitaji.*kufikia|need.*reach|mpango.*wa)")
            ),
            amountPattern = Regex("(?:kwa|for|ya|KSh|ksh|target|lengo)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)")
        ),

        // ═══ GOAL CHECK ═══
        IntentPattern(
            intent = "goal_check",
            patterns = listOf(
                Regex("(?i)(angalia.*lengo|check.*goal|lengo.*gani|progress.*goal|maendeleo)"),
                Regex("(?i)(nimefikia|reached|achieved|how.*close|karibu.*lengo)")
            )
        ),

        // ═══ LOAN RECORD ═══
        IntentPattern(
            intent = "loan_record",
            patterns = listOf(
                Regex("(?i)(mkopo|loan|nimekopa|borrowed|nimechukua.*mkopo)"),
                Regex("(?i)(record.*loan|rekodi.*mkopo|nimelipia.*mkopo)")
            ),
            amountPattern = Regex("(?:kwa|for|ya|KSh|ksh|mkopo|loan)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)")
        ),

        // ═══ LOAN CHECK ═══
        IntentPattern(
            intent = "loan_check",
            patterns = listOf(
                Regex("(?i)(angalia.*mkopo|check.*loan|mkopo.*baki|loan.*remaining|deni)"),
                Regex("(?i)(nina.*mkopo|owe|outstanding|baki.*mkopo)")
            )
        ),

        // ═══ GIVING/TITHE ═══
        IntentPattern(
            intent = "giving",
            patterns = listOf(
                Regex("(?i)(toleo|tithe|zakat|sadaka|charity|nimechangia|giving)"),
                Regex("(?i)(record.*toleo|rekodi.*sadaka|nimetoa)")
            ),
            amountPattern = Regex("(?:kwa|for|ya|KSh|ksh)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)")
        ),

        // ═══ TRANSPORT ═══
        IntentPattern(
            intent = "transport",
            patterns = listOf(
                Regex("(?i)(boda|matatu|transport|usafiri|trip|safari|route|njia)"),
                Regex("(?i)(nimefanya.*trip|nimetoka|nimeweka.*route)")
            ),
            amountPattern = Regex("(?:kwa|for|ya|KSh|ksh)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)")
        ),

        // ═══ FARMING ═══
        IntentPattern(
            intent = "farming",
            patterns = listOf(
                Regex("(?i)(farm|kilimo|mazao|crop|harvest|mavuno|shamba|mbegu|fertilizer)"),
                Regex("(?i)(nimepanda|nimevuna|nimelima|planted|harvested)")
            )
        ),

        // ═══ DIGITAL ═══
        IntentPattern(
            intent = "digital",
            patterns = listOf(
                Regex("(?i)(digital|freelance|client|project|online|kazi.*mtandaoni)"),
                Regex("(?i)(nimemaliza.*project|nimepata.*client)")
            )
        ),

        // ═══ SERVICE ═══
        IntentPattern(
            intent = "service",
            patterns = listOf(
                Regex("(?i)(salon|mechanic|service|huduma|mteja|customer|client)"),
                Regex("(?i)(nimeservice|nimetengeneza|nimekata.*nywele)")
            )
        ),

        // ═══ CORRECTION ═══
        IntentPattern(
            intent = "correction",
            patterns = listOf(
                Regex("(?i)(siyo|sio|happana|no.*wrong|correct|sahihisha|rekebisho)"),
                Regex("(?i)(nilimaanisha|I meant|I mean|badilisha)")
            )
        ),

        // ═══ M-PESA ═══
        IntentPattern(
            intent = "mpesa",
            patterns = listOf(
                Regex("(?i)(mpesa|m-pesa|pesa.*mpesa|lipa.*mpesa)"),
                Regex("(?i)(nimetuma|nimetum|sent.*mpesa|received.*mpesa)"),
                Regex("([A-Z0-9]{10})")  // M-Pesa confirmation code
            )
        )
    )

    /**
     * Classify text into an intent with confidence score.
     * Returns highest-confidence match.
     */
    fun classify(text: String): IntentResult {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return IntentResult(type = "unknown", confidence = 0.0)
        }

        val matches = intentPatterns.mapNotNull { pattern ->
            val matchScore = pattern.patterns.maxOfOrNull { regex ->
                if (regex.containsMatchIn(normalized)) {
                    // Calculate confidence based on match specificity
                    val matchLength = regex.find(normalized)?.value?.length ?: 0
                    val ratio = matchLength.toDouble() / normalized.length
                    0.6 + (ratio * 0.4)  // Base 0.6 + up to 0.4 for specificity
                } else 0.0
            } ?: 0.0

            if (matchScore > 0.0) {
                val entities = extractEntities(normalized, pattern)
                Pair(
                    IntentResult(
                        type = pattern.intent,
                        confidence = matchScore.coerceIn(0.0, 1.0),
                        isSheng = detectSheng(normalized)
                    ),
                    entities
                )
            } else null
        }

        return if (matches.isNotEmpty()) {
            val best = matches.maxByOrNull { it.first.confidence }!!
            best.first.copy(
                confidence = best.first.confidence
            )
        } else {
            IntentResult(type = "unknown", confidence = 0.1)
        }
    }

    /**
     * Extract entities (amount, item, quantity) from text.
     */
    fun extractEntities(text: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        // Extract amount
        val amountRegex = Regex("(\\d[\\d,]*(?:\\.\\d{1,2})?)")
        val amountMatch = amountRegex.find(text)
        if (amountMatch != null) {
            entities["amount"] = amountMatch.value.replace(",", "")
        }

        // Extract M-Pesa code (10 chars, uppercase alphanumeric)
        val mpesaRegex = Regex("[A-Z0-9]{10}")
        val mpesaMatch = mpesaRegex.find(text)
        if (mpesaMatch != null) {
            entities["mpesaCode"] = mpesaMatch.value
        }

        return entities
    }

    private fun extractEntities(text: String, pattern: IntentPattern): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        pattern.amountPattern?.find(text)?.let { match ->
            val amount = match.groupValues.getOrNull(1) ?: match.value
            entities["amount"] = amount.replace(",", "")
        }

        pattern.itemPattern?.find(text)?.let { match ->
            val item = match.groupValues.getOrNull(1) ?: match.value
            if (item.isNotBlank()) entities["item"] = item.trim()
        }

        return entities
    }

    /**
     * Detect Sheng (urban slang mixing Swahili, English, local languages).
     */
    private fun detectSheng(text: String): Boolean {
        val shengMarkers = listOf(
            "niaje", "sasa", "vipi", "poa", "mbogi", "msee", "mzae",
            "ndege", "ngwai", "blaze", "fala", "kamati", "bao",
            "ngata", "kasri", "mbao", "kuji", "tiss", "kuoka"
        )
        return shengMarkers.any { text.lowercase().contains(it) }
    }

    private data class IntentPattern(
        val intent: String,
        val patterns: List<Regex>,
        val amountPattern: Regex? = null,
        val itemPattern: Regex? = null
    )
}
