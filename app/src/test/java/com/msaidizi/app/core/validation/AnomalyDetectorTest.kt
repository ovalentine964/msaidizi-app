package com.msaidizi.app.core.validation

import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * AnomalyDetector tests — detects when mama mboga's records have suspicious patterns.
 *
 * Tests cover:
 * - Amount spike detection
 * - Duplicate transaction detection
 * - Balance change detection
 * - M-Pesa SMS format validation
 * - Date validity checks
 * - Batch analysis
 */
@RunWith(JUnit4::class)
class AnomalyDetectorTest {

    private lateinit var detector: AnomalyDetector

    @Before
    fun setup() {
        detector = AnomalyDetector()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════════════

    private fun makeTransaction(
        id: Long = 1,
        amount: Double = 500.0,
        item: String = "Tomatoes",
        quantity: Double = 10.0,
        unitPrice: Double = 50.0,
        type: TransactionType = TransactionType.SALE,
        customer: String = "",
        createdAt: Long = System.currentTimeMillis() / 1000
    ): Transaction = Transaction(
        id = id,
        type = type,
        item = item,
        quantity = quantity,
        unitPrice = unitPrice,
        totalAmount = amount,
        customer = customer,
        createdAt = createdAt,
        costBasis = 0.0
    )

    // ═══════════════════════════════════════════════════════════════════
    // Amount Spike Detection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `no anomaly for amount within normal range`() {
        val history = (1..10).map { i ->
            makeTransaction(id = i.toLong(), amount = 100.0, item = "Tomatoes")
        }
        val newTx = makeTransaction(id = 11, amount = 120.0, item = "Tomatoes")

        val anomalies = detector.checkTransaction(newTx, history)
        val spikes = anomalies.filter { it.type == AnomalyDetector.AnomalyType.AMOUNT_SPIKE }
        assertTrue("Normal amount should not trigger spike", spikes.isEmpty())
    }

    @Test
    fun `amount spike detected when 10x average`() {
        val history = (1..10).map { i ->
            makeTransaction(id = i.toLong(), amount = 100.0, item = "Tomatoes")
        }
        // 1100 is > 10x the average of 100
        val newTx = makeTransaction(id = 11, amount = 1100.0, item = "Tomatoes")

        val anomalies = detector.checkTransaction(newTx, history)
        val spikes = anomalies.filter { it.type == AnomalyDetector.AnomalyType.AMOUNT_SPIKE }
        assertFalse("10x amount should trigger spike detection", spikes.isEmpty())
    }

