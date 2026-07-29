package com.msaidizi.core.model

import androidx.room.*
import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────────
// Ride Share Entities
// ──────────────────────────────────────────────

fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

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
