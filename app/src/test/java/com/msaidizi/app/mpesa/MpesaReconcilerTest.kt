package com.msaidizi.app.mpesa

import com.msaidizi.app.core.model.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * MpesaReconciler tests — because if the M-Pesa balance doesn't match, mama mboga PANICS.
 *
 * Tests cover:
 * - CSV parsing (standard, malformed, edge cases)
 * - SMS parsing (receive, send, paybill, withdrawal)
 * - Transaction classification
 * - Reconciliation (match, mismatch)
 * - Summary statistics
 */
@RunWith(JUnit4::class)
class MpesaReconcilerTest {

    private lateinit var parser: MpesaStatementParser
    private lateinit var smsParser: MpesaSmsParser

    @Before
    fun setup() {
        parser = MpesaStatementParser()
        smsParser = MpesaSmsParser()
    }

    // ═══════════════════════════════════════════════════════════════════
    // CSV Parsing — Standard M-Pesa Statement Format
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseCsvString parses standard M-Pesa CSV`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "QHK71G4YS0","2026-06-30 12:00:00","Customer Payment to 254712345678",COMPLETED,"100.00","","1,500.00",""
            "QHK71G4YS1","2026-06-30 13:00:00","Pay Bill to KPLC via Paybill 222222",COMPLETED,"","500.00","1,000.00","ACC001"
        """.trimIndent()

        val transactions = parser.parseCsvString(csv)
        assertEquals(2, transactions.size)

        // First transaction: received money
        val t1 = transactions[0]
        assertEquals("QHK71G4YS0", t1.receipt)
        assertEquals(100.0, t1.amount, 0.01)
        assertTrue(t1.isCredit)
        assertEquals(TransactionType.SALE, t1.type)

        // Second transaction: paid bill
        val t2 = transactions[1]
        assertEquals("QHK71G4YS1", t2.receipt)
        assertEquals(500.0, t2.amount, 0.01)
        assertFalse(t2.isCredit)
        assertEquals(TransactionType.EXPENSE, t2.type)
    }

    @Test
    fun `parseCsvString skips non-completed transactions`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "QHK71G4YS0","2026-06-30 12:00:00","Customer Payment",COMPLETED,"100.00","","1,500.00",""
            "QHK71G4YS1","2026-06-30 13:00:00","Customer Payment",FAILED,"200.00","","1,300.00",""
            "QHK71G4YS2","2026-06-30 14:00:00","Customer Payment",REVERSED,"300.00","","1,000.00",""
        """.trimIndent()

        val transactions = parser.parseCsvString(csv)
        assertEquals("Only COMPLETED transactions should be parsed", 1, transactions.size)
    }

    @Test
    fun `parseCsvString handles comma-separated amounts correctly`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "QHK71G4YS0","2026-06-30 12:00:00","Customer Payment",COMPLETED,"1,500.00","","15,000.00",""
        """.trimIndent()

        val transactions = parser.parseCsvString(csv)
        assertEquals(1, transactions.size)
        assertEquals(1500.0, transactions[0].amount, 0.01)
        assertEquals(15000.0, transactions[0].balance, 0.01)
    }

    @Test
    fun `parseCsvString handles empty CSV`() {
        val csv = ""
        val transactions = parser.parseCsvString(csv)
        assertTrue(transactions.isEmpty())
    }

    @Test
    fun `parseCsvString handles CSV with only header`() {
        val csv = """"Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number""""
        val transactions = parser.parseCsvString(csv)
        assertTrue(transactions.isEmpty())
    }

    @Test
    fun `parseCsvString handles malformed lines gracefully`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "QHK71G4YS0","2026-06-30 12:00:00","Customer Payment",COMPLETED,"100.00","","1,500.00",""
            this is a malformed line
            "QHK71G4YS1","2026-06-30 13:00:00","Buy Goods",COMPLETED,"","200.00","1,300.00",""
        """.trimIndent()

        val transactions = parser.parseCsvString(csv)
        assertEquals("Should parse valid lines, skip malformed ones", 2, transactions.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Transaction Classification
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `classifyTransaction identifies customer payment as SALE`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Customer Payment to 254712345678",COMPLETED,"500.00","","5,000.00",""
        """.trimIndent()
        val txns = parser.parseCsvString(csv)
        assertEquals(TransactionType.SALE, txns[0].type)
    }

    @Test
    fun `classifyTransaction identifies received from as SALE`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Received from JOHN DOE 254712345678",COMPLETED,"300.00","","3,000.00",""
        """.trimIndent()
        val txns = parser.parseCsvString(csv)
        assertEquals(TransactionType.SALE, txns[0].type)
    }

    @Test
    fun `classifyTransaction identifies pay bill as EXPENSE`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Pay Bill to KPLC via Paybill 222222",COMPLETED,"","1000.00","4,000.00","ACC001"
        """.trimIndent()
        val txns = parser.parseCsvString(csv)
        assertEquals(TransactionType.EXPENSE, txns[0].type)
    }

    @Test
    fun `classifyTransaction identifies buy goods as PURCHASE`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Buy Goods to SHOP ABC",COMPLETED,"","200.00","4,800.00",""
        """.trimIndent()
        val txns = parser.parseCsvString(csv)
        assertEquals(TransactionType.PURCHASE, txns[0].type)
    }

    @Test
    fun `classifyTransaction identifies withdrawal`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Withdraw Cash at Agent",COMPLETED,"","500.00","4,500.00",""
        """.trimIndent()
        val txns = parser.parseCsvString(csv)
        assertEquals(TransactionType.WITHDRAWAL, txns[0].type)
    }

    @Test
    fun `classifyTransaction identifies reversal as REFUND`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Reversal of transaction R002",COMPLETED,"100.00","","5,100.00",""
        """.trimIndent()
        val txns = parser.parseCsvString(csv)
        assertEquals(TransactionType.REFUND, txns[0].type)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Summary Statistics — parseWithSummary
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseWithSummary computes correct totals`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Customer Payment",COMPLETED,"100.00","","1,100.00",""
            "R002","2026-06-30 13:00:00","Customer Payment",COMPLETED,"200.00","","1,300.00",""
            "R003","2026-06-30 14:00:00","Pay Bill",COMPLETED,"","50.00","1,250.00","ACC001"
        """.trimIndent()

        val result = parser.parseWithSummary(csv.byteInputStream())
        assertEquals(3, result.totalCount)
        assertEquals(300.0, result.totalCredit, 0.01)
        assertEquals(50.0, result.totalDebit, 0.01)
        assertEquals(250.0, result.netAmount, 0.01)
    }

    @Test
    fun `parseWithSummary handles empty statement`() {
        val csv = ""
        val result = parser.parseWithSummary(csv.byteInputStream())
        assertEquals(0, result.totalCount)
        assertEquals(0.0, result.totalCredit, 0.01)
        assertEquals(0.0, result.totalDebit, 0.01)
        assertNull(result.dateRange)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SMS Parsing — M-Pesa notification messages
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `smsParser parses receive SMS`() {
        val sms = "QHK71G4YS0 Confirmed. Ksh100.00 received from JOHN DOE 254712345678 on 30/6/26 at 12:00 PM. New M-PESA balance is Ksh1,500.00."
        val result = smsParser.parse(sms)

        assertNotNull(result)
        assertEquals("QHK71G4YS0", result!!.receipt)
        assertEquals(100.0, result.amount, 0.01)
        assertEquals("JOHN DOE", result.counterparty)
        assertEquals("254712345678", result.phone)
        assertTrue(result.isCredit)
        assertEquals(TransactionType.SALE, result.transactionType)
        assertEquals(1500.0, result.balance!!, 0.01)
    }

    @Test
    fun `smsParser parses send SMS`() {
        val sms = "QHK71G4YS0 Confirmed. Ksh200.00 sent to JANE DOE 254798765432 on 30/6/26 at 1:00 PM. New M-PESA balance is Ksh1,300.00."
        val result = smsParser.parse(sms)

        assertNotNull(result)
        assertEquals(200.0, result!!.amount, 0.01)
        assertEquals("JANE DOE", result.counterparty)
        assertFalse(result.isCredit)
        assertEquals(TransactionType.EXPENSE, result.transactionType)
    }

    @Test
    fun `smsParser parses paybill SMS`() {
        val sms = "QHK71G4YS0 Confirmed. Ksh50.00 paid to KPLC. Account: ACC001. New M-PESA balance is Ksh950.00."
        val result = smsParser.parse(sms)

        assertNotNull(result)
        assertEquals(50.0, result!!.amount, 0.01)
        assertEquals("KPLC", result.counterparty)
        assertFalse(result.isCredit)
    }

    @Test
    fun `smsParser parses withdrawal SMS`() {
        val sms = "QHK71G4YS0 Confirmed. Ksh500.00 withdrawn from M-PESA agent. New M-PESA balance is Ksh500.00."
        val result = smsParser.parse(sms)

        assertNotNull(result)
        assertEquals(500.0, result!!.amount, 0.01)
        assertFalse(result.isCredit)
        assertEquals(TransactionType.WITHDRAWAL, result.transactionType)
    }

    @Test
    fun `smsParser returns null for non-M-Pesa SMS`() {
        val sms = "Hello, how are you today?"
        assertNull(smsParser.parse(sms))
    }

    @Test
    fun `isMpesaSms correctly identifies M-Pesa messages`() {
        assertTrue(smsParser.isMpesaSms("QHK71G4YS0 Confirmed. Ksh100.00 received from JOHN."))
        assertTrue(smsParser.isMpesaSms("Your M-PESA balance is Ksh500.00"))
        assertFalse(smsParser.isMpesaSms("Hello, how are you?"))
        assertFalse(smsParser.isMpesaSms(""))
    }

    // ═══════════════════════════════════════════════════════════════════
    // toTransactions — Conversion to internal model
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `toTransactions converts parsed transactions correctly`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Customer Payment to 254712345678",COMPLETED,"500.00","","5,000.00",""
        """.trimIndent()

