package com.msaidizi.agent.tools

import com.msaidizi.core.database.MarketPoolDao
import com.msaidizi.core.database.MarketPoolMemberDao
import com.msaidizi.core.database.MarketPoolTripDao
import com.msaidizi.core.database.MarketPoolOrderDao
import com.msaidizi.core.database.MarketPoolContributionDao
import com.msaidizi.core.model.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ──────────────────────────────────────────────
// Market Pooling
// ──────────────────────────────────────────────

/**
 * MarketPooling — Mama mbogas pool wholesale market trips, split transport costs.
 *
 * Solves coordination failure: each mama mboga pays KSh 200–400 transport per trip
 * individually (2–3 trips/week = KSh 500–1,000/week). By pooling, 5–10 mama mbogas
 * on the same route designate shared buyers and split transport + porterage.
 *
 * Saves KSh 36,000–44,400/year per mama mboga.
 *
 * Flow:
 *   1. create_trip  → Mama mboga creates a pooled wholesale trip (destination, date, transport cost)
 *   2. join_trip    → Other mama mbogas join the pool, adding their orders
 *   3. split_costs  → Calculate each member's share of transport + purchases
 *   4. complete     → Mark trip done, settle accounts, record actual costs
 *   5. history      → View past trips, savings, and member contributions
 *
 * Offline-first: all data persisted in Room, synced when online.
 * Voice-first: all responses in Swahili + English for mama mbogas.
 */
