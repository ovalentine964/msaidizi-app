package com.msaidizi.app.scanner

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Receipt Scanner — ML Kit OCR + intelligent parsing for Kenyan receipts.
 *
 * Pipeline: Camera Bitmap → ML Kit Text Recognition → Parse → Structured Receipt
 *
 * ## On-Device Processing
 * - ML Kit Latin text recognition: ~260KB, handles English + Swahili
 * - All OCR runs on-device — no internet needed
 * - Works on 2GB phones (ML Kit is optimized for mobile)
 * - Fast: < 2 seconds per receipt
 *
 * ## Kenyan Receipt Handling
 * Supports both:
 * - **Printed receipts**: Supermarkets, dukas, wholesale shops
 * - **Handwritten receipts**: Mama mboga, market vendors, mitumba sellers
 *
 * ## Parsing Strategy
 * 1. Extract all text lines from OCR
 * 2. Look for merchant name (usually first few lines)
 * 3. Find item lines: "item name  qty  price" or "item price"
 * 4. Detect total (keywords: "TOTAL", "JUMLA", "TOTALI", "GHARAMA")
 * 5. Extract date if visible
 * 6. Parse payment method (M-Pesa, cash, etc.)
 *
 * ## Learning from Corrections
 * When a user corrects parsed items, we store the correction pattern
 * to improve future parsing. E.g., if "tmb" is consistently corrected
 * to "tomato", we learn that mapping.
 *
 * @see ReceiptData for the parsed output structure
 */
class ReceiptScanner {

    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    // ── Correction memory: learned item name mappings ──
    // Maps OCR'd text → corrected item name
    private val correctionMemory = mutableMapOf<String, String>()

    // ── Common Kenyan receipt patterns ──

    /** Keywords that indicate a total line */
    private val totalKeywords = listOf(
        "total", "jumla", "totali", "gharama", "grand total",
        "amount due", "balance", "malipo", "pesa", "charge",
        "net amount", "subtotal", "sub total", "sub-total"
    )

    /** Keywords that indicate a date */
    private val dateKeywords = listOf(
        "date", "tarehe", "siku", "created", "on"
    )

    /** Keywords that indicate a merchant/shop name */
    private val merchantKeywords = listOf(
        "supermarket", "store", "shop", "duka", "market", "soko",
        "wholesale", "retail", "enterprises", "trading", "supplies",
        "general", "mini", "mattress", "foods", "agencies"
    )

    /** Common stop words for merchant name detection */
    private val stopWords = setOf(
        "date", "tarehe", "receipt", "risiti", "invoice", "no.", "no",
        "cashier", "serve", "tel", "phone", "p.o.box", "box",
        "vat", "pin", "tin", "location", "road", "street", "st"
    )

    /** M-Pesa patterns in receipts */
    private val mpesaPattern = Pattern.compile(
        "(?i)(mpesa|m-pesa|pesalink|mobile\\s*money|paybill|till\\s*no|till\\s*number)"
    )

    /** Price pattern: matches KSh 200, 200, 200.00, 2,000, etc. */
    private val pricePattern = Pattern.compile(
        "(?:ksh|kes|sh|k)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*(?:/=|-)?"
    )

    /** Quantity pattern: x2, qty 2, 2pcs, etc. */
    private val qtyPattern = Pattern.compile(
        "(?:x|qty|pcs?|pieces?|vifurushi|kilo|kg|ltr|l|pcs)\\s*(\\d+(?:\\.\\d+)?)|^(\\d+(?:\\.\\d+)?)\\s*(?:x|pcs?|kilo|kg|ltr)"
    )

