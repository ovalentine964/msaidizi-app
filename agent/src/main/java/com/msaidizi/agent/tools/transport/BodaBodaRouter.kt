package com.msaidizi.agent.tools.transport

import com.google.gson.JsonObject
import com.msaidizi.core.network.CuOptApiClient
import com.msaidizi.core.network.CuOptResult
import com.msaidizi.core.database.RideOfferDao
import com.msaidizi.core.database.RideUserDao
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import com.msaidizi.agent.tools.core.*

// ══════════════════════════════════════════════
// BODA BODA ROUTER — GPU-Accelerated Routing
// ══════════════════════════════════════════════

/**
 * BodaBodaRouter — cuOpt-powered vehicle routing for boda boda riders.
 *
 * Uses NVIDIA cuOpt GPU-accelerated solver for:
 *   - Nearest rider matching (passenger → closest available rider)
 *   - Multi-stop route optimization (delivery/market trips)
 *   - Batch pickup coordination (multiple passengers, optimal ordering)
 *   - Bulk order delivery routing (multiple pickup/dropoff points)
 *   - Market trip pooling routes (mama mboga shared transport)
 *
 * Gracefully degrades to heuristic routing when cuOpt server is offline.
 *
 * Actions:
 *   - optimize_route:    Optimize a multi-stop route for a single rider
 *   - match_nearest:     Find nearest available rider to a passenger
 *   - batch_pickups:     Optimize pickup ordering for multiple passengers
 *   - bulk_delivery:     Route bulk order deliveries across multiple stops
 *   - market_trip_route: Optimize pooled market trip route
 *   - server_status:     Check cuOpt server availability
 *
 * Voice (Swahili):
 *   - "Nipe njia bora kutoka Gikomba hadi Wakulima" → optimize_route
 *   - "Rider wa karibu wapi?" → match_nearest
 *   - "Panga pickups kwa wateja wangu" → batch_pickups
 */
