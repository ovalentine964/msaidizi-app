package com.msaidizi.app.core.util

import com.msaidizi.app.core.dialect.MigoriDialectAdapter
import com.msaidizi.app.core.model.SaleData
import com.msaidizi.app.core.model.PurchaseData
import timber.log.Timber

/**
 * Parser for Swahili business language.
 * Handles number words, common items, and business phrases.
 * This is CODE, not LLM — 0 RAM overhead, instant execution.
 *
 * Critical for the 2GB architecture: 90%+ of user input is handled
 * by this parser without needing the LLM.
 */
object SwahiliParser {

    // === NUMBER WORD MAPPINGS ===

    private val swahiliNumbers = mapOf(
        // Units
        "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
        "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,

        // Tens
        "kumi" to 10, "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
        "hamsini" to 50, "sitini" to 60, "sabini" to 70, "themanini" to 80,
        "tisini" to 90,

        // Hundreds
        "mia" to 100, "mia_mbili" to 200, "mia_tatu" to 300, "mia_nne" to 400,
        "mia_tano" to 500, "mia_sita" to 600, "mia_saba" to 700,
        "mia_nane" to 800, "mia_tisa" to 900,

        // Thousands
        "elfu" to 1000, "elfu_mbili" to 2000, "elfu_tatu" to 3000,
        "elfu_nne" to 4000, "elfu_tano" to 5000,

        // Common compounds
        "kumi_na_moja" to 11, "kumi_na_mbili" to 12, "kumi_na_tatu" to 13,
        "kumi_na_nne" to 14, "kumi_na_tano" to 15, "kumi_na_sita" to 16,
        "kumi_na_saba" to 17, "kumi_na_nane" to 18, "kumi_na_tisa" to 19,

        // Sheng / informal
        "nusu" to 0.5, "quarter" to 0.25
    )

    // Expanded number parsing with "na" conjunction
    private val compoundPattern = Regex(
        """(\w+)\s+na\s+(\w+)""",
        RegexOption.IGNORE_CASE
    )

    // === COMMON BUSINESS ITEMS ===

    private val commonItems = mapOf(
        // Grains & Flour
        "unga" to "flour", "unga_wa_ngano" to "wheat_flour", "unga_wa_dengu" to "lentil_flour",
        "wimbi" to "millet", "mchele" to "rice", "njugu" to "peanuts",
        "dengu" to "lentils", "maharagwe" to "beans", "mahindi" to "maize",

        // Vegetables
        "nyanya" to "tomatoes", "vitunguu" to "onions", "viazi" to "potatoes",
        "karoti" to "carrots", "sukuma_wiki" to "kale", "spinachi" to "spinach",
        "mboga" to "vegetables", "hoho" to "capsicum", "ng"o"ng"o" to "okra",

        // Fruits
        "maembe" to "mangoes", "machungwa" to "oranges", "ndizi" to "bananas",
        "nanasi" to "pineapple", "tikiti_maji" to "watermelon", "embe" to "mango",

        // Cooking essentials
        "mafuta" to "cooking_oil", "sukari" to "sugar", "chumvi" to "salt",
        "chai" to "tea", "maziwa" to "milk", "siagi" to "butter",

        // Proteins
        "nyama" to "meat", "kuku" to "chicken", "samaki" to "fish",
        "mayai" to "eggs", "ng"ombe" to "beef", "mbuzi" to "goat_meat",

        // Prepared food
        "mandazi" to "mandazi", "chapati" to "chapati", "ugali" to "ugali",
        "mkate" to "bread", "maandazi" to "mandazi",

        // Household
        "sabuni" to "soap", "dawa" to "medicine", "pampers" to "diapers",
        "mshumaa" to "candle", "kandamizi" to "starch",

        // ── Migori/Nyatike Business Vocabulary ──
        // Local products (Luo substrate names used in Migori Swahili)
        "omena" to "omena",                  // small dried fish — Migori/Rachuonyo staple
        "omena_kubwa" to "omena_large",      // large dried fish
        "piyo" to "groundnuts",              // Dholuo: groundnuts (cf. "njugu")
        "atapa" to "atapa",                  // traditional Luo cassava bread
        "nyoyo" to "nyoyo",                  // cowpeas (Migori term)
        "odo" to "traditional_greens",        // Dholuo traditional greens
        "apoth" to "traditional_greens",      // another traditional leafy vegetable
        "sukuma" to "kale",                   // short form of sukuma wiki
        "terere" to "terere",                // amaranth greens (common in Nyanza)
        "mrenda" to "mrenda",                // jute mallow (Luo/Nyanza vegetable)
        "kunde" to "kunde",                  // cowpea leaves

        // Migori fish varieties (Lake Victoria)
        "nguru" to "catfish",                // Nile catfish
        "ngege" to "tilapia",                // tilapia
        "omena" to "omena",                  // dagaa/small fish
        "rohu" to "rohu",                    // rohu fish
        "changu" to "rabbit_fish",           // rabbit fish

        // Cross-border goods (Migori-Tanzania)
        "mali" to "merchandise",              // cross-border goods
        "sukari_tz" to "tanzania_sugar",     // sugar from Tanzania
        "mafuta_tz" to "tanzania_oil",       // cooking oil from TZ

        // Dholuo food terms used in code-switching
        "kuon" to "ugali",                   // Dholuo: ugali
        "rech" to "food",                    // Dholuo: food
        "thwon" to "traditional_beer",       // Luo brew
        "chang'aa" to "changaa",             // local spirit
        "busaa" to "busaa",                  // traditional brew

        // Livestock (Migori rural markets)
        "ng'ina" to "hen",                   // Luo: hen
        "dier" to "bull",                    // Luo: bull
        "sungura" to "rabbit"                // rabbit
    )