    /**
     * Scan a receipt image and extract structured data.
     *
     * @param bitmap The receipt image
     * @return Parsed receipt data, or null if OCR fails completely
     */
    suspend fun scanReceipt(bitmap: Bitmap): ReceiptData? {
        val startTime = System.currentTimeMillis()

        return try {
            // Step 1: Run ML Kit OCR
            val rawText = runOcr(bitmap)
            if (rawText.isNullOrBlank()) {
                Timber.w("ReceiptScanner: OCR returned no text")
                return null
            }

            Timber.d("ReceiptScanner: OCR completed in %dms, %d chars",
                System.currentTimeMillis() - startTime, rawText.length)

            // Step 2: Parse the raw text
            val parsed = parseReceiptText(rawText)
            Timber.d("ReceiptScanner: Parsed %d items, total=%.0f, merchant=%s",
                parsed.items.size, parsed.total, parsed.merchantName)

            parsed.copy(
                rawOcrText = rawText,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Throwable) {
            Timber.e(e, "ReceiptScanner: Error scanning receipt")
            null
        }
    }

    /**
     * Run ML Kit text recognition on a bitmap.
     * Uses Latin script recognizer (covers English + Swahili).
     */
    private suspend fun runOcr(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (cont.isActive) {
                    cont.resume(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                Timber.e(e, "ReceiptScanner: ML Kit OCR failed")
                if (cont.isActive) {
                    cont.resume(null)
                }
            }
    }

    /**
     * Parse raw OCR text into structured receipt data.
     * Handles both printed and handwritten receipt formats.
     */
    fun parseReceiptText(rawText: String): ReceiptData {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Extract components
        val merchantName = extractMerchantName(lines)
        val date = extractDate(lines)
        val items = extractItems(lines)
        val total = extractTotal(lines) ?: items.sumOf { it.totalPrice }
        val paymentMethod = extractPaymentMethod(rawText)

        return ReceiptData(
            merchantName = merchantName,
            date = date,
            items = items,
            total = total,
            paymentMethod = paymentMethod,
            rawOcrText = rawText,
            processingTimeMs = 0
        )
    }

    /**
     * Extract merchant name — usually in the first few lines.
     * Heuristic: first non-numeric, non-keyword line that looks like a name.
     */
    private fun extractMerchantName(lines: List<String>): String {
        // Check first 5 lines for merchant name
        for (line in lines.take(5)) {
            val lower = line.lowercase().trim()

            // Skip lines that are clearly not merchant names
            if (lower.length < 3) continue
            if (lower.all { it.isDigit() || it == '-' || it == '/' || it == ':' }) continue
            if (dateKeywords.any { lower.contains(it) }) continue
            if (totalKeywords.any { lower.contains(it) }) continue
            if (lower.startsWith("receipt") || lower.startsWith("risiti")) continue
            if (lower.startsWith("tel") || lower.startsWith("phone")) continue
            if (lower.startsWith("p.o") || lower.startsWith("box")) continue
            if (lower.startsWith("pin") || lower.startsWith("vat")) continue
            if (lower.startsWith("cashier") || lower.startsWith("serve")) continue

            // This looks like a merchant name
            return cleanMerchantName(line)
        }
        return ""
    }

    /**
     * Clean merchant name: remove common suffixes and normalize.
     */
    private fun cleanMerchantName(name: String): String {
        var cleaned = name.trim()
        // Remove trailing numbers (receipt numbers mixed in)
        cleaned = cleaned.replace(Regex("\\s*#?\\d{4,}$"), "")
        // Remove trailing punctuation
        cleaned = cleaned.replace(Regex("[.,;:]+$"), "")
        return cleaned.trim()
    }

    /**
     * Extract date from receipt text.
     * Handles formats: DD/MM/YYYY, DD-MM-YYYY, "12 Jul 2024", etc.
     */
    private fun extractDate(lines: List<String>): String {
        // Look for date patterns
        val datePatterns = listOf(
            // DD/MM/YYYY or DD-MM-YYYY
            Pattern.compile("(\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4})"),
            // YYYY-MM-DD
            Pattern.compile("(\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2})"),
            // "12 Jul 2024" or "12 July 2024"
            Pattern.compile("(\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{2,4})", Pattern.CASE_INSENSITIVE),
            // "Jul 12, 2024"
            Pattern.compile("((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{2,4})", Pattern.CASE_INSENSITIVE)
        )

        for (line in lines) {
            for (pattern in datePatterns) {
                val matcher = pattern.matcher(line)
                if (matcher.find()) {
                    return matcher.group(1) ?: continue
                }
            }
        }

        // Default to today
        return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Calendar.getInstance().time)
    }

    /**
     * Extract line items from receipt.
     * Handles multiple formats:
     * - "Nyanya      2kg    200"
     * - "Nyanya 200"
     * - "1. Nyanya x2 @ 100 = 200"
     * - Handwritten: "nyanya...200"
     */
    private fun extractItems(lines: List<String>): List<ReceiptItem> {
        val items = mutableListOf<ReceiptItem>()

        // Skip header lines (merchant, address, date) and footer lines (total, thank you)
        val itemLines = lines.filter { line ->
            val lower = line.lowercase()
            // Skip header/footer
            !totalKeywords.any { lower.contains(it) } &&
            !dateKeywords.any { lower.contains(it) } &&
            !lower.startsWith("receipt") &&
            !lower.startsWith("risiti") &&
            !lower.startsWith("thank") &&
            !lower.startsWith("asante") &&
            !lower.startsWith("karibu") &&
            !lower.startsWith("welcome") &&
            !lower.startsWith("tel") &&
            !lower.startsWith("phone") &&
            !lower.startsWith("cashier") &&
            !lower.startsWith("pin") &&
            !lower.startsWith("vat") &&
            !lower.startsWith("p.o") &&
            !lower.startsWith("date") &&
            !lower.startsWith("tarehe") &&
            !mpesaPattern.matcher(lower).find() &&
            // Must contain at least one digit (price)
            line.contains(Regex("\\d")) &&
            // Must have some text (not just numbers)
            line.contains(Regex("[a-zA-Z\\u00C0-\\u024F]"))
        }

        for (line in itemLines) {
            val item = parseItemLine(line)
            if (item != null) {
                // Apply corrections from memory
                val correctedName = correctionMemory[item.itemName.lowercase()] ?: item.itemName
                items.add(item.copy(itemName = correctedName))
            }
        }

        return items
    }

    /**
     * Parse a single line into a ReceiptItem.
     *
     * Patterns:
     * - "ItemName    Qty    Price"
     * - "ItemName    Price"
     * - "ItemName x2 @ 100 = 200"
     * - "ItemName...200" (handwritten with dots)
     */
    private fun parseItemLine(line: String): ReceiptItem? {
        // Try pattern: "name x qty @ price = total"
        val fullPattern = Pattern.compile(
            "^(.+?)\\s+(?:x|×)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:@|at|@\\s*)?\\s*(?:ksh|kes|sh)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*(?:=|:)\\s*(?:ksh|kes|sh)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE
        )
        var matcher = fullPattern.matcher(line)
        if (matcher.find()) {
            return ReceiptItem(
                itemName = cleanItemName(matcher.group(1) ?: ""),
                quantity = parseNumber(matcher.group(2)),
                unitPrice = parseNumber(matcher.group(3)),
                totalPrice = parseNumber(matcher.group(4))
            )
        }

        // Try pattern: "name qty price" (three columns)
        val threeColPattern = Pattern.compile(
            "^(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s+(?:ksh|kes|sh)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*(?:/=|-)?$",
            Pattern.CASE_INSENSITIVE
        )
        matcher = threeColPattern.matcher(line)
        if (matcher.find()) {
            val qty = parseNumber(matcher.group(2))
            val price = parseNumber(matcher.group(3))
            return ReceiptItem(
                itemName = cleanItemName(matcher.group(1) ?: ""),
                quantity = if (qty > 0) qty else 1.0,
                unitPrice = if (qty > 0) price / qty else price,
                totalPrice = price
            )
        }

        // Try pattern: "name price" (two columns)
        val twoColPattern = Pattern.compile(
            "^(.+?)\\s+(?:ksh|kes|sh)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*(?:/=|-)?$",
            Pattern.CASE_INSENSITIVE
        )
        matcher = twoColPattern.matcher(line)
        if (matcher.find()) {
            val name = cleanItemName(matcher.group(1) ?: "")
            // Avoid false positives: name must have at least 2 chars
            if (name.length >= 2 && !name.all { it.isDigit() }) {
                return ReceiptItem(
                    itemName = name,
                    quantity = 1.0,
                    unitPrice = parseNumber(matcher.group(2)),
                    totalPrice = parseNumber(matcher.group(2))
                )
            }
        }

        // Try pattern with dots: "item name.....200" (handwritten style)
        val dotPattern = Pattern.compile(
            "^(.+?)[.\\-_]{2,}\\s*(?:ksh|kes|sh)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE
        )
        matcher = dotPattern.matcher(line)
        if (matcher.find()) {
            val name = cleanItemName(matcher.group(1) ?: "")
            if (name.length >= 2) {
                return ReceiptItem(
                    itemName = name,
                    quantity = 1.0,
                    unitPrice = parseNumber(matcher.group(2)),
                    totalPrice = parseNumber(matcher.group(2))
                )
            }
        }

        return null
    }

    /**
     * Extract total amount from receipt.
     * Looks for "TOTAL" keyword followed by a number.
     */
    private fun extractTotal(lines: List<String>): Double? {
        for (line in lines.reversed()) { // Search from bottom up
            val lower = line.lowercase()
            if (totalKeywords.any { lower.contains(it) }) {
                // Extract the number after the keyword
                val matcher = pricePattern.matcher(line)
                val numbers = mutableListOf<Double>()
                while (matcher.find()) {
                    val numStr = matcher.group(1)
                    if (numStr != null) {
                        numbers.add(parseNumber(numStr))
                    }
                }
                // The total is usually the largest number on the line
                if (numbers.isNotEmpty()) {
                    return numbers.maxOrNull()
                }
            }
        }
        return null
    }

    /**
     * Extract payment method from receipt text.
     */
    private fun extractPaymentMethod(text: String): String {
        val lower = text.lowercase()
        return when {
            mpesaPattern.matcher(lower).find() -> "mpesa"
            lower.contains("cash") || lower.contains("pesa taslimu") -> "cash"
            lower.contains("credit") || lower.contains("mkopo") -> "credit"
            lower.contains("bank") || lower.contains("transfer") -> "bank"
            else -> "cash" // Default for Kenyan informal market
        }
    }

    /**
     * Clean item name: remove leading numbers, dots, bullets.
     */
    private fun cleanItemName(name: String): String {
        var cleaned = name.trim()
        // Remove leading numbers/bullets: "1.", "•", "-"
        cleaned = cleaned.replace(Regex("^\\s*\\d+[.):]\\s*"), "")
        cleaned = cleaned.replace(Regex("^\\s*[•\\-–—]\\s*"), "")
        // Remove trailing punctuation
        cleaned = cleaned.replace(Regex("[.,;:]+$"), "")
        // Normalize whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        return cleaned.trim()
    }

    /**
     * Parse a number string, handling commas and edge cases.
     */
    private fun parseNumber(str: String?): Double {
        if (str == null) return 0.0
        return try {
            str.replace(",", "").replace(" ", "").replace("=", "").toDouble()
        } catch (e: NumberFormatException) {
            0.0
        }
    }

    /**
     * Learn from a user correction.
     * Stores the mapping so future scans are more accurate.
     *
     * @param ocrText The original OCR'd text
     * @param correctedName What the user corrected it to
     */
    fun learnCorrection(ocrText: String, correctedName: String) {
        val key = ocrText.lowercase().trim()
        correctionMemory[key] = correctedName.trim()
        Timber.d("ReceiptScanner: Learned correction '%s' → '%s'", key, correctedName)
    }

    /**
     * Batch learn corrections from a list of (original, corrected) pairs.
     */
    fun learnCorrections(corrections: List<Pair<String, String>>) {
        corrections.forEach { (original, corrected) ->
            learnCorrection(original, corrected)
        }
    }

    /**
     * Release ML Kit resources.
     */
    fun close() {
        textRecognizer.close()
    }
}

/**
 * Parsed receipt data.
 */
data class ReceiptData(
    val merchantName: String = "",
    val date: String = "",
    val items: List<ReceiptItem> = emptyList(),
    val total: Double = 0.0,
    val paymentMethod: String = "cash",
    val rawOcrText: String = "",
    val processingTimeMs: Long = 0
) {
    /** Whether the receipt has any useful data */
    val isValid: Boolean get() = items.isNotEmpty() || total > 0

    companion object {
        /**
         * Parse ReceiptData from a ReceiptScanActivity result intent.
         */
        fun fromIntent(data: android.content.Intent?): ReceiptData? {
            if (data == null) return null
            val items = data.getParcelableArrayListExtra<ReceiptItemParcel>(
                ReceiptScanActivity.EXTRA_RECEIPT_DATA
            )
            val rawText = data.getStringExtra(ReceiptScanActivity.EXTRA_RAW_OCR_TEXT) ?: ""
            if (items.isNullOrEmpty() && rawText.isBlank()) return null

            return ReceiptData(
                merchantName = data.getStringExtra("merchant_name") ?: "",
                date = data.getStringExtra("date") ?: "",
                items = items?.map { it.toReceiptItem() } ?: emptyList(),
                total = data.getDoubleExtra("total", 0.0),
                paymentMethod = data.getStringExtra("payment_method") ?: "cash",
                rawOcrText = rawText
            )
        }
    }

    /** Summary text for voice feedback */
    fun toSummaryText(language: String = "sw"): String {
        return if (language == "sw") {
            buildString {
                append("📋 Nimesoma risiti")
                if (merchantName.isNotBlank()) append(" kutoka $merchantName")
                append(". ")
                if (items.isNotEmpty()) {
                    append("Bidhaa: ")
                    append(items.joinToString(", ") { "${it.itemName} KSh ${"%.0f".format(it.totalPrice)}" })
                    append(". ")
                }
                if (total > 0) append("Jumla: KSh ${"%.0f".format(total)}")
            }
        } else {
            buildString {
                append("📋 Receipt scanned")
                if (merchantName.isNotBlank()) append(" from $merchantName")
                append(". ")
                if (items.isNotEmpty()) {
                    append("Items: ")
                    append(items.joinToString(", ") { "${it.itemName} KSh ${"%.0f".format(it.totalPrice)}" })
                    append(". ")
                }
                if (total > 0) append("Total: KSh ${"%.0f".format(total)}")
            }
        }
    }
}

/**
 * A single line item from a receipt.
 */
data class ReceiptItem(
    val itemName: String,
    val quantity: Double = 1.0,
    val unitPrice: Double = 0.0,
    val totalPrice: Double = 0.0,
    val category: String = ""
)
