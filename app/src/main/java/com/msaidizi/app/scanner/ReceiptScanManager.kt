package com.msaidizi.app.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.result.ActivityResultLauncher
import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.voice.VoicePipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receipt Scan Manager — orchestrates the full receipt scanning flow.
 *
 * Pipeline:
 * 1. Launch camera → capture receipt photo
 * 2. ML Kit OCR → extract text
 * 3. Parse → structured receipt data
 * 4. User confirms/corrects items
 * 5. Create transactions in BusinessAgent
 * 6. Voice feedback: "Nimescan risiti — nilinunua nyanya KSh 200"
 *
 * ## Integration Points
 * - Called from RecordFragment (voice: "Piga picha risiti yako")
 * - Called from IntentRouter (intent: RECEIPT_SCAN)
 * - Called from HomeScreen (scan button)
 *
 * ## Learning Loop
 * When user corrects an item name, the correction is stored in ReceiptScanner
 * so future scans of similar receipts are more accurate.
 *
 * @see ReceiptScanner for OCR + parsing
 * @see ReceiptScanActivity for camera capture
 * @see ReceiptConfirmationFragment for user review
 */
@Singleton
class ReceiptScanManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val businessAgent: BusinessAgent,
    private val receiptScanner: ReceiptScanner,
    private val voicePipeline: VoicePipeline
) {
    // Last scanned receipt for voice reference
    private var lastReceiptData: ReceiptData? = null

    /**
     * Launch the receipt scanner activity.
     *
     * @param launcher Activity result launcher from the calling activity/fragment
     */
    fun launchScanner(launcher: ActivityResultLauncher<Intent>) {
        val intent = ReceiptScanActivity.newIntent(context)
        launcher.launch(intent)
    }

    /**
     * Process the result from ReceiptScanActivity.
     * Returns parsed receipt data for confirmation UI.
     *
     * @param resultCode Activity result code
     * @param data Result intent from ReceiptScanActivity
     * @return Parsed receipt data, or null if scan failed/was cancelled
     */
    fun processScanResult(resultCode: Int, data: Intent?): ReceiptData? {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            return null
        }

        val receiptData = ReceiptScanActivity.parseResult(data)
        if (receiptData != null && receiptData.isValid) {
            lastReceiptData = receiptData
            Timber.d("ReceiptScanManager: Scan successful — %d items, total=%.0f",
                receiptData.items.size, receiptData.total)
        }

        return receiptData
    }

    /**
     * Create transactions from confirmed receipt items.
     * Called after user reviews and confirms in ReceiptConfirmationFragment.
     *
     * @param items Confirmed (and possibly corrected) items
     * @param language Language for transaction recording
     * @return List of created transactions
     */
    suspend fun createTransactionsFromReceipt(
        items: List<EditableReceiptItem>,
        language: String = "sw"
    ): List<Transaction> {
        val transactions = mutableListOf<Transaction>()

        for (item in items) {
            try {
                // Learn corrections
                if (item.isEdited) {
                    receiptScanner.learnCorrection(item.originalName, item.currentName)
                }

                // Create purchase transaction (scanning receipts = purchases)
                val transaction = businessAgent.recordPurchase(
                    item = item.currentName,
                    quantity = item.quantity,
                    amount = item.totalPrice,
                    language = language,
                    confidence = 1.0f
                )
                transactions.add(transaction)

                Timber.d("ReceiptScanManager: Created transaction — %s x%.0f = KSh %.0f",
                    item.currentName, item.quantity, item.totalPrice)
            } catch (e: Throwable) {
                Timber.e(e, "ReceiptScanManager: Failed to create transaction for %s", item.currentName)
            }
        }

        // Voice feedback
        speakReceiptSummary(transactions, language)

        return transactions
    }

    /**
     * Speak a summary of the scanned receipt.
     *
     * "Nimescan risiti — nilinunua nyanya KSh 200, vitunguu KSh 100. Jumla: KSh 300."
     */
    private suspend fun speakReceiptSummary(transactions: List<Transaction>, language: String) {
        try {
            val summary = if (language == "sw") {
                buildString {
                    append("Nimescan risiti. ")
                    if (transactions.isNotEmpty()) {
                        append("Umenunua ")
                        append(transactions.joinToString(", ") {
                            "${it.item} KSh ${"%.0f".format(it.totalAmount)}"
                        })
                        append(". ")
                    }
                    val total = transactions.sumOf { it.totalAmount }
                    append("Jumla: KSh ${"%.0f".format(total)}.")
                }
            } else {
                buildString {
                    append("Receipt scanned. ")
                    if (transactions.isNotEmpty()) {
                        append("You bought ")
                        append(transactions.joinToString(", ") {
                            "${it.item} KSh ${"%.0f".format(it.totalAmount)}"
                        })
                        append(". ")
                    }
                    val total = transactions.sumOf { it.totalAmount }
                    append("Total: KSh ${"%.0f".format(total)}.")
                }
            }

            voicePipeline.speak(summary, language)
        } catch (e: Throwable) {
            Timber.w(e, "ReceiptScanManager: TTS failed for summary")
        }
    }

    /**
     * Get the last scanned receipt data.
     */
    fun getLastReceipt(): ReceiptData? = lastReceiptData

    /**
     * Scan a bitmap directly (for in-app camera or gallery picks).
     *
     * @param bitmap The receipt image
     * @return Parsed receipt data
     */
    suspend fun scanBitmap(bitmap: Bitmap): ReceiptData? {
        return receiptScanner.scanReceipt(bitmap)
    }

    /**
     * Generate voice prompt for receipt scanning.
     * Used when the user says "scan receipt" or similar.
     */
    fun getScanPrompt(language: String = "sw"): String {
        return if (language == "sw") {
            "Piga picha risiti yako. Weka risiti mbele ya kamera na ubonyeze kitufe cha kamera."
        } else {
            "Take a photo of your receipt. Hold the receipt in front of the camera and press the capture button."
        }
    }

    /**
     * Generate confirmation prompt after scan.
     * Speaks parsed items for the user to confirm.
     */
    fun getConfirmationPrompt(receiptData: ReceiptData, language: String = "sw"): String {
        return receiptData.toSummaryText(language)
    }
}
