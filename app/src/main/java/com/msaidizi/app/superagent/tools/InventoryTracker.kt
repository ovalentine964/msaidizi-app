package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.ProductDao
import com.msaidizi.app.core.database.StockMovementDao
import com.msaidizi.app.model.ProductEntity
import com.msaidizi.app.model.StockMovementEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InventoryTracker — Tracks product stock levels, alerts on low stock.
 *
 * Manages stock additions, removals, and provides restock predictions.
 */
@Singleton
class InventoryTracker @Inject constructor(
    private val productDao: ProductDao,
    private val stockMovementDao: StockMovementDao
) : Tool {

    override val name = "inventory_tracker"
    override val description = "Tracks product stock and provides restock alerts"

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "check"
        return when (action.lowercase()) {
            "check" -> checkLevel(params)
            "add" -> addStock(params)
            "remove" -> removeStock(params)
            "alerts" -> getRestockAlerts()
            "list" -> listProducts()
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    /**
     * Add stock for a product.
     */
    suspend fun addStock(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Quantity required", "MISSING_QUANTITY")

            if (quantity <= 0) return ToolResult.error(name, "Quantity must be positive", "INVALID_QUANTITY")

            val product = findProduct(productName)
                ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")

            val previousStock = product.currentStock
            productDao.addStock(product.id, quantity)

            // Record stock movement
            stockMovementDao.insert(
                StockMovementEntity(
                    productId = product.id,
                    type = "purchase",
                    quantity = quantity,
                    previousStock = previousStock,
                    newStock = previousStock + quantity,
                    notes = "Stock added"
                )
            )

            val newStock = previousStock + quantity
            Timber.d("Added stock: $productName +$quantity = $newStock")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to productName,
                    "added" to quantity,
                    "new_stock" to newStock,
                    "unit" to product.unit
                ),
                message = "$productName: added ${quantity.toInt()} ${product.unit}. New stock: ${newStock.toInt()} ${product.unit}"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to add stock")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Remove stock for a product (sale or spoilage).
     */
    suspend fun removeStock(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Quantity required", "MISSING_QUANTITY")
            val reason = params["reason"] ?: "sale"

            if (quantity <= 0) return ToolResult.error(name, "Quantity must be positive", "INVALID_QUANTITY")

            val product = findProduct(productName)
                ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")

            val previousStock = product.currentStock
            if (quantity > previousStock) {
                return ToolResult.error(
                    name,
                    "Not enough stock. Available: ${previousStock.toInt()} ${product.unit}",
                    "INSUFFICIENT_STOCK"
                )
            }

            productDao.reduceStock(product.id, quantity)

            stockMovementDao.insert(
                StockMovementEntity(
                    productId = product.id,
                    type = reason,
                    quantity = -quantity,
                    previousStock = previousStock,
                    newStock = previousStock - quantity,
                    notes = "Stock removed: $reason"
                )
            )

            val newStock = previousStock - quantity
            val alertMsg = if (newStock <= product.minStock) {
                " ⚠️ LOW STOCK! Consider restocking."
            } else ""

            Timber.d("Removed stock: $productName -$quantity = $newStock")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to productName,
                    "removed" to quantity,
                    "new_stock" to newStock,
                    "low_stock_alert" to (newStock <= product.minStock)
                ),
                message = "$productName: removed ${quantity.toInt()} ${product.unit}. Stock: ${newStock.toInt()}${alertMsg}"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove stock")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Check stock level for a specific product or all products.
     */
    suspend fun checkLevel(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]

            if (productName != null) {
                val product = findProduct(productName)
                    ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")

                val status = when {
                    product.currentStock <= 0 -> "OUT OF STOCK"
                    product.currentStock <= product.minStock -> "LOW STOCK"
                    else -> "OK"
                }

                ToolResult.success(
                    toolName = name,
                    data = mapOf(
                        "product" to product.name,
                        "stock" to product.currentStock,
                        "unit" to product.unit,
                        "min_stock" to product.minStock,
                        "status" to status
                    ),
                    message = "${product.name}: ${product.currentStock.toInt()} ${product.unit} ($status)"
                )
            } else {
                val products = productDao.getAllActive().first()
                val summary = products.joinToString("\n") { p ->
                    val status = when {
                        p.currentStock <= 0 -> "❌"
                        p.currentStock <= p.minStock -> "⚠️"
                        else -> "✅"
                    }
                    "$status ${p.name}: ${p.currentStock.toInt()} ${p.unit}"
                }

                ToolResult.success(
                    toolName = name,
                    data = products.map { mapOf("product" to it.name, "stock" to it.currentStock, "unit" to it.unit) },
                    message = if (summary.isEmpty()) "No products in inventory." else summary
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to check stock")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Get restock alerts for products below minimum stock.
     */
    suspend fun getRestockAlerts(): ToolResult {
        return try {
            val lowStock = productDao.getLowStock().first()

            if (lowStock.isEmpty()) {
                return ToolResult.success(name, message = "All products are well stocked! ✅")
            }

            val alerts = lowStock.joinToString("\n") { p ->
                val needed = (p.minStock * 2 - p.currentStock).coerceAtLeast(1.0)
                "⚠️ ${p.name}: ${p.currentStock.toInt()} ${p.unit} left (min: ${p.minStock.toInt()}). Order ~${needed.toInt()} ${p.unit}"
            }

            ToolResult.success(
                toolName = name,
                data = lowStock.map {
                    mapOf(
                        "product" to it.name,
                        "current_stock" to it.currentStock,
                        "min_stock" to it.minStock,
                        "unit" to it.unit
                    )
                },
                message = alerts
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get restock alerts")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * List all active products.
     */
    suspend fun listProducts(): ToolResult {
        return try {
            val products = productDao.getAllActive().first()
            if (products.isEmpty()) {
                return ToolResult.success(name, message = "No products yet. Add some with 'add product'.")
            }

            val list = products.joinToString("\n") { p ->
                "${p.name}: ${p.currentStock.toInt()} ${p.unit} (buy: Ksh ${p.buyPrice.toInt()}, sell: Ksh ${p.sellPrice.toInt()})"
            }

            ToolResult.success(
                toolName = name,
                data = products.map { mapOf("id" to it.id, "name" to it.name, "stock" to it.currentStock, "unit" to it.unit) },
                message = list
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    private suspend fun findProduct(name: String): ProductEntity? {
        return productDao.search(name).first().firstOrNull()
    }
}
