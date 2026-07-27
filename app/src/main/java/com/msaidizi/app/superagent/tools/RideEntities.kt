package com.msaidizi.app.superagent.tools

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.*

@Entity(tableName = "ride_users")
data class RideUserEntity(
    @PrimaryKey val userId: String,          // phone or device ID
    val userType: String,                     // "rider" | "passenger"
    val name: String,
    val phone: String? = null,
    val stageName: String? = null,            // home stage
    val trustScore: Double = 5.0,             // 1.0–5.0
    val totalTrips: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
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
    val createdAt: String = "",
    val updatedAt: String = "",
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
    val createdAt: String = "",
    val updatedAt: String = "",
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
    val completedAt: String = "",
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
    val createdAt: String = ""
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
    suspend fun updateTrust(userId: String, score: Double, now: String)

    @Query("UPDATE ride_users SET totalTrips = totalTrips + 1, updatedAt = :now WHERE userId = :userId")
    suspend fun incrementTrips(userId: String, now: String)

    @Query("SELECT * FROM ride_users WHERE userId IN (:userIds)")
    suspend fun getByIds(userIds: List<String>): List<RideUserEntity>

    @Query("UPDATE ride_users SET stageName = :stage, updatedAt = :now, needsSync = 1 WHERE userId = :userId")
    suspend fun updateStage(userId: String, stage: String, now: String)
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
    suspend fun bookSeats(offerId: Long, seats: Int, now: String)

    @Query("UPDATE ride_offers SET status = :status, updatedAt = :now WHERE offerId = :offerId")
    suspend fun updateStatus(offerId: Long, status: String, now: String)

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
    suspend fun updateStatus(requestId: Long, status: String, now: String)

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
