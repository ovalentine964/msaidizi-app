package com.msaidizi.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ReceiptScannerCV — CameraX + ML Kit powered receipt scanner.
 *
 * Uses ML Kit Text Recognition to extract text from receipt images,
 * then parses the raw OCR output into structured receipt data
 * (items, quantities, prices, total).
 *
 * Complements the existing [ReceiptScanner] (which parses pre-OCR'd text)
 * by adding the vision layer: image → OCR → parse.
 */
@Singleton
class ReceiptScannerCV @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "scan_receipt"
    override val description = "Scan a receipt image using ML Kit OCR and extract items, quantities, prices, and total"

    override val argsSchema = argSchema {
        enum("action", "Scanner action", listOf("scan_image", "scan_text"), required = false)
        string("image_uri", "Content URI of the receipt image to scan", required = false)
        string("text", "Raw OCR text to parse (skip ML Kit if provided)", required = false)
    }

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "scan_image"
        return when (action.lowercase()) {
            "scan_image" -> scanFromImage(params)
            "scan_text" -> scanFromText(params)
            else -> ToolResult.error(name, "Unknown action: $action. Use 'scan_image' or 'scan_text'.", "INVALID_ACTION")
        }
    }

    /**
     * Full pipeline: image URI → ML Kit OCR → parse → structured result.
     */
    private suspend fun scanFromImage(params: Map<String, String>): ToolResult {
        val uriString = params["image_uri"]
            ?: return ToolResult.error(name, "image_uri required for scan_image action", "MISSING_URI")

        return try {
            val uri = Uri.parse(uriString)
            val image = inputImageFromUri(uri)
                ?: return ToolResult.error(name, "Could not load image from URI: $uriString", "IMAGE_LOAD_ERROR")

            val visionText = recognizeText(image)
            val rawText = visionText.text

            if (rawText.isBlank()) {
                return ToolResult.error(name, "ML Kit could not extract any text from the image", "NO_TEXT_FOUND")
            }

            Timber.d("ML Kit extracted ${rawText.length} chars from receipt image")
            parseReceiptText(rawText)
        } catch (e: Exception) {
            Timber.e(e, "Receipt CV scan failed")
            ToolResult.error(name, "Scan failed: ${e.message}", "SCAN_ERROR")
        }
    }

    /**
     * Text-only path: parse raw OCR text (same as ReceiptScanner but with
     * richer parsing heuristics for Kenyan receipts).
     */
    private suspend fun scanFromText(params: Map<String, String>): ToolResult {
        val text = params["text"]
            ?: return ToolResult.error(name, "text required for scan_text action", "MISSING_TEXT")
        return parseReceiptText(text)
    }

    /**
     * Create an [InputImage] from a content URI.
     */
    private suspend fun inputImageFromUri(uri: Uri): InputImage? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) InputImage.fromBitmap(bitmap, 0) else null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create InputImage from URI: $uri")
            null
        }
    }

    /**
     * Run ML Kit text recognition, suspending until complete.
     */
    private suspend fun recognizeText(image: InputImage): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { cont ->
            textRecognizer.process(image)
                .addOnSuccessListener { text ->
                    if (cont.isActive) cont.resume(text)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    // ──────────────────────────────────────────────
    // Receipt Parsing Heuristics
    // ──────────────────────────────────────────────

    /**
     * Parse raw OCR text into structured receipt data.
     *
     * Handles common Kenyan receipt formats:
     *  - "Item Name    2    150.00    300.00"
     *  - "Item Name x2 @150 = 300"
     *  - "Item Name  2@150  300"
     *  - Lines with KES/Ksh amounts
     */
    private fun parseReceiptText(rawText: String): ToolResult {
        val lines = rawText.lines().filter { it.isNotBlank() }
        val items = mutableListOf<ParsedReceiptItem>()
        var total: Double? = null
        var merchantName: String? = null

        // Try to extract merchant name (usually the first few non-numeric lines)
        for (line in lines.take(5)) {
            val cleaned = line.trim()
            if (cleaned.length in 3..60 &&
                !cleaned.contains(Regex("\\d{2,}")) &&
                !cleaned.lowercase().contains("receipt") &&
                !cleaned.lowercase().contains("tax") &&
                !cleaned.lowercase().contains("pin")
            ) {
                merchantName = cleaned
                break
            }
        }

        // Pattern 1: "ItemName   qty   unitPrice   totalPrice"
        val patternQtyPrice = Regex("""^(.+?)\s+(\d+)\s+([\d,.]+)\s+([\d,.]+)$""")

        // Pattern 2: "ItemName  qty@unitPrice  totalPrice" or "ItemName x qty @ unitPrice = total"
        val patternAt = Regex("""^(.+?)\s+(?:x\s*)?(\d+)\s*@\s*([\d,.]+)\s*[=×x]?\s*([\d,.]+)?$""")

        // Pattern 3: "ItemName   amount" (single amount, qty=1 assumed)
        val patternSimple = Regex("""^(.+?)\s+K?[Ee]?[Ss]?\s*([\d,.]+)$""")

        // Pattern 4: "ItemName   amount" (last number on line is the price)
        val patternLastNumber = Regex("""^(.+?)\s+([\d,.]+)\s*$""")

        for (line in lines) {
            val cleaned = line.trim()

            // Skip header/footer lines
            if (isHeaderOrFooter(cleaned)) continue

            // Try total line
            val totalMatch = Regex("""(?i)(?:total|grand\s*total|amount\s*due|net\s*amount|balance)\s*:?\s*K?[Ee]?[Ss]?\s*([\d,.]+)""").find(cleaned)
            if (totalMatch != null) {
                total = parseAmount(totalMatch.groupValues[1])
                continue
            }

            // Try pattern 1: qty + unit price + total
            patternQtyPrice.find(cleaned)?.let { match ->
                val item = match.groupValues[1].trim()
                val qty = match.groupValues[2].toIntOrNull() ?: 1
                val unitPrice = parseAmount(match.groupValues[3])
                val lineTotal = parseAmount(match.groupValues[4])
                if (item.isNotEmpty() && unitPrice != null && qty > 0) {
                    items.add(ParsedReceiptItem(item, qty, unitPrice, lineTotal ?: (unitPrice * qty)))
                    return@let
                }
            }
            if (items.isNotEmpty() && patternQtyPrice.find(cleaned) != null) continue

            // Try pattern 2: qty@price
            patternAt.find(cleaned)?.let { match ->
                val item = match.groupValues[1].trim()
                val qty = match.groupValues[2].toIntOrNull() ?: 1
                val unitPrice = parseAmount(match.groupValues[3])
                val lineTotal = if (match.groupValues[4].isNotEmpty()) parseAmount(match.groupValues[4]) else null
                if (item.isNotEmpty() && unitPrice != null && qty > 0) {
                    items.add(ParsedReceiptItem(item, qty, unitPrice, lineTotal ?: (unitPrice * qty)))
                    return@let
                }
            }
            if (items.isNotEmpty() && patternAt.find(cleaned) != null) continue

            // Try KES prefix pattern
            patternSimple.find(cleaned)?.let { match ->
                val item = match.groupValues[1].trim()
                val amount = parseAmount(match.groupValues[2])
                if (item.isNotEmpty() && amount != null && amount > 0) {
                    items.add(ParsedReceiptItem(item, 1, amount, amount))
                }
            }
            if (items.isNotEmpty() && patternSimple.find(cleaned) != null) continue

            // Fallback: last number on line is the price
            if (items.isNotEmpty()) {  // only after we've found at least one item
                patternLastNumber.find(cleaned)?.let { match ->
                    val item = match.groupValues[1].trim()
                    val amount = parseAmount(match.groupValues[2])
                    if (item.length >= 2 && amount != null && amount > 0 && !isHeaderOrFooter(item)) {
                        items.add(ParsedReceiptItem(item, 1, amount, amount))
                    }
                }
            }
        }

        // Calculate total from items if not found in OCR
        val computedTotal = total ?: items.sumOf { it.lineTotal }

        return if (items.isNotEmpty()) {
            val summary = items.joinToString("\n") { item ->
                if (item.quantity > 1) {
                    "  ${item.name}: ${item.quantity} × Ksh %,.0f = Ksh %,.0f".format(item.unitPrice, item.lineTotal)
                } else {
                    "  ${item.name}: Ksh %,.0f".format(item.lineTotal)
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "merchant" to (merchantName ?: "Unknown"),
                    "items" to items.map { mapOf(
                        "name" to it.name,
                        "quantity" to it.quantity,
                        "unit_price" to it.unitPrice,
                        "line_total" to it.lineTotal
                    )},
                    "item_count" to items.size,
                    "total" to computedTotal,
                    "raw_text_length" to rawText.length
                ),
                message = buildString {
                    appendLine("Receipt scanned (${items.size} items, total: Ksh %,.0f)".format(computedTotal))
                    merchantName?.let { appendLine("Merchant: $it") }
                    appendLine(summary)
                }
            )
        } else {
            ToolResult.error(name, "Could not parse any items from the receipt text", "PARSE_ERROR")
        }
    }

    /**
     * Parse a Kenyan-formatted amount string (commas, periods).
     */
    private fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace(",", "").replace(" ", "").trim()
        return cleaned.toDoubleOrNull()
    }

    /**
     * Heuristic: is this line a header, footer, or metadata line?
     */
    private fun isHeaderOrFooter(line: String): Boolean {
        val lower = line.lowercase()
        val keywords = listOf(
            "receipt", "tax invoice", "vat", "pin", "tel:", "phone",
            "date:", "time:", "cashier", "change", "cash", "mpesa",
            "card", "visa", "mastercard", "thank you", "karibu",
            "welcome", "served by", "transaction", "ref:", "serial",
            "customer", "copy", "original", "---", "===", "***",
            "subtotal", "sub total", "discount", "balance"
        )
        return keywords.any { lower.contains(it) } || line.length < 3
    }
}

/**
 * Parsed line item from a receipt.
 */
data class ParsedReceiptItem(
    val name: String,
    val quantity: Int,
    val unitPrice: Double,
    val lineTotal: Double
)