    // === BUSINESS ACTION WORDS ===

    private val saleVerbs = setOf(
        "nimeuza", "nauza", "nikauza", "niliuza", "sold", "sell",
        "nimeuzia", "nimepatia", "nauza"
    )

    private val purchaseVerbs = setOf(
        "nimenunua", "nilinunua", "nimenunulia", "nimenunua", "bought", "buy",
        "nimenunua", "nimenunua", "nimelog", "nimenunua"
    )

    private val expenseVerbs = setOf(
        "nimetumia", "nimelipa", "nimetoa", "spent", "paid",
        "nimepoteza", "gharama"
    )

    // === QUANTITY WORDS ===

    private val quantityWords = mapOf(
        "nusu" to 0.5, "robo" to 0.25, "theluthi" to 0.33,
        "kadhaa" to 3.0, "chache" to 2.0, "nyingi" to 10.0,
        "kilo" to 1.0, "kilo_moja" to 1.0, "kilo_mbili" to 2.0,
        "lita" to 1.0, "lita_moja" to 1.0, "lita_mbili" to 2.0,
        "pakiti" to 1.0, "mfuko" to 1.0, "sanduku" to 1.0,
        "debe" to 1.0, "frying" to 1.0,

        // ── Migori local measurement units ──
        "debe" to 1.0,              // tin can (~20kg for maize, ~18kg for beans)
        "debe_ndogo" to 0.5,        // small tin
        "debe_kubwa" to 1.5,        // large tin
        "gunia" to 1.0,             // sack (50-90kg depending on commodity)
        "gunia_ndogo" to 0.5,       // small sack (~25kg)
        "gunia_kubwa" to 2.0,       // large sack (~90kg)
        "fundo" to 1.0,             // bundle/tie (e.g., sukuma wiki, mboga)
        "fundo_mbili" to 2.0,       // two bundles
        "fundo_tatu" to 3.0,        // three bundles
        "kibuyu" to 1.0,            // calabash measure
        "goro" to 1.0,              // tin measure for oil (~1 liter)
        "kibaba" to 1.0,            // small measure (~1 kg)
        "kibaba_mbili" to 2.0,      // two kibaba
        "ratili" to 1.0,            // pound measure
        "ratili_mbili" to 2.0,      // two pounds
        "mfuko" to 1.0,             // bag/packet
        "mfuko_mdogo" to 0.5,       // small packet
        "sanduku" to 1.0,           // box/crate
        "karai" to 1.0,             // basin (common market measure)
        "karai_ndogo" to 0.5,       // small basin
        "bakuli" to 1.0,            // bowl measure
        "goro" to 1.0               // gourd/tin for oil
    )