@Singleton
class MarketPooling @Inject constructor(
    private val marketPoolDao: MarketPoolDao,
    private val marketPoolMemberDao: MarketPoolMemberDao,
    private val marketPoolTripDao: MarketPoolTripDao,
    private val marketPoolOrderDao: MarketPoolOrderDao,
    private val marketPoolContributionDao: MarketPoolContributionDao
) : Tool {

    override val name = "market_pooling"
    override val description = "Pool wholesale market trips — mama mbogas share transport, split costs, save 40-60% on trips"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("create_trip", "join_trip", "split_costs", "complete", "history"),
            required = false)
        string("pool_id", "Pool/trip ID (for join_trip, split_costs, complete, history)", required = false)
        string("creator_id", "Creator's user ID", required = false)
        string("creator_name", "Creator's name", required = false)
        string("phone", "M-Pesa phone number", required = false)
        string("market", "Wholesale market destination (e.g. 'Wakulima', 'Gikomba')", required = false)
        string("trip_date", "Planned trip date (e.g. '2026-07-28', 'kesho', 'Monday')", required = false)
        string("schedule_days", "Recurring schedule days (e.g. 'Mon,Wed,Fri' or '1,3,5')", required = false)
        number("transport_cost", "Total transport cost in KSh for the trip", required = false)
        number("porterage_cost", "Porter/loading cost in KSh", required = false)
        string("member_id", "Member user ID joining the trip", required = false)
        string("member_name", "Member name", required = false)
        string("items", "Items to order — format: 'nyanya:10kg:120, sukuma:5:40, vitunguu:5kg:80' (item:qty:unit:max_price)", required = false)
        number("amount_paid", "Amount paid by member in KSh", required = false)
        string("payment_method", "Payment method ('mpesa' or 'cash')", required = false)
        string("payment_ref", "M-Pesa transaction reference", required = false)
        boolean("transport_confirmed", "Whether transport was actually paid (for complete)", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "history"
        return when (action.lowercase()) {
            "create_trip" -> createTrip(params)
            "join_trip" -> joinTrip(params)
            "split_costs" -> splitCosts(params)
            "complete" -> completeTrip(params)
            "history" -> viewHistory(params)
            else -> ToolResult.error(name, "Action sijui: $action. Jaribu: create_trip, join_trip, split_costs, complete, history", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // 1. CREATE TRIP — Mama mboga starts a pooled trip
    // ──────────────────────────────────────────────

    private suspend fun createTrip(params: Map<String, String>): ToolResult {
        return try {
            val market = params["market"]
                ?: return ToolResult.error(name, "Soko linahitajika. Mfano: 'Wakulima', 'Gikomba'", "MISSING_MARKET")
            val tripDate = params["trip_date"]
                ?: return ToolResult.error(name, "Tarehe ya safari inahitajika. Mfano: '2026-07-28', 'kesho'", "MISSING_DATE")
            val transportCost = params["transport_cost"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Gharama ya usafiri inahitajika (KSh). Mfano: '500'", "MISSING_TRANSPORT_COST")
            val creatorId = params["creator_id"] ?: "current_user"
            val creatorName = params["creator_name"] ?: "Mama Mboga"
            val phone = params["phone"] ?: ""
            val porterageCost = params["porterage_cost"]?.toDoubleOrNull() ?: 0.0
            val scheduleDays = params["schedule_days"] ?: ""

            if (transportCost <= 0) return ToolResult.error(name, "Gharama ya usafiri lazima iwe zaidi ya 0", "INVALID_COST")

            val resolvedDate = resolveTripDate(tripDate)
            val poolId = generatePoolId()

            // Create pool group
            val pool = MarketPoolEntity(
                poolId = poolId,
                marketDestination = market,
                tripDate = resolvedDate,
                scheduleDays = scheduleDays,
                transportCost = transportCost,
                porterageCost = porterageCost,
                creatorId = creatorId,
                creatorName = creatorName,
                status = MarketPoolStatus.OPEN.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                needsSync = true
            )
            marketPoolDao.insert(pool)

            // Auto-add creator as first member + admin
            val member = MarketPoolMemberEntity(
                poolId = poolId,
                memberId = creatorId,
                memberName = creatorName,
                phone = phone,
                role = "admin",
                joinedAt = System.currentTimeMillis(),
                needsSync = true
            )
            marketPoolMemberDao.insert(member)

            Timber.d("Market pool trip created: $poolId → $market on $resolvedDate")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "pool_id" to poolId,
                    "market" to market,
                    "trip_date" to resolvedDate,
                    "transport_cost" to transportCost,
                    "porterage_cost" to porterageCost,
                    "status" to "OPEN",
                    "schedule_days" to scheduleDays
                ),
                message = "✅ *Safari ya pamoja imeundwa!*\n" +
                    "✅ *Pool trip created!*\n\n" +
                    "📍 Soko: $market\n" +
                    "📅 Tarehe: $resolvedDate\n" +
                    "🚐 Usafiri: KSh ${transportCost.toInt()}\n" +
                    (if (porterageCost > 0) "📦 Wakulima: KSh ${porterageCost.toInt()}\n" else "") +
                    "🆔 Pool: $poolId\n\n" +
                    "Share ID hii na mama mbogas wenzako ili waunge!\n" +
                    "Share this ID with other mama mbogas to join!"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create market pool trip")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 2. JOIN TRIP — Mama mboga joins with her order
    // ──────────────────────────────────────────────

    private suspend fun joinTrip(params: Map<String, String>): ToolResult {
        return try {
            val poolId = params["pool_id"]
                ?: return ToolResult.error(name, "ID ya pool inahitajika", "MISSING_POOL_ID")
            val memberId = params["member_id"] ?: "user_${System.currentTimeMillis()}"
            val memberName = params["member_name"] ?: "Mama Mboga"
            val phone = params["phone"] ?: ""
            val itemsStr = params["items"]
                ?: return ToolResult.error(
                    name,
                    "Vitu vinahitajika. Mfano: 'nyanya:10kg:120, sukuma:5:40'\n" +
                    "Format: jina:idadi:unit:bei_ya_juu",
                    "MISSING_ITEMS"
                )

            val pool = marketPoolDao.getByPoolId(poolId)
                ?: return ToolResult.error(name, "Pool haikupatikana: $poolId", "NOT_FOUND")

            if (pool.status != MarketPoolStatus.OPEN.name) {
                return ToolResult.error(name, "Pool iko ${pool.status}, huwezi kujiunga", "POOL_NOT_OPEN")
            }

            // Check if member already joined
            val existing = marketPoolMemberDao.getByPoolAndMember(poolId, memberId)
            if (existing != null) {
                return ToolResult.error(name, "Umeshajiunga na pool hii. Ongeza order mpya badala yake.", "ALREADY_JOINED")
            }

            // Add member to pool
            val memberCount = marketPoolMemberDao.getCountByPool(poolId)
            val member = MarketPoolMemberEntity(
                poolId = poolId,
                memberId = memberId,
                memberName = memberName,
                phone = phone,
                role = "member",
                joinedAt = System.currentTimeMillis(),
                needsSync = true
            )
            marketPoolMemberDao.insert(member)

            // Parse and save items as orders
            val orders = parseItems(itemsStr, poolId, memberId, pool.tripDate)
            orders.forEach { order ->
                marketPoolOrderDao.insert(order)
            }

            val orderSummary = orders.joinToString("\n") { o ->
                "  • ${o.itemName}: ${o.quantity.toInt()} ${o.unit} (max KSh ${o.maxPricePerUnit.toInt()}/${o.unit})"
            }

            Timber.d("Member $memberId joined pool $poolId with ${orders.size} items")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "pool_id" to poolId,
                    "member_id" to memberId,
                    "member_name" to memberName,
                    "items_count" to orders.size,
                    "member_position" to (memberCount + 1),
                    "orders" to orders.map { mapOf("item" to it.itemName, "qty" to it.quantity, "unit" to it.unit, "max_price" to it.maxPricePerUnit) }
                ),
                message = "✅ *Umefanikiwa kujiunga!*\n" +
                    "✅ *Joined successfully!*\n\n" +
                    "👤 $memberName — member #${memberCount + 1}\n" +
                    "📍 ${pool.marketDestination} — ${pool.tripDate}\n\n" +
                    "📦 Order yako:\n$orderSummary\n\n" +
                    "💰 Usafiri utagawanywa kati ya members wote.\n" +
                    "💰 Transport will be split among all members."
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to join market pool trip")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. SPLIT COSTS — Calculate each member's share
    // ──────────────────────────────────────────────

    private suspend fun splitCosts(params: Map<String, String>): ToolResult {
        return try {
            val poolId = params["pool_id"]
                ?: return ToolResult.error(name, "ID ya pool inahitajika", "MISSING_POOL_ID")

            val pool = marketPoolDao.getByPoolId(poolId)
                ?: return ToolResult.error(name, "Pool haikupatikana: $poolId", "NOT_FOUND")

            val members = marketPoolMemberDao.getByPoolId(poolId).first()
            if (members.isEmpty()) {
                return ToolResult.error(name, "Hakuna members kwenye pool hii", "NO_MEMBERS")
            }

            val orders = marketPoolOrderDao.getByPoolId(poolId).first()
            val memberCount = members.size
            val transportPerMember = pool.transportCost / memberCount
            val porteragePerMember = pool.porterageCost / memberCount

            // Calculate each member's estimated purchase cost
            val memberBreakdown = members.map { m ->
                val memberOrders = orders.filter { it.memberId == m.memberId }
                val estimatedPurchase = memberOrders.sumOf { it.maxPricePerUnit * it.quantity }
                val totalShare = transportPerMember + porteragePerMember + estimatedPurchase
                mapOf(
                    "member_id" to m.memberId,
                    "member_name" to m.memberName,
                    "items" to memberOrders.map { mapOf("item" to it.itemName, "qty" to it.quantity, "unit" to it.unit) },
                    "estimated_purchase" to estimatedPurchase,
                    "transport_share" to transportPerMember,
                    "porterage_share" to porteragePerMember,
                    "total_share" to totalShare
                )
            }

            val totalEstimatedPurchase = orders.sumOf { it.maxPricePerUnit * it.quantity }
            val totalEstimated = pool.transportCost + pool.porterageCost + totalEstimatedPurchase

            val msg = buildString {
                appendLine("💰 *Mgawanyo wa Gharama — ${pool.marketDestination}*")
                appendLine("💰 *Cost Split — ${pool.marketDestination}*")
                appendLine("📅 ${pool.tripDate} | 👥 Members: $memberCount")
                appendLine()
                appendLine("── Gharama za Pamoja / Shared Costs ──")
                appendLine("🚐 Usafiri/Transport: KSh ${pool.transportCost.toInt()}")
                appendLine("📦 Wakulima/Porterage: KSh ${pool.porterageCost.toInt()}")
                appendLine("   Kwa kila mtu/Per person: KSh ${(transportPerMember + porteragePerMember).toInt()}")
                appendLine()
                appendLine("── Kila Mtu / Each Member ──")
                memberBreakdown.forEach { b ->
                    @Suppress("UNCHECKED_CAST")
                    val items = b["items"] as List<Map<String, Any>>
                    val itemStr = items.joinToString(", ") { "${it["item"]}: ${it["qty"]}" }
                    appendLine("👤 ${b["member_name"]}")
                    appendLine("   📦 $itemStr")
                    appendLine("   🛒 Nunua/Buy: ~KSh ${b["estimated_purchase"]}")
                    appendLine("   🚐 Usafiri/Transport: KSh ${b["transport_share"]}")
                    appendLine("   💰 Jumla/Total: ~KSh ${b["total_share"]}")
                    appendLine()
                }
                appendLine("📊 Jumla ya Safari/Trip Total: ~KSh ${totalEstimated.toInt()}")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "pool_id" to poolId,
                    "member_count" to memberCount,
                    "transport_cost" to pool.transportCost,
                    "transport_per_member" to transportPerMember,
                    "porterage_per_member" to porteragePerMember,
                    "total_estimated" to totalEstimated,
                    "breakdown" to memberBreakdown
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to split costs")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 4. COMPLETE — Mark trip done, settle accounts
    // ──────────────────────────────────────────────

    private suspend fun completeTrip(params: Map<String, String>): ToolResult {
        return try {
            val poolId = params["pool_id"]
                ?: return ToolResult.error(name, "ID ya pool inahitajika", "MISSING_POOL_ID")
            val transportConfirmed = params["transport_confirmed"]?.toBooleanStrictOrNull() ?: false

            if (!transportConfirmed) {
                return ToolResult.error(name, "Weka transport_confirmed=true ili kukamilisha safari", "CONFIRMATION_REQUIRED")
            }

            val pool = marketPoolDao.getByPoolId(poolId)
                ?: return ToolResult.error(name, "Pool haikupatikana: $poolId", "NOT_FOUND")

            if (pool.status == MarketPoolStatus.COMPLETED.name) {
                return ToolResult.error(name, "Safari hii tayari imekamilika", "ALREADY_COMPLETED")
            }

            val members = marketPoolMemberDao.getByPoolId(poolId).first()
            val orders = marketPoolOrderDao.getByPoolId(poolId).first()
            val memberCount = members.size

            // Record actual transport payment
            val actualTransport = params["transport_cost"]?.toDoubleOrNull() ?: pool.transportCost
            val actualPorterage = params["porterage_cost"]?.toDoubleOrNull() ?: pool.porterageCost
            val transportPerMember = actualTransport / memberCount
            val porteragePerMember = actualPorterage / memberCount

            // Record contributions for each member
            members.forEach { m ->
                val memberOrders = orders.filter { it.memberId == m.memberId }
                val purchaseCost = memberOrders.sumOf { it.maxPricePerUnit * it.quantity }
                val totalDue = transportPerMember + porteragePerMember + purchaseCost

                val contribution = MarketPoolContributionEntity(
                    poolId = poolId,
                    memberId = m.memberId,
                    tripDate = pool.tripDate,
                    amountExpected = totalDue,
                    amountPaid = params["amount_paid"]?.toDoubleOrNull() ?: totalDue,
                    paymentMethod = params["payment_method"] ?: "mpesa",
                    paymentRef = params["payment_ref"] ?: "",
                    status = "complete",
                    paidAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    needsSync = true
                )
                marketPoolContributionDao.insert(contribution)
            }

            // Update pool status
            marketPoolDao.updateStatus(poolId, MarketPoolStatus.COMPLETED.name, System.currentTimeMillis())

            // Update member order statuses
            orders.forEach { order ->
                marketPoolOrderDao.updateStatus(order.orderId, "delivered")
            }

            val totalSpent = actualTransport + actualPorterage + orders.sumOf { it.maxPricePerUnit * it.quantity }

            Timber.d("Market pool trip completed: $poolId")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "pool_id" to poolId,
                    "status" to "COMPLETED",
                    "transport_actual" to actualTransport,
                    "porterage_actual" to actualPorterage,
                    "transport_per_member" to transportPerMember,
                    "total_spent" to totalSpent,
                    "member_count" to memberCount
                ),
                message = "🎉 *Safari imekamilika!*\n" +
                    "🎉 *Trip completed!*\n\n" +
                    "📍 ${pool.marketDestination} — ${pool.tripDate}\n" +
                    "👥 Members: $memberCount\n" +
                    "🚐 Usafiri: KSh ${actualTransport.toInt()} (KSh ${transportPerMember.toInt()} kwa kila mtu)\n" +
                    "📦 Wakulima: KSh ${actualPorterage.toInt()}\n" +
                    "💰 Jumla/Total: KSh ${totalSpent.toInt()}\n\n" +
                    "Contributions zimerekodiwa. Angalia history kwa maelezo kamili.\n" +
                    "Contributions recorded. Check history for full breakdown."
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to complete market pool trip")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. HISTORY — View past trips and savings
    // ──────────────────────────────────────────────

    private suspend fun viewHistory(params: Map<String, String>): ToolResult {
        return try {
            val memberId = params["member_id"]
            val poolId = params["pool_id"]

            // If specific pool requested
            if (poolId != null) {
                return viewTripDetail(poolId)
            }

            // Otherwise show member's or all history
            val trips = if (memberId != null) {
                marketPoolTripDao.getByMemberId(memberId).first()
            } else {
                marketPoolTripDao.getAll().first()
            }

            if (trips.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna safari za awali.\n" +
                        "No past trips yet.\n\n" +
                        "Anzisha safari mpya: action=create_trip\n" +
                        "Start a new trip: action=create_trip"
                )
            }

            // Calculate savings
            val totalTransport = trips.filter { it.status == MarketPoolStatus.COMPLETED.name }
                .sumOf { it.transportCost }
            val totalMembers = trips.filter { it.status == MarketPoolStatus.COMPLETED.name }
                .sumOf { marketPoolMemberDao.getCountByPool(it.poolId).toLong() }
            val avgTransportPerTrip = if (trips.isNotEmpty()) totalTransport / trips.size else 0.0

            // Estimate savings: solo transport ~KSh 300/trip vs pooled
            val estimatedSoloCost = trips.size * 300.0
            val actualPooledCost = if (totalMembers > 0) totalTransport else 0.0
            val estimatedSavings = estimatedSoloCost - actualPooledCost

            val msg = buildString {
                appendLine("📋 *Historia ya Safari za Pamoja*")
                appendLine("📋 *Pool Trip History*")
                appendLine()
                appendLine("📊 Jumla ya safari/Total trips: ${trips.size}")
                appendLine("✅ Zilizokamilika/Completed: ${trips.count { it.status == MarketPoolStatus.COMPLETED.name }}")
                appendLine("🟢 Zilizo wazi/Open: ${trips.count { it.status == MarketPoolStatus.OPEN.name }}")
                appendLine()
                appendLine("── Safari za Hivi Karibuni / Recent Trips ──")
                trips.takeLast(10).reversed().forEach { t ->
                    val statusEmoji = when (MarketPoolStatus.valueOf(t.status)) {
                        MarketPoolStatus.OPEN -> "🟢"
                        MarketPoolStatus.IN_PROGRESS -> "🟡"
                        MarketPoolStatus.COMPLETED -> "✅"
                        MarketPoolStatus.CANCELLED -> "❌"
                    }
                    val memberCount = marketPoolMemberDao.getCountByPool(t.poolId)
                    appendLine("$statusEmoji ${t.tripDate} — ${t.marketDestination}")
                    appendLine("   👥 $memberCount | 🚐 KSh ${t.transportCost.toInt()} | 🆔 ${t.poolId}")
                }

                if (estimatedSavings > 0) {
                    appendLine()
                    appendLine("💰 *Okokoa / Savings Estimate*")
                    appendLine("   Bila pool / Without pool: ~KSh ${estimatedSoloCost.toInt()}")
                    appendLine("   Na pool / With pool: ~KSh ${actualPooledCost.toInt()}")
                    appendLine("   Okoa / Saved: ~KSh ${estimatedSavings.toInt()}")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "total_trips" to trips.size,
                    "completed" to trips.count { it.status == MarketPoolStatus.COMPLETED.name },
                    "open" to trips.count { it.status == MarketPoolStatus.OPEN.name },
                    "estimated_savings" to estimatedSavings,
                    "trips" to trips.takeLast(10).map { mapOf("pool_id" to it.poolId, "market" to it.marketDestination, "date" to it.tripDate, "status" to it.status) }
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to view history")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * View detailed breakdown of a single trip.
     */
    private suspend fun viewTripDetail(poolId: String): ToolResult {
        val pool = marketPoolDao.getByPoolId(poolId)
            ?: return ToolResult.error(name, "Pool haikupatikana: $poolId", "NOT_FOUND")

        val members = marketPoolMemberDao.getByPoolId(poolId).first()
        val orders = marketPoolOrderDao.getByPoolId(poolId).first()
        val contributions = marketPoolContributionDao.getByPoolId(poolId).first()

        val statusEmoji = when (MarketPoolStatus.valueOf(pool.status)) {
            MarketPoolStatus.OPEN -> "🟢"
            MarketPoolStatus.IN_PROGRESS -> "🟡"
            MarketPoolStatus.COMPLETED -> "✅"
            MarketPoolStatus.CANCELLED -> "❌"
        }

        val msg = buildString {
            appendLine("$statusEmoji *Safari: ${pool.marketDestination}*")
            appendLine("$statusEmoji *Trip: ${pool.marketDestination}*")
            appendLine("🆔 ${pool.poolId}")
            appendLine("📅 ${pool.tripDate}")
            appendLine("📊 Status: ${pool.status}")
            appendLine()
            appendLine("── Gharama / Costs ──")
            appendLine("🚐 Usafiri/Transport: KSh ${pool.transportCost.toInt()}")
            appendLine("📦 Wakulima/Porterage: KSh ${pool.porterageCost.toInt()}")
            val perMember = if (members.isNotEmpty()) pool.transportCost / members.size else 0.0
            appendLine("👤 Kwa kila mtu/Per person: KSh ${perMember.toInt()}")
            appendLine()
            appendLine("── Members (${members.size}) ──")
            members.forEach { m ->
                val memberOrders = orders.filter { it.memberId == m.memberId }
                val contribution = contributions.find { it.memberId == m.memberId }
                val payStatus = if (contribution != null) "✅" else "⏳"
                appendLine("$payStatus ${m.memberName} (${m.role})")
                memberOrders.forEach { o ->
                    appendLine("   📦 ${o.itemName}: ${o.quantity.toInt()} ${o.unit} (max KSh ${o.maxPricePerUnit.toInt()})")
                }
                if (contribution != null) {
                    appendLine("   💰 Amelipa/Paid: KSh ${contribution.amountPaid.toInt()}")
                }
            }
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "pool" to pool,
                "members" to members,
                "orders" to orders,
                "contributions" to contributions
            ),
            message = msg
        )
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun generatePoolId(): String {
        val ts = System.currentTimeMillis().toString(36).takeLast(6).uppercase()
        val rand = UUID.randomUUID().toString().take(4).uppercase()
        return "MP-$ts-$rand"
    }

    /**
     * Parse items string into order entities.
     * Format: "nyanya:10kg:120, sukuma:5:40, vitunguu:5kg:80"
     * Each item: name:quantity[unit]:max_price_per_unit
     */
    private fun parseItems(
        itemsStr: String,
        poolId: String,
        memberId: String,
        tripDate: String
    ): List<MarketPoolOrderEntity> {
        return itemsStr.split(",").mapNotNull { item ->
            val parts = item.trim().split(":")
            if (parts.size < 2) return@mapNotNull null

            val itemName = parts[0].trim()
            val qtyStr = parts[1].trim()

            // Parse quantity and unit from "10kg", "5", "5bundles", etc.
            val qtyMatch = Regex("([\\d.]+)\\s*(.*)").find(qtyStr)
            val quantity = qtyMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val unit = qtyMatch?.groupValues?.get(2)?.trim()?.ifEmpty { "piece" } ?: "piece"

            val maxPrice = if (parts.size >= 3) parts[2].trim().toDoubleOrNull() ?: 0.0 else 0.0

            MarketPoolOrderEntity(
                poolId = poolId,
                memberId = memberId,
                tripDate = tripDate,
                itemName = itemName,
                quantity = quantity,
                unit = unit,
                maxPricePerUnit = maxPrice,
                status = "pending",
                createdAt = System.currentTimeMillis(),
                needsSync = true
            )
        }
    }

    /**
     * Resolve relative date strings to ISO dates.
     */
    private fun resolveTripDate(input: String): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val cal = java.util.Calendar.getInstance()

        return when (input.lowercase()) {
            "kesho", "tomorrow" -> {
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                sdf.format(cal.time)
            }
            "leo", "today" -> sdf.format(cal.time)
            "kesho kutwa", "day_after_tomorrow" -> {
                cal.add(java.util.Calendar.DAY_OF_MONTH, 2)
                sdf.format(cal.time)
            }
            "monday", "jumatatu" -> getNextDayOfWeek(cal, java.util.Calendar.MONDAY, sdf)
            "tuesday", "jumanne" -> getNextDayOfWeek(cal, java.util.Calendar.TUESDAY, sdf)
            "wednesday", "jumatano" -> getNextDayOfWeek(cal, java.util.Calendar.WEDNESDAY, sdf)
            "thursday", "alhamisi" -> getNextDayOfWeek(cal, java.util.Calendar.THURSDAY, sdf)
            "friday", "ijumaa" -> getNextDayOfWeek(cal, java.util.Calendar.FRIDAY, sdf)
            "saturday", "jumamosi" -> getNextDayOfWeek(cal, java.util.Calendar.SATURDAY, sdf)
            "sunday", "jumapili" -> getNextDayOfWeek(cal, java.util.Calendar.SUNDAY, sdf)
            else -> {
                // Try parsing as ISO date
                try {
                    sdf.parse(input)
                    input
                } catch (_: Exception) {
                    sdf.format(cal.time) // default to today
                }
            }
        }
    }

    private fun getNextDayOfWeek(
        cal: java.util.Calendar,
        dayOfWeek: Int,
        sdf: java.text.SimpleDateFormat
    ): String {
        val current = cal.get(java.util.Calendar.DAY_OF_WEEK)
        var daysUntil = dayOfWeek - current
        if (daysUntil <= 0) daysUntil += 7
        cal.add(java.util.Calendar.DAY_OF_MONTH, daysUntil)
        return sdf.format(cal.time)
    }
}
