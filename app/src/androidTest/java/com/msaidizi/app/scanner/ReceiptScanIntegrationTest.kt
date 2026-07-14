package com.msaidizi.app.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android integration test for receipt scanning.
 * Tests the full pipeline on a real device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptScanIntegrationTest {

    @Test
    fun receiptScanner_initializesSuccessfully() {
        val scanner = ReceiptScanner()
        assertNotNull(scanner)
        scanner.close()
    }

    @Test
    fun receiptData_fromIntent_handlesNull() {
        val result = ReceiptData.fromIntent(null)
        assertNull(result)
    }

    @Test
    fun receiptData_isValid_checksCorrectly() {
        val withItems = ReceiptData(
            items = listOf(ReceiptItem("Item", 1.0, 100.0, 100.0))
        )
        assertTrue(withItems.isValid)

        val withTotal = ReceiptData(total = 500.0)
        assertTrue(withTotal.isValid)

        val empty = ReceiptData()
        assertFalse(empty.isValid)
    }

    @Test
    fun receiptItem_toSummaryText_swahili() {
        val data = ReceiptData(
            merchantName = "Mama Njeri",
            items = listOf(
                ReceiptItem("Nyanya", 2.0, 100.0, 200.0),
                ReceiptItem("Vitunguu", 1.0, 50.0, 50.0)
            ),
            total = 250.0
        )

        val summary = data.toSummaryText("sw")
        assertTrue(summary.contains("Mama Njeri"))
        assertTrue(summary.contains("Nyanya"))
        assertTrue(summary.contains("250"))
    }

    @Test
    fun receiptItem_toSummaryText_english() {
        val data = ReceiptData(
            merchantName = "Quickmart",
            items = listOf(
                ReceiptItem("Bread", 1.0, 70.0, 70.0)
            ),
            total = 70.0
        )

        val summary = data.toSummaryText("en")
        assertTrue(summary.contains("Quickmart"))
        assertTrue(summary.contains("Bread"))
    }

    @Test
    fun receiptScanner_parseReceiptText_basicReceipt() {
        val scanner = ReceiptScanner()
        val text = """
            Mini Shop
            Nyanya 100
            Vitunguu 50
            Total 150
        """.trimIndent()

        val result = scanner.parseReceiptText(text)
        assertTrue(result.items.isNotEmpty())
        assertEquals(150.0, result.total, 1.0)
        scanner.close()
    }

    @Test
    fun receiptScanner_learnCorrection_storesMapping() {
        val scanner = ReceiptScanner()
        scanner.learnCorrection("tmto", "tomato")

        // Verify the correction is applied in subsequent parsing
        val text = "Duka\ntmto 100\nTotal 100"
        val result = scanner.parseReceiptText(text)

        val item = result.items.find { it.itemName.lowercase().contains("tomato") }
        assertNotNull("Correction should map 'tmto' to 'tomato'", item)
        scanner.close()
    }

    @Test
    fun receiptItemParcel_roundTrip() {
        val original = ReceiptItemParcel(
            itemName = "Nyanya",
            quantity = 2.0,
            unitPrice = 100.0,
            totalPrice = 200.0,
            category = "produce"
        )

        // Convert to ReceiptItem and back
        val receiptItem = original.toReceiptItem()
        val restored = ReceiptItemParcel.fromReceiptItem(receiptItem)

        assertEquals(original.itemName, restored.itemName)
        assertEquals(original.quantity, restored.quantity, 0.01)
        assertEquals(original.totalPrice, restored.totalPrice, 0.01)
        assertEquals(original.category, restored.category)
    }
}
