package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.CustomerDao
import com.msaidizi.app.core.database.ProductDao
import com.msaidizi.app.core.database.SaleDao
import com.msaidizi.app.core.database.ExpenseDao
import com.msaidizi.app.core.database.ServiceTransactionDao
import com.msaidizi.app.core.database.ServiceMenuDao
import com.msaidizi.app.model.ExpenseEntity
import com.msaidizi.app.model.ProductEntity
import com.msaidizi.app.model.SaleEntity
import com.msaidizi.app.model.ServiceTransactionEntity
import com.msaidizi.app.model.ServiceMenuEntity
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
    private val serviceTransactionDao: ServiceTransactionDao,
    private val serviceMenuDao: ServiceMenuDao,
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
            "service" -> recordService(params)
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
     * Record a service transaction (fundi, salon, barber, tailor, etc.).
     * Tracks labour vs materials separately for profit analysis.
     */
    suspend fun recordService(params: Map<String, String>): ToolResult {
        return try {
            val serviceName = params["service"]
                ?: return ToolResult.error(name, "Service name is required", "MISSING_SERVICE")
            val totalCharged = params["amount"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Amount is required", "MISSING_AMOUNT")
            val labourCost = params["labour_cost"]?.toDoubleOrNull() ?: totalCharged * 0.7 // Default: 70% labour
            val materialsCost = params["materials_cost"]?.toDoubleOrNull() ?: totalCharged * 0.3 // Default: 30% materials
            val customerName = params["customer"]
            val paymentMethod = params["payment_method"] ?: "cash"
            val category = params["service_category"] ?: inferServiceCategory(serviceName)

            val validation = validateAmount(totalCharged)
            if (!validation.valid) {
                return ToolResult.error(name, validation.message, "INVALID_AMOUNT")
            }

            // Find or create service menu entry
            val menuItem = findOrCreateService(serviceName, totalCharged, category)
            serviceMenuDao.incrementUsage(menuItem.id)

            // Create service transaction
            val transaction = ServiceTransactionEntity(
                serviceName = serviceName,
                serviceCategory = category,
                labourCost = labourCost,
                materialsCost = materialsCost,
                totalCharged = totalCharged,
                customerName = customerName,
                paymentMethod = paymentMethod,
                timestamp = System.currentTimeMillis()
            )

            val transactionId = serviceTransactionDao.insert(transaction)

            // If credit, update customer balance
            if (paymentMethod == "credit" && customerName != null) {
                val customerId = findCustomerId(customerName)
                if (customerId != null) {
                    customerDao.addCredit(customerId, totalCharged)
                }
            }

            Timber.d("Recorded service: $serviceName = Ksh $totalCharged (labour=$labourCost, materials=$materialsCost)")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "transaction_id" to transactionId,
                    "service" to serviceName,
                    "category" to category,
                    "total_charged" to totalCharged,
                    "labour_cost" to labourCost,
                    "materials_cost" to materialsCost,
                    "customer" to (customerName ?: "walk-in"),
                    "payment_method" to paymentMethod
                ),
                message = "Service recorded: $serviceName - Ksh ${"%,.0f".format(totalCharged)} (labour: ${"%,.0f".format(labourCost)}, materials: ${"%,.0f".format(materialsCost)})"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record service")
            ToolResult.error(name, "Failed to record service: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Infer service category from the service name.
     */
    private fun inferServiceCategory(serviceName: String): String {
        val name = serviceName.lowercase()
        return when {
            name.contains(Regex("repair|fix|fundi|service|simu|phone|tv|fridge|pipe|plumb|electri|weld|kuleta")) -> "repair"
            name.contains(Regex("hair|nywele|salon|barber|kata|braid|nail|facial|massage|shave|dye|relaxer")) -> "beauty"
            name.contains(Regex("wash|clean|fua|safi|sweep|gauni|nguo|laundry")) -> "cleaning"
            name.contains(Regex("build|construct|jenga|paint|plaster|roof|fund|cement|tiling")) -> "construction"
            name.contains(Regex("shona|sew|tailor|stitch|embroider|alter|hem|dress")) -> "tailoring"
            name.contains(Regex("car|gari|motor|tire|brake|oil|engine|wash")) -> "automotive"
            else -> "general"
        }
    }

    /**
     * Find a service in the menu, or create a new entry.
     */
    private suspend fun findOrCreateService(name: String, basePrice: Double, category: String): ServiceMenuEntity {
        val existing = serviceMenuDao.search(name).first().firstOrNull()
        if (existing != null) return existing

        val service = ServiceMenuEntity(
            name = name,
            category = category,
            basePrice = basePrice
        )
        val id = serviceMenuDao.insert(service)
        return service.copy(id = id)
    }

    /**
     * Parse voice input to extract transaction details.
     * Enhanced to recognize service-related Swahili voice commands.
     */
    fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()

        // Extract amount — supports Swahili number words and digits
        val amount = extractAmountFromVoice(text)
        if (amount != null) {
            params["amount"] = amount.toString()
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

        // Detect transaction type — service patterns first (more specific)
        when {
            text.contains(Regex("nimefanya|nilifanya|nimetengeza|nimeshona|nimetengeneza|nimekata|nimesukuma|nimechana|nimesafisha|nimepaka|nimeweldi|nimesolder", RegexOption.IGNORE_CASE)) -> {
                params["type"] = "service"
                params["service"] = extractServiceName(text)
            }
            text.contains(Regex("nimeuza|sold|nimemuuza|nimemuuzia", RegexOption.IGNORE_CASE)) -> params["type"] = "sale"
            text.contains(Regex("nimenunua|bought|nimetia", RegexOption.IGNORE_CASE)) -> params["type"] = "purchase"
            text.contains(Regex("nimetumia|spent|nilipia", RegexOption.IGNORE_CASE)) -> params["type"] = "expense"
        }

        return params
    }

    /**
     * Extract service name from Swahili voice input.
     * Removes the action verb and amount, keeps the service description.
     */
    private fun extractServiceName(text: String): String {
        val lower = text.lowercase()
        // Remove common action verbs
        val verbs = listOf(
            "nimefanya", "nilifanya", "nimetengeza", "nimeshona", "nimetengeneza",
            "nimekata", "nimesukuma", "nimechana", "nimesafisha", "nimepaka",
            "nimeweldi", "nimesolder", "ya", "za", "kwa"
        )
        var cleaned = lower
        for (verb in verbs) {
            cleaned = cleaned.replace(verb, " ")
        }
        // Remove amounts and currency words
        cleaned = cleaned.replace(Regex("(?:ksh|kes|shillings?|mia|elfu|laki)\\s*\\d*|\\d+\\.?\\d*"), " ")
        // Remove common filler words
        cleaned = cleaned.replace(Regex("\b(na|ni|ile|hiyo|hii|mmoja|mbili|tatu|nne|tano|sita|saba|nane|tisa|kumi)\b"), " ")
        return cleaned.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "service" }
    }

    /**
     * Extract amount from voice input, including Swahili number words.
     * Supports: "mia tano" (500), "elfu moja" (1000), "mia mbili" (200), etc.
     */
    private fun extractAmountFromVoice(text: String): Double? {
        // Try standard currency pattern first
        val currencyRegex = Regex("""(?:ksh|kes|shillings?)\s*(\d+\.?\d*)|(\d+\.?\d*)\s*(?:ksh|kes|shillings?)""", RegexOption.IGNORE_CASE)
        currencyRegex.find(text)?.let {
            return it.groupValues[1].ifEmpty { it.groupValues[2] }.toDoubleOrNull()
        }

        // Try plain numbers
        val plainNumber = Regex("""(\d+\.?\d*)""").find(text)
        if (plainNumber != null && !text.lowercase().contains(Regex("mia|elfu|laki"))) {
            return plainNumber.groupValues[1].toDoubleOrNull()
        }

        // Parse Swahili number words
        val swahiliOnes = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9
        )
        val swahiliTens = mapOf(
            "kumi" to 10, "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
            "hamsini" to 50, "sitini" to 60, "sabini" to 70, "themanini" to 80, "tisini" to 90
        )

        val lower = text.lowercase()
        var total = 0.0

        // Handle "laki" (100,000) — not common in daily service transactions but supported
        val lakiPattern = Regex("laki\\s*(\\d+)")
        lakiPattern.find(lower)?.let {
            total += it.groupValues[1].toDouble() * 100_000
        }

        // Handle "elfu" (1000)
        val elfuPattern = Regex("elfu\\s*(\\d+)")
        elfuPattern.find(lower)?.let {
            total += it.groupValues[1].toDouble() * 1000
        }
        // Also check "elfu" with Swahili words
        for ((word, value) in swahiliOnes) {
            if (lower.contains("elfu $word")) {
                total += value * 1000
            }
        }

        // Handle "mia" (100)
        val miaPattern = Regex("mia\\s*(\\d+)")
        miaPattern.find(lower)?.let {
            total += it.groupValues[1].toDouble() * 100
        }
        // Also check "mia" with Swahili words
        for ((word, value) in swahiliOnes) {
            if (lower.contains("mia $word")) {
                total += value * 100
            }
        }
        // Handle standalone "mia" (meaning 100)
        if (lower.contains("mia") && !lower.contains(Regex("mia\\s+(mbili|tatu|nne|tano|sita|saba|nane|tisa|\\d)"))) {
            if (total == 0.0) total = 100.0
        }

        // Handle bare Swahili tens (e.g., "ishirini" = 20)
        for ((word, value) in swahiliTens) {
            if (lower.contains(word) && total == 0.0) {
                total += value
            }
        }

        return if (total > 0) total else null
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
