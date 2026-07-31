package com.msaidizi.agent.graph

import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProductResolver — Contextual entity resolution for product names.
 *
 * Resolves user-input product names (Swahili, English, Sheng, misspelled)
 * to canonical product categories using multiple signals:
 *   1. Exact synonym match (Swahili/English product names)
 *   2. Fuzzy string match (Levenshtein distance for typos)
 *   3. Phonetic match (Swahili Soundex for spoken input)
 *   4. Partial/substring match (e.g., "fresh tomatoes" → "Vegetables")
 *
 * Used by tools that need to identify what product the user is talking about.
 */
@Singleton
class ProductResolver @Inject constructor() {

    /** Resolution method used. */
    enum class Method { EXACT_SYNONYM, FUZZY_STRING, PHONETIC, PARTIAL, NONE }

    /** Result of resolving a product name. */
    data class Result(
        val category: String?,
        val method: Method,
        val confidence: Float
    )

    /** Product synonym map: canonical name → category. */
    private val synonyms: Map<String, String> = buildMap {
        // Vegetables
        listOf("nyanya", "tomato", "tomatoes", "tomatoe").forEach { put(it, "Vegetables") }
        listOf("sukuma", "sukuma wiki", "kale", "collard greens").forEach { put(it, "Vegetables") }
        listOf("pilipili", "pepper", "chili").forEach { put(it, "Vegetables") }
        listOf("kabichi", "cabbage").forEach { put(it, "Vegetables") }
        listOf("karoti", "carrot", "carrots").forEach { put(it, "Vegetables") }
        listOf("tango", "cucumber").forEach { put(it, "Vegetables") }
        listOf("vitunguu", "onion", "onions").forEach { put(it, "Vegetables") }
        listOf("viazi", "potato", "potatoes").forEach { put(it, "Vegetables") }

        // Fruits
        listOf("maembe", "mango", "mangoes").forEach { put(it, "Fruits") }
        listOf("ndizi", "banana", "bananas").forEach { put(it, "Fruits") }
        listOf("chungwa", "orange", "oranges").forEach { put(it, "Fruits") }
        listOf("nanasi", "pineapple").forEach { put(it, "Fruits") }
        listOf("papai", "pawpaw", "papaya").forEach { put(it, "Fruits") }
        listOf("embe", "avocado").forEach { put(it, "Fruits") }

        // Dairy
        listOf("maziwa", "milk").forEach { put(it, "Dairy") }
        listOf("yoghurt", "yogurt").forEach { put(it, "Dairy") }
        listOf("cheese").forEach { put(it, "Dairy") }
        listOf("eggs", "mayai").forEach { put(it, "Dairy") }

        // Staples
        listOf("unga", "flour", "maize flour").forEach { put(it, "Staples") }
        listOf("rice", "mchele").forEach { put(it, "Staples") }
        listOf("sugar", "sukari").forEach { put(it, "Staples") }
        listOf("salt", "chumvi").forEach { put(it, "Staples") }
        listOf("cooking oil", "mafuta").forEach { put(it, "Staples") }
        listOf("beans", "maharagwe").forEach { put(it, "Staples") }

        // Meat & Fish
        listOf("nyama", "meat", "beef").forEach { put(it, "Meat") }
        listOf("samaki", "fish").forEach { put(it, "Fish") }
        listOf("kuku", "chicken").forEach { put(it, "Meat") }

        // Household
        listOf("sabuni", "soap").forEach { put(it, "Household") }
        listOf("detergent").forEach { put(it, "Household") }
        listOf("toothpaste").forEach { put(it, "Household") }

        // Beverages
        listOf("chai", "tea").forEach { put(it, "Beverages") }
        listOf("kahawa", "coffee").forEach { put(it, "Beverages") }
        listOf("soda", "juice", "maji").forEach { put(it, "Beverages") }

        // Snacks
        listOf("mandazi").forEach { put(it, "Snacks") }
        listOf("samosa").forEach { put(it, "Snacks") }
        listOf("bread", "mkate").forEach { put(it, "Snacks") }
        listOf("biscuit", "cookies").forEach { put(it, "Snacks") }

        // Spices
        listOf("haldi", "turmeric").forEach { put(it, "Spices") }
        listOf("jeera", "cumin").forEach { put(it, "Spices") }
        listOf("pilipili hoho").forEach { put(it, "Spices") }
        listOf("ginger", "tangawizi").forEach { put(it, "Spices") }
        listOf("garlic", "kitunguu saumu").forEach { put(it, "Spices") }
    }