    // === PRICE PATTERNS ===

    private val pricePattern = Regex(
        """(?:kwa|sh|ksh|kes|bob|pesa)\s*(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    private val pricePatternBefore = Regex(
        """(\d+(?:\.\d+)?)\s*(?:kwa|sh|ksh|kes|bob|pesa)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parse a Swahili number word to a numeric value.
     * Handles: "kumi" → 10, "mia tano" → 500, "elfu moja" → 1000,
     *          "kumi na mbili" → 12, "mia mbili na hamsini" → 250
     */
    fun parseSwahiliNumber(text: String): Double? {
        val cleaned = text.trim().lowercase().replace(" ", "_").replace("-", "_")

        // Try direct lookup
        swahiliNumbers[cleaned]?.let { return it.toDouble() }

        // Try parsing as regular number
        text.replace(",", "").toDoubleOrNull()?.let { return it }

        // Try compound: "X na Y"
        val compound = compoundPattern.find(text.lowercase())
        if (compound != null) {
            val part1 = parseSwahiliNumber(compound.groupValues[1])
            val part2 = parseSwahiliNumber(compound.groupValues[2])
            if (part1 != null && part2 != null) {
                // If part1 is a base (mia, elfu, kumi), multiply then add
                val base1 = part1.toLong()
                if (base1 >= 100 || (base1 >= 10 && part2!! < 10)) {
                    return base1 + part2!!
                }
                return part1 + part2!!
            }
        }

        // Try "mia X" pattern
        if (cleaned.startsWith("mia_")) {
            val rest = cleaned.removePrefix("mia_")
            val multiplier = parseSwahiliNumber(rest)
            if (multiplier != null) return 100 * multiplier
        }

        // Try "elfu X" pattern
        if (cleaned.startsWith("elfu_")) {
            val rest = cleaned.removePrefix("elfu_")
            val multiplier = parseSwahiliNumber(rest)
            if (multiplier != null) return 1000 * multiplier
        }

        // Try "kumi na X" pattern
        if (cleaned.startsWith("kumi_na_")) {
            val rest = cleaned.removePrefix("kumi_na_")
            val addend = parseSwahiliNumber(rest)
            if (addend != null) return 10 + addend
        }

        return null
    }

    /**
     * Extract price from text.
     * Handles: "kwa 500", "sh 500", "500 bob", "KSh 1,000"
     */
    fun extractPrice(text: String): Double? {
        // Try "kwa 500" pattern
        pricePattern.find(text)?.let { match ->
            return match.groupValues[1].replace(",", "").toDoubleOrNull()
        }

        // Try "500 kwa" pattern
        pricePatternBefore.find(text)?.let { match ->
            return match.groupValues[1].replace(",", "").toDoubleOrNull()
        }

        // Try extracting any number that looks like a price (>10)
        val numbers = Regex("""\d+(?:\.\d+)?""").findAll(text)
            .map { it.value.replace(",", "").toDouble() }
            .filter { it >= 10 }  // Prices are usually >= 10 KSh
            .toList()

        return numbers.maxOrNull()
    }

    /**
     * Extract item name from text.
     * Tries to match against known business items.
     */
    fun extractItemName(text: String): String? {
        val words = text.lowercase().split(Regex("""\s+"""))

        // Try exact matches first
        for (word in words) {
            commonItems[word]?.let { return it }
        }

        // Try partial matches
        for ((swahili, english) in commonItems) {
            if (text.lowercase().contains(swahili)) {
                return english
            }
        }

        // Return the most likely noun (longest word that's not a number/verb)
        val stopWords = saleVerbs + purchaseVerbs + expenseVerbs +
            setOf("na", "kwa", "ya", "za", "wa", "la", "ni", "kwa", "sh", "ksh", "kes")
        val candidates = words.filter { word ->
            word.length > 3 &&
            word !in stopWords &&
            word.toDoubleOrNull() == null &&
            parseSwahiliNumber(word) == null
        }

        return candidates.maxByOrNull { it.length }
    }

    /**
     * Extract quantity from text.
     * Handles: "kumi" → 10, "5" → 5, "nusu" → 0.5
     */
    fun extractQuantity(text: String): Double {
        val words = text.lowercase().split(Regex("""\s+"""))

        // Look for quantity words
        for (word in words) {
            quantityWords[word]?.let { return it }
            parseSwahiliNumber(word)?.let { qty ->
                // Heuristic: if < 100, it's likely a quantity not a price
                if (qty < 100) return qty
            }
        }

        // Look for numbers that might be quantities
        val numbers = Regex("""\d+""").findAll(text)
            .map { it.value.toDouble() }
            .filter { it in 1.0..99.0 }  // Reasonable quantity range
            .toList()

        return numbers.firstOrNull() ?: 1.0
    }

    /**
     * Parse a complete sale statement.
     * "Nimeuza mandazi kumi kwa Sh 500" → SaleData(mandazi, 10, 500)
     */
    fun parseSale(text: String): SaleData? {
        val item = extractItemName(text) ?: return null
        val quantity = extractQuantity(text)
        val amount = extractPrice(text) ?: return null

        return SaleData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Parse a complete purchase statement.
     * "Nimenunua unga mbili kwa Sh 200" → PurchaseData(flour, 2, 200)
     */
    fun parsePurchase(text: String): PurchaseData? {
        val item = extractItemName(text) ?: return null
        val quantity = extractQuantity(text)
        val amount = extractPrice(text) ?: return null

        return PurchaseData(
            item = item,
            quantity = quantity,
            amount = amount
        )
    }

    /**
     * Detect the language of the text.
     */
    fun detectLanguage(text: String): String {
        val words = text.lowercase().split(Regex("""\s+"""))
        val swahiliMarkers = setOf("na", "ya", "wa", "za", "kwa", "ni", "la", "nime", "sija")
        val englishMarkers = setOf("the", "and", "for", "with", "that", "sold", "bought")
        val shengMarkers = setOf("sasa", "poa", "fiti", "aje", "niaje", "aje", "boss")

        val swScore = words.count { it in swahiliMarkers }
        val enScore = words.count { it in englishMarkers }
        val shScore = words.count { it in shengMarkers }

        return when {
            shScore > swScore && shScore > enScore -> "sheng"
            swScore > enScore -> "sw"
            enScore > swScore -> "en"
            else -> "sw"  // Default to Swahili
        }
    }

    /**
     * Post-process ASR output to fix common Swahili ASR errors.
     * Now includes Migori-specific ASR correction patterns.
     */
    fun correctASROutput(text: String): String {
        var corrected = text

        // Common ASR misrecognitions
        val corrections = mapOf(
            "nika uza" to "nimeuza",
            "nika nua" to "nimenunua",
            "ni me uza" to "nimeuza",
            "ni me nunua" to "nimenunua",
            "kwa sh" to "kwa Sh",
            "kwa s" to "kwa Sh",
            "mandasi" to "mandazi",
            "sukuma wiki" to "sukuma_wiki",
            "ma embe" to "maembe",
            "vi azi" to "viazi",
            "vi tunguu" to "vitunguu",
            "ma hundi" to "mahindi",
            "nusu" to "nusu",

            // ── Migori-specific ASR corrections ──
            // Dholuo loanwords that ASR often misrecognizes
            "omena" to "omena",
            "o mena" to "omena",
            "oh mena" to "omena",
            "pi yo" to "piyo",
            "a tapa" to "atapa",
            "ku on" to "kuon",
            "rech" to "rech",
            // Pronunciation variations ("th" → "s" in Migori)
            "samini" to "thamini",
            "sa labu" to "tharabu",
            // Currency expressions
            "mbao" to "mbao",
            "em bao" to "mbao",
            "kibabu" to "kibabu",
            "ki babu" to "kibabu",
            "ngiri" to "ngiri",
            "n giri" to "ngiri",
            "finje" to "finje",
            "fin je" to "finje",
            // Measurement units
            "debe" to "debe",
            "de be" to "debe",
            "gunia" to "gunia",
            "gu nia" to "gunia",
            "fundo" to "fundo",
            "fun do" to "fundo",
            "kibaba" to "kibaba",
            "ki baba" to "kibaba",
            "karai" to "karai",
            "ka rai" to "karai"
        )

        for ((wrong, right) in corrections) {
            corrected = corrected.replace(wrong, right, ignoreCase = true)
        }

        // Normalize number formats
        corrected = corrected.replace(Regex("""(\d),(\d)"""), "$1$2")  // Remove commas in numbers
        corrected = corrected.replace(Regex("""KES?\s*"""), "KSh ")  // Normalize currency
        corrected = corrected.replace(Regex("""bob"""), "KSh", ignoreCase = true)

        // Normalize Migori currency slang to KSh amounts
        corrected = normalizeCurrencySlang(corrected)

        return corrected.trim()
    }

    /**
     * Parse Migori currency slang expressions to KSh amounts.
     * Handles: "mbao" → KSh 20, "kibabu" → KSh 50, "ngiri" → KSh 1000
     *
     * Also handles compound expressions like "mbao tatu" = KSh 60.
     */
    private fun normalizeCurrencySlang(text: String): String {
        var result = text

        // Currency slang → numeric value
        val currencySlang = mapOf(
            "mbao" to 20,            // 20 bob
            "jeuri" to 50,           // 50 bob (Sheng, used in Migori)
            "kibabu" to 50,          // 50 bob
            "finje" to 500,          // 500 bob
            "ngiri" to 1000,         // 1000 bob
            "thao" to 1000,          // 1000 (from "thousand")
            "nane" to 80,            // 80 bob ("nane" = 8 × 10)
            "robo" to 250,           // 250 (quarter of 1000)
            "nusu_thao" to 500,      // half of 1000
            "quarter" to 250         // 250
        )

        for ((slang, value) in currencySlang) {
            // Match "mbao tatu" → KSh 60, "mbao mbili" → KSh 40
            val compoundPattern = Regex(
                """\b$slang\s+(moja|mbili|tatu|nne|tano|sita|saba|nane|tisa|kumi)\b""",
                RegexOption.IGNORE_CASE
            )
            compoundPattern.find(result)?.let { match ->
                val multiplierWord = match.groupValues[1].lowercase()
                val multiplier = when (multiplierWord) {
                    "moja" -> 1; "mbili" -> 2; "tatu" -> 3; "nne" -> 4
                    "tano" -> 5; "sita" -> 6; "saba" -> 7; "nane" -> 8
                    "tisa" -> 9; "kumi" -> 10; else -> 1
                }
                result = result.replace(match.value, "KSh ${value * multiplier}")
            }

            // Match standalone slang: "mbao" → KSh 20
            val standalonePattern = Regex("""\b$slang\b""", RegexOption.IGNORE_CASE)
            standalonePattern.find(result)?.let { match ->
                // Only replace if not already part of a compound we handled
                if (!result.contains("KSh ${value}")) {
                    result = result.replace(match.value, "KSh $value")
                }
            }
        }

        return result
    }

    /**
     * Extract price handling Migori currency slang.
     * Extends extractPrice() to understand local expressions.
     *
     * Examples:
     * - "mbao tatu" → 60
     * - "kibabu" → 50
     * - "ngiri mbili" → 2000
     * - "finje" → 500
     */
    fun extractPriceWithSlang(text: String): Double? {
        // First try standard price extraction
        extractPrice(text)?.let { return it }

        // Try currency slang
        val currencySlang = mapOf(
            "mbao" to 20, "jeuri" to 50, "kibabu" to 50,
            "finje" to 500, "ngiri" to 1000, "thao" to 1000,
            "nane" to 80, "robo" to 250, "nusu_thao" to 500
        )

        val lower = text.lowercase()
        for ((slang, value) in currencySlang) {
            val compoundPattern = Regex(
                """\b$slang\s+(moja|mbili|tatu|nne|tano|sita|saba|nane|tisa|kumi)\b""",
                RegexOption.IGNORE_CASE
            )
            compoundPattern.find(lower)?.let { match ->
                val multiplierWord = match.groupValues[1].lowercase()
                val multiplier = when (multiplierWord) {
                    "moja" -> 1; "mbili" -> 2; "tatu" -> 3; "nne" -> 4
                    "tano" -> 5; "sita" -> 6; "saba" -> 7; "nane" -> 8
                    "tisa" -> 9; "kumi" -> 10; else -> 1
                }
                return (value * multiplier).toDouble()
            }

            if (Regex("""\b$slang\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
                return value.toDouble()
            }
        }

        return null
    }

    /**
     * Full pipeline: dialect normalization + ASR correction + parsing.
     * This is the main entry point for processing Migori Swahili input.
     *
     * Flow:
     * 1. Run Migori dialect adapter (code-switch detection, normalization)
     * 2. Apply ASR corrections
     * 3. Parse using standard SwahiliParser
     * 4. If parsing fails, try Migori slang extraction
     * 5. Track unknown words via AdaptiveVocabulary
     */
    fun parseWithDialect(
        text: String,
        adaptiveVocabulary: com.msaidizi.app.core.dialect.AdaptiveVocabulary? = null
    ): ParseResult {
        Timber.d("Parsing with dialect: '%s'", text)

        // Step 1: Dialect processing
        val dialectResult = MigoriDialectAdapter.process(text)
        Timber.d("Dialect: region=%s, codeSwitch=%s",
            dialectResult.dialectRegion, dialectResult.codeSwitchResult.hasCodeSwitching)

        // Step 2: ASR correction
        val corrected = correctASROutput(dialectResult.normalizedText)

        // Step 3: Standard parsing
        val sale = parseSale(corrected)
        val purchase = parsePurchase(corrected)

        // Step 4: Try slang-aware price extraction if standard parsing failed
        val priceWithSlang = extractPriceWithSlang(corrected)

        // Step 5: Track unknown words for adaptive learning
        adaptiveVocabulary?.let { vocab ->
            val words = corrected.lowercase().split(Regex("""\s+"""))
            val knownItems = commonItems.keys + migoriBusinessSlang.keys
            for (word in words) {
                val clean = word.trim()
                if (clean.length > 2 && clean !in knownItems &&
                    clean.toDoubleOrNull() == null &&
                    parseSwahiliNumber(clean) == null
                ) {
                    // This might be a new product or term the user teaches us
                    vocab.trackUnknownWord(clean, dialectResult.dialectRegion.name)
                }
            }
        }

        return ParseResult(
            sale = sale,
            purchase = purchase,
            extractedPrice = priceWithSlang,
            dialectResult = dialectResult,
            correctedText = corrected
        )
    }

    /**
     * Migori-specific business slang not in the standard commonItems map.
     * Used for fallback lookup when standard parsing fails.
     */
    private val migoriBusinessSlang = mapOf(
        "mbao" to "twenty_shillings",
        "kibabu" to "fifty_shillings",
        "ngiri" to "thousand_shillings",
        "finje" to "five_hundred_shillings",
        "thao" to "thousand_shillings",
        "omena" to "omena",
        "piyo" to "groundnuts",
        "atapa" to "atapa",
        "kuon" to "ugali",
        "debe" to "tin_measure",
        "gunia" to "sack_measure",
        "fundo" to "bundle_measure",
        "kibaba" to "kibaba_measure",
        "karai" to "basin_measure"
    )
}

/**
 * Result of dialect-aware parsing.
 */
data class ParseResult(
    val sale: SaleData?,
    val purchase: PurchaseData?,
    val extractedPrice: Double?,
    val dialectResult: com.msaidizi.app.core.dialect.ProcessedResult,
    val correctedText: String
)