        val parsed = parser.parseCsvString(csv)
        val converted = parser.toTransactions(parsed)

        assertEquals(1, converted.size)
        val t = converted[0]
        assertEquals("SALE", t["type"])
        assertEquals(500.0, t["totalAmount"])
        assertEquals("mpesa", t["paymentMethod"])
        assertEquals("R001", t["mpesaReceipt"])
        assertEquals(true, t["isCredit"])
    }

    // ═══════════════════════════════════════════════════════════════════
    // Amount Edge Cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parseCsvString rejects zero amounts`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Customer Payment",COMPLETED,"0.00","","1,000.00",""
        """.trimIndent()

        val txns = parser.parseCsvString(csv)
        assertTrue("Zero amount transactions should be skipped", txns.isEmpty())
    }

    @Test
    fun `parseCsvString rejects amounts exceeding maximum`() {
        val csv = """
            "Receipt No.","Completion Time","Details","Transaction Status","Paid In","Withdrawn","Balance","Account Number"
            "R001","2026-06-30 12:00:00","Customer Payment",COMPLETED,"600,000.00","","600,000.00",""
        """.trimIndent()

        val txns = parser.parseCsvString(csv)
        assertTrue("Amounts over KSh 500,000 should be skipped", txns.isEmpty())
    }
}
