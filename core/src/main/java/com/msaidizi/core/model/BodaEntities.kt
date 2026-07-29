package com.msaidizi.core.model

import androidx.room.*

// ──────────────────────────────────────────────
// Boda Boda Income Entity
// ──────────────────────────────────────────────

/**
 * Tracks a boda boda rider's daily income entries.
 * Each fare/payment the rider receives during the day.
 */
@Entity(
    tableName = "boda_income",
    indices = [Index(value = ["date"])]
)
data class BodaIncomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,                    // fare amount in KES
    val route: String = "",                // "Town → Stage ya Mawe"
    val tripType: String = "fare",         // fare | delivery | charter | other
    val paymentMethod: String = "cash",    // cash | mpesa
    val passengerCount: Int = 1,
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Boda Boda Expense Entity
// ──────────────────────────────────────────────

/**
 * Tracks ALL boda boda expenses including hidden costs.
 * Categories: fuel, hire_fee, police_bribe, maintenance,
 * sacco, airtime, food, other
 */
@Entity(
    tableName = "boda_expenses",
    indices = [Index(value = ["date"])]
)
data class BodaExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val category: String,                  // fuel | hire_fee | police_bribe | maintenance | sacco | airtime | food | other
    val description: String = "",
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Fuel Purchase Entity
// ──────────────────────────────────────────────

@Entity(
    tableName = "fuel_purchases",
    indices = [Index(value = ["date"])]
)
data class FuelPurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val liters: Double,                    // liters purchased
    val costPerLiter: Double,              // KES per liter
    val totalCost: Double,                 // total KES spent
    val stationName: String = "",          // petrol station name
    val odometer: Double? = null,          // optional odometer reading (km)
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Trip Kilometers Entity
// ──────────────────────────────────────────────

@Entity(
    tableName = "trip_kilometers",
    indices = [Index(value = ["date"])]
)
data class TripKilometersEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kilometers: Double,                // km ridden
    val route: String = "",                // route description
    val tripType: String = "regular",      // regular | delivery | charter
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)

// ──────────────────────────────────────────────
// Fare Record Entity
// ──────────────────────────────────────────────

@Entity(
    tableName = "fare_records",
    indices = [
        Index(value = ["route"]),
        Index(value = ["date"]),
        Index(value = ["hourOfDay"])
    ]
)
data class FareRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fare: Double,                      // actual fare charged in KES
    val route: String,                     // normalized route name
    val fromLocation: String,              // origin
    val toLocation: String,                // destination
    val distanceKm: Double? = null,        // estimated distance
    val hourOfDay: Int = 0,                // 0-23
    val dayOfWeek: Int = 0,                // 1=Mon, 7=Sun
    val weather: String = "clear",         // clear | rain | hot | cold
    val passengerCount: Int = 1,
    val date: String = "",                 // YYYY-MM-DD
    val timestamp: Long = System.currentTimeMillis(),
    val needsSync: Boolean = true
)
