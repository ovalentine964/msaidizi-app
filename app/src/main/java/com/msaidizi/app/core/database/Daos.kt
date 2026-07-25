package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.model.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Sale DAO
// ──────────────────────────────────────────────

@Dao
interface SaleDao {
    @Insert
    suspend fun insert(sale: SaleEntity): Long

    @Query("SELECT * FROM sales WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getSalesBetween(start: Long, end: Long): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSales(limit: Int = 20): Flow<List<SaleEntity>>

    @Query("SELECT SUM(totalPrice) FROM sales WHERE timestamp BETWEEN :start AND :end")
    fun getTotalSalesBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT COUNT(*) FROM sales WHERE timestamp BETWEEN :start AND :end")
    fun getTransactionCountBetween(start: Long, end: Long): Flow<Int>

    @Query("SELECT productName, SUM(quantity) as totalQty, SUM(totalPrice) as totalRevenue FROM sales WHERE timestamp BETWEEN :start AND :end GROUP BY productName ORDER BY totalRevenue DESC LIMIT :limit")
    fun getTopProducts(start: Long, end: Long, limit: Int = 5): Flow<List<ProductSalesSummary>>

    @Query("SELECT SUM(totalPrice) FROM sales WHERE paymentMethod = 'mpesa' AND timestamp BETWEEN :start AND :end")
    fun getMpesaSalesBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT SUM(totalPrice) FROM sales WHERE paymentMethod = 'credit' AND timestamp BETWEEN :start AND :end")
    fun getCreditSalesBetween(start: Long, end: Long): Flow<Double?>

    @Delete
    suspend fun delete(sale: SaleEntity)
}

data class ProductSalesSummary(
    val productName: String,
    val totalQty: Double,
    val totalRevenue: Double
)

// ──────────────────────────────────────────────
// Product DAO
// ──────────────────────────────────────────────

@Dao
interface ProductDao {
    @Insert
    suspend fun insert(product: ProductEntity): Long

    @Update
    suspend fun update(product: ProductEntity)

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    fun getAllActive(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' AND isActive = 1")
    fun search(query: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE currentStock <= minStock AND isActive = 1")
    fun getLowStock(): Flow<List<ProductEntity>>

    @Query("UPDATE products SET currentStock = currentStock + :quantity, updatedAt = :now WHERE id = :productId")
    suspend fun addStock(productId: Long, quantity: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE products SET currentStock = currentStock - :quantity, updatedAt = :now WHERE id = :productId")
    suspend fun reduceStock(productId: Long, quantity: Double, now: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(product: ProductEntity)
}

// ──────────────────────────────────────────────
// Expense DAO
// ──────────────────────────────────────────────

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(expense: ExpenseEntity): Long

    @Query("SELECT * FROM expenses WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getExpensesBetween(start: Long, end: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT SUM(amount) FROM expenses WHERE timestamp BETWEEN :start AND :end")
    fun getTotalExpensesBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT category, SUM(amount) as total FROM expenses WHERE timestamp BETWEEN :start AND :end GROUP BY category ORDER BY total DESC")
    fun getExpensesByCategory(start: Long, end: Long): Flow<List<CategoryExpense>>

    @Delete
    suspend fun delete(expense: ExpenseEntity)
}

data class CategoryExpense(val category: String, val total: Double)

// ──────────────────────────────────────────────
// Customer DAO
// ──────────────────────────────────────────────

@Dao
interface CustomerDao {
    @Insert
    suspend fun insert(customer: CustomerEntity): Long

    @Update
    suspend fun update(customer: CustomerEntity)

    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE name LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE creditBalance > 0 ORDER BY creditBalance DESC")
    fun getCustomersWithDebt(): Flow<List<CustomerEntity>>

    @Query("UPDATE customers SET creditBalance = creditBalance + :amount WHERE id = :customerId")
    suspend fun addCredit(customerId: Long, amount: Double)

    @Query("UPDATE customers SET creditBalance = creditBalance - :amount WHERE id = :customerId")
    suspend fun reduceCredit(customerId: Long, amount: Double)
}

// ──────────────────────────────────────────────
// Daily Summary DAO
// ──────────────────────────────────────────────

@Dao
interface DailySummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: DailySummaryEntity)

    @Query("SELECT * FROM daily_summaries WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    fun getSummariesBetween(startDate: String, endDate: String): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    fun getRecentSummaries(limit: Int = 30): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries WHERE date = :date")
    suspend fun getByDate(date: String): DailySummaryEntity?
}

// ──────────────────────────────────────────────
// Stock Movement DAO
// ──────────────────────────────────────────────

@Dao
interface StockMovementDao {
    @Insert
    suspend fun insert(movement: StockMovementEntity): Long

