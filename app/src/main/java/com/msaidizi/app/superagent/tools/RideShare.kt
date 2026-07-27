package com.msaidizi.app.superagent.tools

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════
// RIDE SHARE — Boda boda trip coordination
// ══════════════════════════════════════════════

// ──────────────────────────────────────────────
// Room Entities
// ──────────────────────────────────────────────

/**
 * A registered ride-share user — either a rider (boda boda) or passenger.
 * Trust score builds over time through completed trips and ratings.
 */
@Entity(tableName = "ride_users")
data class RideUserEntity(
    @PrimaryKey val userId: String,          // phone or device ID
    val userType: String,                     // "rider" | "passenger"
    val name: String,
    val phone: String? = null,
    val stageName: String? = null,            // home stage
    val trustScore: Double = 5.0,             // 1.0–5.0
    val totalTrips: Int = 0,
    val createdAt: String = nowIso(),
    val updatedAt: String = nowIso(),
    // Offline sync
    val needsSync: Boolean = true
)

/**
 * A scheduled or posted ride offer from a rider.
 * Riders post available seats on a trip they're making anyway.
 */
@Entity(
    tableName = "ride_offers",
    indices = [
        Index(value = ["status", "fromLocation", "toLocation"]),
        Index(value = ["riderId"]),
        Index(value = ["departureTime"])
    ]
)
data class RideOfferEntity(
    @PrimaryKey(autoGenerate = true) val offerId: Long = 0,
    val riderId: String,
    val riderName: String,
    val fromLocation: String,
    val toLocation: String,
    val departureTime: String,                // ISO datetime
    val seatsAvailable: Int = 1,
    val seatsTaken: Int = 0,
    val fareTotal: Double,                    // total fare in KSh
    val farePerSeat: Double,                  // split amount per passenger
    val distanceKm: Double? = null,
    val status: String = "open",              // open | full | completed | cancelled
    val createdAt: String = nowIso(),
    val updatedAt: String = nowIso(),
    val needsSync: Boolean = true
)

/**
 * A passenger's request to join a ride offer.
 * Links passenger ↔ offer; both must confirm.
 */
