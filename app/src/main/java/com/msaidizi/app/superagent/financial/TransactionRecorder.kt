package com.msaidizi.app.superagent.financial

import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Voice-based transaction recording engine.
 *
 * Parses natural language (Swahili/Sheng) transaction descriptions into
 * structured [Transaction] objects. Extracts item, quantity, price, and
 * context from voice input.
 *
 * ## Voice Input Patterns
 *
 * **Sales:** "Nimeuziwa mandazi kumi, mia mbili"
 * → item=mandazi, qty=10, totalAmount=200
 *
 * **Purchases:** "Nimenunua unga wa ugali, magunia mawili, elfu moja"
 * → item=unga wa ugali, qty=2, totalAmount=1000
 *
 * **Expenses:** "Nimetumia mia tano kwa usafiri"
 * → category=transport, totalAmount=500
 *
 * ## Data Completeness
 *
 * The M-KOPA model requires complete data for the backend intelligence engine.
 * If the worker omits price, the recorder flags it for follow-up:
 * "Bei ngapi?" (How much?)
 *
 * @author Msaidizi Financial Team
 */
class TransactionRecorder {

    companion object {
        private const val TAG = "TransactionRecorder"

        /** Minimum data completeness score for auto-acceptance */
        private const val MIN_COMPLETENESS_SCORE = 0.5f

        /** Swahili number word mappings */
        private val SWAHILI_NUMBERS = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "kumi na moja" to 11, "kumi na mbili" to 12, "kumi na tatu" to 13,
            "kumi na nne" to 14, "kumi na tano" to 15, "kumi na sita" to 16,
            "kumi na saba" to 17, "kumi na nane" to 18, "kumi na tisa" to 19,
            "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
            "hamsini" to 50, "sitini" to 60, "sabini" to 70,
            "themanini" to 80, "tisini" to 90
        )

        /** Swahili magnitude words */
        private val SWAHILI_MAGNITUDES = mapOf(
            "mia" to 100, "elfu" to 1000, "laki" to 100_000
        )

        /** Common Sheng/Swahili sale verbs */
        private val SALE_VERBS = listOf(
            "nimeuziwa", "nimeuza", "nauza", "nimesell", "nimesell",
            "nimepatia", "nimepata", "wamenunua", "wamenunulia"
        )

        /** Common Sheng/Swahili purchase verbs */
        private val PURCHASE_VERBS = listOf(
            "nimenunua", "nimenunulia", "nimenunulie", "nimenunukia",
            "nimebuy", "nimechukua", "nimepata"
        )

        /** Common Sheng/Swahili expense verbs */
        private val EXPENSE_VERBS = listOf(
            "nimetumia", "nimespend", "nimepay", "nimelipia",
            "nimeenda", "nimechange"
        )

        /** Expense categories */
        private val EXPENSE_CATEGORIES = mapOf(
            "usafiri" to "transport", "transport" to "transport",
            "matatu" to "transport", "boda" to "transport", "fare" to "transport",
            "kodi" to "rent", "rent" to "rent", "house" to "rent",
            "stima" to "utilities", "umeme" to "utilities", "water" to "utilities",
            "maji" to "utilities", "token" to "utilities",
            "chakula" to "food", "food" to "food", "lunch" to "food",
            "lunch" to "food", "breakfast" to "food", "supper" to "food",
            "mshahara" to "salary", "salary" to "salary", "worker" to "salary",
            "msaada" to "misc", "misc" to "misc"
        )

