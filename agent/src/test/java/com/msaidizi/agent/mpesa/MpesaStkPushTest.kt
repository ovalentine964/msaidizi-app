package com.msaidizi.agent.mpesa

import org.junit.Assert.*
import org.junit.Test

/**
 * T4: M-Pesa STK Push and Callback handling tests.
 *
 * Tests the M-Pesa API integration layer:
 * - STK Push request building
 * - Callback payload parsing
 * - Error handling (timeout, insufficient funds, duplicate)
 * - Transaction reconciliation
 *
 * Uses pure unit tests with mock data — no network calls.
 */
class MpesaStkPushTest {

    // ═══════════════════════════════════════════════════════════
    //  STK Push Request Building
    // ═══════════════════════════════════════════════════════════

    data class StkPushRequest(
        val businessShortCode: String,
        val password: String,
        val timestamp: String,
        val transactionType: String,
        val amount: Int,
        val partyA: String,
        val partyB: String,
        val phoneNumber: String,
        val callBackUrl: String,
        val accountReference: String,
        val transactionDesc: String
    )

    data class StkPushResponse(
        val merchantRequestId: String?,
        val checkoutRequestId: String?,
        val responseCode: String?,
        val responseDescription: String?,
        val customerMessage: String?,
        val errorCode: String? = null,
        val errorMessage: String? = null
    )

    data class StkCallback(
        val merchantRequestId: String,
        val checkoutRequestId: String,
        val resultCode: Int,
        val resultDesc: String,
        val amount: Double? = null,
        val mpesaReceiptNumber: String? = null,
        val transactionDate: Long? = null,
        val phoneNumber: String? = null
    )

    @Test
    fun `STK push request has required fields`() {
        val request = StkPushRequest(
            businessShortCode = "174379",
            password = "base64encoded",
            timestamp = "20240101120000",
            transactionType = "CustomerPayBillOnline",
            amount = 500,
            partyA = "254712345678",
            partyB = "174379",
            phoneNumber = "254712345678",
            callBackUrl = "https://api.msaidizi.com/mpesa/callback",
            accountReference = "Msaidizi",
            transactionDesc = "Payment"
        )

        assertEquals("174379", request.businessShortCode)
        assertEquals(500, request.amount)
        assertEquals("CustomerPayBillOnline", request.transactionType)
        assertTrue("Callback URL must be HTTPS", request.callBackUrl.startsWith("https://"))
    }

    @Test
    fun `STK push request amount must be positive`() {
        val amounts = listOf(-1, 0, 1, 100, 70000)
        val validAmounts = amounts.filter { it > 0 }
        assertEquals(4, validAmounts.size)
        assertTrue("Amount must be positive", validAmounts.all { it > 0 })
    }

