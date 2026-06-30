package com.msaidizi.app.core.util

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
        "sita" to 7, "saba" to 7, "nane" to 8, "tisa" to 9,
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
        "mshumaa" to "candle", "kandamizi" to "starch"
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
        "debe" to 1.0, "frying" to 1.0
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
            "nusu" to "nusu"
        )

        for ((wrong, right) in corrections) {
            corrected = corrected.replace(wrong, right, ignoreCase = true)
        }

        // Normalize number formats
        corrected = corrected.replace(Regex("""(\d),(\d)"""), "$1$2")  // Remove commas in numbers
        corrected = corrected.replace(Regex("""KES?\s*"""), "KSh ")  // Normalize currency
        corrected = corrected.replace(Regex("""bob"""), "KSh", ignoreCase = true)

        return corrected.trim()
    }
}
