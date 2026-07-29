package com.msaidizi.agent.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CuOptApiClient — HTTP client wrapper for NVIDIA cuOpt REST server.
 *
 * Handles:
 *   - Health checks
 *   - Submitting routing problems (VRP/TSP/PDP)
 *   - Polling for solutions
 *   - Graceful degradation when server is unreachable
 *
 * cuOpt REST workflow:
 *   1. POST /cuopt/request → get reqId
 *   2. Poll GET /cuopt/solution/{reqId} until solution ready
 *   3. Parse response (status, routes, costs)
 *
 * Default server: http://localhost:8000 (configurable via CUOPT_SERVER_URL env or constructor)
 */
@Singleton
class CuOptApiClient @Inject constructor() {

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** cuOpt server base URL. Override via constructor or environment. */
    var serverUrl: String = System.getenv("CUOPT_SERVER_URL") ?: DEFAULT_SERVER_URL

    companion object {
        const val DEFAULT_SERVER_URL = "http://localhost:8000"
        const val HEALTH_ENDPOINT = "/cuopt/health"
        const val REQUEST_ENDPOINT = "/cuopt/request"
        const val SOLUTION_ENDPOINT = "/cuopt/solution"
        const val CLIENT_VERSION = "msaidizi-boda-1.0"

        // Solution status codes
        const val STATUS_SUCCESS = 0
        const val STATUS_FAIL = 1
        const val STATUS_TIMEOUT = 2
        const val STATUS_EMPTY = 3

        // Polling config
        const val POLL_INTERVAL_MS = 1000L
        const val MAX_POLL_ATTEMPTS = 30
    }

    /**
     * Check if cuOpt server is reachable.
     * Returns true if health endpoint responds 200.
     */
    suspend fun isServerHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl$HEALTH_ENDPOINT")
                .get()
                .addHeader("CLIENT-VERSION", CLIENT_VERSION)
                .build()

