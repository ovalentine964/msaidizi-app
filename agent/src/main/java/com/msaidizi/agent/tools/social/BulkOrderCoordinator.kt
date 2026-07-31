package com.msaidizi.agent.tools.social

import com.msaidizi.core.database.BulkOrderDao
import com.msaidizi.core.database.BulkCommitmentDao
import com.msaidizi.core.database.BulkEscrowDao
import com.msaidizi.core.database.ProductDao
import com.msaidizi.core.model.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ──────────────────────────────────────────────
// Bulk Order Coordinator
// ──────────────────────────────────────────────

/**
 * BulkOrderCoordinator — Enables workers to pool purchases and save 20-30%.
 *
 * Solves coordination failure: individual workers overpay because they can't
 * organize group buying. This tool makes bulk purchasing as easy as saying
 * "Nataka kununua nyanya kwa jumla."
 *
 * Flow:
 *   1. create   → Worker starts a bulk order for a product
 *   2. invite   → Find nearby workers who need the same product
 *   3. commit   → Workers commit quantities + pay into M-Pesa escrow
 *   4. negotiate → Once min qty reached, negotiate bulk price with supplier
 *   5. distribute → Split bulk delivery to individual workers
 *   6. release   → Release escrow payments to supplier after delivery confirmed
 *
 * Offline-first: all data persisted in Room, synced when online.
 */
