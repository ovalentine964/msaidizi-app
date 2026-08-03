package com.msaidizi.agent.tools.inventory

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import timber.log.Timber
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * SupplierMatcher — Find, compare, rate, and recommend suppliers.
 *
 * Solves market inefficiencies (price comparison across suppliers) and
 * information asymmetry (supplier quality data from worker feedback).
 *
 * Voice-first: "Nani anauza nyanya cheap hapa Migori?"
 * Offline-first: all data persisted in local SQLite.
 * Learns from worker transactions which suppliers they actually use.
 */
@Singleton
class SupplierMatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) : Tool {

    override val name = "supplier_matcher"
    override val description = "Search, compare, rate, and recommend suppliers for products"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("search", "compare", "rate", "recommend", "add_supplier",
                "add_product", "record_transaction", "my_suppliers", "top_rated"),
            required = false)
        string("product", "Product name to search suppliers for", required = false)
        string("location", "Worker's location / area (e.g. Migori, Nairobi)", required = false)
        string("supplier", "Supplier name", required = false)
        string("payment_method", "Preferred payment method: cash, mpesa, credit, lipa_mdogo", required = false)
        number("order_size", "How many units the worker wants to buy", required = false)
        number("max_price", "Maximum price per unit in KES", required = false)
        number("price", "Price per unit in KES (for add_supplier / add_product)", required = false)
        number("min_order", "Minimum order quantity for this supplier", required = false)
        number("rating", "Rating 1-5 (for rate action)", required = false)
        string("comment", "Comment or feedback about a supplier", required = false)
        string("quality", "Quality feedback: fresh, good, average, poor", required = false)
        string("delivery", "Delivery options: pickup, delivers, both", required = false)
        string("phone", "Supplier phone number (M-Pesa)", required = false)
        string("notes", "Additional notes about supplier or product", required = false)
    }

    // ── SQLite helper ──

    private val dbHelper: SupplierDbHelper by lazy { SupplierDbHelper(context) }

    // ──────────────────────────────────────────────
    // Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "search"
        return when (action.lowercase()) {
            "search" -> searchSuppliers(params)
            "compare" -> compareSuppliers(params)
            "rate" -> rateSupplier(params)
            "recommend" -> recommendSupplier(params)
            "add_supplier" -> addSupplier(params)
            "add_product" -> addSupplierProduct(params)
            "record_transaction" -> recordTransaction(params)
            "my_suppliers" -> listMySuppliers(params)
            "top_rated" -> listTopRated(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // SEARCH — Find suppliers for a product in an area
    // ──────────────────────────────────────────────

    private fun searchSuppliers(params: Map<String, String>): ToolResult {
        val product = params["product"]
            ?: return ToolResult.error(name, "Product name required. Try: search product=nyanya", "MISSING_PRODUCT")
        val location = params["location"]
        val maxPrice = params["max_price"]?.toDoubleOrNull()

        val db = dbHelper.readableDatabase
        try {
            val results = querySupplierProducts(db, product, location, maxPrice)

            if (results.isEmpty()) {
                return ToolResult.success(
                    name,
                    data = emptyList<Map<String, Any?>>(),
                    message = "Sijaona supplier wa $product${if (location != null) " hapa $location" else ""}. " +
                            "Unaweza kuongeza supplier mpya kwa: add_supplier supplier=Jina product=$product price=XX"
                )
            }

            val output = buildString {
                appendLine("🔍 Suppliers wa $product${if (location != null) " hapa $location" else ""}:")
                appendLine()
                results.forEachIndexed { i, r ->
                    appendLine("${i + 1}. ${r["supplier_name"]}")
                    appendLine("   💰 Bei: Ksh ${"%,.0f".format(r["price"])}/${r["unit"] ?: "piece"}")
                    appendLine("   📦 Min order: ${r["min_order"]} ${r["unit"] ?: "pieces"}")
                    val rating = r["avg_rating"]
                    if (rating != null && (rating as Double) > 0) {
                        appendLine("   ⭐ Rating: ${"%.1f".format(rating)}/5 (${r["review_count"]} reviews)")
                    }
                    val delivery = r["delivery"]
                    if (delivery != null) appendLine("   🚚 Delivery: $delivery")
                    val quality = r["quality"]
                    if (quality != null) appendLine("   ✅ Quality: $quality")
                    appendLine()
                }
            }

            return ToolResult.success(name, data = results, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
            return ToolResult.error(name, "Search failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // COMPARE — Side-by-side price comparison
    // ──────────────────────────────────────────────

    private fun compareSuppliers(params: Map<String, String>): ToolResult {
        val product = params["product"]
            ?: return ToolResult.error(name, "Product name required for comparison", "MISSING_PRODUCT")
        val location = params["location"]
        val orderSize = params["order_size"]?.toDoubleOrNull() ?: 1.0

        val db = dbHelper.readableDatabase
        try {
            val results = querySupplierProducts(db, product, location, null)

            if (results.isEmpty()) {
                return ToolResult.success(name, message = "Hakuna suppliers wa $product kwenye database. Ongeza kwanza!")
            }

            // Filter by min_order <= orderSize
            val eligible = results.filter { r ->
                val minOrder = (r["min_order"] as? Number)?.toDouble() ?: 1.0
                orderSize >= minOrder
            }

            // Sort by total cost (price * orderSize)
            val sorted = (if (eligible.isEmpty()) results else eligible).sortedBy { r ->
                (r["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE
            }

            val output = buildString {
                appendLine("📊 Comparison: $product × ${orderSize.toInt()}")
                appendLine()
                sorted.forEachIndexed { i, r ->
                    val price = (r["price"] as? Number)?.toDouble() ?: 0.0
                    val total = price * orderSize
                    val minOrder = (r["min_order"] as? Number)?.toInt() ?: 1
                    val canFulfill = orderSize >= minOrder
                    val badge = if (i == 0 && canFulfill) "🏆 BEST" else if (canFulfill) "✅" else "⚠️ min ${minOrder}"

                    appendLine("$badge ${r["supplier_name"]}")
                    appendLine("   Ksh ${"%,.0f".format(price)} × ${orderSize.toInt()} = Ksh ${"%,.0f".format(total)}")
                    val rating = r["avg_rating"]
                    if (rating != null && (rating as Double) > 0) {
                        appendLine("   ⭐ ${"%.1f".format(rating)}/5")
                    }
                    val delivery = r["delivery"]
                    if (delivery != null) appendLine("   🚚 $delivery")
                    appendLine()
                }

                if (sorted.isNotEmpty()) {
                    val cheapest = sorted.first()
                    val cheapestPrice = (cheapest["price"] as? Number)?.toDouble() ?: 0.0
                    val mostExpensive = sorted.last()
                    val expensivePrice = (mostExpensive["price"] as? Number)?.toDouble() ?: 0.0
                    if (sorted.size > 1 && expensivePrice > cheapestPrice) {
                        val savings = (expensivePrice - cheapestPrice) * orderSize
                        appendLine("💡 Unaweza okoa Ksh ${"%,.0f".format(savings)} ukichagua ${cheapest["supplier_name"]}!")
                    }
                }
            }

            return ToolResult.success(name, data = sorted, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "Compare failed")
            return ToolResult.error(name, "Compare failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RATE — Worker rates a supplier
    // ──────────────────────────────────────────────

    private fun rateSupplier(params: Map<String, String>): ToolResult {
        val supplier = params["supplier"]
            ?: return ToolResult.error(name, "Supplier name required. Try: rate supplier=Jina rating=4", "MISSING_SUPPLIER")
        val rating = params["rating"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Rating required (1-5). Try: rating=4", "MISSING_RATING")

        if (rating < 1 || rating > 5) {
            return ToolResult.error(name, "Rating lazima iwe 1-5", "INVALID_RATING")
        }

        val comment = params["comment"]
        val quality = params["quality"]  // fresh, good, average, poor
        val delivery = params["delivery"] // on_time, late, early

        val db = dbHelper.writableDatabase
        try {
            val supplierId = findSupplierId(db, supplier)
                ?: return ToolResult.error(name, "Supplier '$supplier' haikupatikana. Ongeza kwanza na: add_supplier", "NOT_FOUND")

            val now = System.currentTimeMillis()
            db.execSQL(
                """INSERT INTO supplier_reviews (supplier_id, rating, quality, delivery_time, comment, created_at)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                arrayOf(supplierId, rating, quality, delivery, comment, now)
            )

            // Update supplier average rating
            updateSupplierRating(db, supplierId)

            val stars = "⭐".repeat(rating.toInt())
            return ToolResult.success(
                name,
                data = mapOf("supplier" to supplier, "rating" to rating, "quality" to quality),
                message = "Asante! $supplier amepata $stars (${rating.toInt()}/5). " +
                        "Reviews zako zinawasaidia wafanyakazi wengine kupata suppliers bora!"
            )
        } catch (e: Exception) {
            Timber.e(e, "Rate supplier failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RECOMMEND — Best supplier for worker's needs
    // ──────────────────────────────────────────────

    private fun recommendSupplier(params: Map<String, String>): ToolResult {
        val product = params["product"]
            ?: return ToolResult.error(name, "Product required. Try: recommend product=nyanya location=Migori", "MISSING_PRODUCT")
        val location = params["location"]
        val orderSize = params["order_size"]?.toDoubleOrNull() ?: 1.0
        val paymentMethod = params["payment_method"]
        val maxPrice = params["max_price"]?.toDoubleOrNull()

        val db = dbHelper.readableDatabase
        try {
            val candidates = querySupplierProducts(db, product, location, maxPrice)

            if (candidates.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Sijaona supplier wa $product${if (location != null) " hapa $location" else ""}. " +
                            "Weka supplier wako kwanza: add_supplier"
                )
            }

            // Score each supplier
            data class ScoredSupplier(val data: Map<String, Any?>, val score: Double)

            val scored = candidates.map { r ->
                var score = 0.0
                val price = (r["price"] as? Number)?.toDouble() ?: Double.MAX_VALUE
                val minOrder = (r["min_order"] as? Number)?.toDouble() ?: 1.0
                val avgRating = (r["avg_rating"] as? Number)?.toDouble() ?: 0.0
                val reviewCount = (r["review_count"] as? Number)?.toInt() ?: 0
                val supplierDelivery = r["delivery"]?.toString()?.lowercase() ?: ""
                val supplierPayment = r["payment_methods"]?.toString()?.lowercase() ?: ""

                // Price score (lower is better) — 40% weight
                val allPrices = candidates.mapNotNull { (it["price"] as? Number)?.toDouble() }
                if (allPrices.isNotEmpty()) {
                    val minP = allPrices.min()
                    val maxP = allPrices.max()
                    if (maxP > minP) {
                        score += (1.0 - (price - minP) / (maxP - minP)) * 40.0
                    } else {
                        score += 40.0
                    }
                }

                // Can fulfill order? — 15% weight
                if (orderSize >= minOrder) {
                    score += 15.0
                } else {
                    score -= 20.0 // Penalty for not meeting min order
                }

                // Rating score — 25% weight
                if (avgRating > 0) {
                    score += (avgRating / 5.0) * 25.0
                    // Bonus for having many reviews (more reliable data)
                    if (reviewCount >= 5) score += 5.0
                }

                // Delivery match — 10% weight
                if (supplierDelivery.contains("delivers") || supplierDelivery.contains("both")) {
                    score += 10.0
                }

                // Payment method match — 10% weight
                if (paymentMethod != null && supplierPayment.contains(paymentMethod.lowercase())) {
                    score += 10.0
                } else if (paymentMethod == null) {
                    score += 5.0 // No preference, slight bonus
                }

                ScoredSupplier(r, score)
            }.sortedByDescending { it.score }

            if (scored.isEmpty()) {
                return ToolResult.success(name, message = "Hakuna suppliers wanaofaa criteria zako.")
            }

            val best = scored.first()
            val bestData = best.data
            val price = (bestData["price"] as? Number)?.toDouble() ?: 0.0
            val total = price * orderSize

            val output = buildString {
                appendLine("🏆 Mapendekezo bora kwa $product:")
                appendLine()
                appendLine("👉 ${bestData["supplier_name"]}")
                appendLine("   💰 Bei: Ksh ${"%,.0f".format(price)} × ${orderSize.toInt()} = Ksh ${"%,.0f".format(total)}")
                val rating = bestData["avg_rating"]
                if (rating != null && (rating as Double) > 0) {
                    appendLine("   ⭐ Rating: ${"%.1f".format(rating)}/5 (${bestData["review_count"]} reviews)")
                }
                val delivery = bestData["delivery"]
                if (delivery != null) appendLine("   🚚 $delivery")
                val quality = bestData["quality"]
                if (quality != null) appendLine("   ✅ Quality: $quality")
                val phone = bestData["phone"]
                if (phone != null) appendLine("   📱 $phone")

                if (scored.size > 1) {
                    appendLine()
                    appendLine("Chaguo zingine:")
                    scored.drop(1).take(3).forEach { s ->
                        val d = s.data
                        val p = (d["price"] as? Number)?.toDouble() ?: 0.0
                        appendLine("   • ${d["supplier_name"]} — Ksh ${"%,.0f".format(p)}/${d["unit"] ?: "piece"}")
                    }
                }

                if (paymentMethod != null) {
                    appendLine()
                    appendLine("💳 Payment: $paymentMethod")
                }
            }

            return ToolResult.success(name, data = scored.map { it.data }, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "Recommend failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ADD SUPPLIER — Register a new supplier
    // ──────────────────────────────────────────────

    private fun addSupplier(params: Map<String, String>): ToolResult {
        val supplierName = params["supplier"]
            ?: return ToolResult.error(name, "Supplier name required. Try: add_supplier supplier=Jina", "MISSING_SUPPLIER")
        val location = params["location"]
        val phone = params["phone"]
        val delivery = params["delivery"]
        val notes = params["notes"]

        val db = dbHelper.writableDatabase
        try {
            // Check if supplier already exists
            val existing = findSupplierId(db, supplierName)
            if (existing != null) {
                return ToolResult.success(
                    name,
                    data = mapOf("supplier_id" to existing, "supplier" to supplierName),
                    message = "Supplier '$supplierName' tayari ipo kwenye database. Unaweza kuongeza bidhaa zake: add_product"
                )
            }

            val now = System.currentTimeMillis()
            db.execSQL(
                """INSERT INTO suppliers (name, location, phone, delivery_options, notes, avg_rating, review_count, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)""",
                arrayOf(supplierName, location, phone, delivery, notes, now, now)
            )

            val id = getLastInsertId(db)

            return ToolResult.success(
                name,
                data = mapOf("supplier_id" to id, "supplier" to supplierName),
                message = "✅ Supplier '$supplierName' ameongezwa! Sasa ongeza bidhaa zake: " +
                        "add_product supplier=$supplierName product=JinaLaBidhaa price=100"
            )
        } catch (e: Exception) {
            Timber.e(e, "Add supplier failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ADD PRODUCT — Add a product to a supplier's catalog
    // ──────────────────────────────────────────────

    private fun addSupplierProduct(params: Map<String, String>): ToolResult {
        val supplierName = params["supplier"]
            ?: return ToolResult.error(name, "Supplier name required", "MISSING_SUPPLIER")
        val product = params["product"]
            ?: return ToolResult.error(name, "Product name required. Try: add_product product=nyanya price=100", "MISSING_PRODUCT")
        val price = params["price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Price required in KES. Try: price=100", "MISSING_PRICE")
        val minOrder = params["min_order"]?.toDoubleOrNull() ?: 1.0
        val unit = params["notes"] // Reuse notes field for unit (e.g. kg, bunch, piece)

        val db = dbHelper.writableDatabase
        try {
            val supplierId = findSupplierId(db, supplierName)
                ?: return ToolResult.error(name, "Supplier '$supplierName' haikupatikana. add_supplier kwanza.", "NOT_FOUND")

            val now = System.currentTimeMillis()
            db.execSQL(
                """INSERT INTO supplier_products (supplier_id, product_name, price, min_order, unit, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(supplierId, product.lowercase(), price, minOrder, unit ?: "piece", now, now)
            )

            return ToolResult.success(
                name,
                data = mapOf("supplier" to supplierName, "product" to product, "price" to price),
                message = "✅ ${supplierName} sasa anauza $product kwa Ksh ${"%,.0f".format(price)} " +
                        "(min: ${minOrder.toInt()} ${unit ?: "pieces"})"
            )
        } catch (e: Exception) {
            Timber.e(e, "Add product failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RECORD TRANSACTION — Learn from actual purchases
    // ──────────────────────────────────────────────

    private fun recordTransaction(params: Map<String, String>): ToolResult {
        val supplierName = params["supplier"]
            ?: return ToolResult.error(name, "Supplier required", "MISSING_SUPPLIER")
        val product = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val price = params["price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Price required", "MISSING_PRICE")
        val orderSize = params["order_size"]?.toDoubleOrNull() ?: 1.0
        val quality = params["quality"]
        val notes = params["notes"]

        val db = dbHelper.writableDatabase
        try {
            val supplierId = findSupplierId(db, supplierName)
                ?: return ToolResult.error(name, "Supplier '$supplierName' haikupatikana.", "NOT_FOUND")

            val now = System.currentTimeMillis()
            db.execSQL(
                """INSERT INTO supplier_transactions
                   (supplier_id, product_name, price, quantity, quality, notes, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(supplierId, product.lowercase(), price, orderSize, quality, notes, now)
            )

            // Update the supplier's product price if it changed
            db.execSQL(
                """UPDATE supplier_products SET price = ?, updated_at = ?
                   WHERE supplier_id = ? AND product_name = ?""",
                arrayOf(price, now, supplierId, product.lowercase())
            )

            val total = price * orderSize
            return ToolResult.success(
                name,
                data = mapOf("supplier" to supplierName, "product" to product, "total" to total),
                message = "📝 Transaction recorded: $supplierName → $product × ${orderSize.toInt()} = Ksh ${"%,.0f".format(total)}. " +
                        "Data hii inasaidia kupata mapendekezo bora baadaye!"
            )
        } catch (e: Exception) {
            Timber.e(e, "Record transaction failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // MY SUPPLIERS — List suppliers the worker has used
    // ──────────────────────────────────────────────

    private fun listMySuppliers(params: Map<String, String>): ToolResult {
        val db = dbHelper.readableDatabase
        try {
            val results = mutableListOf<Map<String, Any?>>()
            val cursor = db.rawQuery(
                """SELECT s.name, s.location, s.phone, s.avg_rating, s.review_count,
                          s.delivery_options,
                          COUNT(DISTINCT t.id) as tx_count,
                          SUM(t.price * t.quantity) as total_spent,
                          GROUP_CONCAT(DISTINCT t.product_name) as products
                   FROM suppliers s
                   LEFT JOIN supplier_transactions t ON t.supplier_id = s.id
                   GROUP BY s.id
                   ORDER BY tx_count DESC""",
                null
            )

            cursor.use { c ->
                while (c.moveToNext()) {
                    results.add(cursorToMap(c))
                }
            }

            if (results.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hujawahi record na supplier yoyote. Anza na: add_supplier supplier=Jina"
                )
            }

            val output = buildString {
                appendLine("📋 Suppliers wako:")
                appendLine()
                results.forEachIndexed { i, r ->
                    val txCount = (r["tx_count"] as? Number)?.toInt() ?: 0
                    val totalSpent = (r["total_spent"] as? Number)?.toDouble() ?: 0.0
                    appendLine("${i + 1}. ${r["name"]}")
                    if (r["location"] != null) appendLine("   📍 ${r["location"]}")
                    val rating = r["avg_rating"]
                    if (rating != null && (rating as Double) > 0) {
                        appendLine("   ⭐ ${"%.1f".format(rating)}/5")
                    }
                    if (txCount > 0) {
                        appendLine("   🛒 $txCount transactions, Ksh ${"%,.0f".format(totalSpent)} total")
                    }
                    val products = r["products"]
                    if (products != null) appendLine("   📦 $products")
                    appendLine()
                }
            }

            return ToolResult.success(name, data = results, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "List suppliers failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // TOP RATED — Best rated suppliers
    // ──────────────────────────────────────────────

    private fun listTopRated(params: Map<String, String>): ToolResult {
        val product = params["product"]
        val location = params["location"]
        val limit = params["order_size"]?.toIntOrNull() ?: 10  // Reuse order_size as limit

        val db = dbHelper.readableDatabase
        try {
            val results = mutableListOf<Map<String, Any?>>()
            val query = buildString {
                append("""SELECT s.name, s.location, s.phone, s.avg_rating, s.review_count,
                                 s.delivery_options,
                                 GROUP_CONCAT(DISTINCT sp.product_name) as products
                          FROM suppliers s
                          LEFT JOIN supplier_products sp ON sp.supplier_id = s.id
                          WHERE s.review_count > 0""")
                if (product != null) append(" AND sp.product_name = ?")
                if (location != null) append(" AND s.location LIKE ?")
                append(""" GROUP BY s.id
                          ORDER BY s.avg_rating DESC, s.review_count DESC
                          LIMIT ?""")
            }

            val args = mutableListOf<String>()
            if (product != null) args.add(product.lowercase())
            if (location != null) args.add("%$location%")
            args.add(limit.toString())

            val cursor = db.rawQuery(query, args.toTypedArray())
            cursor.use { c ->
                while (c.moveToNext()) {
                    results.add(cursorToMap(c))
                }
            }

            if (results.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna suppliers waliopewa rating bado. Kuwa wa kwanza ku-rate supplier wako!"
                )
            }

            val output = buildString {
                appendLine("🏆 Top Rated Suppliers${if (product != null) " wa $product" else ""}${if (location != null) " hapa $location" else ""}:")
                appendLine()
                results.forEachIndexed { i, r ->
                    val rating = (r["avg_rating"] as? Number)?.toDouble() ?: 0.0
                    val reviews = (r["review_count"] as? Number)?.toInt() ?: 0
                    val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}." }
                    appendLine("$medal ${r["name"]} — ${"%.1f".format(rating)}/5 ($reviews reviews)")
                    val products = r["products"]
                    if (products != null) appendLine("   📦 $products")
                    val delivery = r["delivery_options"]
                    if (delivery != null) appendLine("   🚚 $delivery")
                }
            }

            return ToolResult.success(name, data = results, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "Top rated failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // Helper: Query supplier products with ratings
    // ──────────────────────────────────────────────

    private fun querySupplierProducts(
        db: SQLiteDatabase,
        product: String,
        location: String?,
        maxPrice: Double?
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val query = buildString {
            append("""SELECT s.name as supplier_name, s.location, s.phone, s.delivery_options,
                            sp.price, sp.min_order, sp.unit, sp.product_name as quality,
                            s.avg_rating, s.review_count,
                            (SELECT GROUP_CONCAT(DISTINCT pay) FROM (
                                SELECT 'cash' as pay UNION SELECT 'mpesa' UNION SELECT 'credit'
                            )) as payment_methods
                     FROM supplier_products sp
                     JOIN suppliers s ON s.id = sp.supplier_id
                     WHERE sp.product_name = ?""")
            if (location != null) append(" AND s.location LIKE ?")
            if (maxPrice != null) append(" AND sp.price <= ?")
            append(" ORDER BY sp.price ASC")
        }

        val args = mutableListOf<String>(product.lowercase())
        if (location != null) args.add("%$location%")
        if (maxPrice != null) args.add(maxPrice.toString())

        val cursor = db.rawQuery(query, args.toTypedArray())
        cursor.use { c ->
            while (c.moveToNext()) {
                results.add(cursorToMap(c))
            }
        }
        return results
    }

    private fun findSupplierId(db: SQLiteDatabase, name: String): Long? {
        val cursor = db.rawQuery("SELECT id FROM suppliers WHERE name = ? COLLATE NOCASE", arrayOf(name))
        cursor.use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private fun updateSupplierRating(db: SQLiteDatabase, supplierId: Long) {
        db.execSQL(
            """UPDATE suppliers SET
                avg_rating = (SELECT AVG(rating) FROM supplier_reviews WHERE supplier_id = ?),
                review_count = (SELECT COUNT(*) FROM supplier_reviews WHERE supplier_id = ?),
                updated_at = ?
               WHERE id = ?""",
            arrayOf(supplierId, supplierId, System.currentTimeMillis(), supplierId)
        )
    }

    private fun getLastInsertId(db: SQLiteDatabase): Long {
        val cursor = db.rawQuery("SELECT last_insert_rowid()", null)
        cursor.use { c ->
            return if (c.moveToFirst()) c.getLong(0) else -1
        }
    }

    private fun cursorToMap(cursor: Cursor): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            map[name] = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                else -> null
            }
        }
        return map
    }
}

// ──────────────────────────────────────────────
// SQLiteOpenHelper — Supplier database
// ──────────────────────────────────────────────

class SupplierDbHelper(context: Context) : SQLiteOpenHelper(
    context, "suppliers.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE suppliers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE,
                location TEXT,
                phone TEXT,
                delivery_options TEXT,
                notes TEXT,
                avg_rating REAL DEFAULT 0,
                review_count INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE supplier_products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                supplier_id INTEGER NOT NULL,
                product_name TEXT NOT NULL COLLATE NOCASE,
                price REAL NOT NULL,
                min_order REAL DEFAULT 1,
                unit TEXT DEFAULT 'piece',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE supplier_reviews (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                supplier_id INTEGER NOT NULL,
                rating REAL NOT NULL,
                quality TEXT,
                delivery_time TEXT,
                comment TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE supplier_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                supplier_id INTEGER NOT NULL,
                product_name TEXT NOT NULL COLLATE NOCASE,
                price REAL NOT NULL,
                quantity REAL NOT NULL,
                quality TEXT,
                notes TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE
            )
        """)

        // Indexes for fast queries
        db.execSQL("CREATE INDEX idx_products_name ON supplier_products(product_name)")
        db.execSQL("CREATE INDEX idx_products_supplier ON supplier_products(supplier_id)")
        db.execSQL("CREATE INDEX idx_reviews_supplier ON supplier_reviews(supplier_id)")
        db.execSQL("CREATE INDEX idx_transactions_supplier ON supplier_transactions(supplier_id)")
        db.execSQL("CREATE INDEX idx_transactions_product ON supplier_transactions(product_name)")
        db.execSQL("CREATE INDEX idx_suppliers_location ON suppliers(location)")
        db.execSQL("CREATE INDEX idx_suppliers_rating ON suppliers(avg_rating DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }
}