            client.newCall(request).execute().use { response ->
                val healthy = response.isSuccessful
                Timber.d("cuOpt health check: ${if (healthy) "OK" else "FAIL (${response.code})"}")
                healthy
            }
        } catch (e: Exception) {
            Timber.d("cuOpt server unreachable: ${e.message}")
            false
        }
    }

    /**
     * Submit a routing problem and wait for solution.
     *
     * @param payload The cuOpt routing problem JSON payload
     * @return CuOptResult with solution data or error
     */
    suspend fun solveRoutingProblem(payload: JsonObject): CuOptResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Submit problem
            val reqId = submitRequest(payload)
                ?: return@withContext CuOptResult.Error("Failed to submit request to cuOpt server")

            Timber.d("cuOpt request submitted: $reqId")

            // Step 2: Poll for solution
            val solution = pollForSolution(reqId)
                ?: return@withContext CuOptResult.Error("Timeout waiting for cuOpt solution (reqId=$reqId)")

            // Step 3: Parse solution
            parseSolution(solution)
        } catch (e: Exception) {
            Timber.e(e, "cuOpt routing failed")
            CuOptResult.Error("cuOpt error: ${e.message}")
        }
    }

    /**
     * Submit a request payload to cuOpt server.
     * Returns the request ID (reqId) on success, null on failure.
     */
    private fun submitRequest(payload: JsonObject): String? {
        val body = payload.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("$serverUrl$REQUEST_ENDPOINT")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("CLIENT-VERSION", CLIENT_VERSION)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("cuOpt submit failed: ${response.code} — ${response.body?.string()}")
                return null
            }
            val bodyStr = response.body?.string() ?: return null
            val json = JsonParser.parseString(bodyStr).asJsonObject
            return json.get("reqId")?.asString
        }
    }

    /**
     * Poll cuOpt server for solution until ready or timeout.
     */
    private fun pollForSolution(reqId: String): JsonObject? {
        for (attempt in 0 until MAX_POLL_ATTEMPTS) {
            val request = Request.Builder()
                .url("$serverUrl$SOLUTION_ENDPOINT/$reqId")
                .get()
                .addHeader("CLIENT-VERSION", CLIENT_VERSION)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("cuOpt poll attempt $attempt failed: ${response.code}")
                    Thread.sleep(POLL_INTERVAL_MS)
                    return@use
                }

                val bodyStr = response.body?.string() ?: return@use
                val json = JsonParser.parseString(bodyStr).asJsonObject

                // Check if solution is ready
                if (json.has("response")) {
                    return json
                }
            }

            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * Parse cuOpt solution response into CuOptResult.
     */
    private fun parseSolution(response: JsonObject): CuOptResult {
        return try {
            val solverResponse = response.getAsJsonObject("response")
                ?.getAsJsonObject("solver_response")
                ?: return CuOptResult.Error("Missing solver_response in cuOpt output")

            val status = solverResponse.get("status")?.asInt ?: -1

            when (status) {
                STATUS_SUCCESS -> {
                    val solutionCost = solverResponse.get("solution_cost")?.asDouble ?: 0.0
                    val vehicleData = solverResponse.getAsJsonObject("vehicle_data")

                    val routes = mutableListOf<CuOptRoute>()
                    vehicleData?.entrySet()?.forEach { (vehicleId, data) ->
                        val vObj = data.asJsonObject
                        val route = vObj.getAsJsonArray("route")?.map { it.asInt } ?: emptyList()
                        val arrivalTimes = vObj.getAsJsonArray("arrival_times")?.map { it.asDouble } ?: emptyList()
                        val routeCost = vObj.get("route_cost")?.asDouble ?: 0.0

                        routes.add(CuOptRoute(
                            vehicleId = vehicleId.toIntOrNull() ?: 0,
                            route = route,
                            arrivalTimes = arrivalTimes,
                            routeCost = routeCost
                        ))
                    }

                    CuOptResult.Success(
                        totalCost = solutionCost,
                        routes = routes,
                        rawResponse = response
                    )
                }
                STATUS_TIMEOUT -> CuOptResult.Error("cuOpt solver timed out")
                STATUS_EMPTY -> CuOptResult.Error("cuOpt returned empty solution — check constraints")
                STATUS_FAIL -> {
                    val errorMsg = solverResponse.get("error")?.asString ?: "Unknown solver error"
                    CuOptResult.Error("cuOpt solver failed: $errorMsg")
                }
                else -> CuOptResult.Error("cuOpt unknown status: $status")
            }
        } catch (e: Exception) {
            CuOptResult.Error("Failed to parse cuOpt solution: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // PAYLOAD BUILDERS
    // ──────────────────────────────────────────────

    /**
     * Build a VRP payload for boda boda routing.
     *
     * @param costMatrix NxN travel cost matrix (distance or time)
     * @param travelTimeMatrix NxN travel time matrix (minutes)
     * @param taskLocations Indices of pickup/delivery locations (1-indexed, 0 = depot)
     * @param demand Demand per task (passengers, weight, etc.)
     * @param taskTimeWindows Pair of [earliest, latest] per task
     * @param serviceTimes Service time at each location (minutes)
     * @param vehicleStartLocations Starting location index per rider
     * @param capacities Capacity per rider
     * @param vehicleTimeWindows Working time windows per rider
     * @param timeLimit Solver time limit in seconds
     */
    fun buildVrpPayload(
        costMatrix: List<List<Double>>,
        travelTimeMatrix: List<List<Double>>,
        taskLocations: List<Int>,
        demand: List<List<Int>>,
        taskTimeWindows: List<List<Int>>? = null,
        serviceTimes: List<Int>? = null,
        vehicleStartLocations: List<List<Int>>? = null,
        capacities: List<List<Int>>? = null,
        vehicleTimeWindows: List<List<Int>>? = null,
        timeLimit: Int = 10
    ): JsonObject {
        val payload = JsonObject()

        // Cost matrix
        val costMatrixData = JsonObject()
        val costData = JsonObject()
        costData.add("0", gson.toJsonTree(costMatrix))
        costMatrixData.add("data", costData)
        payload.add("cost_matrix_data", costMatrixData)

        // Travel time matrix
        val ttMatrixData = JsonObject()
        val ttData = JsonObject()
        ttData.add("0", gson.toJsonTree(travelTimeMatrix))
        ttMatrixData.add("data", ttData)
        payload.add("travel_time_matrix_data", ttMatrixData)

        // Task data
        val taskData = JsonObject()
        taskData.add("task_locations", gson.toJsonTree(taskLocations))
        taskData.add("demand", gson.toJsonTree(demand))
        taskTimeWindows?.let { taskData.add("task_time_windows", gson.toJsonTree(it)) }
        serviceTimes?.let { taskData.add("service_times", gson.toJsonTree(it)) }
        payload.add("task_data", taskData)

        // Fleet data
        val fleetData = JsonObject()
        vehicleStartLocations?.let { fleetData.add("vehicle_locations", gson.toJsonTree(it)) }
        capacities?.let { fleetData.add("capacities", gson.toJsonTree(it)) }
        vehicleTimeWindows?.let { fleetData.add("vehicle_time_windows", gson.toJsonTree(it)) }
        payload.add("fleet_data", fleetData)

        // Solver config
        val solverConfig = JsonObject()
        solverConfig.addProperty("time_limit", timeLimit)
        payload.add("solver_config", solverConfig)

        return payload
    }

    /**
     * Build a simple TSP payload (single vehicle, all locations).
     */
    fun buildTspPayload(
        costMatrix: List<List<Double>>,
        travelTimeMatrix: List<List<Double>>,
        depot: Int = 0,
        timeLimit: Int = 10
    ): JsonObject {
        val n = costMatrix.size
        val taskLocations = (1 until n).toList()

        return buildVrpPayload(
            costMatrix = costMatrix,
            travelTimeMatrix = travelTimeMatrix,
            taskLocations = taskLocations,
            demand = listOf(List(taskLocations.size) { 0 }),
            vehicleStartLocations = listOf(listOf(depot, depot)),
            capacities = listOf(listOf(Int.MAX_VALUE)),
            timeLimit = timeLimit
        )
    }
}

// ──────────────────────────────────────────────
// RESULT TYPES
// ──────────────────────────────────────────────

/**
 * Result from cuOpt routing solve.
 */
sealed class CuOptResult {
    data class Success(
        val totalCost: Double,
        val routes: List<CuOptRoute>,
        val rawResponse: JsonObject
    ) : CuOptResult()

    data class Error(val message: String) : CuOptResult()
}

/**
 * A single vehicle's route from cuOpt solution.
 */
data class CuOptRoute(
    val vehicleId: Int,
    val route: List<Int>,           // Location indices in visit order
    val arrivalTimes: List<Double>, // Arrival time at each stop
    val routeCost: Double           // Cost of this route
)