@Singleton
class BodaBodaRouter @Inject constructor(
    private val cuOptClient: CuOptApiClient,
    private val rideOfferDao: RideOfferDao,
    private val rideUserDao: RideUserDao
) : Tool {

    override val name = "boda_boda_router"
    override val description = "GPU-accelerated route optimization for boda boda riders — " +
            "find nearest rider, optimize multi-stop routes, coordinate batch pickups, " +
            "plan bulk deliveries. Uses NVIDIA cuOpt. Falls back to heuristic when offline."

    override val argsSchema = argSchema {
        enum("action", "Routing action to perform",
            listOf("optimize_route", "match_nearest", "batch_pickups",
                "bulk_delivery", "market_trip_route", "server_status"))

        // ── Common ──
        string("rider_id", "Rider's user ID", required = false)
        string("rider_name", "Rider's display name", required = false)

        // ── optimize_route ──
        string("stops", "Comma-separated stop names (e.g. 'Gikomba,Wakulima,Eastleigh')", required = false)
        string("depot", "Starting location / depot name", required = false)
        integer("max_stops", "Maximum stops to include in optimized route", required = false)
        string("optimize_for", "Optimize for 'time' or 'distance'", required = false)

        // ── match_nearest ──
        string("passenger_location", "Passenger's current location/area", required = false)
        string("destination", "Where the passenger wants to go", required = false)
        string("passenger_id", "Passenger's user ID", required = false)
        integer("max_results", "Max number of riders to return", required = false)

        // ── batch_pickups ──
        string("pickup_locations", "Comma-separated pickup locations", required = false)
        string("dropoff_location", "Common dropoff location", required = false)

        // ── bulk_delivery ──
        string("order_id", "Bulk order ID for delivery routing", required = false)
        string("supplier_location", "Supplier pickup location", required = false)
        string("delivery_locations", "Comma-separated delivery addresses", required = false)

        // ── market_trip_route ──
        string("pool_id", "Market pool ID", required = false)
        string("market", "Market destination", required = false)
        string("member_locations", "Comma-separated member pickup locations", required = false)

        // ── Offline/fallback ──
        boolean("force_heuristic", "Skip cuOpt and use local heuristic (for offline)", required = false)

        // ── Voice ──
        string("voice_text", "Raw Swahili voice input", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Parse voice input if provided
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "server_status"
        return when (action.lowercase()) {
            "optimize_route" -> optimizeRoute(effectiveParams)
            "match_nearest" -> matchNearest(effectiveParams)
            "batch_pickups" -> batchPickups(effectiveParams)
            "bulk_delivery" -> bulkDelivery(effectiveParams)
            "market_trip_route" -> marketTripRoute(effectiveParams)
            "server_status" -> serverStatus()
            else -> ToolResult.error(name, "Action sijui: $action. Jaribu: optimize_route, match_nearest, batch_pickups, bulk_delivery, market_trip_route, server_status", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // 1. OPTIMIZE ROUTE — Multi-stop VRP
    // ──────────────────────────────────────────────

    private suspend fun optimizeRoute(params: Map<String, String>): ToolResult {
        return try {
            val stopsStr = params["stops"]
                ?: return ToolResult.error(name, "Stops required. Mfano: 'Gikomba,Wakulima,Eastleigh'", "MISSING_STOPS")
            val depot = params["depot"] ?: params["rider_name"] ?: "Home"
            val optimizeFor = params["optimize_for"] ?: "distance"

            val stops = stopsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (stops.size < 2) {
                return ToolResult.error(name, "At least 2 stops required. You provided: ${stops.size}", "INSUFFICIENT_STOPS")
            }

            // All locations: depot + stops
            val allLocations = listOf(depot) + stops
            val n = allLocations.size

            // Build cost/time matrices (using Haversine distance or fallback heuristic)
            val costMatrix = buildDistanceMatrix(allLocations)
            val timeMatrix = buildTimeMatrix(allLocations)

            val forceHeuristic = params["force_heuristic"]?.toBooleanStrictOrNull() ?: false

            // Try cuOpt first, fall back to heuristic
            val result = if (!forceHeuristic && cuOptClient.isServerHealthy()) {
                optimizeWithCuOpt(costMatrix, timeMatrix, allLocations, optimizeFor)
            } else {
                optimizeWithHeuristic(costMatrix, allLocations)
            }

            return result
        } catch (e: Exception) {
            Timber.e(e, "Route optimization failed")
            ToolResult.error(name, "Route optimization failed: ${e.message}", "OPTIMIZATION_ERROR")
        }
    }

    private suspend fun optimizeWithCuOpt(
        costMatrix: List<List<Double>>,
        timeMatrix: List<List<Double>>,
        locations: List<String>,
        optimizeFor: String
    ): ToolResult {
        val n = locations.size
        val taskLocations = (1 until n).toList() // All except depot

        val payload = cuOptClient.buildVrpPayload(
            costMatrix = costMatrix,
            travelTimeMatrix = timeMatrix,
            taskLocations = taskLocations,
            demand = listOf(List(taskLocations.size) { 0 }), // No capacity constraint
            serviceTimes = List(taskLocations.size) { 2 },    // 2 min per stop
            vehicleStartLocations = listOf(listOf(0, 0)),     // Start and end at depot
            capacities = listOf(listOf(100)),                  // High capacity
            timeLimit = 10
        )

        when (val cuResult = cuOptClient.solveRoutingProblem(payload)) {
            is CuOptResult.Success -> {
                val route = cuResult.routes.firstOrNull()
                val routeLocations = route?.route?.map { idx -> locations[idx] } ?: locations
                val totalCost = cuResult.totalCost

                val report = buildString {
                    appendLine("🛵 *Njia Bora / Optimized Route*")
                    appendLine("🛵 *Optimized Route*")
                    appendLine()
                    appendLine("📍 Depot: ${locations[0]}")
                    appendLine("📊 Optimize for: $optimizeFor")
                    appendLine("📏 Total ${if (optimizeFor == "time") "time" else "distance"}: ${"%.1f".format(totalCost)} ${if (optimizeFor == "time") "min" else "km"}")
                    appendLine("🔢 Stops: ${routeLocations.size - 1}")
                    appendLine()
                    appendLine("── Njia / Route Order ──")
                    routeLocations.forEachIndexed { i, loc ->
                        val label = if (i == 0) "🏠 Start" else "📍 Stop $i"
                        appendLine("  ${i + 1}. $label: $loc")
                    }
                    appendLine("  ${routeLocations.size + 1}. 🏠 Return: ${locations[0]}")
                    appendLine()
                    appendLine("⚡ Powered by NVIDIA cuOpt GPU solver")
                }

                return ToolResult.success(
                    name,
                    data = mapOf(
                        "route" to routeLocations,
                        "total_cost" to totalCost,
                        "optimizer" to "cuopt",
                        "locations" to locations,
                        "optimize_for" to optimizeFor
                    ),
                    message = report
                )
            }
            is CuOptResult.Error -> {
                Timber.w("cuOpt failed, falling back to heuristic: ${cuResult.message}")
                return optimizeWithHeuristic(costMatrix, locations)
            }
        }
    }

    private fun optimizeWithHeuristic(
        costMatrix: List<List<Double>>,
        locations: List<String>
    ): ToolResult {
        // Nearest-neighbor heuristic for TSP
        val n = locations.size
        val visited = mutableSetOf(0) // Start at depot
        val route = mutableListOf(0)
        var current = 0
        var totalCost = 0.0

        while (visited.size < n) {
            var bestNext = -1
            var bestCost = Double.MAX_VALUE

            for (next in 0 until n) {
                if (next !in visited && costMatrix[current][next] < bestCost) {
                    bestCost = costMatrix[current][next]
                    bestNext = next
                }
            }

            if (bestNext >= 0) {
                visited.add(bestNext)
                route.add(bestNext)
                totalCost += bestCost
                current = bestNext
            }
        }

        // Return to depot
        totalCost += costMatrix[current][0]
        route.add(0)

        val routeLocations = route.map { locations[it] }

        val report = buildString {
            appendLine("🛵 *Njia Bora / Optimized Route*")
            appendLine("🛵 *Optimized Route (Heuristic)*")
            appendLine()
            appendLine("📍 Depot: ${locations[0]}")
            appendLine("📏 Total distance: ${"%.1f".format(totalCost)} km")
            appendLine("🔢 Stops: ${routeLocations.size - 2}")
            appendLine()
            appendLine("── Njia / Route Order ──")
            routeLocations.forEachIndexed { i, loc ->
                val label = if (i == 0) "🏠 Start"
                else if (i == routeLocations.size - 1) "🏠 Return"
                else "📍 Stop $i"
                appendLine("  ${i + 1}. $label: $loc")
            }
            appendLine()
            appendLine("⚠️ Offline mode: using nearest-neighbor heuristic.")
            appendLine("   Connect to cuOpt server for GPU-optimized routes.")
        }

        return ToolResult.success(
            name,
            data = mapOf(
                "route" to routeLocations,
                "total_cost" to totalCost,
                "optimizer" to "heuristic",
                "locations" to locations
            ),
            message = report
        )
    }

    // ──────────────────────────────────────────────
    // 2. MATCH NEAREST — Find closest rider
    // ──────────────────────────────────────────────

    private suspend fun matchNearest(params: Map<String, String>): ToolResult {
        return try {
            val passengerLoc = params["passenger_location"]
                ?: return ToolResult.error(name, "passenger_location required. Sema: uko wapi?", "MISSING_LOCATION")
            val destination = params["destination"]
                ?: return ToolResult.error(name, "destination required. Sema: unakwenda wapi?", "MISSING_DESTINATION")
            val maxResults = params["max_results"]?.toIntOrNull() ?: 3

            // Get available riders from ride offers
            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val futureWindow = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.HOUR_OF_DAY, 2)
            }
            val futureStr = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault())
                .format(futureWindow.time)

            val availableOffers = rideOfferDao.getAvailableInWindow(now, futureStr)

            if (availableOffers.isEmpty()) {
                // Try broader search
                val broaderWindow = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.HOUR_OF_DAY, 8)
                }
                val broaderStr = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault())
                    .format(broaderWindow.time)
                val broaderOffers = rideOfferDao.getAvailableInWindow(now, broaderStr)

                if (broaderOffers.isEmpty()) {
                    return ToolResult.success(
                        name,
                        data = mapOf("matches" to emptyList<Any>()),
                        message = "🛵 Hakuna riders wanaopatikana karibu na $passengerLoc sasa hivi.\n" +
                            "🛵 No riders available near $passengerLoc right now.\n\n" +
                            "Jaribu tena baada ya dakika 15, au subiri rider aangalie app.\n" +
                            "Try again in 15 minutes, or wait for a rider to check the app."
                    )
                }
            }

            // Score riders by proximity to passenger and route match
            val scored = availableOffers.mapNotNull { offer ->
                val rider = rideUserDao.getById(offer.riderId) ?: return@mapNotNull null

                // Calculate proximity score
                val fromProximity = locationSimilarity(passengerLoc, offer.fromLocation)
                val toMatch = locationSimilarity(destination, offer.toLocation)
                val score = fromProximity * 0.6 + toMatch * 0.4

                mapOf(
                    "offer_id" to offer.offerId.toString(),
                    "rider_id" to offer.riderId,
                    "rider_name" to offer.riderName,
                    "trust_score" to rider.trustScore,
                    "total_trips" to rider.totalTrips,
                    "from" to offer.fromLocation,
                    "to" to offer.toLocation,
                    "departure" to offer.departureTime,
                    "seats_left" to (offer.seatsAvailable - offer.seatsTaken),
                    "fare_per_seat" to offer.farePerSeat,
                    "proximity_score" to score
                )
            }.sortedByDescending { it["proximity_score"] as Double }
                .take(maxResults)

            if (scored.isEmpty()) {
                return ToolResult.success(
                    name,
                    data = mapOf("matches" to emptyList<Any>()),
                    message = "🛵 Hakuna riders wanaokwenda $destination kutoka $passengerLoc.\n" +
                        "🛵 No riders heading $destination from $passengerLoc.\n\n" +
                        "Tafuta safari mpya: ride_share action=find_match"
                )
            }

            val report = buildString {
                appendLine("🛵 *Riders Wanaokaribia / Nearby Riders*")
                appendLine("🛵 *Nearby Riders*")
                appendLine()
                appendLine("📍 Uko/You are: $passengerLoc")
                appendLine("🎯 Unakwenda/Going to: $destination")
                appendLine()
                scored.forEachIndexed { i, m ->
                    val trust = m["trust_score"] as Double
                    val trips = m["total_trips"] as Int
                    val seats = m["seats_left"] as Int
                    val fare = m["fare_per_seat"] as Double

                    appendLine("  ${i + 1}. ${m["rider_name"]} — ⭐ ${"%.1f".format(trust)} ($trips trips)")
                    appendLine("     📍 ${m["from"]} → ${m["to"]}")
                    appendLine("     ⏰ ${m["departure"]} | 💺 $seats nafasi | 💰 KSh ${"%,.0f".format(fare)}")
                }
                appendLine()
                appendLine("Tumia ride_share action=accept kuchukua nafasi.")
                appendLine("Use ride_share action=accept to book a seat.")
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "matches" to scored,
                    "count" to scored.size,
                    "passenger_location" to passengerLoc,
                    "destination" to destination
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Nearest rider match failed")
            ToolResult.error(name, "Match failed: ${e.message}", "MATCH_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. BATCH PICKUPS — Optimize multi-pickup route
    // ──────────────────────────────────────────────

    private suspend fun batchPickups(params: Map<String, String>): ToolResult {
        return try {
            val pickupsStr = params["pickup_locations"]
                ?: return ToolResult.error(name, "pickup_locations required. Mfano: 'Gikomba,Eastleigh,Kariakoo'", "MISSING_PICKUPS")
            val dropoff = params["dropoff_location"]
                ?: return ToolResult.error(name, "dropoff_location required", "MISSING_DROPOFF")

            val pickups = pickupsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (pickups.isEmpty()) {
                return ToolResult.error(name, "At least 1 pickup location required", "INSUFFICIENT_PICKUPS")
            }

            // All locations: first pickup as depot (rider starts there), then other pickups, then dropoff
            val allLocations = pickups + listOf(dropoff)
            val costMatrix = buildDistanceMatrix(allLocations)
            val timeMatrix = buildTimeMatrix(allLocations)

            val forceHeuristic = params["force_heuristic"]?.toBooleanStrictOrNull() ?: false

            val result = if (!forceHeuristic && cuOptClient.isServerHealthy()) {
                // cuOpt: optimize pickup ordering with dropoff as final destination
                val n = allLocations.size
                val taskLocations = (1 until n).toList() // All except first pickup (depot)

                val payload = cuOptClient.buildVrpPayload(
                    costMatrix = costMatrix,
                    travelTimeMatrix = timeMatrix,
                    taskLocations = taskLocations,
                    demand = listOf(List(taskLocations.size) { 1 }), // 1 passenger per pickup
                    serviceTimes = List(taskLocations.size) { 3 },   // 3 min per pickup
                    vehicleStartLocations = listOf(listOf(0, n - 1)), // Start at first pickup, end at dropoff
                    capacities = listOf(listOf(4)),                    // Max 4 passengers
                    timeLimit = 10
                )

                when (val cuResult = cuOptClient.solveRoutingProblem(payload)) {
                    is CuOptResult.Success -> {
                        val route = cuResult.routes.firstOrNull()
                        val routeNames = route?.route?.map { allLocations[it] }
                            ?: allLocations

                        val report = buildString {
                            appendLine("🛵 *Batch Pickup — Njia Bora*")
                            appendLine("🛵 *Batch Pickup — Optimized Route*")
                            appendLine()
                            appendLine("📏 Total: ${"%.1f".format(cuResult.totalCost)} km")
                            appendLine("👥 Pickups: ${pickups.size}")
                            appendLine("📍 Dropoff: $dropoff")
                            appendLine()
                            appendLine("── Njia / Route ──")
                            routeNames.forEachIndexed { i, loc ->
                                when {
                                    i == 0 -> appendLine("  ${i + 1}. 🏁 Start: $loc")
                                    i == routeNames.size - 1 -> appendLine("  ${i + 1}. 🎯 Dropoff: $loc")
                                    else -> appendLine("  ${i + 1}. 👤 Pickup $i: $loc")
                                }
                            }
                            appendLine()
                            appendLine("⚡ GPU-optimized by NVIDIA cuOpt")
                        }

                        ToolResult.success(
                            name,
                            data = mapOf(
                                "route" to routeNames,
                                "total_cost" to cuResult.totalCost,
                                "optimizer" to "cuopt",
                                "pickups" to pickups,
                                "dropoff" to dropoff
                            ),
                            message = report
                        )
                    }
                    is CuOptResult.Error -> {
                        Timber.w("cuOpt batch pickup failed: ${cuResult.message}")
                        batchPickupsHeuristic(costMatrix, allLocations, pickups, dropoff)
                    }
                }
            } else {
                batchPickupsHeuristic(costMatrix, allLocations, pickups, dropoff)
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Batch pickup optimization failed")
            ToolResult.error(name, "Batch pickup failed: ${e.message}", "BATCH_ERROR")
        }
    }

    private fun batchPickupsHeuristic(
        costMatrix: List<List<Double>>,
        locations: List<String>,
        pickups: List<String>,
        dropoff: String
    ): ToolResult {
        // Simple nearest-neighbor from first pickup
        val n = locations.size
        val visited = mutableSetOf(0)
        val route = mutableListOf(0)
        var current = 0
        var totalCost = 0.0

        // Visit all pickups first, then dropoff
        val dropoffIdx = n - 1
        while (visited.size < n - 1) { // Don't visit dropoff until all pickups done
            var bestNext = -1
            var bestCost = Double.MAX_VALUE
            for (next in 0 until dropoffIdx) { // Only look at pickups
                if (next !in visited && costMatrix[current][next] < bestCost) {
                    bestCost = costMatrix[current][next]
                    bestNext = next
                }
            }
            if (bestNext >= 0) {
                visited.add(bestNext)
                route.add(bestNext)
                totalCost += bestCost
                current = bestNext
            }
        }

        // Finally go to dropoff
        totalCost += costMatrix[current][dropoffIdx]
        route.add(dropoffIdx)

        val routeNames = route.map { locations[it] }

        val report = buildString {
            appendLine("🛵 *Batch Pickup — Njia Bora (Heuristic)*")
            appendLine("🛵 *Batch Pickup — Heuristic Route*")
            appendLine()
            appendLine("📏 Total: ${"%.1f".format(totalCost)} km")
            appendLine("👥 Pickups: ${pickups.size}")
            appendLine("📍 Dropoff: $dropoff")
            appendLine()
            appendLine("── Njia / Route ──")
            routeNames.forEachIndexed { i, loc ->
                when {
                    i == 0 -> appendLine("  ${i + 1}. 🏁 Start: $loc")
                    i == routeNames.size - 1 -> appendLine("  ${i + 1}. 🎯 Dropoff: $loc")
                    else -> appendLine("  ${i + 1}. 👤 Pickup $i: $loc")
                }
            }
            appendLine()
            appendLine("⚠️ Offline mode — connect to cuOpt for GPU optimization.")
        }

        return ToolResult.success(
            name,
            data = mapOf(
                "route" to routeNames,
                "total_cost" to totalCost,
                "optimizer" to "heuristic",
                "pickups" to pickups,
                "dropoff" to dropoff
            ),
            message = report
        )
    }

    // ──────────────────────────────────────────────
    // 4. BULK DELIVERY — Route bulk order deliveries
    // ──────────────────────────────────────────────

    private suspend fun bulkDelivery(params: Map<String, String>): ToolResult {
        return try {
            val supplierLoc = params["supplier_location"]
                ?: return ToolResult.error(name, "supplier_location required", "MISSING_SUPPLIER")
            val deliveriesStr = params["delivery_locations"]
                ?: return ToolResult.error(name, "delivery_locations required", "MISSING_DELIVERIES")

            val deliveries = deliveriesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (deliveries.isEmpty()) {
                return ToolResult.error(name, "At least 1 delivery location required", "INSUFFICIENT_DELIVERIES")
            }

            // Route: supplier (depot) → all delivery locations → back to supplier
            val allLocations = listOf(supplierLoc) + deliveries
            val costMatrix = buildDistanceMatrix(allLocations)
            val timeMatrix = buildTimeMatrix(allLocations)

            val forceHeuristic = params["force_heuristic"]?.toBooleanStrictOrNull() ?: false

            val result = if (!forceHeuristic && cuOptClient.isServerHealthy()) {
                val n = allLocations.size
                val taskLocations = (1 until n).toList()

                val payload = cuOptClient.buildVrpPayload(
                    costMatrix = costMatrix,
                    travelTimeMatrix = timeMatrix,
                    taskLocations = taskLocations,
                    demand = listOf(List(taskLocations.size) { 1 }),
                    serviceTimes = List(taskLocations.size) { 5 }, // 5 min per delivery
                    vehicleStartLocations = listOf(listOf(0, 0)),
                    capacities = listOf(listOf(20)), // Can carry 20 items
                    timeLimit = 15
                )

                when (val cuResult = cuOptClient.solveRoutingProblem(payload)) {
                    is CuOptResult.Success -> {
                        val route = cuResult.routes.firstOrNull()
                        val routeNames = route?.route?.map { allLocations[it] } ?: allLocations

                        val report = buildString {
                            appendLine("📦 *Bulk Delivery Route — cuOpt*")
                            appendLine()
                            appendLine("🏪 Supplier: $supplierLoc")
                            appendLine("📍 Deliveries: ${deliveries.size}")
                            appendLine("📏 Total: ${"%.1f".format(cuResult.totalCost)} km")
                            appendLine()
                            appendLine("── Delivery Order ──")
                            routeNames.forEachIndexed { i, loc ->
                                when {
                                    i == 0 -> appendLine("  ${i + 1}. 🏪 Pickup: $loc")
                                    i == routeNames.size - 1 && loc == supplierLoc -> appendLine("  ${i + 1}. 🏠 Return: $loc")
                                    else -> appendLine("  ${i + 1}. 📦 Delivery $i: $loc")
                                }
                            }
                            appendLine()
                            appendLine("⚡ GPU-optimized by NVIDIA cuOpt")
                        }

                        ToolResult.success(name, data = mapOf(
                            "route" to routeNames, "total_cost" to cuResult.totalCost,
                            "optimizer" to "cuopt", "supplier" to supplierLoc,
                            "deliveries" to deliveries
                        ), message = report)
                    }
                    is CuOptResult.Error -> {
                        Timber.w("cuOpt bulk delivery failed: ${cuResult.message}")
                        optimizeWithHeuristic(costMatrix, allLocations)
                    }
                }
            } else {
                optimizeWithHeuristic(costMatrix, allLocations)
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Bulk delivery routing failed")
            ToolResult.error(name, "Bulk delivery failed: ${e.message}", "DELIVERY_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. MARKET TRIP ROUTE — Optimize pooled market trip
    // ──────────────────────────────────────────────

    private suspend fun marketTripRoute(params: Map<String, String>): ToolResult {
        return try {
            val market = params["market"]
                ?: return ToolResult.error(name, "market required. Mfano: 'Wakulima', 'Gikomba'", "MISSING_MARKET")
            val memberLocsStr = params["member_locations"]
                ?: return ToolResult.error(name, "member_locations required", "MISSING_MEMBERS")

            val memberLocs = memberLocsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (memberLocs.isEmpty()) {
                return ToolResult.error(name, "At least 1 member location required", "INSUFFICIENT_MEMBERS")
            }

            // Route: first member (start) → pick up other members → market → return
            val allLocations = memberLocs + listOf(market)
            val costMatrix = buildDistanceMatrix(allLocations)
            val timeMatrix = buildTimeMatrix(allLocations)

            val forceHeuristic = params["force_heuristic"]?.toBooleanStrictOrNull() ?: false

            val result = if (!forceHeuristic && cuOptClient.isServerHealthy()) {
                val n = allLocations.size
                val marketIdx = n - 1
                val taskLocations = (1 until marketIdx).toList() + listOf(marketIdx)

                val payload = cuOptClient.buildVrpPayload(
                    costMatrix = costMatrix,
                    travelTimeMatrix = timeMatrix,
                    taskLocations = taskLocations,
                    demand = listOf(List(taskLocations.size) { 1 }),
                    serviceTimes = List(taskLocations.size) { 3 },
                    vehicleStartLocations = listOf(listOf(0, 0)),
                    capacities = listOf(listOf(10)), // Can carry 10 mama mbogas' goods
                    timeLimit = 10
                )

                when (val cuResult = cuOptClient.solveRoutingProblem(payload)) {
                    is CuOptResult.Success -> {
                        val route = cuResult.routes.firstOrNull()
                        val routeNames = route?.route?.map { allLocations[it] } ?: allLocations

                        val report = buildString {
                            appendLine("🚐 *Market Trip Route — cuOpt*")
                            appendLine()
                            appendLine("📍 Market: $market")
                            appendLine("👥 Members: ${memberLocs.size}")
                            appendLine("📏 Total: ${"%.1f".format(cuResult.totalCost)} km")
                            appendLine()
                            appendLine("── Pickup Order ──")
                            routeNames.forEachIndexed { i, loc ->
                                when {
                                    loc == market -> appendLine("  ${i + 1}. 🏪 Market: $loc")
                                    i == 0 -> appendLine("  ${i + 1}. 🏁 Start: $loc")
                                    else -> appendLine("  ${i + 1}. 👤 Member $i: $loc")
                                }
                            }
                            appendLine()
                            appendLine("⚡ GPU-optimized by NVIDIA cuOpt")
                        }

                        ToolResult.success(name, data = mapOf(
                            "route" to routeNames, "total_cost" to cuResult.totalCost,
                            "optimizer" to "cuopt", "market" to market,
                            "member_locations" to memberLocs
                        ), message = report)
                    }
                    is CuOptResult.Error -> {
                        Timber.w("cuOpt market trip failed: ${cuResult.message}")
                        optimizeWithHeuristic(costMatrix, allLocations)
                    }
                }
            } else {
                optimizeWithHeuristic(costMatrix, allLocations)
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Market trip routing failed")
            ToolResult.error(name, "Market trip failed: ${e.message}", "MARKET_ROUTE_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 6. SERVER STATUS — Check cuOpt availability
    // ──────────────────────────────────────────────

    private suspend fun serverStatus(): ToolResult {
        val healthy = cuOptClient.isServerHealthy()
        val status = if (healthy) "🟢 Connected" else "🔴 Unavailable"
        val mode = if (healthy) "GPU-accelerated (cuOpt)" else "Heuristic (offline)"

        return ToolResult.success(
            name,
            data = mapOf(
                "server_url" to cuOptClient.serverUrl,
                "healthy" to healthy,
                "mode" to mode
            ),
            message = "⚡ *cuOpt Server Status*\n\n" +
                "🔌 Server: $status\n" +
                "🌐 URL: ${cuOptClient.serverUrl}\n" +
                "🔧 Mode: $mode\n\n" +
                if (healthy) "✅ GPU routing optimization available!\nAll routes will be optimized with NVIDIA cuOpt."
                else "⚠️ Server haipatikani / Server unavailable.\n" +
                    "Routes will use nearest-neighbor heuristic.\n" +
                    "Start cuOpt: `docker run --gpus all -p 8000:8000 nvidia/cuopt:latest`"
        )
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    /**
     * Build a distance matrix using Haversine formula.
     * Uses well-known Kenyan location coordinates for common areas.
     * Falls back to string-similarity-based estimation for unknown locations.
     */
    private fun buildDistanceMatrix(locations: List<String>): List<List<Double>> {
        val n = locations.size
        val coords = locations.map { getCoordinates(it) }

        return List(n) { i ->
            List(n) { j ->
                if (i == j) 0.0
                else haversineDistance(coords[i], coords[j])
            }
        }
    }

    /**
     * Build a time matrix (minutes) from distance matrix.
     * Average boda boda speed in Nairobi: ~25 km/h in traffic.
     */
    private fun buildTimeMatrix(locations: List<String>): List<List<Double>> {
        val distMatrix = buildDistanceMatrix(locations)
        val avgSpeedKmh = 25.0 // Boda boda average in Nairobi traffic

        return distMatrix.map { row ->
            row.map { dist ->
                (dist / avgSpeedKmh) * 60.0 // Convert to minutes
            }
        }
    }

    /**
     * Haversine distance between two coordinate pairs (km).
     */
    private fun haversineDistance(
        coord1: Pair<Double, Double>,
        coord2: Pair<Double, Double>
    ): Double {
        val R = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(coord2.first - coord1.first)
        val dLon = Math.toRadians(coord2.second - coord1.second)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(coord1.first)) * cos(Math.toRadians(coord2.first)) *
            sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Get approximate coordinates for known Kenyan locations.
     * Returns (latitude, longitude).
     */
    private fun getCoordinates(location: String): Pair<Double, Double> {
        val loc = location.lowercase().trim()
        return KNOWN_LOCATIONS[loc] ?: estimateCoordinates(loc)
    }

    /**
     * Well-known Nairobi and Kenya locations with coordinates.
     */
    private val KNOWN_LOCATIONS = mapOf(
        // Nairobi markets and stages
        "gikomba" to Pair(-1.2864, 36.8464),
        "wakulima" to Pair(-1.2833, 36.8286),
        "kariakoo" to Pair(-1.2833, 36.8333),
        "eastleigh" to Pair(-1.2753, 36.8443),
        "buruburu" to Pair(-1.2921, 36.8739),
        "kawangware" to Pair(-1.2833, 36.7500),
        "kibera" to Pair(-1.3133, 36.7833),
        "mathare" to Pair(-1.2600, 36.8600),
        "dandora" to Pair(-1.2500, 36.8833),
        "kayole" to Pair(-1.2667, 36.9000),
        "embakasi" to Pair(-1.3167, 36.8833),
        "umoja" to Pair(-1.2833, 36.8833),
        "roysambu" to Pair(-1.2167, 36.8500),
        "kasarani" to Pair(-1.2167, 36.8667),
        "thika" to Pair(-1.0333, 37.0833),
        "kiambu" to Pair(-1.1667, 36.8333),
        "rongai" to Pair(-1.4000, 36.7500),
        "langata" to Pair(-1.3667, 36.7500),
        "south b" to Pair(-1.3167, 36.8333),
        "south c" to Pair(-1.3167, 36.8167),
        "industrial area" to Pair(-1.3000, 36.8500),
        "cbd" to Pair(-1.2864, 36.8172),
        "kenyatta avenue" to Pair(-1.2833, 36.8167),
        "moi avenue" to Pair(-1.2833, 36.8222),
        "tom mboya" to Pair(-1.2833, 36.8278),
        "river road" to Pair(-1.2833, 36.8250),
        "ngara" to Pair(-1.2750, 36.8250),
        "parklands" to Pair(-1.2667, 36.8167),
        "westlands" to Pair(-1.2667, 36.8000),
        "kilimani" to Pair(-1.2833, 36.7833),
        "lavington" to Pair(-1.2833, 36.7667),
        "karen" to Pair(-1.3167, 36.7167),
        "ngong" to Pair(-1.3667, 36.6667),
        "machakos" to Pair(-1.5167, 37.2667),
        "mombasa" to Pair(-4.0500, 39.6667),
        "kisumu" to Pair(-0.1000, 34.7500),
        "nakuru" to Pair(-0.3000, 36.0667),
        // Common home/stage names
        "home" to Pair(-1.2864, 36.8172),
        "stage" to Pair(-1.2864, 36.8172)
    )

    /**
     * Estimate coordinates for unknown locations based on string similarity
     * to known locations. Falls back to Nairobi CBD center.
     */
    private fun estimateCoordinates(location: String): Pair<Double, Double> {
        // Try fuzzy match against known locations
        for ((known, coords) in KNOWN_LOCATIONS) {
            if (location.contains(known) || known.contains(location)) {
                return coords
            }
        }
        // Default: Nairobi CBD
        return Pair(-1.2864, 36.8172)
    }

    /**
     * Simple string similarity for location matching (0.0 to 1.0).
     */
    private fun locationSimilarity(a: String, b: String): Double {
        val aLower = a.lowercase().trim()
        val bLower = b.lowercase().trim()

        if (aLower == bLower) return 1.0
        if (aLower.contains(bLower) || bLower.contains(aLower)) return 0.8

        // Character overlap ratio
        val common = aLower.toSet().intersect(bLower.toSet()).size
        val total = maxOf(aLower.toSet().size, bLower.toSet().size)
        return if (total > 0) common.toDouble() / total else 0.0
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSER (Swahili)
    // ──────────────────────────────────────────────

    fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = text.lowercase()

        // Detect action
        when {
            lower.contains(Regex("nipe njia|njia bora|optimize|route|panga njia|calculate route")) -> {
                params["action"] = "optimize_route"
            }
            lower.contains(Regex("rider wa karibu|rider gani|rider wapi|nearest|karibu")) -> {
                params["action"] = "match_nearest"
            }
            lower.contains(Regex("batch|panga pickups|chukua wote|wateja wangu")) -> {
                params["action"] = "batch_pickups"
            }
            lower.contains(Regex("delivery|peleka|bulk delivery|utoaji")) -> {
                params["action"] = "bulk_delivery"
            }
            lower.contains(Regex("soko|market|trip ya soko|mama mboga")) -> {
                params["action"] = "market_trip_route"
            }
            lower.contains(Regex("server|cuopt|status|connection")) -> {
                params["action"] = "server_status"
            }
        }

        // Extract locations from common patterns
        val routePattern = Regex("""(?:kutoka|from)\s+(\w[\w\s]*?)\s+(?:hadi|kwenda|to|mpaka)\s+(\w[\w\s]*?)(?:\s|$)""", RegexOption.IGNORE_CASE)
        routePattern.find(text)?.let {
            params["depot"] = it.groupValues[1].trim().replaceFirstChar { c -> c.uppercase() }
            val dest = it.groupValues[2].trim().replaceFirstChar { c -> c.uppercase() }
            params["stops"] = dest
        }

        // "Njia ya X, Y, Z"
        val multiStop = Regex("""njia\s+(?:ya\s+)?(.+?)(?:\s*$)""", RegexOption.IGNORE_CASE)
        multiStop.find(text)?.let {
            val stops = it.groupValues[1]
                .replace(Regex("(na|,|hadi)"), ",")
                .split(",")
                .map { s -> s.trim().replaceFirstChar { c -> c.uppercase() } }
                .filter { s -> s.isNotEmpty() }
            if (stops.size >= 2) {
                params["stops"] = stops.joinToString(",")
            }
        }

        // Extract "soko la X" or "market X"
        val marketPattern = Regex("""(?:soko\s+(?:la\s+)?|market\s+)(\w+)""", RegexOption.IGNORE_CASE)
        marketPattern.find(text)?.let {
            params["market"] = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
        }

        return params
    }
}