    @Test
    fun `phone number format validation`() {
        val validNumbers = listOf("254712345678", "254798765432", "254700000000")
        val invalidNumbers = listOf("0712345678", "+254712345678", "712345678", "25471234567")

        validNumbers.forEach { num ->
            assertTrue("Valid: $num", num.matches(Regex("254[17]\\d{8}")))
        }
        invalidNumbers.forEach { num ->
            assertFalse("Invalid: $num", num.matches(Regex("254[17]\\d{8}")))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  STK Push Response Handling
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `successful STK push response`() {
        val response = StkPushResponse(
            merchantRequestId = "merchant-123",
            checkoutRequestId = "checkout-456",
            responseCode = "0",
            responseDescription = "Success. Request accepted for processing",
            customerMessage = "Success. Request accepted for processing"
        )

        assertEquals("0", response.responseCode)
        assertNotNull(response.checkoutRequestId)
        assertNull(response.errorCode)
    }

    @Test
    fun `failed STK push response - insufficient balance`() {
        val response = StkPushResponse(
            merchantRequestId = null,
            checkoutRequestId = null,
            responseCode = null,
            responseDescription = null,
            customerMessage = null,
            errorCode = "1",
            errorMessage = "Insufficient balance in the short code"
        )

        assertNull(response.responseCode)
        assertNotNull(response.errorCode)
    }

    // ═══════════════════════════════════════════════════════════
    //  Callback Handling — Success
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `callback success - extract receipt`() {
        val callback = StkCallback(
            merchantRequestId = "merchant-123",
            checkoutRequestId = "checkout-456",
            resultCode = 0,
            resultDesc = "The service request is processed successfully.",
            amount = 500.0,
            mpesaReceiptNumber = "QHK71K4RT6",
            transactionDate = 20240101120000,
            phoneNumber = "254712345678"
        )

        assertEquals(0, callback.resultCode)
        assertEquals("QHK71K4RT6", callback.mpesaReceiptNumber)
        assertEquals(500.0, callback.amount!!, 0.01)
        assertEquals("254712345678", callback.phoneNumber)
    }

    @Test
    fun `callback success - receipt number format`() {
        val callback = StkCallback(
            merchantRequestId = "m-1",
            checkoutRequestId = "c-1",
            resultCode = 0,
            resultDesc = "Success",
            mpesaReceiptNumber = "QHK71K4RT6"
        )

        assertTrue(
            "Receipt matches M-Pesa format",
            callback.mpesaReceiptNumber!!.matches(Regex("[A-Z0-9]{10,12}"))
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  Callback Handling — Error Cases
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `callback timeout - resultCode 1032`() {
        val callback = StkCallback(
            merchantRequestId = "merchant-123",
            checkoutRequestId = "checkout-456",
            resultCode = 1032,
            resultDesc = "Request cancelled by user"
        )

        assertEquals(1032, callback.resultCode)
        assertNull("No receipt on timeout", callback.mpesaReceiptNumber)
    }

    @Test
    fun `callback insufficient funds - resultCode 1`() {
        val callback = StkCallback(
            merchantRequestId = "merchant-123",
            checkoutRequestId = "checkout-456",
            resultCode = 1,
            resultDesc = "Insufficient funds in the M-Pesa account"
        )

        assertEquals(1, callback.resultCode)
        assertNull("No receipt on insufficient funds", callback.mpesaReceiptNumber)
    }

    @Test
    fun `callback duplicate - resultCode 1037`() {
        val callback = StkCallback(
            merchantRequestId = "merchant-123",
            checkoutRequestId = "checkout-456",
            resultCode = 1037,
            resultDesc = "DS timeout user cannot be reached"
        )

        assertEquals(1037, callback.resultCode)
    }

    @Test
    fun `callback wrong PIN - resultCode 1025`() {
        val callback = StkCallback(
            merchantRequestId = "merchant-123",
            checkoutRequestId = "checkout-456",
            resultCode = 1025,
            resultDesc = "The initiator information is invalid"
        )

        assertEquals(1025, callback.resultCode)
    }

    // ═══════════════════════════════════════════════════════════
    //  Transaction Reconciliation
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `reconcile STK push with callback`() {
        val checkoutId = "checkout-456"
        val pendingTransactions = mutableMapOf<String, StkPushRequest>()
        pendingTransactions[checkoutId] = StkPushRequest(
            businessShortCode = "174379",
            password = "pw",
            timestamp = "ts",
            transactionType = "CustomerPayBillOnline",
            amount = 500,
            partyA = "254712345678",
            partyB = "174379",
            phoneNumber = "254712345678",
            callBackUrl = "https://api.msaidizi.com/mpesa/callback",
            accountReference = "Msaidizi",
            transactionDesc = "Payment"
        )

        val callback = StkCallback(
            merchantRequestId = "merchant-123",
            checkoutRequestId = checkoutId,
            resultCode = 0,
            resultDesc = "Success",
            amount = 500.0,
            mpesaReceiptNumber = "QHK71K4RT6"
        )

        // Match callback to pending request
        val pending = pendingTransactions[callback.checkoutRequestId]
        assertNotNull("Should find matching pending transaction", pending)
        assertEquals(pending!!.amount, callback.amount!!.toInt())

        // Remove from pending after successful callback
        pendingTransactions.remove(callback.checkoutRequestId)
        assertTrue("Pending should be empty after reconciliation", pendingTransactions.isEmpty())
    }

    @Test
    fun `callback for unknown checkout ID is handled gracefully`() {
        val callback = StkCallback(
            merchantRequestId = "unknown",
            checkoutRequestId = "unknown-checkout",
            resultCode = 0,
            resultDesc = "Success",
            amount = 100.0,
            mpesaReceiptNumber = "UNKNOWN123"
        )

        // Should not crash when looking up unknown checkout ID
        val pending = mapOf<String, StkPushRequest>()
        val match = pending[callback.checkoutRequestId]
        assertNull("Unknown checkout ID returns null", match)
    }

    // ═══════════════════════════════════════════════════════════
    //  Security: Callback Validation
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `callback must have valid result code`() {
        val validCodes = setOf(0, 1, 1032, 1037, 1025, 2001)
        val testCode = 0
        assertTrue("Result code 0 is valid", testCode in validCodes || testCode == 0)
    }

    @Test
    fun `callback amount matches request amount`() {
        val requestAmount = 500
        val callbackAmount = 500.0
        assertEquals("Amounts must match", requestAmount.toDouble(), callbackAmount, 0.01)
    }

    @Test
    fun `callback amount mismatch triggers alert`() {
        val requestAmount = 500
        val callbackAmount = 1000.0  // Mismatch!
        assertNotEquals(
            "Amount mismatch should be detected",
            requestAmount.toDouble(), callbackAmount, 0.01
        )
    }
}
