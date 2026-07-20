package com.msaidizi.app.agent

import com.msaidizi.app.core.model.IntentResult
import com.msaidizi.app.core.model.IntentType
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.testutil.TestModels
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TransactionHandler] — sale, purchase, and expense recording.
 *
 * Tests the transaction pipeline: data extraction → recording → response generation.
 * All dependencies are mocked; we test the handler's own logic.
 */
@DisplayName("TransactionHandler")
class TransactionHandlerTest {

    private lateinit var businessAgent: BusinessAgent
    private lateinit var adaptiveLearning: AdaptiveLearningEngine
    private lateinit var learningAgent: LearningAgent
    private lateinit var handler: TransactionHandler

    @BeforeEach
    fun setup() {
        businessAgent = mockk(relaxed = true)
        adaptiveLearning = mockk(relaxed = true)
        learningAgent = mockk(relaxed = true)
        handler = TransactionHandler(
            businessAgent = businessAgent,
            adaptiveLearning = adaptiveLearning,
            learningAgent = learningAgent
        )
    }

    // =====================================================================
    // SALE HANDLING
    // =====================================================================

    @Nested
    @DisplayName("handleSale")
    inner class SaleTests {

        @Test
        fun `successful sale returns CONFIRMATION with profit`() = runTest {
            val tx = TestModels.sale(item = "mandazi", quantity = 10.0, totalAmount = 200.0, costBasis = 120.0)
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf(
                    "item" to "mandazi",
                    "quantity" to "10",
                    "amount" to "200"
                )
            )

            val response = handler.handleSale(intent, "sw")

            assertEquals(ResponseType.CONFIRMATION, response.type)
            assertTrue(response.text.contains("mandazi"))
            assertTrue(response.text.contains("200"))
            assertTrue(response.text.contains("Faida") || response.text.contains("Profit"))
        }