        /** Common item name normalizations */
        private val ITEM_ALIASES = mapOf(
            "nyanya" to "nyanya", "tomato" to "nyanya", "tomatoes" to "nyanya",
            "vitunguu" to "vitunguu", "onion" to "vitunguu", "onions" to "vitunguu",
            "ndizi" to "ndizi", "banana" to "ndizi", "bananas" to "ndizi",
            "viazi" to "viazi", "potato" to "viazi", "potatoes" to "viazi",
            "sukari" to "sukari", "sugar" to "sukari",
            "unga" to "unga", "flour" to "unga",
            "mafuta" to "mafuta", "oil" to "mafuta",
            "chai" to "chai", "tea" to "chai",
            "mandazi" to "mandazi", "chapati" to "chapati",
            "maziwa" to "maziwa", "milk" to "maziwa",
            "mayai" to "mayai", "eggs" to "mayai", "egg" to "mayai"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // TRANSACTION RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a voice input string into a structured [Transaction].
     *
     * @param voiceInput Raw text from speech-to-text (Swahili/Sheng)
     * @param language Language code (default "sw" for Swahili)
     * @return [RecordedTransaction] with parsed data and completeness info
     */
    fun recordFromVoice(voiceInput: String, language: String = "sw"): RecordedTransaction {
        Timber.tag(TAG).d("Parsing voice input: %s", voiceInput)

        val normalized = voiceInput.lowercase().trim()

        // Detect transaction type from verb
        val type = detectTransactionType(normalized)

        // Extract components based on type
        val result = when (type) {
            TransactionType.SALE -> parseSale(normalized, language)
            TransactionType.PURCHASE -> parsePurchase(normalized, language)
            TransactionType.EXPENSE -> parseExpense(normalized, language)
            else -> parseGeneric(normalized, language, type)
        }

        // Calculate data completeness
        val completeness = calculateCompleteness(result)

        // Generate follow-up questions for missing data
        val followUps = generateFollowUpQuestions(result, completeness)

        // Generate confirmation message
        val confirmation = generateConfirmation(result)

        Timber.tag(TAG).d(
            "Recorded: type=%s, item=%s, amount=%.0f, completeness=%.2f",
            result.type, result.item, result.totalAmount, completeness
        )

        return RecordedTransaction(
            transaction = result,
            confirmationMessage = confirmation,
            dataCompleteness = completeness,
            followUpQuestions = followUps
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // TYPE DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Detect transaction type from verb in the input text.
     */
    private fun detectTransactionType(input: String): TransactionType {
        return when {
            SALE_VERBS.any { input.contains(it) } -> TransactionType.SALE
            PURCHASE_VERBS.any { input.contains(it) } -> TransactionType.PURCHASE
            EXPENSE_VERBS.any { input.contains(it) } -> TransactionType.EXPENSE
            else -> TransactionType.OTHER
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SALE PARSING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a sale transaction.
     *
     * Pattern: "Nimeuziwa [item] [quantity], [price]"
     * Example: "Nimeuziwa mandazi kumi, mia mbili"
     */
    private fun parseSale(input: String, language: String): Transaction {
        // Remove sale verb prefix
        var remaining = input
        SALE_VERBS.forEach { verb ->
            remaining = remaining.replace(verb, "").trim()
        }

        // Extract quantity and item
        val (item, quantity, restAfterItem) = extractItemAndQuantity(remaining)

        // Extract total amount
        val totalAmount = extractAmount(restAfterItem.ifEmpty { remaining })

        // Calculate unit price
        val unitPrice = if (quantity > 0 && totalAmount > 0) totalAmount / quantity else 0.0

        return Transaction(
            type = TransactionType.SALE,
            item = normalizeItemName(item),
            quantity = quantity,
            unitPrice = unitPrice,
            totalAmount = totalAmount,
            language = language,
            confidence = 0.85f // Base confidence for parsed sales
        )
    }

    /**
     * Parse a purchase transaction.
     *
     * Pattern: "Nimenunua [item] [quantity], [price]"
     * Example: "Nimenunua unga wa ugali, magunia mawili, elfu moja"
     */
    private fun parsePurchase(input: String, language: String): Transaction {
        var remaining = input
        PURCHASE_VERBS.forEach { verb ->
            remaining = remaining.replace(verb, "").trim()
        }

        val (item, quantity, restAfterItem) = extractItemAndQuantity(remaining)
        val totalAmount = extractAmount(restAfterItem.ifEmpty { remaining })
        val unitPrice = if (quantity > 0 && totalAmount > 0) totalAmount / quantity else 0.0

        return Transaction(
            type = TransactionType.PURCHASE,
            item = normalizeItemName(item),
            quantity = quantity,
            unitPrice = unitPrice,
            totalAmount = totalAmount,
            costBasis = totalAmount,
            language = language,
            confidence = 0.85f
        )
    }

    /**
     * Parse an expense transaction.
     *
     * Pattern: "Nimetumia [amount] kwa [category]"
     * Example: "Nimetumia mia tano kwa usafiri"
     */
    private fun parseExpense(input: String, language: String): Transaction {
        var remaining = input
        EXPENSE_VERBS.forEach { verb ->
            remaining = remaining.replace(verb, "").trim()
        }

        val totalAmount = extractAmount(remaining)
        val category = extractExpenseCategory(remaining)

        return Transaction(
            type = TransactionType.EXPENSE,
            item = category.ifEmpty { "matumizi" },
            category = category,
            totalAmount = totalAmount,
            language = language,
            confidence = 0.80f
        )
    }

    /**
     * Parse a generic transaction when type is unclear.
     */
    private fun parseGeneric(input: String, language: String, type: TransactionType): Transaction {
        val totalAmount = extractAmount(input)
        val (item, quantity, _) = extractItemAndQuantity(input)

        return Transaction(
            type = type,
            item = normalizeItemName(item.ifEmpty { "unknown" }),
            quantity = quantity,
            totalAmount = totalAmount,
            language = language,
            confidence = 0.60f
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // EXTRACTION HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract item name and quantity from text.
     * Returns Triple(itemName, quantity, remainingText)
     */
    private fun extractItemAndQuantity(text: String): Triple<String, Double, String> {
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return Triple("", 1.0, "")

        var itemParts = mutableListOf<String>()
        var quantity = 1.0
        var amountStartIndex = words.size
        var foundQuantity = false

        for (i in words.indices) {
            val word = words[i]

            // Check if this is a number word
            val numberValue = parseSwahiliNumber(word)
            if (numberValue != null && !foundQuantity) {
                quantity = numberValue.toDouble()
                foundQuantity = true
                continue
            }

            // Check if this starts an amount phrase
            if (isAmountPhrase(words, i)) {
                amountStartIndex = i
                break
            }

            // Check if it's a quantity unit (magunia, vipande, etc.)
            if (isQuantityUnit(word) && !foundQuantity) {
                // Next word might be the number
                continue
            }

            // Otherwise it's part of the item name
            itemParts.add(word)
        }

        val item = itemParts.joinToString(" ").trim()
        val remaining = if (amountStartIndex < words.size) {
            words.subList(amountStartIndex, words.size).joinToString(" ")
        } else ""

        return Triple(item, quantity, remaining)
    }

    /**
     * Extract monetary amount from text.
     * Handles Swahili number phrases: "mia mbili" = 200, "elfu moja" = 1000
     */
    fun extractAmount(text: String): Double {
        val normalized = text.lowercase().trim()
        val words = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }

        var total = 0.0
        var current = 0.0
        var i = 0

        while (i < words.size) {
            val word = words[i]

            // Check for magnitude words (mia, elfu, laki)
            val magnitude = SWAHILI_MAGNITUDES[word]
            if (magnitude != null) {
                // Look ahead for the number after magnitude
                if (i + 1 < words.size) {
                    val nextNumber = parseSwahiliNumber(words[i + 1])
                    if (nextNumber != null) {
                        total += magnitude * nextNumber
                        i += 2
                        continue
                    }
                }
                // Magnitude alone (e.g., "mia" after a number)
                if (current > 0) {
                    total += current * magnitude
                    current = 0.0
                    i++
                    continue
                }
                // "mia" by itself means 100
                total += magnitude
                i++
                continue
            }

            // Check for regular Swahili numbers
            val number = parseSwahiliNumber(word)
            if (number != null) {
                current = number.toDouble()
                i++
                continue
            }

            // Check for Arabic numerals
            val arabicNumber = word.toDoubleOrNull()
            if (arabicNumber != null) {
                total += arabicNumber
                i++
                continue
            }

            i++
        }

        // Add any remaining current value
        total += current

        return total
    }

    /**
     * Parse a Swahili number word to its integer value.
     */
    private fun parseSwahiliNumber(word: String): Int? {
        return SWAHILI_NUMBERS[word]
    }

    /**
     * Check if a word position starts an amount phrase.
     * Amount phrases typically follow commas or "kwa" or come at the end.
     */
    private fun isAmountPhrase(words: List<String>, index: Int): Boolean {
        val word = words[index].lowercase()
        // Amount phrases often start with magnitude words
        if (SWAHILI_MAGNITUDES.containsKey(word)) return true
        // Or after "kwa" (for expenses: "kwa usafiri")
        if (index > 0 && words[index - 1].lowercase() == "kwa") return true
        return false
    }

    /**
     * Check if a word is a quantity unit.
     */
    private fun isQuantityUnit(word: String): Boolean {
        val units = listOf(
            "magunia", "gunia", "kilo", "kg", "lita", "liters",
            "vipande", "kande", "mifuko", "mfuko", "bao", "mabao",
            "vikombe", "kikombe", "chupa", "machupa", "bunduki"
        )
        return units.contains(word.lowercase())
    }

    /**
     * Extract expense category from text.
     */
    private fun extractExpenseCategory(text: String): String {
        val words = text.lowercase().split("\\s+".toRegex())
        for (word in words) {
            EXPENSE_CATEGORIES[word]?.let { return it }
        }
        return "misc"
    }

    /**
     * Normalize item name using known aliases.
     */
    private fun normalizeItemName(item: String): String {
        if (item.isBlank()) return "unknown"
        val lower = item.lowercase().trim()
        return ITEM_ALIASES[lower] ?: lower
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA COMPLETENESS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate how complete the extracted transaction data is.
     * Returns a score from 0.0 (empty) to 1.0 (fully complete).
     *
     * The M-KOPA model requires complete data for the backend intelligence engine.
     */
    private fun calculateCompleteness(transaction: Transaction): Float {
        var score = 0f
        var maxScore = 0f

        // Has item name (weight: 0.25)
        maxScore += 0.25f
        if (transaction.item.isNotBlank() && transaction.item != "unknown") {
            score += 0.25f
        }

        // Has quantity (weight: 0.15)
        maxScore += 0.15f
        if (transaction.quantity > 0) {
            score += 0.15f
        }

        // Has total amount (weight: 0.30)
        maxScore += 0.30f
        if (transaction.totalAmount > 0) {
            score += 0.30f
        }

        // Has category (weight: 0.10)
        maxScore += 0.10f
        if (transaction.category.isNotBlank()) {
            score += 0.10f
        }

        // Has payment method (weight: 0.10)
        maxScore += 0.10f
        if (transaction.paymentMethod != "cash") { // "cash" is default, not explicitly given
            score += 0.05f
        }
        score += 0.05f // Cash is always a valid assumption

        // Has location (weight: 0.10)
        maxScore += 0.10f
        if (transaction.locationName.isNotBlank() ||
            (transaction.locationLat != null && transaction.locationLng != null)
        ) {
            score += 0.10f
        }

        return if (maxScore > 0) (score / maxScore).coerceIn(0f, 1f) else 0f
    }

    /**
     * Generate follow-up questions for missing data fields.
     * Returns questions in the worker's language.
     */
    private fun generateFollowUpQuestions(
        transaction: Transaction,
        completeness: Float
    ): List<String> {
        val questions = mutableListOf<String>()

        if (transaction.totalAmount <= 0) {
            questions.add("Bei ngapi?") // How much?
        }

        if (transaction.quantity <= 0 || transaction.quantity == 1.0) {
            if (transaction.type == TransactionType.SALE || transaction.type == TransactionType.PURCHASE) {
                questions.add("Ngapi? Jinsi gani?") // How many?
            }
        }

        if (transaction.item.isBlank() || transaction.item == "unknown") {
            questions.add("Nini? Kitu gani?") // What item?
        }

        return questions
    }

    /**
     * Generate a Swahili confirmation message for the recorded transaction.
     */
    private fun generateConfirmation(transaction: Transaction): String {
        val item = transaction.item.replaceFirstChar { it.uppercase() }
        val amount = formatAmount(transaction.totalAmount)

        return when (transaction.type) {
            TransactionType.SALE -> {
                val qty = if (transaction.quantity > 1) " ${transaction.quantity.toInt()}" else ""
                "Umeuza$item $qty kwa KSh $amount. Imerekodiwa!"
            }
            TransactionType.PURCHASE -> {
                val qty = if (transaction.quantity > 1) " ${transaction.quantity.toInt()}" else ""
                "Umenunua$qty $item kwa KSh $amount. Imerekodiwa!"
            }
            TransactionType.EXPENSE -> {
                "Umetumia KSh $amount kwa $item. Imerekodiwa!"
            }
            else -> {
                "Imerekodiwa: $item, KSh $amount."
            }
        }
    }

    /**
     * Format a monetary amount for display.
     */
    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}