    @Query("SELECT * FROM stock_movements WHERE productId = :productId ORDER BY timestamp DESC LIMIT :limit")
    fun getByProduct(productId: Long, limit: Int = 50): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getMovementsBetween(start: Long, end: Long): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE productId = :productId AND type = 'sale' AND timestamp >= :since ORDER BY timestamp ASC")
    fun getSalesSince(productId: Long, since: Long): Flow<List<StockMovementEntity>>
}

// ──────────────────────────────────────────────
// Restock Threshold DAO
// ──────────────────────────────────────────────

@Dao
interface RestockThresholdDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(threshold: RestockThresholdEntity): Long

    @Update
    suspend fun update(threshold: RestockThresholdEntity)

    @Query("SELECT * FROM restock_thresholds WHERE productId = :productId LIMIT 1")
    suspend fun get(productId: Long): RestockThresholdEntity?

    @Query("SELECT * FROM restock_thresholds")
    fun getAll(): Flow<List<RestockThresholdEntity>>

    @Delete
    suspend fun delete(threshold: RestockThresholdEntity)
}

// ──────────────────────────────────────────────
// Service Transaction DAO
// ──────────────────────────────────────────────

@Dao
interface ServiceTransactionDao {
    @Insert
    suspend fun insert(transaction: ServiceTransactionEntity): Long

    @Query("SELECT * FROM service_transactions WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getTransactionsBetween(start: Long, end: Long): Flow<List<ServiceTransactionEntity>>

    @Query("SELECT * FROM service_transactions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactions(limit: Int = 20): Flow<List<ServiceTransactionEntity>>

    @Query("SELECT SUM(totalCharged) FROM service_transactions WHERE timestamp BETWEEN :start AND :end")
    fun getTotalRevenueBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT SUM(labourCost) FROM service_transactions WHERE timestamp BETWEEN :start AND :end")
    fun getTotalLabourBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT SUM(materialsCost) FROM service_transactions WHERE timestamp BETWEEN :start AND :end")
    fun getTotalMaterialsBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT COUNT(*) FROM service_transactions WHERE timestamp BETWEEN :start AND :end")
    fun getTransactionCountBetween(start: Long, end: Long): Flow<Int>

    @Query("SELECT serviceName, COUNT(*) as count, SUM(totalCharged) as totalRevenue FROM service_transactions WHERE timestamp BETWEEN :start AND :end GROUP BY serviceName ORDER BY totalRevenue DESC LIMIT :limit")
    fun getTopServices(start: Long, end: Long, limit: Int = 5): Flow<List<ServiceSalesSummary>>

    @Query("SELECT SUM(totalCharged) FROM service_transactions WHERE paymentMethod = 'mpesa' AND timestamp BETWEEN :start AND :end")
    fun getMpesaRevenueBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT SUM(totalCharged) FROM service_transactions WHERE paymentMethod = 'credit' AND timestamp BETWEEN :start AND :end")
    fun getCreditRevenueBetween(start: Long, end: Long): Flow<Double?>

    @Delete
    suspend fun delete(transaction: ServiceTransactionEntity)
}

data class ServiceSalesSummary(
    val serviceName: String,
    val count: Int,
    val totalRevenue: Double
)

// ──────────────────────────────────────────────
// Service Menu DAO
// ──────────────────────────────────────────────

@Dao
interface ServiceMenuDao {
    @Insert
    suspend fun insert(service: ServiceMenuEntity): Long

    @Update
    suspend fun update(service: ServiceMenuEntity)

    @Query("SELECT * FROM service_menu WHERE isActive = 1 ORDER BY name ASC")
    fun getAllActive(): Flow<List<ServiceMenuEntity>>

    @Query("SELECT * FROM service_menu WHERE id = :id")
    suspend fun getById(id: Long): ServiceMenuEntity?

    @Query("SELECT * FROM service_menu WHERE name LIKE '%' || :query || '%' AND isActive = 1")
    fun search(query: String): Flow<List<ServiceMenuEntity>>

    @Query("SELECT * FROM service_menu WHERE category = :category AND isActive = 1")
    fun getByCategory(category: String): Flow<List<ServiceMenuEntity>>

    @Query("UPDATE service_menu SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Long)

    @Delete
    suspend fun delete(service: ServiceMenuEntity)
}

// ──────────────────────────────────────────────
// Conversation DAO
// ──────────────────────────────────────────────

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getSession(sessionId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE role = 'user' ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentUserMessages(limit: Int = 50): Flow<List<ConversationEntity>>

    @Query("DELETE FROM conversations WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM conversations WHERE sessionId = :sessionId")
    suspend fun getSessionCount(sessionId: String): Int

    @Query("DELETE FROM conversations WHERE id IN (SELECT id FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldestForSession(sessionId: String, count: Int)

    @Query("SELECT DISTINCT sessionId FROM conversations ORDER BY timestamp DESC")
    suspend fun getAllSessionIds(): List<String>
}

// ──────────────────────────────────────────────
// Knowledge DAO
// ──────────────────────────────────────────────

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: KnowledgeEntity): Long

    @Update
    suspend fun update(entry: KnowledgeEntity)

    @Query("SELECT * FROM knowledge_entries WHERE category = :category")
    fun getByCategory(category: String): Flow<List<KnowledgeEntity>>

    @Query("SELECT * FROM knowledge_entries WHERE category = :category AND key = :key")
    suspend fun getEntry(category: String, key: String): KnowledgeEntity?

    @Query("SELECT * FROM knowledge_entries WHERE category = :category AND key LIKE '%' || :query || '%'")
    fun search(category: String, query: String): Flow<List<KnowledgeEntity>>

    @Query("UPDATE knowledge_entries SET usageCount = usageCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementUsage(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM knowledge_entries ORDER BY usageCount DESC LIMIT :limit")
    fun getMostUsed(limit: Int = 20): Flow<List<KnowledgeEntity>>

    @Query("SELECT COUNT(*) FROM knowledge_entries WHERE category = :category")
    suspend fun getCategoryCount(category: String): Int

    @Query("DELETE FROM knowledge_entries WHERE id IN (SELECT id FROM knowledge_entries WHERE category = :category ORDER BY usageCount ASC, updatedAt ASC LIMIT :count)")
    suspend fun deleteLeastUsedForCategory(category: String, count: Int)
}

// ──────────────────────────────────────────────
// Bulk Order DAO
// ──────────────────────────────────────────────

@Dao
interface BulkOrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: BulkOrderEntity): Long