@Singleton
class BulkOrderCoordinator @Inject constructor(
    private val bulkOrderDao: BulkOrderDao,
    private val bulkCommitmentDao: BulkCommitmentDao,
    private val bulkEscrowDao: BulkEscrowDao,
    private val productDao: ProductDao
) : Tool {

    override val name = "bulk_order_coordinator"
    override val description = "Coordinate group buying — workers pool orders to get bulk discounts (20-30% savings)"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("create", "invite", "commit", "status", "negotiate",
                "distribute", "release", "cancel", "my_orders", "browse"),
            required = false)
        string("order_id", "Bulk order ID (for commit/status/negotiate/distribute/release/cancel)", required = false)
        string("product", "Product name", required = false)
        number("quantity", "Quantity needed", required = false)
        number("target_price", "Target price per unit in KES", required = false)
        string("unit", "Unit of measure (kg, piece, bunch, litre)", required = false)
        string("deadline", "Order deadline (e.g. '3d', '1w', '2026-07-30')", required = false)
        string("worker_id", "Worker ID committing to the order", required = false)
        string("worker_name", "Worker name", required = false)
        string("phone", "M-Pesa phone number for escrow", required = false)
        string("area", "Area/location for finding nearby workers", required = false)
        string("supplier", "Supplier name (for negotiation)", required = false)
        number("agreed_price", "Negotiated bulk price per unit", required = false)
        boolean("delivery_confirmed", "Whether delivery was received (for escrow release)", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "browse"
        return when (action.lowercase()) {
            "create" -> createOrder(params)
            "invite" -> inviteNearby(params)
            "commit" -> commitToOrder(params)
            "status" -> orderStatus(params)
            "negotiate" -> negotiatePrice(params)
            "distribute" -> distributeOrder(params)
            "release" -> releaseEscrow(params)
            "cancel" -> cancelOrder(params)
            "my_orders" -> myOrders(params)
            "browse" -> browseOrders(params)
            else -> ToolResult.error(name, "Unknown action: $action. Try: create, invite, commit, status, negotiate, distribute, release, cancel, my_orders, browse", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // 1. CREATE — Start a new bulk order
    // ──────────────────────────────────────────────

    private suspend fun createOrder(params: Map<String, String>): ToolResult {
        return try {
            val product = params["product"]
                ?: return ToolResult.error(name, "Product name required. Example: 'Nyanya', 'Mafuta'", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Quantity required. How much do you need?", "MISSING_QUANTITY")
            val targetPrice = params["target_price"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Target price per unit required in KES", "MISSING_TARGET_PRICE")
            val unit = params["unit"] ?: "piece"
            val area = params["area"] ?: ""
            val workerId = params["worker_id"] ?: "current_user"
            val workerName = params["worker_name"] ?: "You"
            val phone = params["phone"] ?: ""
            val deadline = params["deadline"] ?: "3d"

            if (quantity <= 0) return ToolResult.error(name, "Quantity must be positive", "INVALID_QUANTITY")
            if (targetPrice <= 0) return ToolResult.error(name, "Target price must be positive", "INVALID_PRICE")

            val deadlineTimestamp = parseDeadline(deadline)
            val orderId = generateOrderId()

            val order = BulkOrderEntity(
                orderId = orderId,
                product = product,
                unit = unit,
                targetPricePerUnit = targetPrice,
                totalQuantityNeeded = quantity,
                totalQuantityCommitted = 0.0,
                minimumQuantity = (quantity * 0.5).coerceAtLeast(1.0), // 50% minimum to proceed
                area = area,
                creatorWorkerId = workerId,
                creatorName = workerName,
                creatorPhone = phone,
                status = BulkOrderStatus.OPEN.name,
                deadline = deadlineTimestamp,
                supplierName = null,
                agreedPricePerUnit = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                needsSync = true
            )

            bulkOrderDao.insert(order)

            // Auto-commit creator's own quantity
            val commitment = BulkCommitmentEntity(
                orderId = orderId,
                workerId = workerId,
                workerName = workerName,
                phone = phone,
                quantity = quantity,
                amountPaid = 0.0,
                paymentStatus = PaymentStatus.PENDING.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                needsSync = true
            )
            bulkCommitmentDao.insert(commitment)
            bulkOrderDao.updateCommittedQuantity(orderId, quantity)

            Timber.d("Bulk order created: $orderId for $product")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "order_id" to orderId,
                    "product" to product,
                    "quantity" to quantity,
                    "unit" to unit,
                    "target_price" to targetPrice,
                    "minimum_quantity" to order.minimumQuantity,
                    "deadline" to deadline,
                    "area" to area,
                    "status" to "OPEN"
                ),
                message = "✅ Bulk order created!\n" +
                    "📦 $product — ${quantity.toInt()} $unit\n" +
                    "🎯 Target: Ksh ${targetPrice.toInt()}/$unit\n" +
                    "📍 Area: ${area.ifEmpty { "Any" }}\n" +
                    "⏰ Deadline: $deadline\n" +
                    "🆔 Order: $orderId\n\n" +
                    "You've committed ${quantity.toInt()} $unit. Share this order ID with nearby workers to join!"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create bulk order")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 2. INVITE — Find nearby workers who need same product
    // ──────────────────────────────────────────────

    private suspend fun inviteNearby(params: Map<String, String>): ToolResult {
        return try {
            val orderId = params["order_id"]
                ?: return ToolResult.error(name, "Order ID required", "MISSING_ORDER_ID")

            val order = bulkOrderDao.getByOrderId(orderId)
                ?: return ToolResult.error(name, "Order not found: $orderId", "NOT_FOUND")

            if (order.status != BulkOrderStatus.OPEN.name) {
                return ToolResult.error(name, "Order is ${order.status}, cannot invite", "ORDER_NOT_OPEN")
            }

            val remaining = order.totalQuantityNeeded - order.totalQuantityCommitted
            val percentFilled = if (order.totalQuantityNeeded > 0)
                (order.totalQuantityCommitted / order.totalQuantityNeeded * 100).toInt() else 0

            // Generate invitation message in Kiswahili + English
            val inviteMessage = buildString {
                appendLine("🔔 *Bulk Order — ${order.product}*")
                appendLine()
                appendLine("Tafuta wafanyakazi wenzako kununua pamoja!")
                appendLine("Find coworkers to buy together!")
                appendLine()
                appendLine("📦 Product: ${order.product}")
                appendLine("🎯 Target: Ksh ${order.targetPricePerUnit.toInt()}/${order.unit}")
                appendLine("📊 Progress: ${order.totalQuantityCommitted.toInt()}/${order.totalQuantityNeeded.toInt()} ${order.unit} ($percentFilled%)")
                appendLine("📍 Area: ${order.area.ifEmpty { "Flexible" }}")
                appendLine("⏰ Deadline: ${formatTimestamp(order.deadline)}")
                appendLine()
                if (remaining > 0) {
                    appendLine("Bado tunahitaji: ${remaining.toInt()} ${order.unit}")
                    appendLine("Still needed: ${remaining.toInt()} ${order.unit}")
                }
                appendLine()
                appendLine("📱 Join: Send 'commit ${order.orderId} [quantity] [phone]'")
                appendLine("📱 Jiunge: Tuma 'commit ${order.orderId} [idadi] [namba]'")
            }

            // Find potential matches: open orders for same product in same area
            val similarOrders = bulkOrderDao.searchByProduct(order.product).first()
            val potentialPartners = similarOrders
                .filter { it.orderId != orderId && it.status == BulkOrderStatus.OPEN.name }
                .map { "${it.creatorName} (${it.area.ifEmpty { "unknown area" }}) — ${it.totalQuantityCommitted.toInt()}/${it.totalQuantityNeeded.toInt()} ${it.unit}" }

            Timber.d("Invitation generated for order $orderId")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "order_id" to orderId,
                    "product" to order.product,
                    "invite_message" to inviteMessage,
                    "similar_orders" to potentialPartners,
                    "remaining_quantity" to remaining,
                    "percent_filled" to percentFilled
                ),
                message = inviteMessage + if (potentialPartners.isNotEmpty()) {
                    "\n\n👥 Other workers buying ${order.product}:\n" + potentialPartners.joinToString("\n") { "• $it" }
                } else ""
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate invite")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. COMMIT — Worker commits quantity + payment
    // ──────────────────────────────────────────────

    private suspend fun commitToOrder(params: Map<String, String>): ToolResult {
        return try {
            val orderId = params["order_id"]
                ?: return ToolResult.error(name, "Order ID required", "MISSING_ORDER_ID")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Quantity required", "MISSING_QUANTITY")
            val workerId = params["worker_id"] ?: "user_${System.currentTimeMillis()}"
            val workerName = params["worker_name"] ?: "Worker"
            val phone = params["phone"] ?: ""

            if (quantity <= 0) return ToolResult.error(name, "Quantity must be positive", "INVALID_QUANTITY")

            val order = bulkOrderDao.getByOrderId(orderId)
                ?: return ToolResult.error(name, "Order not found: $orderId", "NOT_FOUND")

            if (order.status != BulkOrderStatus.OPEN.name) {
                return ToolResult.error(name, "Order is ${order.status}, cannot commit", "ORDER_NOT_OPEN")
            }

            if (System.currentTimeMillis() > order.deadline) {
                bulkOrderDao.updateStatus(orderId, BulkOrderStatus.EXPIRED.name)
                return ToolResult.error(name, "Order deadline has passed", "ORDER_EXPIRED")
            }

            // Check if worker already committed
            val existing = bulkCommitmentDao.getByOrderAndWorker(orderId, workerId)
            if (existing != null) {
                // Update existing commitment
                val newQty = existing.quantity + quantity
                bulkCommitmentDao.updateQuantity(existing.id, newQty, System.currentTimeMillis())
                bulkOrderDao.updateCommittedQuantity(orderId, order.totalQuantityCommitted + quantity)
            } else {
                // New commitment
                val commitment = BulkCommitmentEntity(
                    orderId = orderId,
                    workerId = workerId,
                    workerName = workerName,
                    phone = phone,
                    quantity = quantity,
                    amountPaid = 0.0,
                    paymentStatus = PaymentStatus.PENDING.name,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    needsSync = true
                )
                bulkCommitmentDao.insert(commitment)
                bulkOrderDao.updateCommittedQuantity(orderId, order.totalQuantityCommitted + quantity)
            }

            val newTotal = order.totalQuantityCommitted + quantity
            val percentFilled = (newTotal / order.totalQuantityNeeded * 100).toInt()
            val reachedMinimum = newTotal >= order.minimumQuantity

            val msg = buildString {
                appendLine("✅ Committed: ${quantity.toInt()} ${order.unit} of ${order.product}")
                appendLine("📊 Total: ${newTotal.toInt()}/${order.totalQuantityNeeded.toInt()} ${order.unit} ($percentFilled%)")
                if (reachedMinimum) {
                    appendLine("🎉 Minimum reached! Ready to negotiate with supplier!")
                } else {
                    val needed = order.minimumQuantity - newTotal
                    appendLine("⏳ Need ${needed.toInt()} more ${order.unit} to reach minimum for bulk price")
                }
                if (phone.isNotEmpty()) {
                    appendLine("📱 M-Pesa: $phone (for escrow)")
                }
            }

            // Auto-update status if minimum reached
            if (reachedMinimum && order.status == BulkOrderStatus.OPEN.name) {
                bulkOrderDao.updateStatus(orderId, BulkOrderStatus.MINIMUM_MET.name)
            }

            Timber.d("Worker $workerId committed $quantity to order $orderId")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "order_id" to orderId,
                    "worker_id" to workerId,
                    "quantity_committed" to quantity,
                    "total_committed" to newTotal,
                    "percent_filled" to percentFilled,
                    "minimum_reached" to reachedMinimum
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to commit to order")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 4. STATUS — Check bulk order status
    // ──────────────────────────────────────────────

    private suspend fun orderStatus(params: Map<String, String>): ToolResult {
        return try {
            val orderId = params["order_id"]
                ?: return ToolResult.error(name, "Order ID required", "MISSING_ORDER_ID")

            val order = bulkOrderDao.getByOrderId(orderId)
                ?: return ToolResult.error(name, "Order not found: $orderId", "NOT_FOUND")

            val commitments = bulkCommitmentDao.getByOrderId(orderId).first()
            val escrows = bulkEscrowDao.getByOrderId(orderId).first()

            val percentFilled = if (order.totalQuantityNeeded > 0)
                (order.totalQuantityCommitted / order.totalQuantityNeeded * 100).toInt() else 0

            val totalEscrow = escrows.sumOf { it.amount }
            val statusEmoji = when (BulkOrderStatus.valueOf(order.status)) {
                BulkOrderStatus.OPEN -> "🟢"
                BulkOrderStatus.MINIMUM_MET -> "🟡"
                BulkOrderStatus.NEGOTIATING -> "🟠"
                BulkOrderStatus.CONFIRMED -> "🔵"
                BulkOrderStatus.DISTRIBUTING -> "📦"
                BulkOrderStatus.COMPLETED -> "✅"
                BulkOrderStatus.CANCELLED -> "❌"
                BulkOrderStatus.EXPIRED -> "⏰"
            }

            val msg = buildString {
                appendLine("$statusEmoji *Bulk Order: ${order.product}*")
                appendLine("🆔 ${order.orderId}")
                appendLine("📊 Status: ${order.status}")
                appendLine()
                appendLine("📦 Quantity: ${order.totalQuantityCommitted.toInt()}/${order.totalQuantityNeeded.toInt()} ${order.unit} ($percentFilled%)")
                appendLine("🎯 Target: Ksh ${order.targetPricePerUnit.toInt()}/${order.unit}")
                order.agreedPricePerUnit?.let {
                    val savings = ((order.targetPricePerUnit - it) / order.targetPricePerUnit * 100).toInt()
                    appendLine("💰 Agreed: Ksh ${it.toInt()}/${order.unit} (saves $savings%)")
                }
                appendLine("⏰ Deadline: ${formatTimestamp(order.deadline)}")
                appendLine("👥 ${commitments.size} workers committed")
                appendLine("💵 Escrow: Ksh ${totalEscrow.toInt()}")
                appendLine()
                appendLine("── Workers ──")
                commitments.forEach { c ->
                    val payStatus = when (PaymentStatus.valueOf(c.paymentStatus)) {
                        PaymentStatus.PENDING -> "⏳"
                        PaymentStatus.IN_ESCROW -> "🔒"
                        PaymentStatus.RELEASED -> "✅"
                        PaymentStatus.REFUNDED -> "↩️"
                    }
                    appendLine("$payStatus ${c.workerName}: ${c.quantity.toInt()} ${order.unit} — Ksh ${c.amountPaid.toInt()}")
                }
                if (order.supplierName != null) {
                    appendLine()
                    appendLine("🏪 Supplier: ${order.supplierName}")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "order" to order,
                    "commitments" to commitments,
                    "escrows" to escrows,
                    "percent_filled" to percentFilled,
                    "total_escrow" to totalEscrow
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get order status")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. NEGOTIATE — Set bulk price with supplier
    // ──────────────────────────────────────────────

    private suspend fun negotiatePrice(params: Map<String, String>): ToolResult {
        return try {
            val orderId = params["order_id"]
                ?: return ToolResult.error(name, "Order ID required", "MISSING_ORDER_ID")
            val supplier = params["supplier"]
                ?: return ToolResult.error(name, "Supplier name required", "MISSING_SUPPLIER")
            val agreedPrice = params["agreed_price"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Agreed price per unit required in KES", "MISSING_PRICE")

            val order = bulkOrderDao.getByOrderId(orderId)
                ?: return ToolResult.error(name, "Order not found: $orderId", "NOT_FOUND")

            if (order.status != BulkOrderStatus.OPEN.name &&
                order.status != BulkOrderStatus.MINIMUM_MET.name) {
                return ToolResult.error(name, "Order is ${order.status}, cannot negotiate", "INVALID_STATUS")
            }

            if (order.totalQuantityCommitted < order.minimumQuantity) {
                return ToolResult.error(
                    name,
                    "Minimum not reached yet. Need ${order.minimumQuantity.toInt()}, have ${order.totalQuantityCommitted.toInt()}",
                    "BELOW_MINIMUM"
                )
            }

            bulkOrderDao.updateNegotiation(orderId, supplier, agreedPrice, BulkOrderStatus.CONFIRMED.name)

            val savings = ((order.targetPricePerUnit - agreedPrice) / order.targetPricePerUnit * 100).toInt()
            val totalCost = agreedPrice * order.totalQuantityCommitted
            val totalSavings = (order.targetPricePerUnit - agreedPrice) * order.totalQuantityCommitted

            val msg = buildString {
                appendLine("🤝 *Deal Confirmed!*")
                appendLine()
                appendLine("🏪 Supplier: $supplier")
                appendLine("📦 ${order.product}: ${order.totalQuantityCommitted.toInt()} ${order.unit}")
                appendLine("💰 Price: Ksh ${agreedPrice.toInt()}/${order.unit}")
                appendLine("💵 Total cost: Ksh ${totalCost.toInt()}")
                if (savings > 0) {
                    appendLine("🎉 Savings: $savings% (Ksh ${totalSavings.toInt()} total!)")
                }
                appendLine()
                appendLine("Workers: pay your share to escrow to confirm delivery.")
                appendLine("Wafanyakazi: lipa mgawo wako kwa escrow ili kuthibitisha utoaji.")
            }

            Timber.d("Negotiation complete for order $orderId: Ksh $agreedPrice/unit from $supplier")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "order_id" to orderId,
                    "supplier" to supplier,
                    "agreed_price" to agreedPrice,
                    "total_cost" to totalCost,
                    "savings_percent" to savings,
                    "total_savings" to totalSavings
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to negotiate price")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 6. DISTRIBUTE — Split bulk order to workers
    // ──────────────────────────────────────────────

    private suspend fun distributeOrder(params: Map<String, String>): ToolResult {
        return try {
            val orderId = params["order_id"]
                ?: return ToolResult.error(name, "Order ID required", "MISSING_ORDER_ID")

            val order = bulkOrderDao.getByOrderId(orderId)
                ?: return ToolResult.error(name, "Order not found: $orderId", "NOT_FOUND")

            if (order.status != BulkOrderStatus.CONFIRMED.name) {
                return ToolResult.error(name, "Order must be CONFIRMED before distributing. Current: ${order.status}", "INVALID_STATUS")
            }

            val commitments = bulkCommitmentDao.getByOrderId(orderId).first()
            if (commitments.isEmpty()) {
                return ToolResult.error(name, "No commitments found", "NO_COMMITMENTS")
            }

            bulkOrderDao.updateStatus(orderId, BulkOrderStatus.DISTRIBUTING.name)

            val pricePerUnit = order.agreedPricePerUnit ?: order.targetPricePerUnit

            val msg = buildString {
                appendLine("📦 *Distribution Plan — ${order.product}*")
                appendLine("🆔 ${order.orderId}")
                appendLine("🏪 From: ${order.supplierName ?: "TBD"}")
                appendLine("💰 Price: Ksh ${pricePerUnit.toInt()}/${order.unit}")
                appendLine()
                appendLine("── Each Worker Gets ──")
                commitments.forEach { c ->
                    val cost = c.quantity * pricePerUnit
                    appendLine("👤 ${c.workerName}: ${c.quantity.toInt()} ${order.unit} → Ksh ${cost.toInt()}")
                }
                appendLine()
                appendLine("Total: ${order.totalQuantityCommitted.toInt()} ${order.unit} → Ksh ${(order.totalQuantityCommitted * pricePerUnit).toInt()}")
                appendLine()
                appendLine("After receiving your share, confirm delivery to release escrow.")
                appendLine("Baada ya kupokea, thibitisha ili kutoa escrow.")
            }

            Timber.d("Distribution started for order $orderId")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "order_id" to orderId,
                    "status" to "DISTRIBUTING",
                    "distributions" to commitments.map { c ->
                        mapOf(
                            "worker" to c.workerName,
                            "quantity" to c.quantity,
                            "cost" to (c.quantity * pricePerUnit)
                        )
                    }
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to distribute order")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 7. RELEASE ESCROW — After delivery confirmed
    // ──────────────────────────────────────────────

    private suspend fun releaseEscrow(params: Map<String, String>): ToolResult {
        return try {
            val orderId = params["order_id"]
                ?: return ToolResult.error(name, "Order ID required", "MISSING_ORDER_ID")
            val workerId = params["worker_id"] ?: "current_user"
            val confirmed = params["delivery_confirmed"]?.toBooleanStrictOrNull() ?: false

            if (!confirmed) {
                return ToolResult.error(name, "Set delivery_confirmed=true to release escrow", "CONFIRMATION_REQUIRED")
            }

            val order = bulkOrderDao.getByOrderId(orderId)
                ?: return ToolResult.error(name, "Order not found: $orderId", "NOT_FOUND")

            val commitment = bulkCommitmentDao.getByOrderAndWorker(orderId, workerId)
                ?: return ToolResult.error(name, "No commitment found for worker $workerId", "NOT_FOUND")

            if (commitment.paymentStatus == PaymentStatus.RELEASED.name) {
                return ToolResult.error(name, "Escrow already released", "ALREADY_RELEASED")
            }

            // Release this worker's escrow
            bulkCommitmentDao.updatePaymentStatus(commitment.id, PaymentStatus.RELEASED.name, System.currentTimeMillis())

            // Record escrow release
            val escrow = BulkEscrowEntity(
                orderId = orderId,
                workerId = workerId,
                amount = commitment.quantity * (order.agreedPricePerUnit ?: order.targetPricePerUnit),
                type = EscrowType.RELEASE.name,
                mpesaReference = "RELEASE_${System.currentTimeMillis()}",
                createdAt = System.currentTimeMillis(),
                needsSync = true
            )
            bulkEscrowDao.insert(escrow)

            // Check if all workers confirmed
            val allCommitments = bulkCommitmentDao.getByOrderId(orderId).first()
            val allReleased = allCommitments.all { it.paymentStatus == PaymentStatus.RELEASED.name }
            if (allReleased) {
                bulkOrderDao.updateStatus(orderId, BulkOrderStatus.COMPLETED.name)
            }

            val amount = commitment.quantity * (order.agreedPricePerUnit ?: order.targetPricePerUnit)

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "order_id" to orderId,
                    "worker_id" to workerId,
                    "amount_released" to amount,
                    "all_released" to allReleased
                ),
                message = "✅ Escrow released! Ksh ${amount.toInt()} sent to supplier.\n" +
                    if (allReleased) "🎉 Order complete! All payments released." else "⏳ Waiting for other workers to confirm delivery."
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to release escrow")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CANCEL — Cancel a bulk order
    // ──────────────────────────────────────────────

    private suspend fun cancelOrder(params: Map<String, String>): ToolResult {
        return try {
            val orderId = params["order_id"]
                ?: return ToolResult.error(name, "Order ID required", "MISSING_ORDER_ID")

            val order = bulkOrderDao.getByOrderId(orderId)
                ?: return ToolResult.error(name, "Order not found: $orderId", "NOT_FOUND")

            if (order.status == BulkOrderStatus.COMPLETED.name) {
                return ToolResult.error(name, "Cannot cancel completed order", "ALREADY_COMPLETED")
            }

            // Refund any escrowed payments
            val commitments = bulkCommitmentDao.getByOrderId(orderId).first()
            commitments.filter { it.paymentStatus == PaymentStatus.IN_ESCROW.name }.forEach { c ->
                bulkCommitmentDao.updatePaymentStatus(c.id, PaymentStatus.REFUNDED.name, System.currentTimeMillis())
                bulkEscrowDao.insert(
                    BulkEscrowEntity(
                        orderId = orderId,
                        workerId = c.workerId,
                        amount = c.amountPaid,
                        type = EscrowType.REFUND.name,
                        mpesaReference = "REFUND_${System.currentTimeMillis()}",
                        createdAt = System.currentTimeMillis(),
                        needsSync = true
                    )
                )
            }

            bulkOrderDao.updateStatus(orderId, BulkOrderStatus.CANCELLED.name)

            ToolResult.success(
                toolName = name,
                data = mapOf("order_id" to orderId, "status" to "CANCELLED"),
                message = "❌ Order cancelled. Any escrowed payments will be refunded."
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel order")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // MY ORDERS — List worker's orders
    // ──────────────────────────────────────────────

    private suspend fun myOrders(params: Map<String, String>): ToolResult {
        return try {
            val workerId = params["worker_id"] ?: "current_user"
            val commitments = bulkCommitmentDao.getByWorkerId(workerId).first()

            if (commitments.isEmpty()) {
                return ToolResult.success(name, message = "You haven't joined any bulk orders yet.\nBrowse open orders: action=browse")
            }

            val msg = buildString {
                appendLine("📋 *Your Bulk Orders*")
                appendLine()
                commitments.forEach { c ->
                    val order = bulkOrderDao.getByOrderId(c.orderId)
                    if (order != null) {
                        val payEmoji = when (PaymentStatus.valueOf(c.paymentStatus)) {
                            PaymentStatus.PENDING -> "⏳"
                            PaymentStatus.IN_ESCROW -> "🔒"
                            PaymentStatus.RELEASED -> "✅"
                            PaymentStatus.REFUNDED -> "↩️"
                        }
                        val statusEmoji = when (BulkOrderStatus.valueOf(order.status)) {
                            BulkOrderStatus.OPEN -> "🟢"
                            BulkOrderStatus.MINIMUM_MET -> "🟡"
                            BulkOrderStatus.NEGOTIATING -> "🟠"
                            BulkOrderStatus.CONFIRMED -> "🔵"
                            BulkOrderStatus.DISTRIBUTING -> "📦"
                            BulkOrderStatus.COMPLETED -> "✅"
                            BulkOrderStatus.CANCELLED -> "❌"
                            BulkOrderStatus.EXPIRED -> "⏰"
                        }
                        appendLine("$statusEmoji ${order.product} — ${c.quantity.toInt()} ${order.unit}")
                        appendLine("   ${order.status} | $payEmoji Ksh ${c.amountPaid.toInt()}")
                        appendLine("   🆔 ${order.orderId}")
                        appendLine()
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = commitments,
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to list orders")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // BROWSE — Browse open bulk orders
    // ──────────────────────────────────────────────

    private suspend fun browseOrders(params: Map<String, String>): ToolResult {
        return try {
            val product = params["product"]
            val area = params["area"]

            val orders = if (product != null) {
                bulkOrderDao.searchByProduct(product).first()
            } else {
                bulkOrderDao.getOpenOrders().first()
            }

            val filtered = if (area != null) {
                orders.filter { it.area.contains(area, ignoreCase = true) || it.area.isEmpty() }
            } else orders

            if (filtered.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "No open bulk orders found${if (product != null) " for '$product'" else ""}.\nCreate one: action=create"
                )
            }

            val msg = buildString {
                appendLine("🛒 *Open Bulk Orders*")
                appendLine()
                filtered.forEach { o ->
                    val percent = if (o.totalQuantityNeeded > 0)
                        (o.totalQuantityCommitted / o.totalQuantityNeeded * 100).toInt() else 0
                    appendLine("📦 ${o.product} — ${o.totalQuantityCommitted.toInt()}/${o.totalQuantityNeeded.toInt()} ${o.unit} ($percent%)")
                    appendLine("   🎯 Ksh ${o.targetPricePerUnit.toInt()}/${o.unit} | 📍 ${o.area.ifEmpty { "Any" }}")
                    appendLine("   ⏰ ${formatTimestamp(o.deadline)} | 🆔 ${o.orderId}")
                    appendLine()
                }
            }

            ToolResult.success(
                toolName = name,
                data = filtered,
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to browse orders")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun generateOrderId(): String {
        val ts = System.currentTimeMillis().toString(36).takeLast(6).uppercase()
        val rand = UUID.randomUUID().toString().take(4).uppercase()
        return "BO-$ts-$rand"
    }

    private fun parseDeadline(input: String): Long {
        val now = System.currentTimeMillis()
        return when {
            input.endsWith("d") -> now + input.removeSuffix("d").toLongOrNull()
                ?.let { it * 24 * 60 * 60 * 1000 } ?: (3 * 24 * 60 * 60 * 1000)
            input.endsWith("w") -> now + input.removeSuffix("w").toLongOrNull()
                ?.let { it * 7 * 24 * 60 * 60 * 1000 } ?: (7 * 24 * 60 * 60 * 1000)
            input.endsWith("h") -> now + input.removeSuffix("h").toLongOrNull()
                ?.let { it * 60 * 60 * 1000 } ?: (24 * 60 * 60 * 1000)
            else -> now + 3 * 24 * 60 * 60 * 1000 // default 3 days
        }
    }

    private fun formatTimestamp(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }
}