        @Test
        fun `sale records profit correctly`() = runTest {
            // Sale: 500 revenue, 300 cost = 200 profit
            val tx = TestModels.sale(totalAmount = 500.0, costBasis = 300.0)
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "bidhaa", "amount" to "500")
            )

            val response = handler.handleSale(intent, "en")

            assertEquals(ResponseType.CONFIRMATION, response.type)
            assertTrue(response.text.contains("Profit"))
            // Profit = 500 - 300 = 200
            val profit = response.data["profit"]?.toDoubleOrNull()
            assertNotNull(profit)
            assertEquals(200.0, profit!!, 0.01)
        }

        @Test
        fun `sale without item returns CLARIFICATION`() = runTest {
            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("amount" to "500") // No item
            )

            val response = handler.handleSale(intent, "sw")

            assertEquals(ResponseType.CLARIFICATION, response.type)
        }

        @Test
        fun `sale without amount returns CLARIFICATION`() = runTest {
            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi") // No amount
            )

            val response = handler.handleSale(intent, "sw")

            assertEquals(ResponseType.CLARIFICATION, response.type)
        }

        @Test
        fun `sale with suggestedPrice fallback works`() = runTest {
            val tx = TestModels.sale(item = "mandazi", totalAmount = 300.0)
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf(
                    "item" to "mandazi",
                    "suggestedPrice" to "300" // Uses suggestedPrice instead of amount
                )
            )

            val response = handler.handleSale(intent, "en")

            assertEquals(ResponseType.CONFIRMATION, response.type)
        }

        @Test
        fun `sale learns from transaction`() = runTest {
            val tx = TestModels.sale()
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi", "amount" to "200")
            )

            handler.handleSale(intent, "en")

            coVerify { adaptiveLearning.learnFromTransaction(tx) }
        }

        @Test
        fun `sale records sale time for pattern learning`() = runTest {
            val tx = TestModels.sale()
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi", "amount" to "200")
            )

            handler.handleSale(intent, "en")

            verify { learningAgent.recordSaleTime(any(), any()) }
        }

        @Test
        fun `sale tracks lastTransaction`() = runTest {
            val tx = TestModels.sale(item = "test")
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "test", "amount" to "100")
            )

            handler.handleSale(intent, "en")

            assertNotNull(handler.lastTransaction)
            assertEquals("test", handler.lastTransaction!!.item)
        }

        @Test
        fun `sale error returns ERROR response`() = runTest {
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } throws RuntimeException("DB error")

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi", "amount" to "200")
            )

            val response = handler.handleSale(intent, "en")

            assertEquals(ResponseType.ERROR, response.type)
        }
    }

    // =====================================================================
    // PURCHASE HANDLING
    // =====================================================================

    @Nested
    @DisplayName("handlePurchase")
    inner class PurchaseTests {

        @Test
        fun `successful purchase returns CONFIRMATION`() = runTest {
            val tx = TestModels.purchase(item = "unga", totalAmount = 500.0)
            coEvery { businessAgent.recordPurchase(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.PURCHASE,
                confidence = 0.9,
                extractedData = mapOf("item" to "unga", "quantity" to "5", "amount" to "500")
            )

            val response = handler.handlePurchase(intent, "en")

            assertEquals(ResponseType.CONFIRMATION, response.type)
            assertTrue(response.text.contains("unga"))
            assertTrue(response.text.contains("500"))
        }

        @Test
        fun `purchase without item returns CLARIFICATION`() = runTest {
            val intent = IntentResult(
                intent = IntentType.PURCHASE,
                confidence = 0.9,
                extractedData = mapOf("amount" to "500")
            )

            val response = handler.handlePurchase(intent, "en")

            assertEquals(ResponseType.CLARIFICATION, response.type)
        }

        @Test
        fun `purchase without amount returns CLARIFICATION`() = runTest {
            val intent = IntentResult(
                intent = IntentType.PURCHASE,
                confidence = 0.9,
                extractedData = mapOf("item" to "unga")
            )

            val response = handler.handlePurchase(intent, "en")

            assertEquals(ResponseType.CLARIFICATION, response.type)
        }

        @Test
        fun `purchase learns from transaction`() = runTest {
            val tx = TestModels.purchase()
            coEvery { businessAgent.recordPurchase(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.PURCHASE,
                confidence = 0.9,
                extractedData = mapOf("item" to "unga", "amount" to "500")
            )

            handler.handlePurchase(intent, "en")

            coVerify { adaptiveLearning.learnFromTransaction(tx) }
        }

        @Test
        fun `purchase error returns ERROR response`() = runTest {
            coEvery { businessAgent.recordPurchase(any(), any(), any(), any(), any()) } throws RuntimeException("DB error")

            val intent = IntentResult(
                intent = IntentType.PURCHASE,
                confidence = 0.9,
                extractedData = mapOf("item" to "unga", "amount" to "500")
            )

            val response = handler.handlePurchase(intent, "en")

            assertEquals(ResponseType.ERROR, response.type)
        }
    }

    // =====================================================================
    // EXPENSE HANDLING
    // =====================================================================

    @Nested
    @DisplayName("handleExpense")
    inner class ExpenseTests {

        @Test
        fun `successful expense returns CONFIRMATION`() = runTest {
            val tx = TestModels.expense(category = "usafiri", amount = 200.0)
            coEvery { businessAgent.recordExpense(any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.EXPENSE,
                confidence = 0.85,
                extractedData = mapOf("category" to "usafiri", "amount" to "200")
            )

            val response = handler.handleExpense(intent, "en")

            assertEquals(ResponseType.CONFIRMATION, response.type)
            assertTrue(response.text.contains("usafiri"))
        }

        @Test
        fun `expense without amount returns CLARIFICATION`() = runTest {
            val intent = IntentResult(
                intent = IntentType.EXPENSE,
                confidence = 0.85,
                extractedData = mapOf("category" to "usafiri")
            )

            val response = handler.handleExpense(intent, "en")

            assertEquals(ResponseType.CLARIFICATION, response.type)
        }

        @Test
        fun `expense defaults category to other`() = runTest {
            val tx = TestModels.expense()
            coEvery { businessAgent.recordExpense(any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.EXPENSE,
                confidence = 0.85,
                extractedData = mapOf("amount" to "100") // No category
            )

            val response = handler.handleExpense(intent, "en")

            assertEquals(ResponseType.CONFIRMATION, response.type)
        }

        @Test
        fun `expense error returns ERROR response`() = runTest {
            coEvery { businessAgent.recordExpense(any(), any(), any(), any()) } throws RuntimeException("DB error")

            val intent = IntentResult(
                intent = IntentType.EXPENSE,
                confidence = 0.85,
                extractedData = mapOf("amount" to "100")
            )

            val response = handler.handleExpense(intent, "en")

            assertEquals(ResponseType.ERROR, response.type)
        }
    }

    // =====================================================================
    // LANGUAGE SUPPORT
    // =====================================================================

    @Nested
    @DisplayName("Language support")
    inner class LanguageTests {

        @Test
        fun `sale confirmation in Swahili uses Swahili text`() = runTest {
            val tx = TestModels.sale(item = "mandazi", totalAmount = 200.0, costBasis = 100.0)
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi", "amount" to "200")
            )

            val response = handler.handleSale(intent, "sw")

            assertTrue(response.text.contains("Umeuza"), "Swahili response should contain 'Umeuza'")
        }

        @Test
        fun `sale confirmation in English uses English text`() = runTest {
            val tx = TestModels.sale(item = "mandazi", totalAmount = 200.0, costBasis = 100.0)
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi", "amount" to "200")
            )

            val response = handler.handleSale(intent, "en")

            assertTrue(response.text.contains("Sold"), "English response should contain 'Sold'")
        }

        @Test
        fun `missing item clarification in Swahili`() = runTest {
            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("amount" to "200")
            )

            val response = handler.handleSale(intent, "sw")

            assertTrue(response.text.contains("bidhaa"), "Swahili clarification should mention 'bidhaa'")
        }
    }

    // =====================================================================
    // DATA EXTRACTION
    // =====================================================================

    @Nested
    @DisplayName("Response data map")
    inner class DataMapTests {

        @Test
        fun `sale response includes transactionId`() = runTest {
            val tx = TestModels.sale(id = 42)
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi", "amount" to "200")
            )

            val response = handler.handleSale(intent, "en")

            assertEquals("42", response.data["transactionId"])
        }

        @Test
        fun `sale response includes profit`() = runTest {
            val tx = TestModels.sale(totalAmount = 500.0, costBasis = 200.0)
            coEvery { businessAgent.recordSale(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.9,
                extractedData = mapOf("item" to "mandazi", "amount" to "500")
            )

            val response = handler.handleSale(intent, "en")

            assertEquals("300.0", response.data["profit"])
        }

        @Test
        fun `purchase response includes quantity`() = runTest {
            val tx = TestModels.purchase(quantity = 5.0)
            coEvery { businessAgent.recordPurchase(any(), any(), any(), any(), any()) } returns tx

            val intent = IntentResult(
                intent = IntentType.PURCHASE,
                confidence = 0.9,
                extractedData = mapOf("item" to "unga", "quantity" to "5", "amount" to "500")
            )

            val response = handler.handlePurchase(intent, "en")

            assertEquals("5.0", response.data["quantity"])
        }
    }
}