    @Query("SELECT * FROM bulk_orders WHERE orderId = :orderId")
    suspend fun getByOrderId(orderId: String): BulkOrderEntity?

    @Query("SELECT * FROM bulk_orders WHERE status IN ('OPEN', 'MINIMUM_MET') ORDER BY createdAt DESC")
    fun getOpenOrders(): Flow<List<BulkOrderEntity>>

    @Query("SELECT * FROM bulk_orders WHERE product LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchByProduct(query: String): Flow<List<BulkOrderEntity>>

    @Query("SELECT * FROM bulk_orders WHERE area LIKE '%' || :area || '%' AND status IN ('OPEN', 'MINIMUM_MET') ORDER BY createdAt DESC")
    fun getByArea(area: String): Flow<List<BulkOrderEntity>>

    @Query("UPDATE bulk_orders SET totalQuantityCommitted = :qty, updatedAt = :now WHERE orderId = :orderId")
    suspend fun updateCommittedQuantity(orderId: String, qty: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE bulk_orders SET status = :status, updatedAt = :now WHERE orderId = :orderId")
    suspend fun updateStatus(orderId: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE bulk_orders SET supplierName = :supplier, agreedPricePerUnit = :price, status = :status, updatedAt = :now WHERE orderId = :orderId")
    suspend fun updateNegotiation(orderId: String, supplier: String, price: Double, status: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM bulk_orders WHERE updatedAt < :before AND needsSync = 1")
    suspend fun getPendingSync(before: Long): List<BulkOrderEntity>

    @Query("UPDATE bulk_orders SET needsSync = 0 WHERE orderId = :orderId")
    suspend fun markSynced(orderId: String)

    @Delete
    suspend fun delete(order: BulkOrderEntity)
}

@Dao
interface BulkCommitmentDao {
    @Insert
    suspend fun insert(commitment: BulkCommitmentEntity): Long

    @Query("SELECT * FROM bulk_commitments WHERE orderId = :orderId ORDER BY createdAt ASC")
    fun getByOrderId(orderId: String): Flow<List<BulkCommitmentEntity>>

    @Query("SELECT * FROM bulk_commitments WHERE workerId = :workerId ORDER BY createdAt DESC")
    fun getByWorkerId(workerId: String): Flow<List<BulkCommitmentEntity>>

    @Query("SELECT * FROM bulk_commitments WHERE orderId = :orderId AND workerId = :workerId LIMIT 1")
    suspend fun getByOrderAndWorker(orderId: String, workerId: String): BulkCommitmentEntity?

    @Query("UPDATE bulk_commitments SET quantity = :qty, updatedAt = :now WHERE id = :id")
    suspend fun updateQuantity(id: Long, qty: Double, now: Long)

    @Query("UPDATE bulk_commitments SET paymentStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun updatePaymentStatus(id: Long, status: String, now: Long)

    @Query("SELECT * FROM bulk_commitments WHERE updatedAt < :before AND needsSync = 1")
    suspend fun getPendingSync(before: Long): List<BulkCommitmentEntity>

    @Query("UPDATE bulk_commitments SET needsSync = 0 WHERE id = :id")
    suspend fun markSynced(id: Long)
}

@Dao
interface BulkEscrowDao {
    @Insert
    suspend fun insert(escrow: BulkEscrowEntity): Long

    @Query("SELECT * FROM bulk_escrow WHERE orderId = :orderId ORDER BY createdAt ASC")
    fun getByOrderId(orderId: String): Flow<List<BulkEscrowEntity>>

    @Query("SELECT SUM(amount) FROM bulk_escrow WHERE orderId = :orderId AND type = 'DEPOSIT'")
    suspend fun getTotalDeposits(orderId: String): Double?

    @Query("SELECT * FROM bulk_escrow WHERE createdAt < :before AND needsSync = 1")
    suspend fun getPendingSync(before: Long): List<BulkEscrowEntity>

    @Query("UPDATE bulk_escrow SET needsSync = 0 WHERE id = :id")
    suspend fun markSynced(id: Long)
}

// ──────────────────────────────────────────────
// User Profile DAO
// ──────────────────────────────────────────────

@Dao
interface UserProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: UserProfileEntity)

    @Update
    suspend fun update(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun getProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun getProfileOnce(): UserProfileEntity?

    @Query("UPDATE user_profile SET isOnboarded = :onboarded WHERE id = 1")
    suspend fun setOnboarded(onboarded: Boolean)
}

// ──────────────────────────────────────────────
// Debt DAO
// ──────────────────────────────────────────────

@Dao
interface DebtDao {
    @Insert
    suspend fun insert(debt: DebtEntity): Long

    @Update
    suspend fun update(debt: DebtEntity)

    @Delete
    suspend fun delete(debt: DebtEntity)

    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun getById(id: Long): DebtEntity?

    @Query("SELECT * FROM debts WHERE status = 'active' ORDER BY createdAt DESC")
    fun getActiveDebts(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE customerName LIKE '%' || :name || '%' ORDER BY createdAt DESC")
    fun getByCustomer(name: String): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE status = 'active' AND outstandingBalance > 0 ORDER BY dueDate ASC")
    fun getOverdueDebts(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE status = 'active' AND dueDate IS NOT NULL AND dueDate < :now AND outstandingBalance > 0")
    fun getDebtsPastDue(now: Long): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE customerName = :name AND status = 'active'")
    fun getActiveByCustomer(name: String): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE customerName = :name ORDER BY createdAt DESC")
    fun getAllByCustomer(name: String): Flow<List<DebtEntity>>

    @Query("SELECT SUM(outstandingBalance) FROM debts WHERE status = 'active'")
    fun getTotalOutstanding(): Flow<Double?>

    @Query("SELECT SUM(outstandingBalance) FROM debts WHERE status = 'active' AND dueDate IS NOT NULL AND dueDate < :now")
    fun getTotalOverdue(now: Long): Flow<Double?>

    @Query("SELECT COUNT(*) FROM debts WHERE status = 'active' AND outstandingBalance > 0")
    fun getActiveDebtCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT customerName) FROM debts WHERE status = 'active' AND outstandingBalance > 0")
    fun getUniqueDebtorsCount(): Flow<Int>

    @Query("""
        SELECT 
            CASE
                WHEN dueDate IS NULL OR dueDate >= :now THEN 'current'
                WHEN :now - dueDate <= 30 * 86400000 THEN '30_days'
                WHEN :now - dueDate <= 60 * 86400000 THEN '60_days'
                ELSE '90_plus'
            END as bucket,
            COUNT(*) as debtCount,
            SUM(outstandingBalance) as totalAmount
        FROM debts
        WHERE status = 'active' AND outstandingBalance > 0
        GROUP BY bucket
    """)
    fun getAgingBuckets(now: Long): Flow<List<AgingBucket>>

    @Query("""
        SELECT 
            customerName,
            COUNT(*) as totalDebts,
            SUM(amount) as totalAmount,
            SUM(outstandingBalance) as totalOutstanding,
            SUM(amount - outstandingBalance) as totalRepaid,
            0 as onTimePayments,
            0 as latePayments,
            NULL as averageRepaymentDays
        FROM debts
        WHERE customerName = :name
        GROUP BY customerName
    """)
    suspend fun getCustomerSummary(name: String): CustomerDebtSummary?

    @Query("SELECT * FROM debts WHERE status = 'active' AND customerName = :name LIMIT 1")
    suspend fun getActiveDebtForCustomer(name: String): DebtEntity?

    @Query("UPDATE debts SET outstandingBalance = :balance, updatedAt = :now WHERE id = :debtId")
    suspend fun updateBalance(debtId: Long, balance: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE debts SET status = :status, updatedAt = :now WHERE id = :debtId")
    suspend fun updateStatus(debtId: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM debts ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentDebts(limit: Int = 20): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE status = 'active' ORDER BY outstandingBalance DESC LIMIT :limit")
    fun getTopDebtors(limit: Int = 10): Flow<List<DebtEntity>>

    @Query("SELECT SUM(amount) FROM debts WHERE createdAt BETWEEN :start AND :end")
    fun getDebtsCreatedBetween(start: Long, end: Long): Flow<Double?>

    @Query("SELECT SUM(amount - outstandingBalance) FROM debts WHERE updatedAt BETWEEN :start AND :end")
    fun getRepaymentsBetween(start: Long, end: Long): Flow<Double?>
}

// ──────────────────────────────────────────────
// Debt Repayment DAO
// ──────────────────────────────────────────────

@Dao
interface DebtRepaymentDao {
    @Insert
    suspend fun insert(repayment: DebtRepaymentEntity): Long

    @Query("SELECT * FROM debt_repayments WHERE debtId = :debtId ORDER BY timestamp DESC")
    fun getByDebt(debtId: Long): Flow<List<DebtRepaymentEntity>>

    @Query("SELECT * FROM debt_repayments WHERE debtId = :debtId ORDER BY timestamp DESC")
    suspend fun getByDebtOnce(debtId: Long): List<DebtRepaymentEntity>

    @Query("SELECT SUM(amount) FROM debt_repayments WHERE debtId = :debtId")
    suspend fun getTotalRepaid(debtId: Long): Double?

    @Query("SELECT COUNT(*) FROM debt_repayments WHERE debtId = :debtId")
    suspend fun getRepaymentCount(debtId: Long): Int

    @Query("SELECT * FROM debt_repayments WHERE debtId = :debtId AND timestamp > :dueDate ORDER BY timestamp ASC")
    suspend fun getLateRepayments(debtId: Long, dueDate: Long): List<DebtRepaymentEntity>

    @Query("SELECT * FROM debt_repayments WHERE debtId = :debtId AND timestamp <= :dueDate ORDER BY timestamp ASC")
    suspend fun getOnTimeRepayments(debtId: Long, dueDate: Long): List<DebtRepaymentEntity>

    @Query("SELECT * FROM debt_repayments WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getRepaymentsBetween(start: Long, end: Long): Flow<List<DebtRepaymentEntity>>

    @Query("SELECT SUM(amount) FROM debt_repayments WHERE timestamp BETWEEN :start AND :end")
    fun getTotalRepaymentsBetween(start: Long, end: Long): Flow<Double?>
}

// ──────────────────────────────────────────────
// Chama DAO
// ──────────────────────────────────────────────

@Dao
interface ChamaDao {
    @Insert
    suspend fun insert(chama: ChamaEntity): Long

    @Update
    suspend fun update(chama: ChamaEntity)

    @Query("SELECT * FROM chamas WHERE id = :id")
    suspend fun getById(id: Long): ChamaEntity?

    @Query("SELECT * FROM chamas WHERE isActive = 1 ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ChamaEntity>>

    @Query("SELECT * FROM chamas WHERE name LIKE '%' || :query || '%' AND isActive = 1")
    fun search(query: String): Flow<List<ChamaEntity>>

    @Query("UPDATE chamas SET savingsTarget = :target, updatedAt = :now WHERE id = :chamaId")
    suspend fun updateSavingsTarget(chamaId: Long, target: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE chamas SET isActive = 0, updatedAt = :now WHERE id = :chamaId")
    suspend fun deactivate(chamaId: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM chamas WHERE isActive = 1")
    fun getActiveChamaCount(): Flow<Int>
}

// ──────────────────────────────────────────────
// Chama Member DAO
// ──────────────────────────────────────────────

@Dao
interface ChamaMemberDao {
    @Insert
    suspend fun insert(member: ChamaMemberEntity): Long

    @Update
    suspend fun update(member: ChamaMemberEntity)

    @Query("SELECT * FROM chama_members WHERE chamaId = :chamaId AND isActive = 1 ORDER BY rotationOrder ASC")
    fun getByChama(chamaId: Long): Flow<List<ChamaMemberEntity>>

    @Query("SELECT * FROM chama_members WHERE chamaId = :chamaId AND phone = :phone AND isActive = 1")
    suspend fun getByPhone(chamaId: Long, phone: String): ChamaMemberEntity?

    @Query("SELECT * FROM chama_members WHERE chamaId = :chamaId AND name LIKE '%' || :name || '%' AND isActive = 1")
    suspend fun getByName(chamaId: Long, name: String): ChamaMemberEntity?

    @Query("SELECT * FROM chama_members WHERE id = :id")
    suspend fun getById(id: Long): ChamaMemberEntity?

    @Query("SELECT COUNT(*) FROM chama_members WHERE chamaId = :chamaId AND isActive = 1")
    suspend fun getMemberCount(chamaId: Long): Int

    @Query("UPDATE chama_members SET isActive = 0 WHERE id = :memberId")
    suspend fun deactivate(memberId: Long)
}

// ──────────────────────────────────────────────
// Chama Contribution DAO
// ──────────────────────────────────────────────

@Dao
interface ChamaContributionDao {
    @Insert
    suspend fun insert(contribution: ChamaContributionEntity): Long

    @Query("SELECT * FROM chama_contributions WHERE chamaId = :chamaId ORDER BY timestamp DESC")
    fun getByChama(chamaId: Long): Flow<List<ChamaContributionEntity>>

    @Query("SELECT * FROM chama_contributions WHERE chamaId = :chamaId AND memberId = :memberId ORDER BY timestamp DESC")
    fun getByMember(chamaId: Long, memberId: Long): Flow<List<ChamaContributionEntity>>

    @Query("SELECT SUM(amount) FROM chama_contributions WHERE chamaId = :chamaId")
    suspend fun getTotalContributed(chamaId: Long): Double?

    @Query("SELECT SUM(amount) FROM chama_contributions WHERE chamaId = :chamaId AND timestamp >= :sinceTimestamp")
    suspend fun getTotalContributedSince(chamaId: Long, sinceTimestamp: Long): Double?

    @Query("SELECT SUM(penalty) FROM chama_contributions WHERE chamaId = :chamaId")
    suspend fun getTotalPenalties(chamaId: Long): Double?

    @Query("SELECT SUM(penalty) FROM chama_contributions WHERE chamaId = :chamaId AND timestamp >= :sinceTimestamp")
    suspend fun getTotalPenaltiesSince(chamaId: Long, sinceTimestamp: Long): Double?

    @Query("SELECT COUNT(DISTINCT memberId) FROM chama_contributions WHERE chamaId = :chamaId AND timestamp >= :sinceTimestamp")
    suspend fun getUniqueContributorsSince(chamaId: Long, sinceTimestamp: Long): Int

    @Query("SELECT * FROM chama_contributions WHERE chamaId = :chamaId ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(chamaId: Long, limit: Int = 20): Flow<List<ChamaContributionEntity>>
}

// ──────────────────────────────────────────────
// Chama Payout DAO
// ──────────────────────────────────────────────

@Dao
interface ChamaPayoutDao {
    @Insert
    suspend fun insert(payout: ChamaPayoutEntity): Long

    @Query("SELECT * FROM chama_payouts WHERE chamaId = :chamaId ORDER BY cycleNumber DESC")
    fun getByChama(chamaId: Long): Flow<List<ChamaPayoutEntity>>

    @Query("SELECT * FROM chama_payouts WHERE chamaId = :chamaId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPayout(chamaId: Long): ChamaPayoutEntity?

    @Query("SELECT COUNT(*) FROM chama_payouts WHERE chamaId = :chamaId")
    suspend fun getPayoutCount(chamaId: Long): Int

    @Query("SELECT SUM(amount) FROM chama_payouts WHERE chamaId = :chamaId")
    suspend fun getTotalPaidOut(chamaId: Long): Double?
}

// ──────────────────────────────────────────────
// Market Pooling DAO
// ──────────────────────────────────────────────

@Dao
interface MarketPoolDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pool: MarketPoolEntity): Long

    @Query("SELECT * FROM market_pools WHERE poolId = :poolId")
    suspend fun getByPoolId(poolId: String): MarketPoolEntity?

    @Query("SELECT * FROM market_pools WHERE status = 'OPEN' ORDER BY tripDate ASC")
    fun getOpenPools(): Flow<List<MarketPoolEntity>>

    @Query("SELECT * FROM market_pools WHERE marketDestination LIKE '%' || :market || '%' ORDER BY tripDate DESC")
    fun getByMarket(market: String): Flow<List<MarketPoolEntity>>

    @Query("SELECT * FROM market_pools ORDER BY createdAt DESC")
    fun getAll(): Flow<List<MarketPoolEntity>>

    @Query("SELECT * FROM market_pools WHERE tripDate = :date AND status = 'OPEN'")
    fun getByDate(date: String): Flow<List<MarketPoolEntity>>

    @Query("UPDATE market_pools SET status = :status, updatedAt = :now WHERE poolId = :poolId")
    suspend fun updateStatus(poolId: String, status: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM market_pools WHERE updatedAt < :before AND needsSync = 1")
    suspend fun getPendingSync(before: Long): List<MarketPoolEntity>

    @Query("UPDATE market_pools SET needsSync = 0 WHERE poolId = :poolId")
    suspend fun markSynced(poolId: String)

    @Delete
    suspend fun delete(pool: MarketPoolEntity)
}

@Dao
interface MarketPoolMemberDao {
    @Insert
    suspend fun insert(member: MarketPoolMemberEntity): Long

    @Query("SELECT * FROM market_pool_members WHERE poolId = :poolId ORDER BY joinedAt ASC")
    fun getByPoolId(poolId: String): Flow<List<MarketPoolMemberEntity>>

    @Query("SELECT * FROM market_pool_members WHERE poolId = :poolId AND memberId = :memberId LIMIT 1")
    suspend fun getByPoolAndMember(poolId: String, memberId: String): MarketPoolMemberEntity?

    @Query("SELECT COUNT(*) FROM market_pool_members WHERE poolId = :poolId")
    suspend fun getCountByPool(poolId: String): Int

    @Query("SELECT * FROM market_pool_members WHERE memberId = :memberId")
    fun getByMemberId(memberId: String): Flow<List<MarketPoolMemberEntity>>

    @Query("DELETE FROM market_pool_members WHERE poolId = :poolId AND memberId = :memberId")
    suspend fun removeMember(poolId: String, memberId: String)
}

@Dao
interface MarketPoolTripDao {
    @Query("SELECT * FROM market_pools ORDER BY tripDate DESC")
    fun getAll(): Flow<List<MarketPoolEntity>>

    @Query("SELECT DISTINCT p.* FROM market_pools p INNER JOIN market_pool_members m ON p.poolId = m.poolId WHERE m.memberId = :memberId ORDER BY p.tripDate DESC")
    fun getByMemberId(memberId: String): Flow<List<MarketPoolEntity>>

    @Query("SELECT * FROM market_pools WHERE status = :status ORDER BY tripDate DESC")
    fun getByStatus(status: String): Flow<List<MarketPoolEntity>>
}

@Dao
interface MarketPoolOrderDao {
    @Insert
    suspend fun insert(order: MarketPoolOrderEntity): Long

    @Query("SELECT * FROM market_pool_orders WHERE poolId = :poolId ORDER BY createdAt ASC")
    fun getByPoolId(poolId: String): Flow<List<MarketPoolOrderEntity>>

    @Query("SELECT * FROM market_pool_orders WHERE poolId = :poolId AND memberId = :memberId")
    fun getByPoolAndMember(poolId: String, memberId: String): Flow<List<MarketPoolOrderEntity>>

    @Query("UPDATE market_pool_orders SET status = :status WHERE orderId = :orderId")
    suspend fun updateStatus(orderId: Long, status: String)

    @Query("UPDATE market_pool_orders SET actualPricePerUnit = :price, actualQuantity = :qty, status = 'bought' WHERE orderId = :orderId")
    suspend fun updateActual(orderId: Long, price: Double, qty: Double)
}

@Dao
interface MarketPoolContributionDao {
    @Insert
    suspend fun insert(contribution: MarketPoolContributionEntity): Long

    @Query("SELECT * FROM market_pool_contributions WHERE poolId = :poolId")
    fun getByPoolId(poolId: String): Flow<List<MarketPoolContributionEntity>>

    @Query("SELECT * FROM market_pool_contributions WHERE memberId = :memberId ORDER BY createdAt DESC")
    fun getByMemberId(memberId: String): Flow<List<MarketPoolContributionEntity>>

    @Query("SELECT SUM(amountPaid) FROM market_pool_contributions WHERE poolId = :poolId")
    suspend fun getTotalPaid(poolId: String): Double?

    @Query("UPDATE market_pool_contributions SET amountPaid = :amount, status = :status, paidAt = :paidAt WHERE contributionId = :id")
    suspend fun updatePayment(id: Long, amount: Double, status: String, paidAt: Long)
}

// ──────────────────────────────────────────────
// Customer Profile DAO
// ──────────────────────────────────────────────

@Dao
interface CustomerProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(profile: CustomerProfileEntity): Long

    @Update
    suspend fun update(profile: CustomerProfileEntity)

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId ORDER BY totalSpend DESC")
    fun getByWorker(workerId: String): Flow<List<CustomerProfileEntity>>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId AND customerKey = :customerKey")
    suspend fun getByKey(workerId: String, customerKey: String): CustomerProfileEntity?

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId AND customerKey = :customerKey")
    fun getByKeyFlow(workerId: String, customerKey: String): Flow<CustomerProfileEntity?>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId AND segment = :segment ORDER BY totalSpend DESC")
    fun getBySegment(workerId: String, segment: String): Flow<List<CustomerProfileEntity>>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId ORDER BY totalSpend DESC LIMIT :limit")
    fun getTopCustomers(workerId: String, limit: Int = 10): Flow<List<CustomerProfileEntity>>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId ORDER BY daysSinceLastVisit DESC LIMIT :limit")
    fun getByRecency(workerId: String, limit: Int = 20): Flow<List<CustomerProfileEntity>>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId AND creditOutstanding > 0 ORDER BY creditOutstanding DESC")
    fun getWithCredit(workerId: String): Flow<List<CustomerProfileEntity>>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId AND daysSinceLastVisit >= :threshold ORDER BY daysSinceLastVisit DESC")
    fun getChurnRisk(workerId: String, threshold: Int = 14): Flow<List<CustomerProfileEntity>>

    @Query("SELECT COUNT(*) FROM customer_profiles WHERE workerId = :workerId")
    suspend fun getCustomerCount(workerId: String): Int

    @Query("SELECT COUNT(*) FROM customer_profiles WHERE workerId = :workerId AND segment != 'new'")
    suspend fun getRepeatCustomerCount(workerId: String): Int

    @Query("SELECT SUM(totalSpend) FROM customer_profiles WHERE workerId = :workerId")
    suspend fun getTotalRevenue(workerId: String): Double?

    @Query("SELECT segment, COUNT(*) as count, SUM(totalSpend) as totalSpend, AVG(totalSpend) as avgSpend FROM customer_profiles WHERE workerId = :workerId GROUP BY segment")
    suspend fun getSegmentSummary(workerId: String): List<CustomerSegmentSummary>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId ORDER BY totalSpend DESC LIMIT :limit")
    suspend fun getTopCustomersOnce(workerId: String, limit: Int = 10): List<CustomerProfileEntity>

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId AND daysSinceLastVisit >= :threshold ORDER BY daysSinceLastVisit DESC")
    suspend fun getChurnRiskOnce(workerId: String, threshold: Int = 14): List<CustomerProfileEntity>

    @Query("SELECT SUM(creditOutstanding) FROM customer_profiles WHERE workerId = :workerId")
    suspend fun getTotalCreditOutstanding(workerId: String): Double?

    @Query("SELECT * FROM customer_profiles WHERE workerId = :workerId AND creditOutstanding > 0 ORDER BY creditOutstanding DESC")
    suspend fun getWithCreditOnce(workerId: String): List<CustomerProfileEntity>

    @Query("DELETE FROM customer_profiles WHERE workerId = :workerId")
    suspend fun deleteAllForWorker(workerId: String)
}

// ──────────────────────────────────────────────
// Customer Visit DAO
// ──────────────────────────────────────────────

@Dao
interface CustomerVisitDao {
    @Insert
    suspend fun insert(visit: CustomerVisitEntity): Long

    @Query("SELECT * FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey ORDER BY visitDate DESC")
    fun getByCustomer(workerId: String, customerKey: String): Flow<List<CustomerVisitEntity>>

    @Query("SELECT * FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey ORDER BY visitDate DESC")
    suspend fun getByCustomerOnce(workerId: String, customerKey: String): List<CustomerVisitEntity>

    @Query("SELECT * FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey ORDER BY visitDate DESC LIMIT :limit")
    suspend fun getRecentByCustomer(workerId: String, customerKey: String, limit: Int = 10): List<CustomerVisitEntity>

    @Query("SELECT * FROM customer_visits WHERE workerId = :workerId AND visitDate BETWEEN :startDate AND :endDate ORDER BY visitDate DESC")
    fun getVisitsBetween(workerId: String, startDate: String, endDate: String): Flow<List<CustomerVisitEntity>>

    @Query("SELECT * FROM customer_visits WHERE workerId = :workerId AND visitDate = :date")
    suspend fun getByDate(workerId: String, date: String): List<CustomerVisitEntity>

    @Query("SELECT COUNT(*) FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey")
    suspend fun getVisitCount(workerId: String, customerKey: String): Int

    @Query("SELECT SUM(amount) FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey")
    suspend fun getTotalSpend(workerId: String, customerKey: String): Double?

    @Query("SELECT COUNT(DISTINCT customerKey) FROM customer_visits WHERE workerId = :workerId AND visitDate BETWEEN :startDate AND :endDate")
    suspend fun getUniqueCustomersBetween(workerId: String, startDate: String, endDate: String): Int

    @Query("SELECT DISTINCT customerKey FROM customer_visits WHERE workerId = :workerId")
    suspend fun getAllCustomerKeys(workerId: String): List<String>

    @Query("SELECT MAX(visitDate) FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey")
    suspend fun getLastVisitDate(workerId: String, customerKey: String): String?

    @Query("SELECT COUNT(*) FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey AND visitDate BETWEEN :startDate AND :endDate")
    suspend fun getVisitCountBetween(workerId: String, customerKey: String, startDate: String, endDate: String): Int

    @Query("SELECT SUM(amount) FROM customer_visits WHERE workerId = :workerId AND customerKey = :customerKey AND visitDate BETWEEN :startDate AND :endDate")
    suspend fun getSpendBetween(workerId: String, customerKey: String, startDate: String, endDate: String): Double?

    @Query("DELETE FROM customer_visits WHERE workerId = :workerId")
    suspend fun deleteAllForWorker(workerId: String)
}
