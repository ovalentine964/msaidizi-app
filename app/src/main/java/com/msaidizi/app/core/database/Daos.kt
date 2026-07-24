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