    /** All known categories. */
    fun getKnownCategories(): Set<String> = synonyms.values.toSet()

    /**
     * Resolve a product name to a category.
     */
    suspend fun resolve(input: String): Result {
        val normalized = input.trim().lowercase()
        if (normalized.isBlank()) return Result(null, Method.NONE, 0.0f)

        // Signal 1: Exact synonym match
        synonyms[normalized]?.let { category ->
            return Result(category, Method.EXACT_SYNONYM, 1.0f)
        }

        // Signal 2: Partial/substring match
        for ((synonym, category) in synonyms) {
            if (normalized.contains(synonym) || synonym.contains(normalized)) {
                return Result(category, Method.PARTIAL, 0.7f)
            }
        }

        // Signal 3: Fuzzy string match (Levenshtein)
        var bestMatch: String? = null
        var bestDistance = Int.MAX_VALUE
        for ((synonym, category) in synonyms) {
            val distance = levenshtein(normalized, synonym)
            if (distance < bestDistance && distance <= 2) {
                bestDistance = distance
                bestMatch = category
            }
        }
        if (bestMatch != null) {
            val confidence = (1.0f - bestDistance / 4.0f).coerceIn(0.3f, 0.9f)
            return Result(bestMatch, Method.FUZZY_STRING, confidence)
        }

        // Signal 4: Phonetic match (Swahili Soundex)
        val inputSoundex = swahiliSoundex(normalized)
        if (inputSoundex.isNotEmpty()) {
            for ((synonym, category) in synonyms) {
                if (swahiliSoundex(synonym) == inputSoundex) {
                    return Result(category, Method.PHONETIC, 0.6f)
                }
            }
        }

        return Result(null, Method.NONE, 0.0f)
    }

    /**
     * Resolve multiple product names at once.
     */
    suspend fun resolveAll(inputs: List<String>): Map<String, String?> {
        return inputs.associateWith { resolve(it).category }
    }

    /**
     * Levenshtein edit distance between two strings.
     */
    fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    /**
     * Swahili Soundex — phonetic encoding for Swahili words.
     * Handles Swahili digraphs: ny, ch, sh, ng, th.
     * Returns a 4-character code (padded with '0').
     */
    fun swahiliSoundex(input: String): String {
        if (input.isEmpty()) return ""
        val s = input.lowercase().trim()

        // Step 1: Keep first letter
        val first = s[0]
        val rest = s.substring(1)

        // Step 2: Map Swahili phonemes to digits
        val encoded = StringBuilder()
        encoded.append(first)

        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            val digit = when {
                // Swahili digraphs (check two-char sequences first)
                i + 1 < rest.length && rest.substring(i, i + 2) == "ny" -> { i++; '6' }
                i + 1 < rest.length && rest.substring(i, i + 2) == "ch" -> { i++; '2' }
                i + 1 < rest.length && rest.substring(i, i + 2) == "sh" -> { i++; '2' }
                i + 1 < rest.length && rest.substring(i, i + 2) == "ng" -> { i++; '5' }
                i + 1 < rest.length && rest.substring(i, i + 2) == "th" -> { i++; '3' }
                // Single consonant mappings
                c in "bp" -> '1'
                c in "cskq" -> '2'
                c in "dt" -> '3'
                c == 'r' -> '4'
                c in "gj" -> '5'
                c in "mn" -> '6'
                c in "fvw" -> '7'
                c in "hx" -> '8'
                c == 'z' -> '9'
                c in "aeiou" -> '0' // vowels get 0 (will be removed)
                else -> '0'
            }
            if (digit != '0') encoded.append(digit)
            i++
        }

        // Step 3: Remove consecutive duplicates
        val deduped = StringBuilder()
        deduped.append(encoded[0])
        for (j in 1 until encoded.length) {
            if (encoded[j] != encoded[j - 1]) deduped.append(encoded[j])
        }

        // Step 4: Pad or truncate to 4 characters
        return deduped.toString().padEnd(4, '0').take(4)
    }
}