@Entity(
    tableName = "ride_requests",
    indices = [
        Index(value = ["offerId"]),
        Index(value = ["passengerId"]),
        Index(value = ["status"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = RideOfferEntity::class,
            parentColumns = ["offerId"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RideRequestEntity(
    @PrimaryKey(autoGenerate = true) val requestId: Long = 0,
    val offerId: Long,
    val passengerId: String,
    val passengerName: String,
    val seatsRequested: Int = 1,
    val pickupLocation: String? = null,       // optional specific pickup point
    val status: String = "pending",           // pending | accepted | rejected | completed | cancelled
    val createdAt: String = nowIso(),
    val updatedAt: String = nowIso(),
    val needsSync: Boolean = true
)

/**
 * A completed ride trip — immutable record for history and earnings.
 */
@Entity(
    tableName = "ride_trips",
    indices = [
        Index(value = ["riderId", "completedAt"]),
        Index(value = ["completedAt"])
    ]
)
data class RideTripEntity(
    @PrimaryKey(autoGenerate = true) val tripId: Long = 0,
    val offerId: Long,
    val riderId: String,
    val riderName: String,
    val passengerIds: String,                 // comma-separated
    val passengerNames: String,               // comma-separated
    val fromLocation: String,
    val toLocation: String,
    val fareTotal: Double,
    val farePerPassenger: Double,
    val distanceKm: Double? = null,
    val durationMin: Int? = null,
    val departedAt: String? = null,
    val completedAt: String = nowIso(),
    val savingsVsSolo: Double = 0.0,          // estimated savings vs individual fare
    val needsSync: Boolean = true
)

/**
 * Rating for a completed trip — builds trust scores.
 */
@Entity(
    tableName = "ride_ratings",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["ratedId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = RideTripEntity::class,
            parentColumns = ["tripId"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RideRatingEntity(
    @PrimaryKey(autoGenerate = true) val ratingId: Long = 0,
    val tripId: Long,
    val raterId: String,
    val ratedId: String,
    val score: Int,                           // 1–5
    val comment: String? = null,
    val createdAt: String = nowIso()
)

// ──────────────────────────────────────────────
// DAOs
// ──────────────────────────────────────────────

@Dao
interface RideUserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: RideUserEntity): Long

    @Update
    suspend fun update(user: RideUserEntity)

    @Query("SELECT * FROM ride_users WHERE userId = :userId")
    suspend fun getById(userId: String): RideUserEntity?

    @Query("SELECT * FROM ride_users WHERE userType = :type")
    fun getByType(type: String): Flow<List<RideUserEntity>>

    @Query("SELECT * FROM ride_users WHERE stageName = :stage AND userType = :type")
    fun getByStageAndType(stage: String, type: String): Flow<List<RideUserEntity>>

    @Query("UPDATE ride_users SET trustScore = :score, totalTrips = totalTrips + 1, updatedAt = :now WHERE userId = :userId")
    suspend fun updateTrust(userId: String, score: Double, now: String = nowIso())

    @Query("UPDATE ride_users SET totalTrips = totalTrips + 1, updatedAt = :now WHERE userId = :userId")
    suspend fun incrementTrips(userId: String, now: String = nowIso())

    @Query("SELECT * FROM ride_users WHERE userId IN (:userIds)")
    suspend fun getByIds(userIds: List<String>): List<RideUserEntity>

    @Query("UPDATE ride_users SET stageName = :stage, updatedAt = :now, needsSync = 1 WHERE userId = :userId")
    suspend fun updateStage(userId: String, stage: String, now: String = nowIso())
}

@Dao
interface RideOfferDao {
    @Insert
    suspend fun insert(offer: RideOfferEntity): Long

    @Update
    suspend fun update(offer: RideOfferEntity)

    @Query("SELECT * FROM ride_offers WHERE offerId = :offerId")
    suspend fun getById(offerId: Long): RideOfferEntity?

    @Query("""
        SELECT * FROM ride_offers
        WHERE status = 'open'
          AND seatsAvailable > seatsTaken
          AND fromLocation LIKE '%' || :fromLoc || '%'
          AND toLocation LIKE '%' || :toLoc || '%'
          AND departureTime >= :earliest
          AND departureTime <= :latest
        ORDER BY departureTime ASC
    """)
    suspend fun findMatchingOffers(
        fromLoc: String,
        toLoc: String,
        earliest: String,
        latest: String
    ): List<RideOfferEntity>

    @Query("SELECT * FROM ride_offers WHERE riderId = :riderId ORDER BY createdAt DESC LIMIT :limit")
    fun getByRider(riderId: String, limit: Int = 20): Flow<List<RideOfferEntity>>

    @Query("SELECT * FROM ride_offers WHERE riderId = :riderId AND status = 'open' ORDER BY departureTime ASC")
    fun getOpenByRider(riderId: String): Flow<List<RideOfferEntity>>

    @Query("UPDATE ride_offers SET seatsTaken = seatsTaken + :seats, updatedAt = :now WHERE offerId = :offerId")
    suspend fun bookSeats(offerId: Long, seats: Int, now: String = nowIso())

    @Query("UPDATE ride_offers SET status = :status, updatedAt = :now WHERE offerId = :offerId")
    suspend fun updateStatus(offerId: Long, status: String, now: String = nowIso())

    @Query("""
        SELECT * FROM ride_offers
        WHERE status = 'open'
          AND seatsAvailable > seatsTaken
          AND departureTime >= :earliest
          AND departureTime <= :latest
        ORDER BY departureTime ASC
    """)
    suspend fun getAvailableInWindow(earliest: String, latest: String): List<RideOfferEntity>

    @Query("SELECT * FROM ride_offers WHERE riderId = :riderId ORDER BY createdAt DESC")
    fun getAllByRider(riderId: String): Flow<List<RideOfferEntity>>
}

@Dao
interface RideRequestDao {
    @Insert
    suspend fun insert(request: RideRequestEntity): Long

    @Update
    suspend fun update(request: RideRequestEntity)

    @Query("SELECT * FROM ride_requests WHERE requestId = :requestId")
    suspend fun getById(requestId: Long): RideRequestEntity?

    @Query("SELECT * FROM ride_requests WHERE offerId = :offerId")
    fun getByOffer(offerId: Long): Flow<List<RideRequestEntity>>

    @Query("SELECT * FROM ride_requests WHERE offerId = :offerId AND status = :status")
    suspend fun getByOfferAndStatus(offerId: Long, status: String): List<RideRequestEntity>

    @Query("SELECT * FROM ride_requests WHERE passengerId = :passengerId ORDER BY createdAt DESC LIMIT :limit")
    fun getByPassenger(passengerId: String, limit: Int = 20): Flow<List<RideRequestEntity>>

    @Query("SELECT * FROM ride_requests WHERE offerId = :offerId AND passengerId = :passengerId LIMIT 1")
    suspend fun getByOfferAndPassenger(offerId: Long, passengerId: String): RideRequestEntity?

    @Query("UPDATE ride_requests SET status = :status, updatedAt = :now WHERE requestId = :requestId")
    suspend fun updateStatus(requestId: Long, status: String, now: String = nowIso())

    @Query("SELECT * FROM ride_requests WHERE offerId IN (:offerIds) AND status = 'accepted'")
    suspend fun getAcceptedForOffers(offerIds: List<Long>): List<RideRequestEntity>
}

@Dao
interface RideTripDao {
    @Insert
    suspend fun insert(trip: RideTripEntity): Long

    @Query("SELECT * FROM ride_trips WHERE tripId = :tripId")
    suspend fun getById(tripId: Long): RideTripEntity?

    @Query("""
        SELECT * FROM ride_trips
        WHERE riderId = :userId OR passengerIds LIKE '%' || :userId || '%'
        ORDER BY completedAt DESC
        LIMIT :limit
    """)
    fun getHistoryForUser(userId: String, limit: Int = 30): Flow<List<RideTripEntity>>

    @Query("SELECT * FROM ride_trips WHERE riderId = :riderId ORDER BY completedAt DESC LIMIT :limit")
    fun getRiderHistory(riderId: String, limit: Int = 30): Flow<List<RideTripEntity>>

    @Query("""
        SELECT COUNT(*) FROM ride_trips
        WHERE riderId = :userId OR passengerIds LIKE '%' || :userId || '%'
    """)
    suspend fun getTripCount(userId: String): Int

    @Query("""
        SELECT COALESCE(SUM(savingsVsSolo), 0) FROM ride_trips
        WHERE riderId = :userId OR passengerIds LIKE '%' || :userId || '%'
    """)
    suspend fun getTotalSavings(userId: String): Double

    @Query("""
        SELECT COALESCE(SUM(fareTotal), 0) FROM ride_trips
        WHERE riderId = :riderId
          AND completedAt >= :since
    """)
    suspend fun getRiderEarningsSince(riderId: String, since: String): Double

    @Query("""
        SELECT COALESCE(SUM(farePerPassenger), 0) FROM ride_trips
        WHERE passengerIds LIKE '%' || :passengerId || '%'
          AND completedAt >= :since
    """)
    suspend fun getPassengerSpendingSince(passengerId: String, since: String): Double
}

@Dao
interface RideRatingDao {
    @Insert
    suspend fun insert(rating: RideRatingEntity): Long

    @Query("SELECT * FROM ride_ratings WHERE ratedId = :userId ORDER BY createdAt DESC")
    fun getForUser(userId: String): Flow<List<RideRatingEntity>>

    @Query("SELECT AVG(score) FROM ride_ratings WHERE ratedId = :userId")
    suspend fun getAverageScore(userId: String): Double?

    @Query("SELECT COUNT(*) FROM ride_ratings WHERE ratedId = :userId")
    suspend fun getRatingCount(userId: String): Int

    @Query("SELECT * FROM ride_ratings WHERE tripId = :tripId AND raterId = :raterId LIMIT 1")
    suspend fun getByTripAndRater(tripId: Long, raterId: String): RideRatingEntity?
}

// ──────────────────────────────────────────────
// RIDE SHARE TOOL
// ──────────────────────────────────────────────

/**
 * RideShare — Boda boda trip coordination tool.
 *
 * Riders share passengers & routes, reducing idle time and fuel costs.
 * Passengers get cheaper fares through ride-sharing.
 *
 * Actions:
 *  - find_match:  Find available rides matching origin/destination/time
 *  - offer_ride:  Rider posts an available seat on a trip
 *  - accept:      Passenger accepts an offer (or rider accepts a request)
 *  - complete:    Mark trip done; record fare, distance, savings
 *  - history:     View trip history and earnings/savings
 *
 * Voice (Swahili):
 *  - "Nataka kwenda Wakulima kesho asubuhi" → find_match
 *  - "Ninatoa Gikomba saa kumi, nafasi moja" → offer_ride
 *  - "Nimefika" / "Safari imeisha" → complete
 */
@Singleton
class RideShare @Inject constructor(
    private val rideUserDao: RideUserDao,
    private val rideOfferDao: RideOfferDao,
    private val rideRequestDao: RideRequestDao,
    private val rideTripDao: RideTripDao,
    private val rideRatingDao: RideRatingDao
) : Tool {

    override val name = "ride_share"
    override val description = "Boda boda ride sharing — find rides, offer seats, " +
            "accept bookings, complete trips, view history. Reduces idle time and fuel costs " +
            "for riders; cheaper fares for passengers. Supports Swahili voice input."

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("find_match", "offer_ride", "accept", "complete", "history"))

        // ── find_match ──
        string("user_id", "User ID (phone or device ID)", required = false)
        string("from_location", "Origin stage/area name (e.g. 'Gikomba')", required = false)
        string("to_location", "Destination name (e.g. 'Wakulima')", required = false)
        string("departure_window", "Time window: '30m', '1h', '2h', 'today', 'tomorrow'", required = false)
        enum("role", "User role", listOf("rider", "passenger"), required = false)

        // ── offer_ride ──
        string("rider_id", "Rider's user ID", required = false)
        string("rider_name", "Rider's display name", required = false)
        string("departure_time", "Departure time (ISO datetime or 'HH:mm' for today)", required = false)
        integer("seats", "Available passenger seats (1-4)", required = false)
        number("fare_total", "Total fare for the trip in KSh", required = false)
        number("distance_km", "Estimated distance in km", required = false)

        // ── accept ──
        string("request_id", "Request ID to accept (for riders)", required = false)
        string("offer_id", "Offer ID to accept (for passengers)", required = false)
        string("passenger_id", "Passenger's user ID", required = false)
        string("passenger_name", "Passenger's display name", required = false)
        string("pickup_location", "Specific pickup point", required = false)

        // ── complete ──
        string("trip_id", "Trip ID to complete", required = false)
        number("actual_fare", "Actual fare paid in KSh (if different from estimate)", required = false)
        number("actual_distance", "Actual distance in km", required = false)
        integer("actual_duration", "Actual trip duration in minutes", required = false)
        integer("rating", "Rating 1-5 for the other party", required = false)
        string("rating_comment", "Optional rating comment", required = false)

        // ── history ──
        integer("limit", "Number of history entries to return", required = false)

        // ── Voice input ──
        string("voice_text", "Raw Swahili voice input to parse", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Parse voice input if provided
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "find_match"
        return when (action.lowercase()) {
            "find_match" -> findMatch(effectiveParams)
            "offer_ride" -> offerRide(effectiveParams)
            "accept" -> acceptRide(effectiveParams)
            "complete" -> completeTrip(effectiveParams)
            "history" -> viewHistory(effectiveParams)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // FIND MATCH
    // ──────────────────────────────────────────────

    private suspend fun findMatch(params: Map<String, String>): ToolResult {
        return try {
            val userId = params["user_id"] ?: "local_user"
            val fromLoc = params["from_location"]
                ?: return ToolResult.error(name, "from_location required. Sema: unatoka wapi?", "MISSING_FROM")
            val toLoc = params["to_location"]
                ?: return ToolResult.error(name, "to_location required. Sema: unakwenda wapi?", "MISSING_TO")
            val window = params["departure_window"] ?: "2h"
            val role = params["role"] ?: "passenger"

            // Calculate time window
            val now = Calendar.getInstance()
            val earliest = now.clone() as Calendar
            val latest = now.clone() as Calendar

            when {
                window == "today" -> {
                    latest.set(Calendar.HOUR_OF_DAY, 23)
                    latest.set(Calendar.MINUTE, 59)
                }
                window == "tomorrow" -> {
                    earliest.add(Calendar.DAY_OF_YEAR, 1)
                    earliest.set(Calendar.HOUR_OF_DAY, 5)
                    earliest.set(Calendar.MINUTE, 0)
                    latest.add(Calendar.DAY_OF_YEAR, 1)
                    latest.set(Calendar.HOUR_OF_DAY, 23)
                    latest.set(Calendar.MINUTE, 59)
                }
                window.endsWith("m") -> {
                    val mins = window.removeSuffix("m").toIntOrNull() ?: 120
                    latest.add(Calendar.MINUTE, mins)
                }
                window.endsWith("h") -> {
                    val hours = window.removeSuffix("h").toIntOrNull() ?: 2
                    latest.add(Calendar.HOUR_OF_DAY, hours)
                }
                else -> latest.add(Calendar.HOUR_OF_DAY, 2)
            }

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
            val offers = rideOfferDao.findMatchingOffers(
                fromLoc = fromLoc,
                toLoc = toLoc,
                earliest = isoFormat.format(earliest.time),
                latest = isoFormat.format(latest.time)
            )

            if (offers.isEmpty()) {
                // Also try broader search — any available in window
                val broadOffers = rideOfferDao.getAvailableInWindow(
                    earliest = isoFormat.format(earliest.time),
                    latest = isoFormat.format(latest.time)
                )

                if (broadOffers.isEmpty()) {
                    return ToolResult.success(
                        name,
                        data = mapOf("matches" to emptyList<Any>()),
                        message = "🛵 Hakuna riders wanaenda $fromLoc → $toLoc kwenye muda huu. " +
                                "Jaribu tena baada ya dakika 15, au weka ombi lako (offer_ride)."
                    )
                }

                // Show nearby routes
                val nearby = broadOffers.take(5).map { offer ->
                    val rider = rideUserDao.getById(offer.riderId)
                    mapOf(
                        "offer_id" to offer.offerId,
                        "rider_name" to offer.riderName,
                        "trust_score" to (rider?.trustScore ?: 5.0),
                        "from" to offer.fromLocation,
                        "to" to offer.toLocation,
                        "departure" to offer.departureTime,
                        "seats_left" to (offer.seatsAvailable - offer.seatsTaken),
                        "fare_per_seat" to offer.farePerSeat
                    )
                }

                val report = buildString {
                    appendLine("🛵 Hakuna safari za moja kwa moja $fromLoc → $toLoc,")
                    appendLine("   lakini kuna riders wengine karibu:")
                    nearby.forEachIndexed { i, m ->
                        appendLine("  ${i + 1}. ${m["rider_name"]} — ${m["from"]} → ${m["to"]}")
                        appendLine("     ⏰ ${m["departure"]} | 💺 Nafasi: ${m["seats_left"]} | 💰 KSh ${m["fare_per_seat"]}")
                        appendLine("     ⭐ Trust: ${m["trust_score"]}")
                    }
                }

                return ToolResult.success(
                    name,
                    data = mapOf("matches" to nearby, "note" to "no_direct_match"),
                    message = report.trim()
                )
            }

            // Build match results
            val matches = offers.map { offer ->
                val rider = rideUserDao.getById(offer.riderId)
                mapOf(
                    "offer_id" to offer.offerId,
                    "rider_id" to offer.riderId,
                    "rider_name" to offer.riderName,
                    "trust_score" to (rider?.trustScore ?: 5.0),
                    "total_trips" to (rider?.totalTrips ?: 0),
                    "from" to offer.fromLocation,
                    "to" to offer.toLocation,
                    "departure" to offer.departureTime,
                    "seats_left" to (offer.seatsAvailable - offer.seatsTaken),
                    "fare_per_seat" to offer.farePerSeat,
                    "distance_km" to offer.distanceKm
                )
            }

            val report = buildString {
                appendLine("🛵 Riders ${matches.size} wanaenda $fromLoc → $toLoc:")
                matches.forEachIndexed { i, m ->
                    appendLine("")
                    appendLine("  ${i + 1}. ${m["rider_name"]} — ⭐ ${m["trust_score"]} (${m["total_trips"]} trips)")
                    appendLine("     ⏰ ${m["departure"]} | 💺 Nafasi: ${m["seats_left"]} | 💰 KSh ${m["fare_per_seat"]}/mtu")
                    m["distance_km"]?.let { appendLine("     📏 Km: $it") }
                }
                appendLine("")
                appendLine("Tumia 'accept' kuchukua nafasi. Mfano: accept offer_id ${matches.first()["offer_id"]}")
            }

            // Ensure local user exists
            ensureUserExists(userId, role, userId)

            ToolResult.success(
                name,
                data = mapOf("matches" to matches, "count" to matches.size),
                message = report.trim()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to find matches")
            ToolResult.error(name, "Failed to find matches: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // OFFER RIDE
    // ──────────────────────────────────────────────

    private suspend fun offerRide(params: Map<String, String>): ToolResult {
        return try {
            val riderId = params["rider_id"] ?: params["user_id"]
                ?: return ToolResult.error(name, "rider_id required. Sema: jina lako ni nani?", "MISSING_RIDER_ID")
            val riderName = params["rider_name"] ?: riderId
            val fromLoc = params["from_location"]
                ?: return ToolResult.error(name, "from_location required. Sema: unatoka wapi?", "MISSING_FROM")
            val toLoc = params["to_location"]
                ?: return ToolResult.error(name, "to_location required. Sema: unakwenda wapi?", "MISSING_TO")
            val departureTime = resolveDepartureTime(params["departure_time"])
            val seats = params["seats"]?.toIntOrNull() ?: 1
            val fareTotal = params["fare_total"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "fare_total required. Sema: bei ni ngapi?", "MISSING_FARE")
            val distanceKm = params["distance_km"]?.toDoubleOrNull()

            if (seats < 1 || seats > 4) {
                return ToolResult.error(name, "Seats must be 1-4", "INVALID_SEATS")
            }

            // Ensure rider exists
            ensureUserExists(riderId, "rider", riderName)
            rideUserDao.updateStage(riderId, fromLoc)

            val farePerSeat = fareTotal / seats

            val offer = RideOfferEntity(
                riderId = riderId,
                riderName = riderName,
                fromLocation = fromLoc,
                toLocation = toLoc,
                departureTime = departureTime,
                seatsAvailable = seats,
                fareTotal = fareTotal,
                farePerSeat = farePerSeat,
                distanceKm = distanceKm
            )
            val offerId = rideOfferDao.insert(offer)

            val displayTime = formatDepartureDisplay(departureTime)

            ToolResult.success(
                name,
                data = mapOf(
                    "offer_id" to offerId,
                    "rider" to riderName,
                    "from" to fromLoc,
                    "to" to toLoc,
                    "departure" to displayTime,
                    "seats" to seats,
                    "fare_per_seat" to farePerSeat,
                    "fare_total" to fareTotal,
                    "distance_km" to distanceKm
                ),
                message = "✅ Safari imepangwa!\n" +
                        "🛵 $fromLoc → $toLoc\n" +
                        "⏰ $displayTime\n" +
                        "💺 Nafasi: $seats | 💰 KSh ${"%,.0f".format(farePerSeat)}/mtu\n" +
                        "Total fare: KSh ${"%,.0f".format(fareTotal)}\n\n" +
                        "Passengers wataona safari yako. Utapata notification mtu akiomba nafasi."
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ride offer")
            ToolResult.error(name, "Failed to create offer: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ACCEPT RIDE
    // ──────────────────────────────────────────────

    private suspend fun acceptRide(params: Map<String, String>): ToolResult {
        return try {
            val offerId = params["offer_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "offer_id required", "MISSING_OFFER_ID")

            val offer = rideOfferDao.getById(offerId)
                ?: return ToolResult.error(name, "Offer haipatikani. Labda imekwisha.", "OFFER_NOT_FOUND")

            if (offer.status != "open") {
                return ToolResult.error(name, "Offer haiwezi kuchukuliwa. Status: ${offer.status}", "OFFER_CLOSED")
            }

            val seatsLeft = offer.seatsAvailable - offer.seatsTaken
            if (seatsLeft <= 0) {
                return ToolResult.error(name, "Hakuna nafasi zilizobaki. Safari imejaa.", "NO_SEATS")
            }

            // Passenger details
            val passengerId = params["passenger_id"] ?: params["user_id"]
                ?: return ToolResult.error(name, "passenger_id required", "MISSING_PASSENGER_ID")
            val passengerName = params["passenger_name"] ?: passengerId
            val pickupLocation = params["pickup_location"]

            // Check if already booked
            val existing = rideRequestDao.getByOfferAndPassenger(offerId, passengerId)
            if (existing != null && existing.status in listOf("pending", "accepted")) {
                return ToolResult.error(name, "Umeshapokea nafasi hii tayari.", "ALREADY_BOOKED")
            }

            // Ensure passenger exists
            ensureUserExists(passengerId, "passenger", passengerName)

            // Create request
            val request = RideRequestEntity(
                offerId = offerId,
                passengerId = passengerId,
                passengerName = passengerName,
                seatsRequested = 1,
                pickupLocation = pickupLocation
            )
            val requestId = rideRequestDao.insert(request)

            // Auto-accept: book the seat
            rideRequestDao.updateStatus(requestId, "accepted")
            rideOfferDao.bookSeats(offerId, 1)

            // Check if offer is now full
            val updatedOffer = rideOfferDao.getById(offerId)
            if (updatedOffer != null && updatedOffer.seatsTaken >= updatedOffer.seatsAvailable) {
                rideOfferDao.updateStatus(offerId, "full")
            }

            val displayTime = formatDepartureDisplay(offer.departureTime)

            ToolResult.success(
                name,
                data = mapOf(
                    "request_id" to requestId,
                    "offer_id" to offerId,
                    "rider_name" to offer.riderName,
                    "passenger_name" to passengerName,
                    "from" to offer.fromLocation,
                    "to" to offer.toLocation,
                    "departure" to displayTime,
                    "fare_per_seat" to offer.farePerSeat,
                    "seats_remaining" to (updatedOffer?.let { it.seatsAvailable - it.seatsTaken } ?: seatsLeft - 1)
                ),
                message = "✅ Umepokea nafasi!\n" +
                        "🛵 ${offer.riderName} anakubeba ${offer.fromLocation} → ${offer.toLocation}\n" +
                        "⏰ $displayTime\n" +
                        "💰 Fare: KSh ${"%,.0f".format(offer.farePerSeat)}\n" +
                        (if (pickupLocation != null) "📍 Pickup: $pickupLocation\n" else "") +
                        "Rider atakupigia simu kuthibisha."
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to accept ride")
            ToolResult.error(name, "Failed to accept ride: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // COMPLETE TRIP
    // ──────────────────────────────────────────────

    private suspend fun completeTrip(params: Map<String, String>): ToolResult {
        return try {
            val offerId = params["offer_id"]?.toLongOrNull()
                ?: params["trip_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "offer_id or trip_id required", "MISSING_ID")

            val offer = rideOfferDao.getById(offerId)
                ?: return ToolResult.error(name, "Safari haipatikani", "TRIP_NOT_FOUND")

            val riderId = params["rider_id"] ?: params["user_id"] ?: offer.riderId
            val actualFare = params["actual_fare"]?.toDoubleOrNull() ?: offer.fareTotal
            val actualDistance = params["actual_distance"]?.toDoubleOrNull() ?: offer.distanceKm
            val actualDuration = params["actual_duration"]?.toIntOrNull()

            // Get accepted passengers
            val acceptedRequests = rideRequestDao.getByOfferAndStatus(offerId, "accepted")
            val passengerIds = acceptedRequests.map { it.passengerId }
            val passengerNames = acceptedRequests.map { it.passengerName }

            if (passengerIds.isEmpty()) {
                return ToolResult.error(name, "Hakuna passengers waliochukua nafasi.", "NO_PASSENGERS")
            }

            val farePerPassenger = actualFare / (passengerIds.size + 1) // +1 for rider's share
            // Estimate savings: assume solo fare would be ~60% of total (rider bears more)
            val soloFareEstimate = actualFare * 0.6 // each passenger would pay this alone
            val savingsPerPassenger = soloFareEstimate - farePerPassenger

            // Create trip record
            val trip = RideTripEntity(
                offerId = offerId,
                riderId = riderId,
                riderName = offer.riderName,
                passengerIds = passengerIds.joinToString(","),
                passengerNames = passengerNames.joinToString(","),
                fromLocation = offer.fromLocation,
                toLocation = offer.toLocation,
                fareTotal = actualFare,
                farePerPassenger = farePerPassenger,
                distanceKm = actualDistance,
                durationMin = actualDuration,
                departedAt = offer.departureTime,
                savingsVsSolo = savingsPerPassenger * passengerIds.size
            )
            val tripId = rideTripDao.insert(trip)

            // Mark offer as completed
            rideOfferDao.updateStatus(offerId, "completed")

            // Mark all requests as completed
            acceptedRequests.forEach { req ->
                rideRequestDao.updateStatus(req.requestId, "completed")
            }

            // Update user trip counts
            rideUserDao.incrementTrips(riderId)
            passengerIds.forEach { pid -> rideUserDao.incrementTrips(pid) }

            // Handle rating
            val rating = params["rating"]?.toIntOrNull()
            if (rating != null && rating in 1..5) {
                val ratedId = params["rated_id"] ?: offer.riderId
                val raterId = riderId
                val ratingEntity = RideRatingEntity(
                    tripId = tripId,
                    raterId = raterId,
                    ratedId = ratedId,
                    score = rating,
                    comment = params["rating_comment"]
                )
                rideRatingDao.insert(ratingEntity)

                // Update trust score
                val avgScore = rideRatingDao.getAverageScore(ratedId) ?: rating.toDouble()
                val ratingCount = rideRatingDao.getRatingCount(ratedId)
                // Weighted: more ratings = more stable score
                val newTrust = if (ratingCount > 1) {
                    (avgScore * ratingCount + rating) / (ratingCount + 1)
                } else {
                    rating.toDouble()
                }
                rideUserDao.updateTrust(ratedId, newTrust)
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "trip_id" to tripId,
                    "rider" to offer.riderName,
                    "passengers" to passengerNames,
                    "from" to offer.fromLocation,
                    "to" to offer.toLocation,
                    "fare_total" to actualFare,
                    "fare_per_passenger" to farePerPassenger,
                    "distance_km" to actualDistance,
                    "duration_min" to actualDuration,
                    "savings_vs_solo" to (savingsPerPassenger * passengerIds.size)
                ),
                message = "🏁 Safari imekamilika!\n" +
                        "🛵 ${offer.fromLocation} → ${offer.toLocation}\n" +
                        "👥 Rider: ${offer.riderName} | Passengers: ${passengerNames.joinToString(", ")}\n" +
                        "💰 Fare: KSh ${"%,.0f".format(actualFare)} (KSh ${"%,.0f".format(farePerPassenger)}/mtu)\n" +
                        (actualDistance?.let { "📏 Km: $it\n" } ?: "") +
                        (actualDuration?.let { "⏱️ Dakika: $it\n" } ?: "") +
                        "💚 Okoa: KSh ${"%,.0f".format(savingsPerPassenger * passengerIds.size)} (badala ya kila mtu kulipa peke yake)"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to complete trip")
            ToolResult.error(name, "Failed to complete trip: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VIEW HISTORY
    // ──────────────────────────────────────────────

    private suspend fun viewHistory(params: Map<String, String>): ToolResult {
        return try {
            val userId = params["user_id"] ?: params["rider_id"] ?: params["passenger_id"]
                ?: return ToolResult.error(name, "user_id required", "MISSING_USER_ID")
            val limit = params["limit"]?.toIntOrNull() ?: 20
            val role = params["role"]

            // Get user info
            val user = rideUserDao.getById(userId)
            val userRole = role ?: user?.userType ?: "passenger"

            // Get trips
            val trips = rideTripDao.getHistoryForUser(userId, limit).first()

            if (trips.isEmpty()) {
                return ToolResult.success(
                    name,
                    data = mapOf("trips" to emptyList<Any>()),
                    message = "📜 Hakuna safari za awali kwa $userId. Anza safari yako ya kwanza leo!"
                )
            }

            val totalSavings = rideTripDao.getTotalSavings(userId)
            val totalTrips = rideTripDao.getTripCount(userId)

            // Calculate this week's stats
            val weekStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
            }
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
            val weekStartStr = isoFormat.format(weekStart.time)

            val weeklyEarnings = if (userRole == "rider") {
                rideTripDao.getRiderEarningsSince(userId, weekStartStr)
            } else {
                0.0
            }
            val weeklySpending = if (userRole == "passenger") {
                rideTripDao.getPassengerSpendingSince(userId, weekStartStr)
            } else {
                0.0
            }

            val recentTrips = trips.take(10).map { trip ->
                mapOf(
                    "trip_id" to trip.tripId,
                    "from" to trip.fromLocation,
                    "to" to trip.toLocation,
                    "rider" to trip.riderName,
                    "passengers" to trip.passengerNames,
                    "fare" to trip.fareTotal,
                    "fare_per_person" to trip.farePerPassenger,
                    "distance_km" to trip.distanceKm,
                    "duration_min" to trip.durationMin,
                    "completed" to trip.completedAt,
                    "savings" to trip.savingsVsSolo
                )
            }

            val report = buildString {
                appendLine("📜 Safari za $userId")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 Jumla ya safari: $totalTrips")
                appendLine("💚 Jumla ya okoa: KSh ${"%,.0f".format(totalSavings)}")
                if (userRole == "rider" && weeklyEarnings > 0) {
                    appendLine("💰 Mapato wiki hii: KSh ${"%,.0f".format(weeklyEarnings)}")
                }
                if (userRole == "passenger" && weeklySpending > 0) {
                    appendLine("💸 Matumizi wiki hii: KSh ${"%,.0f".format(weeklySpending)}")
                }
                user?.let {
                    appendLine("⭐ Trust score: ${"%.1f".format(it.trustScore)}")
                }
                appendLine("")
                appendLine("── Safari za Hivi Karibuni ──")
                recentTrips.forEachIndexed { i, t ->
                    appendLine("")
                    appendLine("  ${i + 1}. ${t["from"]} → ${t["to"]}")
                    appendLine("     👤 ${t["rider"]} | 👥 ${t["passengers"]}")
                    appendLine("     💰 KSh ${"%,.0f".format(t["fare"] as Double)} (${t["fare_per_person"]}/mtu)")
                    t["distance_km"]?.let { appendLine("     📏 Km: $it") }
                    t["duration_min"]?.let { appendLine("     ⏱️ Dakika: $it") }
                    val savings = t["savings"] as Double
                    if (savings > 0) appendLine("     💚 Okoa: KSh ${"%,.0f".format(savings)}")
                    appendLine("     📅 ${t["completed"]}")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "user_id" to userId,
                    "role" to userRole,
                    "total_trips" to totalTrips,
                    "total_savings" to totalSavings,
                    "weekly_earnings" to weeklyEarnings,
                    "weekly_spending" to weeklySpending,
                    "trust_score" to (user?.trustScore ?: 5.0),
                    "recent_trips" to recentTrips
                ),
                message = report.trim()
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get history")
            ToolResult.error(name, "Failed to get history: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSER (Swahili)
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili voice input for ride-sharing actions.
     *
     * Examples:
     *  - "Nataka kwenda Wakulima kesho asubuhi" → find_match, to=Wakulima, window=tomorrow
     *  - "Ninatoa Gikomba saa kumi, nafasi mbili" → offer_ride, from=Gikomba, seats=2
     *  - "Nimepokea nafasi ya James" → accept
     *  - "Safari imeisha, nimelipa mia tano" → complete, fare=500
     *  - "Safari zangu za leo" → history
     */
    fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = text.lowercase()

        // ── Detect action ──
        when {
            // find_match patterns
            lower.contains(Regex("nataka kwenda|nataka safari|ninaenda|nipe rider|kuna rider|tafuta|pata|nataka kwenda|nitoke|nitoke")) -> {
                params["action"] = "find_match"
                params["role"] = "passenger"
            }
            // offer_ride patterns
            lower.contains(Regex("ninatoa|natoa|nina nafasi|nafasi [0-9]|natoa safari|nitoke na|ninaenda na")) -> {
                params["action"] = "offer_ride"
                params["role"] = "rider"
            }
            // accept patterns
            lower.contains(Regex("nipokee|nichukue|napokea|sawa|ndiyo|nataka hii|chukua hii|accept|pokea")) -> {
                params["action"] = "accept"
            }
            // complete patterns
            lower.contains(Regex("nimefika|safari imeisha|tumefika|imekamilika|nimemaliza|nimelipa|complete|finish")) -> {
                params["action"] = "complete"
            }
            // history patterns
            lower.contains(Regex("safari zangu|history|ripoti|mapato|nilipata|nilikula|nilitumia|earning|savings")) -> {
                params["action"] = "history"
            }
        }

        // ── Extract locations ──
        // "kwenda X" or "to X"
        val toPattern = Regex("""(?:kwenda|enda|to|destination)\s+(\w[\w\s]*?)(?:\s+(?:kesho|leo|saa|asubuhi|mchana|jioni|dakika|\d|$))""", RegexOption.IGNORE_CASE)
        toPattern.find(text)?.let {
            params["to_location"] = it.groupValues[1].trim().replaceFirstChar { c -> c.uppercase() }
        }
        // Also try simple "kwenda X" at end
        if (!params.containsKey("to_location")) {
            val simpleTo = Regex("""kwenda\s+(\w+)""", RegexOption.IGNORE_CASE)
            simpleTo.find(text)?.let {
                params["to_location"] = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
            }
        }

        // "from X" or "nitoke X" or "natoa X"
        val fromPattern = Regex("""(?:from|nitoke|natoa|toka|tokea)\s+(\w[\w\s]*?)(?:\s+(?:kwenda|enda|saa|asubuhi|mchana|jioni|\d|$))""", RegexOption.IGNORE_CASE)
        fromPattern.find(text)?.let {
            params["from_location"] = it.groupValues[1].trim().replaceFirstChar { c -> c.uppercase() }
        }
        // "X → Y" or "X hadi Y"
        val routePattern = Regex("""(\w+)\s*(?:→|->|hadi|kwenda)\s*(\w+)""")
        routePattern.find(text)?.let {
            params["from_location"] = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
            params["to_location"] = it.groupValues[2].replaceFirstChar { c -> c.uppercase() }
        }

        // ── Extract time ──
        when {
            lower.contains("kesho") -> params["departure_window"] = "tomorrow"
            lower.contains("leo") -> params["departure_window"] = "today"
            lower.contains(Regex("dakika\\s+(\\d+)")) -> {
                val mins = Regex("dakika\\s+(\\d+)").find(text)?.groupValues?.get(1) ?: "30"
                params["departure_window"] = "${mins}m"
            }
            lower.contains(Regex("saa\\s+(\\d+)")) -> {
                // "saa kumi" or "saa 10" — extract hour
                val hourWord = Regex("saa\\s+(\\w+)").find(text)?.groupValues?.get(1)
                val hour = hourWord?.let { swahiliNumberToDigit(it) } ?: hourWord?.toIntOrNull()
                if (hour != null) {
                    params["departure_time"] = String.format("%02d:00", hour)
                }
            }
        }

        // ── Extract seats ──
        val seatsPattern = Regex("""nafasi\s+(\w+)""")
        seatsPattern.find(text)?.let {
            val num = swahiliNumberToDigit(it.groupValues[1]) ?: it.groupValues[1].toIntOrNull()
            if (num != null) params["seats"] = num.toString()
        }

        // ── Extract fare ──
        val farePattern = Regex("""(?:bei|fare|nimelipa|lipa|pesa)\s+(?:ni\s+)?(?:ksh\s*)?(\d+)""")
        farePattern.find(text)?.let {
            params["fare_total"] = it.groupValues[1]
        }
        // Also try "mia tano" etc.
        val amount = extractSwahiliAmount(lower)
        if (amount != null && !params.containsKey("fare_total")) {
            params["fare_total"] = amount.toInt().toString()
        }

        // ── Extract rating ──
        val ratingPattern = Regex("""(?:rating|alama|point)\s+(\d)""")
        ratingPattern.find(text)?.let {
            params["rating"] = it.groupValues[1]
        }

        return params
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private suspend fun ensureUserExists(userId: String, userType: String, name: String) {
        val existing = rideUserDao.getById(userId)
        if (existing == null) {
            rideUserDao.insert(
                RideUserEntity(
                    userId = userId,
                    userType = userType,
                    name = name
                )
            )
        }
    }

    private fun resolveDepartureTime(input: String?): String {
        if (input == null) {
            // Default: 1 hour from now
            val cal = Calendar.getInstance()
            cal.add(Calendar.HOUR_OF_DAY, 1)
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).format(cal.time)
        }

        // Try ISO format
        if (input.contains("T")) return input

        // Try "HH:mm" — today at that time
        val parts = input.split(":")
        if (parts.size == 2) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 8)
            cal.set(Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
            cal.set(Calendar.SECOND, 0)
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).format(cal.time)
        }

        // Try parsing as full datetime
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).parse(input)?.let {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()).format(it)
            } ?: input
        } catch (e: Exception) {
            input
        }
    }

    private fun formatDepartureDisplay(isoTime: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
            val date = sdf.parse(isoTime) ?: return isoTime
            val now = Date()
            val diffMs = date.time - now.time
            val diffHours = TimeUnit.MILLISECONDS.toHours(diffMs)
            val diffMins = TimeUnit.MILLISECONDS.toMinutes(diffMs) % 60

            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            val dayStr = when {
                diffMs < 0 -> "imepita"
                diffHours < 1 -> "dakika $diffMins"
                diffHours < 24 -> "leo $timeStr"
                diffHours < 48 -> "kesho $timeStr"
                else -> SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(date)
            }
            dayStr
        } catch (e: Exception) {
            isoTime
        }
    }

    private fun swahiliNumberToDigit(word: String): Int? {
        return when (word.lowercase()) {
            "moja" -> 1; "mbili" -> 2; "tatu" -> 3; "nne" -> 4; "tano" -> 5
            "sita" -> 6; "saba" -> 7; "nane" -> 8; "tisa" -> 9; "kumi" -> 10
            "kumi na moja" -> 11; "kumi na mbili" -> 12
            else -> word.toIntOrNull()
        }
    }

    private fun extractSwahiliAmount(text: String): Double? {
        val swahiliOnes = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9
        )

        var total = 0.0

        Regex("elfu\\s*(\\d+)").find(text)?.let {
            total += it.groupValues[1].toDouble() * 1000
        }
        for ((word, value) in swahiliOnes) {
            if (text.contains("elfu $word")) total += value * 1000
            if (text.contains("$word elfu")) total += value * 1000
        }

        Regex("mia\\s*(\\d+)").find(text)?.let {
            total += it.groupValues[1].toDouble() * 100
        }
        for ((word, value) in swahiliOnes) {
            if (text.contains("mia $word")) total += value * 100
        }

        if (total == 0.0) {
            val plainNumber = Regex("""(\d+\.?\d*)""").find(text)
            return plainNumber?.groupValues?.get(1)?.toDoubleOrNull()
        }

        return if (total > 0) total else null
    }
}

// ──────────────────────────────────────────────
// Utility function
// ──────────────────────────────────────────────

fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
