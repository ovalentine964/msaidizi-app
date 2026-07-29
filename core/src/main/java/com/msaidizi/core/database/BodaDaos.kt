package com.msaidizi.core.database

import androidx.room.*
import com.msaidizi.core.model.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Boda Income DAO
// ──────────────────────────────────────────────

@Dao
interface BodaIncomeDao {
    @Insert
    suspend fun insert(income: BodaIncomeEntity): Long

    @Query("SELECT * FROM boda_income WHERE date = :date ORDER BY timestamp DESC")
    fun getByDate(date: String): Flow<List<BodaIncomeEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM boda_income WHERE date = :date")
    suspend fun getTotalForDate(date: String): Double

    @Query("SELECT COALESCE(SUM(amount), 0) FROM boda_income WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalBetween(startDate: String, endDate: String): Double

    @Query("SELECT COUNT(*) FROM boda_income WHERE date = :date")
    suspend fun getTripCountForDate(date: String): Int

    @Query("SELECT route, COUNT(*) as count, AVG(amount) as avgFare FROM boda_income WHERE date BETWEEN :startDate AND :endDate GROUP BY route ORDER BY count DESC LIMIT :limit")
    suspend fun getTopRoutes(startDate: String, endDate: String, limit: Int = 10): List<RouteSummary>

    @Query("SELECT * FROM boda_income ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<BodaIncomeEntity>>

    @Delete
    suspend fun delete(income: BodaIncomeEntity)
}

data class RouteSummary(
    val route: String,
    val count: Int,
    val avgFare: Double
)

// ──────────────────────────────────────────────
// Boda Expense DAO
// ──────────────────────────────────────────────

@Dao
interface BodaExpenseDao {
    @Insert
    suspend fun insert(expense: BodaExpenseEntity): Long

    @Query("SELECT * FROM boda_expenses WHERE date = :date ORDER BY timestamp DESC")
    fun getByDate(date: String): Flow<List<BodaExpenseEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date = :date")
    suspend fun getTotalForDate(date: String): Double

    @Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date = :date AND category = :category")
    suspend fun getTotalForDateByCategory(date: String, category: String): Double

    @Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalBetween(startDate: String, endDate: String): Double

    @Query("SELECT category, COALESCE(SUM(amount), 0) as total FROM boda_expenses WHERE date = :date GROUP BY category ORDER BY total DESC")
    suspend fun getByCategoryForDate(date: String): List<CategoryTotal>

    @Query("SELECT category, COALESCE(SUM(amount), 0) as total FROM boda_expenses WHERE date BETWEEN :startDate AND :endDate GROUP BY category ORDER BY total DESC")
    suspend fun getByCategoryBetween(startDate: String, endDate: String): List<CategoryTotal>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM boda_expenses WHERE date BETWEEN :startDate AND :endDate AND category = 'police_bribe'")
    suspend fun getBribesBetween(startDate: String, endDate: String): Double

    @Query("SELECT * FROM boda_expenses ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<BodaExpenseEntity>>

    @Delete
    suspend fun delete(expense: BodaExpenseEntity)
}

data class CategoryTotal(
    val category: String,
    val total: Double
)

// ──────────────────────────────────────────────
// Fuel Purchase DAO
// ──────────────────────────────────────────────

@Dao
interface FuelPurchaseDao {
    @Insert
    suspend fun insert(purchase: FuelPurchaseEntity): Long

    @Query("SELECT * FROM fuel_purchases WHERE date = :date ORDER BY timestamp DESC")
    fun getByDate(date: String): Flow<List<FuelPurchaseEntity>>

    @Query("SELECT COALESCE(SUM(totalCost), 0) FROM fuel_purchases WHERE date = :date")
    suspend fun getTotalCostForDate(date: String): Double

    @Query("SELECT COALESCE(SUM(liters), 0) FROM fuel_purchases WHERE date = :date")
    suspend fun getTotalLitersForDate(date: String): Double

    @Query("SELECT COALESCE(SUM(totalCost), 0) FROM fuel_purchases WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalCostBetween(startDate: String, endDate: String): Double

    @Query("SELECT COALESCE(SUM(liters), 0) FROM fuel_purchases WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalLitersBetween(startDate: String, endDate: String): Double

    @Query("SELECT AVG(costPerLiter) FROM fuel_purchases WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getAvgCostPerLiterBetween(startDate: String, endDate: String): Double?

    @Query("SELECT * FROM fuel_purchases ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<FuelPurchaseEntity>>

    @Query("SELECT stationName, COUNT(*) as visits, AVG(costPerLiter) as avgPrice, SUM(totalCost) as totalSpent FROM fuel_purchases WHERE date BETWEEN :startDate AND :endDate GROUP BY stationName ORDER BY visits DESC")
    suspend fun getStationComparison(startDate: String, endDate: String): List<StationSummary>

    @Delete
    suspend fun delete(purchase: FuelPurchaseEntity)
}

data class StationSummary(
    val stationName: String,
    val visits: Int,
    val avgPrice: Double,
    val totalSpent: Double
)

// ──────────────────────────────────────────────
// Trip Kilometers DAO
// ──────────────────────────────────────────────

@Dao
interface TripKilometersDao {
    @Insert
    suspend fun insert(trip: TripKilometersEntity): Long

    @Query("SELECT * FROM trip_kilometers WHERE date = :date ORDER BY timestamp DESC")
    fun getByDate(date: String): Flow<List<TripKilometersEntity>>

    @Query("SELECT COALESCE(SUM(kilometers), 0) FROM trip_kilometers WHERE date = :date")
    suspend fun getTotalKmForDate(date: String): Double

    @Query("SELECT COALESCE(SUM(kilometers), 0) FROM trip_kilometers WHERE date BETWEEN :startDate AND :endDate")
    suspend fun getTotalKmBetween(startDate: String, endDate: String): Double

    @Query("SELECT * FROM trip_kilometers ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<TripKilometersEntity>>

    @Delete
    suspend fun delete(trip: TripKilometersEntity)
}

// ──────────────────────────────────────────────
// Fare Record DAO
// ──────────────────────────────────────────────

@Dao
interface FareRecordDao {
    @Insert
    suspend fun insert(record: FareRecordEntity): Long

    @Query("SELECT * FROM fare_records WHERE route = :route ORDER BY timestamp DESC LIMIT :limit")
    fun getByRoute(route: String, limit: Int = 50): Flow<List<FareRecordEntity>>

    @Query("SELECT route, COUNT(*) as tripCount, AVG(fare) as avgFare, MIN(fare) as minFare, MAX(fare) as maxFare FROM fare_records WHERE date BETWEEN :startDate AND :endDate GROUP BY route ORDER BY tripCount DESC")
    suspend fun getRouteSummary(startDate: String, endDate: String): List<RouteFareSummary>

    @Query("SELECT route, AVG(fare) as avgFare, COUNT(*) as count FROM fare_records WHERE hourOfDay BETWEEN :startHour AND :endHour AND date BETWEEN :startDate AND :endDate GROUP BY route ORDER BY avgFare DESC")
    suspend fun getRoutesByTimeOfDay(startHour: Int, endHour: Int, startDate: String, endDate: String): List<TimeRouteSummary>

    @Query("SELECT weather, route, AVG(fare) as avgFare, COUNT(*) as count FROM fare_records WHERE route = :route GROUP BY weather ORDER BY avgFare DESC")
    suspend fun getWeatherFareComparison(route: String): List<WeatherFareSummary>

    @Query("SELECT weather, AVG(fare) as avgFare, COUNT(*) as count FROM fare_records WHERE date BETWEEN :startDate AND :endDate GROUP BY weather ORDER BY avgFare DESC")
    suspend fun getOverallWeatherComparison(startDate: String, endDate: String): List<WeatherFareSummary>

    @Query("SELECT hourOfDay, AVG(fare) as avgFare, COUNT(*) as count FROM fare_records WHERE date BETWEEN :startDate AND :endDate GROUP BY hourOfDay ORDER BY hourOfDay")
    suspend fun getHourlyFarePattern(startDate: String, endDate: String): List<HourlyFarePattern>

    @Query("SELECT dayOfWeek, AVG(fare) as avgFare, COUNT(*) as count FROM fare_records WHERE date BETWEEN :startDate AND :endDate GROUP BY dayOfWeek ORDER BY dayOfWeek")
    suspend fun getDayOfWeekPattern(startDate: String, endDate: String): List<DailyFarePattern>

    @Query("SELECT * FROM fare_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<FareRecordEntity>>

    @Query("SELECT DISTINCT route FROM fare_records ORDER BY route ASC")
    suspend fun getAllRoutes(): List<String>

    @Delete
    suspend fun delete(record: FareRecordEntity)
}

data class RouteFareSummary(
    val route: String,
    val tripCount: Int,
    val avgFare: Double,
    val minFare: Double,
    val maxFare: Double
)

data class TimeRouteSummary(
    val route: String,
    val avgFare: Double,
    val count: Int
)

data class WeatherFareSummary(
    val weather: String,
    val route: String? = null,
    val avgFare: Double,
    val count: Int
)

data class HourlyFarePattern(
    val hourOfDay: Int,
    val avgFare: Double,
    val count: Int
)

data class DailyFarePattern(
    val dayOfWeek: Int,
    val avgFare: Double,
    val count: Int
)
