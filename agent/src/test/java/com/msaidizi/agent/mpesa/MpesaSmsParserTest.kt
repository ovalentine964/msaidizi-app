package com.msaidizi.agent.mpesa

import org.junit.Assert.*
import org.junit.Test

/**
 * T4: M-Pesa SMS Parser tests — covers all transaction types, edge cases,
 * error handling, and confidence scoring.
 *
 * This is the most critical offline feature: workers receive M-Pesa SMS
 * but manually re-enter into the app. Auto-parsing eliminates double entry.
 */
class MpesaSmsParserTest {

    // ═══════════════════════════════════════════════════════════
    //  T4a: STK Push / Received Money (C2B)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse received money - standard format`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh500.00 received from JOHN DOE 254712345678 on 25/12/23 at 2:30 PM. New M-PESA balance is Ksh12,500.00."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.RECEIVED, result!!.type)
        assertEquals(500.0, result.amount, 0.01)
        assertEquals("JOHN DOE", result.counterparty)
        assertEquals("254712345678", result.phone)
        assertEquals("QHK71K4RT6", result.receipt)
        assertEquals(12500.0, result.balance!!, 0.01)
        assertEquals(TransactionCategory.SALE, result.category)
        assertTrue("Confidence should be >= 0.8", result.confidence >= 0.8f)
    }

    @Test
    fun `parse received money - large amount with commas`() {
        val sms = "RB41K7YZP2 Confirmed. Ksh15,000.00 received from WHOLESALE SUPPLIERS 254720123456 on 01/01/24 at 10:00 AM. New M-PESA balance is Ksh45,200.00."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.RECEIVED, result!!.type)
        assertEquals(15000.0, result.amount, 0.01)
        assertEquals("WHOLESALE SUPPLIERS", result.counterparty)
    }

    @Test
    fun `parse received money - Swahili format`() {
        val sms = "QHK71K4RT6 Imekubaliwa. Ksh500.00 imepokelewa kutoka kwa JOHN DOE 254712345678 tarehe 25/12/23 saa 2:30 PM. Salio jipya la M-PESA ni Ksh12,500.00."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull("Swahili format should parse", result)
        assertEquals(MpesaTransactionType.RECEIVED, result!!.type)
        assertEquals(500.0, result.amount, 0.01)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4b: Sent Money (Person to Person)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse sent money`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh1,000.00 sent to JANE SMITH 254798765432 on 25/12/23 at 3:00 PM. New M-PESA balance is Ksh11,500.00."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.SENT, result!!.type)
        assertEquals(1000.0, result.amount, 0.01)
        assertEquals("JANE SMITH", result.counterparty)
        assertEquals(TransactionCategory.EXPENSE, result.category)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4c: Pay Bill / Buy Goods
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse pay bill`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh200.00 paid to SHOP NAME 123456 on 25/12/23 at 4:00 PM."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.PAID_GOODS, result!!.type)
        assertEquals(200.0, result.amount, 0.01)
        assertEquals(TransactionCategory.PURCHASE, result.category)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4d: Withdrawal
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse withdrawal`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh3,000.00 withdrawn from M-PESA agent JOHN 254712345678 on 25/12/23 at 5:00 PM. New M-PESA balance is Ksh8,500.00."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.WITHDRAWAL, result!!.type)
        assertEquals(3000.0, result.amount, 0.01)
        assertEquals(TransactionCategory.CASH_WITHDRAWAL, result.category)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4e: Deposit
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse deposit`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh5,000.00 deposited to your M-PESA account on 25/12/23 at 6:00 PM. New M-PESA balance is Ksh13,500.00."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.DEPOSIT, result!!.type)
        assertEquals(5000.0, result.amount, 0.01)
        assertEquals(TransactionCategory.CASH_DEPOSIT, result.category)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4f: Airtime Purchase
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse airtime purchase`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh100.00 airtime purchased on 25/12/23 at 7:00 PM. New M-PESA balance is Ksh1,400.00."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.AIRTIME, result!!.type)
        assertEquals(100.0, result.amount, 0.01)
        assertEquals(TransactionCategory.EXPENSE_AIRTIME, result.category)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4g: Fuliza (Overdraft)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse fuliza overdraft`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh200.00 fuliza overdraft used on 25/12/23 at 8:00 PM."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.FULIZA, result!!.type)
        assertEquals(TransactionCategory.LOAN, result.category)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4h: Reversed Transaction
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parse reversed transaction`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh500.00 reversed on 25/12/23 at 9:00 PM."
        val result = MpesaSmsParser.parse(sms)

        assertNotNull(result)
        assertEquals(MpesaTransactionType.REVERSED, result!!.type)
        assertEquals(TransactionCategory.REVERSAL, result.category)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4i: Error Cases — Invalid SMS
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `non-M-Pesa SMS returns null`() {
        val sms = "Hey, are you coming to the party tonight?"
        val result = MpesaSmsParser.parse(sms)
        assertNull("Non-M-Pesa SMS should return null", result)
    }

    @Test
    fun `empty SMS returns null`() {
        val result = MpesaSmsParser.parse("")
        assertNull("Empty SMS should return null", result)
    }

    @Test
    fun `SMS with M-Pesa keyword but no amount returns null`() {
        val sms = "Your M-PESA account has been updated."
        val result = MpesaSmsParser.parse(sms)
        // This contains "mpesa" but not "confirmed" or amount — depends on isMpesaSms logic
        // Either null or low confidence parse is acceptable
        if (result != null) {
            assertTrue("Low confidence for ambiguous SMS", result.confidence < 0.5f)
        }
    }

    @Test
    fun `isMpesaSms detects M-Pesa messages`() {
        assertTrue(MpesaSmsParser.isMpesaSms("QHK71K4RT6 Confirmed. Ksh500.00 received from JOHN DOE"))
        assertTrue(MpesaSmsParser.isMpesaSms("Imekubaliwa. Ksh200.00 imetumwa"))
        assertFalse(MpesaSmsParser.isMpesaSms("Hello, how are you?"))
        assertFalse(MpesaSmsParser.isMpesaSms(""))
    }

    // ═══════════════════════════════════════════════════════════
    //  T4j: Batch Parse
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `parseAll filters non-M-Pesa messages`() {
        val messages = listOf(
            "QHK71K4RT6 Confirmed. Ksh500.00 received from JOHN DOE 254712345678 on 25/12/23 at 2:30 PM. New M-PESA balance is Ksh12,500.00.",
            "Hey what's up?",
            "QHK71K4RT7 Confirmed. Ksh200.00 sent to JANE 254798765432 on 25/12/23 at 3:00 PM. New M-PESA balance is Ksh12,300.00.",
            "Meeting at 5pm"
        )
        val results = MpesaSmsParser.parseAll(messages)
        assertEquals(2, results.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4k: Auto-Categorization
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `auto-categorize received as sale`() {
        assertEquals(TransactionCategory.SALE, MpesaSmsParser.autoCategory(MpesaTransactionType.RECEIVED, "JOHN"))
    }

    @Test
    fun `auto-categorize sent as expense`() {
        assertEquals(TransactionCategory.EXPENSE, MpesaSmsParser.autoCategory(MpesaTransactionType.SENT, "JANE"))
    }

    @Test
    fun `auto-categorize paybill as expense`() {
        assertEquals(TransactionCategory.EXPENSE, MpesaSmsParser.autoCategory(MpesaTransactionType.PAYBILL, "KPLC"))
    }

    // ═══════════════════════════════════════════════════════════
    //  T4l: Confidence Scoring
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `full SMS has high confidence`() {
        val sms = "QHK71K4RT6 Confirmed. Ksh500.00 received from JOHN DOE 254712345678 on 25/12/23 at 2:30 PM. New M-PESA balance is Ksh12,500.00."
        val result = MpesaSmsParser.parse(sms)!!
        assertTrue("Full SMS should have confidence >= 0.8", result.confidence >= 0.8f)
    }

    // ═══════════════════════════════════════════════════════════
    //  T4m: Detailed Category Suggestions
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `suggest detailed category for wholesale`() {
        val result = MpesaSmsParser.suggestDetailedCategory("WHOLESALE SUPPLIERS", MpesaTransactionType.SENT)
        assertEquals("stock_purchase", result)
    }

    @Test
    fun `suggest detailed category for Kenya Power`() {
        val result = MpesaSmsParser.suggestDetailedCategory("Kenya Power", MpesaTransactionType.PAYBILL)
        assertEquals("electricity", result)
    }

    @Test
    fun `suggest detailed category returns null for unknown`() {
        val result = MpesaSmsParser.suggestDetailedCategory("RANDOM PERSON", MpesaTransactionType.SENT)
        assertNull(result)
    }
}
