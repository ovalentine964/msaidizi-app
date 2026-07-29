package com.msaidizi.core.database

import androidx.room.*
import com.msaidizi.core.model.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Ride Share DAOs
// ──────────────────────────────────────────────

@Dao
interface RideUserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: RideUserEntity): String

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