    @Test
    fun `no spike with insufficient history`() {
        val history = (1..3).map { i ->
            makeTransaction(id = i.toLong(), amount = 100.0, item = "Tomatoes")
        }
        val newTx = makeTransaction(id = 4, amount = 5000.0, item = "Tomatoes")

        val anomalies = detector.checkTransaction(newTx, history)
        val spikes = anomalies.filter { it.type == AnomalyDetector.AnomalyType.AMOUNT_SPIKE }
        assertTrue("Should not detect spike with < 5 history items", spikes.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // Absolute Maximum
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `amount exceeding absolute max flagged as critical`() {
        val tx = makeTransaction(amount = 1_500_000.0)
        val anomalies = detector.checkTransaction(tx, emptyList())

        val maxExceeded = anomalies.filter { it.type == AnomalyDetector.AnomalyType.MAX_AMOUNT_EXCEEDED }
        assertFalse("Amount > 1M should be flagged", maxExceeded.isEmpty())
        assertEquals(AnomalyDetector.AnomalySeverity.CRITICAL, maxExceeded.first().severity)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Duplicate Detection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `duplicate transaction detected within time window`() {
        val now = System.currentTimeMillis() / 1000
        val existing = makeTransaction(id = 1, amount = 500.0, item = "Tomatoes", createdAt = now - 30)
        val newTx = makeTransaction(id = 2, amount = 500.0, item = "Tomatoes", createdAt = now)

        val anomalies = detector.checkTransaction(newTx, listOf(existing))
        val dupes = anomalies.filter { it.type == AnomalyDetector.AnomalyType.DUPLICATE_TRANSACTION }
        assertFalse("Same amount/item within 60s should be flagged as duplicate", dupes.isEmpty())
    }

    @Test
    fun `no duplicate when different item`() {
        val now = System.currentTimeMillis() / 1000
        val existing = makeTransaction(id = 1, amount = 500.0, item = "Tomatoes", createdAt = now - 30)
        val newTx = makeTransaction(id = 2, amount = 500.0, item = "Onions", createdAt = now)

        val anomalies = detector.checkTransaction(newTx, listOf(existing))
        val dupes = anomalies.filter {
            it.type == AnomalyDetector.AnomalyType.DUPLICATE_TRANSACTION &&
                it.details.contains("Potential duplicate")
        }
        assertTrue("Different item should not be flagged as duplicate", dupes.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // Date Validity
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `future date flagged as critical`() {
        val future = System.currentTimeMillis() / 1000 + 7200 // 2 hours ahead
        val tx = makeTransaction(createdAt = future)

        val anomalies = detector.checkTransaction(tx, emptyList())
        val dateIssues = anomalies.filter { it.type == AnomalyDetector.AnomalyType.IMPOSSIBLE_DATE }
        assertFalse("Future date should be flagged", dateIssues.isEmpty())
        assertEquals(AnomalyDetector.AnomalySeverity.CRITICAL, dateIssues.first().severity)
    }

    @Test
    fun `negative timestamp flagged as critical`() {
        val tx = makeTransaction(createdAt = -100)

        val anomalies = detector.checkTransaction(tx, emptyList())
        val dateIssues = anomalies.filter { it.type == AnomalyDetector.AnomalyType.IMPOSSIBLE_DATE }
        assertFalse("Negative timestamp should be flagged", dateIssues.isEmpty())
    }

    @Test
    fun `valid recent date not flagged`() {
        val tx = makeTransaction(createdAt = System.currentTimeMillis() / 1000 - 60)
        val anomalies = detector.checkTransaction(tx, emptyList())
        val dateIssues = anomalies.filter { it.type == AnomalyDetector.AnomalyType.IMPOSSIBLE_DATE }
        assertTrue("Valid recent date should not be flagged", dateIssues.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // Balance Change Detection
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `balance math mismatch flagged`() {
        val todayTx = listOf(
            makeTransaction(amount = 500.0, type = TransactionType.SALE),
            makeTransaction(id = 2, amount = 200.0, type = TransactionType.SALE)
        )
        // Expected: 10000 + 500 + 200 = 10700, but actual is 15000 (big discrepancy)
        val anomalies = detector.checkBalanceChange(10000.0, 15000.0, todayTx)
        val balanceAnomalies = anomalies.filter { it.type == AnomalyDetector.AnomalyType.BALANCE_CHANGE }
        assertFalse("Large balance mismatch should be flagged", balanceAnomalies.isEmpty())
    }

    @Test
    fun `negative balance flagged as critical`() {
        val anomalies = detector.checkBalanceChange(100.0, -50.0, emptyList())
        val negBalance = anomalies.filter { it.type == AnomalyDetector.AnomalyType.NEGATIVE_BALANCE }
        assertFalse("Negative balance should be flagged", negBalance.isEmpty())
        assertEquals(AnomalyDetector.AnomalySeverity.CRITICAL, negBalance.first().severity)
    }

    @Test
    fun `correct balance not flagged`() {
        val todayTx = listOf(
            makeTransaction(amount = 500.0, type = TransactionType.SALE)
        )
        // Expected: 10000 + 500 = 10500
        val anomalies = detector.checkBalanceChange(10000.0, 10500.0, todayTx)
        assertTrue("Correct balance should not be flagged", anomalies.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // M-Pesa SMS Format Validation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `valid M-Pesa SMS passes check`() {
        val sms = "QH34AB5CD6 Confirmed. KSh 500.00 received from JOHN DOE 0712345678"
        val anomaly = detector.checkMpesaSmsFormat(sms)
        assertNull("Valid M-Pesa SMS should pass", anomaly)
    }

    @Test
    fun `SMS without amount indicator flagged`() {
        val sms = "QH34AB5CD6 Confirmed. 500 received from JOHN"
        val anomaly = detector.checkMpesaSmsFormat(sms)
        assertNotNull("SMS without KSh/KES should be flagged", anomaly)
        assertEquals(AnomalyDetector.AnomalySeverity.WARNING, anomaly!!.severity)
    }

    @Test
    fun `SMS without transaction code flagged`() {
        val sms = "Confirmed. KSh 500.00 received from JOHN"
        val anomaly = detector.checkMpesaSmsFormat(sms)
        assertNotNull("SMS without code should be flagged", anomaly)
    }

    @Test
    fun `SMS without Confirmed keyword gets info`() {
        val sms = "QH34AB5CD6 KSh 500.00 sent to JOHN"
        val anomaly = detector.checkMpesaSmsFormat(sms)
        assertNotNull("SMS without 'Confirmed' should be flagged", anomaly)
        assertEquals(AnomalyDetector.AnomalySeverity.INFO, anomaly!!.severity)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch Analysis
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `analyzeBatch returns empty for empty list`() {
        val result = detector.analyzeBatch(emptyList())
        assertEquals(0, result.transactionCount)
        assertTrue(result.anomalies.isEmpty())
    }

    @Test
    fun `analyzeBatch detects critical anomalies`() {
        val txs = listOf(
            makeTransaction(id = 1, amount = 1_500_000.0) // over max
        )
        val result = detector.analyzeBatch(txs)
        assertTrue("Should detect critical anomaly", result.hasCritical)
        assertTrue(result.criticalCount > 0)
    }

    @Test
    fun `analyzeBatch summary reflects severity`() {
        // Clean transactions
        val txs = (1..5).map { i ->
            makeTransaction(id = i.toLong(), amount = 100.0 + i, item = "Item $i")
        }
        val result = detector.analyzeBatch(txs)
        assertTrue("Clean batch summary should indicate ok",
            result.summary.contains("sawa") || result.summary.contains("✓"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Anomaly Result Fields
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `anomaly contains Swahili message and details`() {
        val tx = makeTransaction(amount = 1_500_000.0)
        val anomalies = detector.checkTransaction(tx, emptyList())

        val anomaly = anomalies.firstOrNull()
        assertNotNull("Should have anomaly", anomaly)
        assertTrue("Message should not be empty", anomaly!!.message.isNotEmpty())
        assertTrue("Details should not be empty", anomaly.details.isNotEmpty())
        assertTrue("Suggested action should not be empty", anomaly.suggestedAction.isNotEmpty())
    }
}
