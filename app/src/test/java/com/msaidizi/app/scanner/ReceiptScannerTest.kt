package com.msaidizi.app.scanner

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ReceiptScanner parsing logic.
 * Tests parsing of various receipt formats common in Kenya:
 * - Printed supermarket receipts
 * - Handwritten market receipts
 * - Mixed Swahili/English text
 */
class ReceiptScannerTest {

    private lateinit var scanner: ReceiptScanner

    @Before
    fun setup() {
        scanner = ReceiptScanner()
    }

    @Test
    fun `parse printed supermarket receipt`() {
        val rawText = """
            Naivas Supermarket
            Moi Avenue, Nairobi
            Date: 12/07/2026
            Cashier: John

            Nyanya 2kg 200
            Vitunguu 1kg 100
            Mafuta ya kupikia 1ltr 350
            Sukari 2kg 250
            Mchele 5kg 600

            TOTAL 1,500
            Cash 2,000
            Change 500

            Asante kununua!
        """.trimIndent()

        val result = scanner.parseReceiptText(rawText)

        assertNotNull(result)
        assertTrue(result.items.size >= 4)
        assertEquals(1500.0, result.total, 1.0)
        assertEquals("Naivas Supermarket", result.merchantName)
        assertEquals("cash", result.paymentMethod)
    }

    @Test
    fun `parse handwritten market receipt`() {
        val rawText = """
            Mama Njeri Duka
            Gikomba Market

            nyanya.....200
            vitunguu...100
            karoti.....80
            sukuma wiki.50

            jumla 430
        """.trimIndent()

        val result = scanner.parseReceiptText(rawText)

        assertNotNull(result)
        assertTrue(result.items.size >= 3)
        assertEquals(430.0, result.total, 1.0)
    }

    @Test
    fun `parse receipt with quantity and unit price`() {
        val rawText = """
            Mini Market
            Date: 12/07/2026

            Mayai x2 @ 150 = 300
            Mkate x1 @ 70 = 70
            Maziwa x2 @ 60 = 120

            TOTAL 490
        """.trimIndent()

        val result = scanner.parseReceiptText(rawText)

        assertNotNull(result)
        assertTrue(result.items.size >= 2)
        assertEquals(490.0, result.total, 1.0)
    }

    @Test
    fun `parse receipt with KSh prefix`() {
        val rawText = """
            Duka la Mama

            Nyama KSh 500
            Ugali KSh 50
            Chai KSh 30

            Total KSh 580
        """.trimIndent()

        val result = scanner.parseReceiptText(rawText)

        assertNotNull(result)
        assertTrue(result.items.size >= 2)
        assertEquals(580.0, result.total, 1.0)
    }

    @Test
    fun `parse M-Pesa receipt`() {
        val rawText = """
            Jumia Foods
            Order #12345
            Date: 12/07/2026

            Pizza x1 800
            Soda x2 100
            Delivery 150

            TOTAL 1,050
            M-Pesa Payment
            Ref: QHK71ABC2D
        """.trimIndent()

        val result = scanner.parseReceiptText(rawText)

        assertNotNull(result)
        assertEquals("mpesa", result.paymentMethod)
        assertEquals(1050.0, result.total, 1.0)
    }

    @Test
    fun `parse receipt with commas in prices`() {
        val rawText = """
            Wholesale Shop

            Mchele 10kg 1,200
            Sukari 5kg 650
            Mafuta 3ltr 1,050

            TOTAL 2,900
        """.trimIndent()

        val result = scanner.parseReceiptText(rawText)

        assertNotNull(result)
        assertEquals(2900.0, result.total, 1.0)
    }

    @Test
    fun `learn correction improves future parsing`() {
        // First scan - OCR misreads "tmb" as an item
        scanner.learnCorrection("tmb", "tomato")

        // The correction should be stored
        val rawText = """
            Duka
            tmb 100
            vitunguu 50
            Total 150
        """.trimIndent()

        val result = scanner.parseReceiptText(rawText)

        // The item should be corrected to "tomato"
        val tomatoItem = result.items.find { it.itemName.lowercase().contains("tomato") }
        assertNotNull(tomatoItem)
    }

    @Test
    fun `empty receipt returns empty result`() {
        val rawText = ""
        val result = scanner.parseReceiptText(rawText)

        assertNotNull(result)
        assertTrue(result.items.isEmpty())
        assertEquals(0.0, result.total, 0.01)
    }

    @Test
    fun `receipt summary text in swahili`() {
        val data = ReceiptData(
            merchantName = "Naivas",
            items = listOf(
                ReceiptItem("Nyanya", 2.0, 100.0, 200.0),
                ReceiptItem("Vitunguu", 1.0, 100.0, 100.0)
            ),
            total = 300.0
        )

        val summary = data.toSummaryText("sw")

        assertTrue(summary.contains("Naivas"))
        assertTrue(summary.contains("Nyanya"))
        assertTrue(summary.contains("Vitunguu"))
        assertTrue(summary.contains("300"))
    }

    @Test
    fun `receipt summary text in english`() {
        val data = ReceiptData(
            merchantName = "Naivas",
            items = listOf(
                ReceiptItem("Tomato", 2.0, 100.0, 200.0)
            ),
            total = 200.0
        )

        val summary = data.toSummaryText("en")

        assertTrue(summary.contains("Naivas"))
        assertTrue(summary.contains("Tomato"))
        assertTrue(summary.contains("200"))
    }

    @Test
    fun `receipt validation`() {
        // Valid receipt with items
        val validReceipt = ReceiptData(
            items = listOf(ReceiptItem("Item", 1.0, 100.0, 100.0)),
            total = 100.0
        )
        assertTrue(validReceipt.isValid)

        // Valid receipt with total only
        val totalOnlyReceipt = ReceiptData(total = 100.0)
        assertTrue(totalOnlyReceipt.isValid)

        // Invalid receipt - no items, no total
        val invalidReceipt = ReceiptData()
        assertFalse(invalidReceipt.isValid)
    }
}
