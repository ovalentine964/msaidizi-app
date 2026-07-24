package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.CustomerDao
import com.msaidizi.app.core.database.ProductDao
import com.msaidizi.app.core.database.SaleDao
import com.msaidizi.app.core.database.ExpenseDao
import com.msaidizi.app.model.ExpenseEntity
import com.msaidizi.app.model.ProductEntity
import com.msaidizi.app.model.SaleEntity
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TransactionRecorder — Records business transactions from voice or text input.
 *
 * Handles sales, expenses, and stock purchases.
 * Parses voice input, validates data, and persists to Room DB.
 */
@Singleton
class TransactionRecorder @Inject constructor(
    private val saleDao: SaleDao,
    private val productDao: ProductDao,
    private val expenseDao: ExpenseDao,
    private val customerDao: CustomerDao,
    private val gson: Gson
) : Tool {

    override val name = "record_transaction"
    override val description = "Records a business transaction (sale, expense, or purchase)"

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val type = params["type"] ?: "sale"
        return when (type.lowercase()) {
            "sale" -> recordSale(params)
            "expense" -> recordExpense(params)
            "purchase" -> recordPurchase(params)
            else -> ToolResult.error(name, "Unknown transaction type: $type", "INVALID_TYPE")
        }
    }

    /**
     * Record a sale transaction.
     */
    suspend fun recordSale(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Amount is required", "MISSING_AMOUNT")
            val productName = params["product"] ?: "Unknown"
            val quantity = params["quantity"]?.toDoubleOrNull() ?: 1.0
            val paymentMethod = params["payment_method"] ?: "cash"
            val customerName = params["customer"]
            val unitPrice = amount / quantity

            // Validate amount
            val validation = validateAmount(amount)
            if (!validation.valid) {
                return ToolResult.error(name, validation.message, "INVALID_AMOUNT")
            }

            // Find or create product
            val product = findOrCreateProduct(productName, unitPrice)

            // Find customer if specified
            val customerId = customerName?.let { findCustomerId(it) }

            // Create sale record
            val sale = SaleEntity(
                productId = product.id,
                productName = productName,
                quantity = quantity,
                unitPrice = unitPrice,
                totalPrice = amount,
                paymentMethod = paymentMethod,
                customerId = customerId,
                customerName = customerName,
                timestamp = System.currentTimeMillis()
            )

            val saleId = saleDao.insert(sale)

            // Update product stock
            productDao.reduceStock(product.id, quantity)

            // If credit sale, update customer balance
            if (paymentMethod == "credit" && customerId != null) {
                customerDao.addCredit(customerId, amount)
            }

            Timber.d("Recorded sale: $productName x$quantity = Ksh $amount")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "sale_id" to saleId,
                    "product" to productName,
                    "quantity" to quantity,
                    "amount" to amount,
                    "payment_method" to paymentMethod
                ),
                message = "Sale recorded: $productName x${quantity.toInt()} = Ksh ${"%,.0f".format(amount)}"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record sale")
            ToolResult.error(name, "Failed to record sale: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Record an expense transaction.
     */
    suspend fun recordExpense(params: Map<String, String>): ToolResult {
        return try {
            val amount = params["amount"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Amount is required", "MISSING_AMOUNT")
            val category = params["category"] ?: "misc"
            val description = params["description"] ?: category

            val validation = validateAmount(amount)
            if (!validation.valid) {
                return ToolResult.error(name, validation.message, "INVALID_AMOUNT")
            }

            val expense = ExpenseEntity(
                category = category,
                description = description,
                amount = amount,
                timestamp = System.currentTimeMillis()
            )

            val expenseId = expenseDao.insert(expense)

            Timber.d("Recorded expense: $category - Ksh $amount")

            ToolResult.success(
                toolName = name,
                data = mapOf("expense_id" to expenseId, "category" to category, "amount" to amount),
                message = "Expense recorded: $category - Ksh ${"%,.0f".format(amount)}"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record expense")
            ToolResult.error(name, "Failed to record expense: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Record a stock purchase (adds inventory).
     */
    suspend fun recordPurchase(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Product name is required", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull() ?: 1.0
            val cost = params["cost"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Cost is required", "MISSING_COST")
            val unitCost = cost / quantity

            val product = findOrCreateProduct(productName, unitCost * 1.5) // Default 50% markup
            productDao.addStock(product.id, quantity)

            // Also record as expense
            val expense = ExpenseEntity(
                category = "stock",
                description = "Stock purchase: $productName x${quantity.toInt()}",
                amount = cost,
                timestamp = System.currentTimeMillis()
            )
            expenseDao.insert(expense)

            Timber.d("Recorded purchase: $productName x$quantity = Ksh $cost")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product_id" to product.id,
                    "product" to productName,
                    "quantity" to quantity,
                    "cost" to cost
                ),
                message = "Stock added: $productName x${quantity.toInt()} (Ksh ${"%,.0f".format(cost)})"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record purchase")
            ToolResult.error(name, "Failed to record purchase: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Parse voice input to extract transaction details.
     */
    fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        // Extract amount
        val amountRegex = Regex("""(?:ksh|kes|shillings?)\s*(\d+\.?\d*)|(\d+\.?\d*)\s*(?:ksh|kes|shillings?)""", RegexOption.IGNORE_CASE)
        amountRegex.find(text)?.let {
            params["amount"] = it.groupValues[1].ifEmpty { it.groupValues[2] }
        }

        // Extract quantity
        val qtyRegex = Regex("""(\d+)\s*(?:pieces?|pcs?|vifaa|makilo|kilo|bunch|bundl)""", RegexOption.IGNORE_CASE)
        qtyRegex.find(text)?.let {
            params["quantity"] = it.groupValues[1]
        }

        // Detect payment method
        when {
            text.contains(Regex("mpesa|m-pesa|pesa", RegexOption.IGNORE_CASE)) -> params["payment_method"] = "mpesa"
            text.contains(Regex("credit|deni|owe", RegexOption.IGNORE_CASE)) -> params["payment_method"] = "credit"
            else -> params["payment_method"] = "cash"
        }

        // Detect transaction type
        when {
            text.contains(Regex("nimeuza|sold|nimemuuza|nimemuuzia", RegexOption.IGNORE_CASE)) -> params["type"] = "sale"
            text.contains(Regex("nimenunua|bought|nimetia", RegexOption.IGNORE_CASE)) -> params["type"] = "purchase"
            text.contains(Regex("nimetumia|spent|nilipia", RegexOption.IGNORE_CASE)) -> params["type"] = "expense"
        }

        return params
    }

    /**
     * Validate a transaction amount.
     */
    fun validateAmount(amount: Double): AmountValidation {
        return when {
            amount <= 0 -> AmountValidation(false, "Amount must be positive")
            amount > 1_000_000 -> AmountValidation(false, "Amount exceeds maximum (Ksh 1,000,000)")
            amount < 1 -> AmountValidation(false, "Amount too small")
            else -> AmountValidation(true, "OK")
        }
    }

    private suspend fun findOrCreateProduct(name: String, sellPrice: Double): ProductEntity {
        val existing = productDao.search(name).first().firstOrNull()
        if (existing != null) return existing

        val product = ProductEntity(
            name = name,
            category = "general",
            unit = "piece",
            buyPrice = sellPrice * 0.7,
            sellPrice = sellPrice,
            currentStock = 0.0,
            minStock = 5.0
        )
        val id = productDao.insert(product)
        return product.copy(id = id)
    }

    private suspend fun findCustomerId(name: String): Long? {
        return customerDao.search(name).first().firstOrNull()?.id
    }
}

data class AmountValidation(val valid: Boolean, val message: String)
