package com.msaidizi.agent.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.msaidizi.core.database.CustomerDao
import com.msaidizi.core.database.CustomerProfileDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.core.model.CustomerProfileEntity
import com.msaidizi.agent.flywheel.FlywheelEngine
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CustomerMatcher — Match service businesses to their customers.
 *
 * For salon, barbershop, cyber cafe, laundry, and similar businesses
 * that serve repeat walk-in customers.
 *
 * Features:
 *  1. Track customer visit patterns (frequency, preferred services, spend)
 *  2. Predict customer visits: "Mteja wako Amina anatarajia kufika Jumamosi"
 *  3. Customer preference tracking (favorite barber, service, time)
 *  4. Loyalty rewards tracking
 *  5. Visit reminders and churn alerts
 *  6. Customer search and lookup by name or phone
 *
 * Integrates with:
 *  - CustomerInsights (profile data, segments, churn detection)
 *  - ServiceMenu (service catalog and pricing)
 *  - BookingScheduler (appointment data)
 *  - GamificationEngine (loyalty points)
 *
 * 7 Actions: register_visit, predict_visits, preferences, loyalty,
 *            search, reminder, overview
 *
 * Voice-first, bilingual (Kiswahili + English).
 */
@Singleton
class CustomerMatcher @Inject constructor(
    private val context: Context,
    private val profileDao: CustomerProfileDao,
    private val saleDao: SaleDao,
    private val customerDao: CustomerDao,
    private val gamificationEngine: GamificationEngine,
    private val flywheelEngine: FlywheelEngine,
    private val gson: Gson
) : Tool {

    override val name = "customer_matcher"
    override val description = "Track service business customers, predict visits, manage loyalty. Voice: 'Mteja wangu Amina anakuja lini?'"

    override val argsSchema = argSchema {
        enum(
            "action", "Customer matching action",
            listOf(
                "register_visit",   // Record a customer visit (auto from QuickSale or manual)
                "predict_visits",   // Predict who's coming when
                "preferences",      // View/update customer preferences
                "loyalty",          // Loyalty rewards overview
                "search",           // Find customer by name or phone
                "reminder",         // Set/check visit reminders
                "overview"          // Business customer overview
            ),
            required = false
        )
        string("worker_id", "Business/worker ID (e.g. salon_001)", required = true)
        string("customer_key", "Customer name or phone number", required = false)
        string("service", "Service provided (e.g. braids, haircut, print)", required = false)
        string("amount", "Amount charged in KES", required = false)
        string("staff", "Staff member who served (e.g. barber name)", required = false)
        string("notes", "Notes about the visit (preferences, feedback)", required = false)
        string("preference_key", "Preference to set/get (e.g. favorite_service, preferred_time)", required = false)
        string("preference_value", "Value for the preference", required = false)
        integer("days_ahead", "Days ahead to predict (default: 7)", required = false)
        integer("limit", "Max results (default: 20)", required = false)
        string("voice_input", "Raw Swahili/English voice text to parse", required = false)
    }

    private val dbHelper: CustomerMatcherDbHelper by lazy { CustomerMatcherDbHelper(context) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayFormat = SimpleDateFormat("EEEE", Locale("sw"))

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "overview"
        val workerId = params["worker_id"]

        if (workerId.isNullOrBlank()) {
            return ToolResult.error(name, "Worker ID required. Example: worker_id=salon_001", "MISSING_WORKER_ID")
        }

        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!, workerId, params)
        }

        return when (action.lowercase()) {
            "register_visit" -> registerVisit(params)
            "predict_visits" -> predictVisits(params)
            "preferences" -> handlePreferences(params)
            "loyalty" -> loyaltyOverview(params)
            "search" -> searchCustomer(params)
            "reminder" -> handleReminder(params)
            "overview" -> businessOverview(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // REGISTER VISIT — Record a customer visit
    // ──────────────────────────────────────────────

    private suspend fun registerVisit(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]!!
        val customerKey = params["customer_key"]
            ?: return ToolResult.error(name, "Customer name or phone required. Try: customer_key=Amina", "MISSING_CUSTOMER_KEY")
        val service = params["service"] ?: "Service"
        val amount = params["amount"]?.toDoubleOrNull() ?: 0.0
        val staff = params["staff"]
        val notes = params["notes"]

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()
            val today = dateFormat.format(now)
            val dayOfWeek = dayFormat.format(now)

            // Insert visit record
            db.execSQL(
                """INSERT INTO customer_visits
                   (worker_id, customer_key, service, amount, staff, notes, visit_date, day_of_week, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(workerId, customerKey, service, amount, staff, notes, today, dayOfWeek, now)
            )

            // Update or create customer profile in local DB
            updateCustomerProfile(db, workerId, customerKey, service, amount, now)

            // Sync with CustomerInsights if available
            try {
                val existingProfile = profileDao.getByKey(workerId, customerKey)
                if (existingProfile != null) {
                    profileDao.update(existingProfile.copy(
                        totalVisits = existingProfile.totalVisits + 1,
                        totalSpend = existingProfile.totalSpend + amount,
                        lastVisit = today,
                        updatedAt = now
                    ))
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not sync with CustomerInsights")
            }

            // Gamification: points for recording customer visit
            gamificationEngine.addPoints(mapOf("action_type" to "record_sale"))

            // Check if this is a special visit milestone
            val visitCount = getCustomerVisitCount(db, workerId, customerKey)
            val milestoneMsg = when (visitCount) {
                10 -> "\n🎉 Ziara ya 10! Mteja wa kawaida!"
                25 -> "\n🌟 Ziara ya 25! Mteja wa VIP!"
                50 -> "\n👑 Ziara ya 50! Mteja wa dhahabu!"
                else -> ""
            }

            return ToolResult.success(
                name,
                data = mapOf(
                    "customer_key" to customerKey,
                    "service" to service,
                    "amount" to amount,
                    "visit_count" to visitCount
                ),
                message = "✅ Ziara imerekodwa: $customerKey — $service (KES ${"%,.0f".format(amount)})$milestoneMsg"
            )
        } catch (e: Exception) {
            Timber.e(e, "Register visit failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // PREDICT VISITS — Who's coming when
    // ──────────────────────────────────────────────

    private suspend fun predictVisits(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]!!
        val daysAhead = params["days_ahead"]?.toIntOrNull() ?: 7

        val db = dbHelper.readableDatabase
        try {
            // Get all customers with visit history
            val customers = getCustomersWithHistory(db, workerId)

            if (customers.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📋 Hakuna wateja bado.\n\nAnza kurekodi ziara za wateja: register_visit"
                )
            }

            val now = System.currentTimeMillis()
            val predictions = mutableListOf<PredictedVisit>()

            for (customer in customers) {
                val customerKey = customer["customer_key"] as? String ?: continue
                val visitDates = getCustomerVisitDates(db, workerId, customerKey)
                if (visitDates.size < 2) continue // Need at least 2 visits to predict

                val avgDaysBetween = calculateAverageInterval(visitDates)
                val lastVisit = visitDates.maxOrNull() ?: continue
                val daysSinceLastVisit = TimeUnit.MILLISECONDS.toDays(now - lastVisit).toInt()
                val expectedDaysFromNow = avgDaysBetween - daysSinceLastVisit

                if (expectedDaysFromNow in 0..daysAhead) {
                    val predictedDate = dateFormat.format(now + TimeUnit.DAYS.toMillis(expectedDaysFromNow.toLong()))
                    val preferredService = customer["preferred_service"]?.toString()
                    val confidence = calculateConfidence(visitDates, avgDaysBetween)

                    predictions.add(PredictedVisit(
                        customerKey = customerKey,
                        predictedDate = predictedDate,
                        daysFromNow = expectedDaysFromNow,
                        preferredService = preferredService,
                        confidence = confidence,
                        avgSpend = (customer["avg_spend"] as? Number)?.toDouble() ?: 0.0
                    ))
                }
            }

            if (predictions.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📋 Hakuna wateja wanaotarajiwa siku $daysAhead zijazo.\n\n" +
                            "Wateja wanahitaji ziara 2+ ili kubashiri."
                )
            }

            // Sort by predicted date
            predictions.sortBy { it.daysFromNow }

            // ── Flywheel: Adjust confidence with credit patterns ──
            val creditPatterns = try {
                flywheelEngine.getCreditPatterns()
            } catch (e: Exception) {
                emptyList()
            }
            val paidCount = creditPatterns.count { it.data["paid"]?.toBoolean() == true }
            val totalCredit = creditPatterns.size
            val paymentReliability = if (totalCredit > 0) paidCount.toFloat() / totalCredit else 0.5f

            // Boost/penalize predictions based on payment reliability
            if (totalCredit > 0) {
                predictions.forEachIndexed { i, pred ->
                    val adjustedConfidence = (pred.confidence * (0.8f + 0.4f * paymentReliability)).coerceIn(0.3, 0.95)
                    predictions[i] = pred.copy(confidence = adjustedConfidence)
                }
                Timber.d("CustomerMatcher: Adjusted predictions with flywheel payment reliability=%.2f", paymentReliability)
            }

            val output = buildString {
                appendLine("🔮 *Wateja Wanaotarajia Kufika — Siku $daysAhead Zijazo*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                predictions.forEach { pred ->
                    val dayLabel = when (pred.daysFromNow) {
                        0 -> "Leo"
                        1 -> "Kesho"
                        2 -> "Kesho kutwa"
                        else -> dateFormat.format(
                            System.currentTimeMillis() + TimeUnit.DAYS.toDays(pred.daysFromNow.toLong())
                        )
                    }
                    val confEmoji = when {
                        pred.confidence >= 0.8 -> "🟢"
                        pred.confidence >= 0.5 -> "🟡"
                        else -> "🔴"
                    }
                    val confLabel = when {
                        pred.confidence >= 0.8 -> "Uhakika mkubwa"
                        pred.confidence >= 0.5 -> "Uhakika wa kati"
                        else -> "Uhakika mdogo"
                    }

                    appendLine("$confEmoji *${pred.customerKey}*")
                    appendLine("   📅 $dayLabel (${pred.daysFromNow} siku)")
                    if (pred.preferredService != null) appendLine("   🔧 Anaweza kuomba: ${pred.preferredService}")
                    appendLine("   💰 Wastani: KES ${"%,.0f".format(pred.avgSpend)}")
                    appendLine("   📊 $confLabel (${(pred.confidence * 100).toInt()}%)")
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💡 *Mapendekezo:*")
                appendLine("• Tuma ujumbe wa kukumbusha siku moja kabla")
                appendLine("• Hakikisha bidhaa za huduma wanazopenda zipo")
                appendLine("• Weka nafasi ya wateja wa VIP kwanza")
            }

            return ToolResult.success(
                name,
                data = predictions.map {
                    mapOf(
                        "customer" to it.customerKey,
                        "predicted_date" to it.predictedDate,
                        "days_from_now" to it.daysFromNow,
                        "confidence" to it.confidence,
                        "preferred_service" to it.preferredService,
                        "avg_spend" to it.avgSpend
                    )
                },
                message = output
            )
        } catch (e: Exception) {
            Timber.e(e, "Predict visits failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // PREFERENCES — View/set customer preferences
    // ──────────────────────────────────────────────

    private suspend fun handlePreferences(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]!!
        val customerKey = params["customer_key"]
        val prefKey = params["preference_key"]
        val prefValue = params["preference_value"]

        val db = dbHelper.writableDatabase

        // Setting a preference
        if (customerKey != null && prefKey != null && prefValue != null) {
            db.execSQL(
                """INSERT OR REPLACE INTO customer_preferences
                   (worker_id, customer_key, pref_key, pref_value, updated_at)
                   VALUES (?, ?, ?, ?, ?)""",
                arrayOf(workerId, customerKey, prefKey, prefValue, System.currentTimeMillis())
            )
            return ToolResult.success(
                name,
                message = "✅ Mapendeleo yamesasishwa: $customerKey → $prefKey = $prefValue"
            )
        }

        // Getting preferences
        if (customerKey != null) {
            val prefs = getCustomerPreferences(db, workerId, customerKey)
            if (prefs.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📋 Hakuna mapendeleo ya $customerKey bado.\n\nWeka mapendeleo: preferences customer_key=$customerKey preference_key=favorite_service preference_value=braids"
                )
            }

            val output = buildString {
                appendLine("❤️ *Mapendeleo ya $customerKey*")
                prefs.forEach { (key, value) ->
                    appendLine("   • ${preferenceToSwahili(key)}: $value")
                }
            }
            return ToolResult.success(name, data = prefs, message = output)
        }

        // No customer specified — show all customers' preferences summary
        val allPrefs = getAllPreferencesSummary(db, workerId)
        if (allPrefs.isEmpty()) {
            return ToolResult.success(name, message = "📋 Hakuna mapendeleo yaliyorekodwa bado.")
        }

        val output = buildString {
            appendLine("❤️ *Mapendeleo ya Wateja*")
            allPrefs.forEach { (customer, prefs) ->
                appendLine("\n*$customer*")
                prefs.forEach { (key, value) ->
                    appendLine("   • ${preferenceToSwahili(key)}: $value")
                }
            }
        }
        return ToolResult.success(name, message = output)
    }

    // ──────────────────────────────────────────────
    // LOYALTY — Customer loyalty overview
    // ──────────────────────────────────────────────

    private suspend fun loyaltyOverview(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]!!
        val limit = params["limit"]?.toIntOrNull() ?: 10

        val db = dbHelper.readableDatabase
        try {
            val customers = getCustomersWithHistory(db, workerId)
            if (customers.isEmpty()) {
                return ToolResult.success(name, message = "📋 Hakuna wateja bado.")
            }

            // Sort by visit count (most loyal first)
            val sorted = customers.sortedByDescending { (it["total_visits"] as? Number)?.toInt() ?: 0 }.take(limit)

            val output = buildString {
                appendLine("🏆 *Wateja Waaminifu — Top $limit*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                sorted.forEachIndexed { i, cust ->
                    val visitCount = (cust["total_visits"] as? Number)?.toInt() ?: 0
                    val totalSpend = (cust["total_spend"] as? Number)?.toDouble() ?: 0.0
                    val lastVisit = cust["last_visit"]?.toString() ?: "N/A"
                    val tier = when {
                        visitCount >= 50 -> "👑 Dhahabu"
                        visitCount >= 25 -> "🌟 VIP"
                        visitCount >= 10 -> "🟢 Wa Kawaida"
                        else -> "🆕 Mpya"
                    }

                    appendLine("${i + 1}. ${cust["customer_key"]} — $tier")
                    appendLine("   📅 Ziara: $visitCount | 💰 KES ${"%,.0f".format(totalSpend)}")
                    appendLine("   📆 Ziara ya mwisho: $lastVisit")
                    appendLine()
                }

                // Loyalty stats
                val totalCustomers = customers.size
                val repeatCustomers = customers.count { ((it["total_visits"] as? Number)?.toInt() ?: 0) >= 2 }
                val vipCustomers = customers.count { ((it["total_visits"] as? Number)?.toInt() ?: 0) >= 25 }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📊 *Muhtasari:*")
                appendLine("   👥 Wateja jumla: $totalCustomers")
                appendLine("   🔁 Warudi: $repeatCustomers (${if (totalCustomers > 0) (repeatCustomers * 100 / totalCustomers) else 0}%)")
                appendLine("   🌟 VIP: $vipCustomers")
            }

            return ToolResult.success(name, data = sorted, message = output)
        } catch (e: Exception) {
            Timber.e(e, "Loyalty overview failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SEARCH — Find customer by name or phone
    // ──────────────────────────────────────────────

    private fun searchCustomer(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]!!
        val customerKey = params["customer_key"]
            ?: return ToolResult.error(name, "Customer name or phone required. Try: customer_key=Amina", "MISSING_CUSTOMER_KEY")

        val db = dbHelper.readableDatabase
        try {
            val profile = getCustomerProfile(db, workerId, customerKey)
                ?: return ToolResult.success(name, message = "🔍 Mteja '$customerKey' hajapatikana.\n\nRekodi ziara yake kwanza: register_visit")

            val prefs = getCustomerPreferences(db, workerId, customerKey)

            val visitCount = (profile["total_visits"] as? Number)?.toInt() ?: 0
            val totalSpend = (profile["total_spend"] as? Number)?.toDouble() ?: 0.0
            val avgSpend = if (visitCount > 0) totalSpend / visitCount else 0.0
            val lastVisit = profile["last_visit"]?.toString() ?: "N/A"
            val preferredService = profile["preferred_service"]?.toString()

            val tier = when {
                visitCount >= 50 -> "👑 Dhahabu"
                visitCount >= 25 -> "🌟 VIP"
                visitCount >= 10 -> "🟢 Wa Kawaida"
                else -> "🆕 Mpya"
            }

            val output = buildString {
                appendLine("👤 *Wasifu wa Mteja: $customerKey*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📊 *$tier*")
                appendLine("📅 Ziara jumla: $visitCount")
                appendLine("💰 Jumla ya matumizi: KES ${"%,.0f".format(totalSpend)}")
                appendLine("💰 Wastani kwa ziara: KES ${"%,.0f".format(avgSpend)}")
                appendLine("📆 Ziara ya mwisho: $lastVisit")
                if (preferredService != null) appendLine("🔧 Huduma anayoipenda: $preferredService")

                if (prefs.isNotEmpty()) {
                    appendLine()
                    appendLine("❤️ *Mapendeleo:*")
                    prefs.forEach { (key, value) ->
                        appendLine("   • ${preferenceToSwahili(key)}: $value")
                    }
                }

                // Recent visits
                val recentVisits = getCustomerRecentVisits(db, workerId, customerKey, 5)
                if (recentVisits.isNotEmpty()) {
                    appendLine()
                    appendLine("📋 *Ziara za Hivi Karibuni:*")
                    recentVisits.forEach { visit ->
                        appendLine("   • ${visit["visit_date"]} — ${visit["service"]} (KES ${"%,.0f".format((visit["amount"] as? Number)?.toDouble() ?: 0.0)})")
                    }
                }
            }

            return ToolResult.success(name, data = profile, message = output)
        } catch (e: Exception) {
            Timber.e(e, "Search customer failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // REMINDER — Set/check visit reminders
    // ──────────────────────────────────────────────

    private fun handleReminder(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]!!
        val customerKey = params["customer_key"]
        val daysAhead = params["days_ahead"]?.toIntOrNull() ?: 1

        val db = dbHelper.writableDatabase

        if (customerKey != null) {
            // Set a reminder for a specific customer
            val remindDate = dateFormat.format(
                System.currentTimeMillis() + TimeUnit.DAYS.toMillis(daysAhead.toLong())
            )
            db.execSQL(
                """INSERT OR REPLACE INTO visit_reminders
                   (worker_id, customer_key, remind_date, created_at)
                   VALUES (?, ?, ?, ?)""",
                arrayOf(workerId, customerKey, remindDate, System.currentTimeMillis())
            )
            return ToolResult.success(
                name,
                message = "✅ Kumbusho limewekwa: $customerKey atakumbushwa $remindDate"
            )
        }

        // Show today's reminders
        val today = dateFormat.format(System.currentTimeMillis())
        val reminders = getRemindersForDate(db, workerId, today)

        if (reminders.isEmpty()) {
            return ToolResult.success(name, message = "📋 Hakuna kumbusho za leo.")
        }

        val output = buildString {
            appendLine("🔔 *Kumbusho za Leo*")
            reminders.forEach { reminder ->
                appendLine("   • ${reminder["customer_key"]} — ${reminder["remind_date"]}")
            }
        }
        return ToolResult.success(name, data = reminders, message = output)
    }

    // ──────────────────────────────────────────────
    // OVERVIEW — Business customer overview
    // ──────────────────────────────────────────────

    private suspend fun businessOverview(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]!!

        val db = dbHelper.readableDatabase
        try {
            val totalCustomers = getCustomerCount(db, workerId)
            val todayVisits = getVisitCountForDate(db, workerId, dateFormat.format(System.currentTimeMillis()))
            val weekVisits = getVisitCountForPeriod(db, workerId, 7)
            val monthVisits = getVisitCountForPeriod(db, workerId, 30)
            val totalRevenue = getTotalRevenue(db, workerId)
            val monthRevenue = getRevenueForPeriod(db, workerId, 30)
            val topServices = getTopServices(db, workerId, 5)
            val repeatRate = getRepeatCustomerRate(db, workerId)

            if (totalCustomers == 0) {
                return ToolResult.success(
                    name,
                    message = "📋 *Hakuna wateja bado.*\n\nAnza kurekodi ziara: register_visit customer_key=Jina service=Huduma amount=500"
                )
            }

            val output = buildString {
                appendLine("👥 *Muhtasari wa Wateja*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("👥 Wateja jumla: $totalCustomers")
                appendLine("🔁 Warudi: ${"%.0f".format(repeatRate * 100)}%")
                appendLine()
                appendLine("📅 *Ziara:*")
                appendLine("   Leo: $todayVisits")
                appendLine("   Wiki hii: $weekVisits")
                appendLine("   Mwezi huu: $monthVisits")
                appendLine()
                appendLine("💰 *Mapato:*")
                appendLine("   Jumla: KES ${"%,.0f".format(totalRevenue)}")
                appendLine("   Mwezi huu: KES ${"%,.0f".format(monthRevenue)}")
                if (monthVisits > 0) {
                    appendLine("   Wastani kwa ziara: KES ${"%,.0f".format(monthRevenue / monthVisits)}")
                }

                if (topServices.isNotEmpty()) {
                    appendLine()
                    appendLine("🔧 *Huduma Zinazopendwa:*")
                    topServices.forEachIndexed { i, service ->
                        appendLine("   ${i + 1}. ${service["service"]} (${service["count"]} ziara)")
                    }
                }

                appendLine()
                appendLine("💡 *Vidokezo:*")
                if (repeatRate < 0.3) appendLine("   ⚠️ Kiwango cha kurudi ni chini. Jaribu loyalty rewards!")
                if (todayVisits == 0) appendLine("   📞 Kumbusha wateja wako wa kawaida leo.")
                appendLine("   🔮 Angalia wanaokuja: predict_visits")
            }

            return ToolResult.success(name, message = output)
        } catch (e: Exception) {
            Timber.e(e, "Business overview failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSING
    // ──────────────────────────────────────────────

    private suspend fun parseVoiceInput(voiceInput: String, workerId: String, params: Map<String, String>): ToolResult {
        val input = voiceInput.trim().lowercase()

        // Prediction patterns
        if (input.contains("anakuja lini") || input.contains("atarajia") || input.contains("predict") ||
            input.contains("nani anakuja") || input.contains("next visit")) {
            return predictVisits(params)
        }

        // Loyalty patterns
        if (input.contains("bora") || input.contains("vip") || input.contains("loyal") ||
            input.contains("wateja wangu") || input.contains("waaminifu")) {
            return loyaltyOverview(params)
        }

        // Search patterns
        val searchPatterns = listOf(
            Regex("""(?:mteja|customer|wasifu\s+wa)\s+([A-Za-zÀ-ÿ\s]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in searchPatterns) {
            pattern.find(voiceInput)?.let { match ->
                val customerName = match.groupValues[1].trim()
                if (customerName.isNotBlank() && customerName.length > 1) {
                    return searchCustomer(mapOf("worker_id" to workerId, "customer_key" to customerName))
                }
            }
        }

        // Overview patterns
        if (input.contains("overview") || input.contains("muhtasari") || input.contains("wateja wangu wakoje")) {
            return businessOverview(params)
        }

        // Default: overview
        return businessOverview(params)
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun updateCustomerProfile(db: SQLiteDatabase, workerId: String, customerKey: String, service: String, amount: Double, now: Long) {
        val existing = getCustomerProfile(db, workerId, customerKey)
        if (existing != null) {
            val visits = ((existing["total_visits"] as? Number)?.toInt() ?: 0) + 1
            val totalSpend = ((existing["total_spend"] as? Number)?.toDouble() ?: 0.0) + amount
            db.execSQL(
                """UPDATE customer_profiles SET total_visits = ?, total_spend = ?, last_visit = ?,
                   preferred_service = ?, updated_at = ?
                   WHERE worker_id = ? AND customer_key = ?""",
                arrayOf(visits, totalSpend, dateFormat.format(now), service, now, workerId, customerKey)
            )
        } else {
            db.execSQL(
                """INSERT INTO customer_profiles
                   (worker_id, customer_key, total_visits, total_spend, first_visit, last_visit,
                    preferred_service, created_at, updated_at)
                   VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)""",
                arrayOf(workerId, customerKey, amount, dateFormat.format(now), dateFormat.format(now), service, now, now)
            )
        }
    }

    private fun getCustomerProfile(db: SQLiteDatabase, workerId: String, customerKey: String): Map<String, Any?>? {
        val cursor = db.rawQuery(
            "SELECT * FROM customer_profiles WHERE worker_id = ? AND customer_key = ?",
            arrayOf(workerId, customerKey)
        )
        cursor.use { c -> return if (c.moveToFirst()) cursorToMap(c) else null }
    }

    private fun getCustomersWithHistory(db: SQLiteDatabase, workerId: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            "SELECT * FROM customer_profiles WHERE worker_id = ? ORDER BY total_visits DESC",
            arrayOf(workerId)
        )
        cursor.use { c ->
            while (c.moveToNext()) results.add(cursorToMap(c))
        }
        return results
    }

    private fun getCustomerVisitCount(db: SQLiteDatabase, workerId: String, customerKey: String): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM customer_visits WHERE worker_id = ? AND customer_key = ?",
            arrayOf(workerId, customerKey)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun getCustomerVisitDates(db: SQLiteDatabase, workerId: String, customerKey: String): List<Long> {
        val dates = mutableListOf<Long>()
        val cursor = db.rawQuery(
            "SELECT created_at FROM customer_visits WHERE worker_id = ? AND customer_key = ? ORDER BY created_at",
            arrayOf(workerId, customerKey)
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                dates.add(c.getLong(0))
            }
        }
        return dates
    }

    private fun getCustomerRecentVisits(db: SQLiteDatabase, workerId: String, customerKey: String, limit: Int): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            """SELECT visit_date, service, amount, staff, notes FROM customer_visits
               WHERE worker_id = ? AND customer_key = ?
               ORDER BY created_at DESC LIMIT ?""",
            arrayOf(workerId, customerKey, limit.toString())
        )
        cursor.use { c ->
            while (c.moveToNext()) results.add(cursorToMap(c))
        }
        return results
    }

    private fun getCustomerPreferences(db: SQLiteDatabase, workerId: String, customerKey: String): Map<String, String> {
        val prefs = mutableMapOf<String, String>()
        val cursor = db.rawQuery(
            "SELECT pref_key, pref_value FROM customer_preferences WHERE worker_id = ? AND customer_key = ?",
            arrayOf(workerId, customerKey)
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                prefs[c.getString(0)] = c.getString(1)
            }
        }
        return prefs
    }

    private fun getAllPreferencesSummary(db: SQLiteDatabase, workerId: String): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        val cursor = db.rawQuery(
            "SELECT customer_key, pref_key, pref_value FROM customer_preferences WHERE worker_id = ?",
            arrayOf(workerId)
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                result.getOrPut(key) { mutableMapOf() }[c.getString(1)] = c.getString(2)
            }
        }
        return result
    }

    private fun getCustomerCount(db: SQLiteDatabase, workerId: String): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM customer_profiles WHERE worker_id = ?",
            arrayOf(workerId)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun getVisitCountForDate(db: SQLiteDatabase, workerId: String, date: String): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM customer_visits WHERE worker_id = ? AND visit_date = ?",
            arrayOf(workerId, date)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun getVisitCountForPeriod(db: SQLiteDatabase, workerId: String, days: Int): Int {
        val since = dateFormat.format(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong()))
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM customer_visits WHERE worker_id = ? AND visit_date >= ?",
            arrayOf(workerId, since)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun getTotalRevenue(db: SQLiteDatabase, workerId: String): Double {
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(amount), 0) FROM customer_visits WHERE worker_id = ?",
            arrayOf(workerId)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    private fun getRevenueForPeriod(db: SQLiteDatabase, workerId: String, days: Int): Double {
        val since = dateFormat.format(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong()))
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(amount), 0) FROM customer_visits WHERE worker_id = ? AND visit_date >= ?",
            arrayOf(workerId, since)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    private fun getTopServices(db: SQLiteDatabase, workerId: String, limit: Int): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            """SELECT service, COUNT(*) as count, SUM(amount) as total
               FROM customer_visits WHERE worker_id = ?
               GROUP BY service ORDER BY count DESC LIMIT ?""",
            arrayOf(workerId, limit.toString())
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                results.add(mapOf(
                    "service" to c.getString(0),
                    "count" to c.getInt(1),
                    "total" to c.getDouble(2)
                ))
            }
        }
        return results
    }

    private fun getRepeatCustomerRate(db: SQLiteDatabase, workerId: String): Double {
        val cursor = db.rawQuery(
            """SELECT
                COUNT(*) as total,
                SUM(CASE WHEN total_visits >= 2 THEN 1 ELSE 0 END) as repeat_count
               FROM customer_profiles WHERE worker_id = ?""",
            arrayOf(workerId)
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                val total = c.getInt(0)
                val repeat = c.getInt(1)
                return if (total > 0) repeat.toDouble() / total else 0.0
            }
        }
        return 0.0
    }

    private fun getRemindersForDate(db: SQLiteDatabase, workerId: String, date: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            "SELECT customer_key, remind_date FROM visit_reminders WHERE worker_id = ? AND remind_date <= ?",
            arrayOf(workerId, date)
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                results.add(mapOf("customer_key" to c.getString(0), "remind_date" to c.getString(1)))
            }
        }
        return results
    }

    private fun calculateAverageInterval(visitDates: List<Long>): Int {
        if (visitDates.size < 2) return 7 // Default to weekly
        val intervals = mutableListOf<Long>()
        for (i in 1 until visitDates.size) {
            intervals.add(visitDates[i] - visitDates[i - 1])
        }
        val avgMs = intervals.average()
        return TimeUnit.MILLISECONDS.toDays(avgMs.toLong()).toInt().coerceAtLeast(1)
    }

    private fun calculateConfidence(visitDates: List<Long>, avgInterval: Int): Double {
        if (visitDates.size < 3) return 0.5
        val intervals = mutableListOf<Long>()
        for (i in 1 until visitDates.size) {
            intervals.add(visitDates[i] - visitDates[i - 1])
        }
        val avgMs = intervals.average()
        val stdDev = Math.sqrt(intervals.map { (it - avgMs) * (it - avgMs) }.average())
        // Lower std deviation = higher confidence
        val cv = if (avgMs > 0) stdDev / avgMs else 1.0 // Coefficient of variation
        return (1.0 - cv.coerceIn(0.0, 1.0)).coerceIn(0.3, 0.95)
    }

    private fun preferenceToSwahili(key: String): String = when (key) {
        "favorite_service" -> "Huduma anayoipenda"
        "preferred_time" -> "Saa anayoipenda"
        "preferred_staff" -> "Msaidizi anayemtaka"
        "hair_type" -> "Aina ya nywele"
        "notes" -> "Maelezo"
        "allergies" -> "Mzio"
        "payment_method" -> "Njia ya malipo"
        else -> key.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun cursorToMap(cursor: Cursor): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            map[name] = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                else -> null
            }
        }
        return map
    }
}

// ──────────────────────────────────────────────
// Data classes
// ──────────────────────────────────────────────

data class PredictedVisit(
    val customerKey: String,
    val predictedDate: String,
    val daysFromNow: Int,
    val preferredService: String?,
    val confidence: Double,
    val avgSpend: Double
)

// ──────────────────────────────────────────────
// SQLiteOpenHelper — Customer matcher database
// ──────────────────────────────────────────────

class CustomerMatcherDbHelper(context: Context) : SQLiteOpenHelper(
    context, "customer_matcher.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        // Customer visit records
        db.execSQL("""
            CREATE TABLE customer_visits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL,
                customer_key TEXT NOT NULL,
                service TEXT NOT NULL,
                amount REAL DEFAULT 0,
                staff TEXT,
                notes TEXT,
                visit_date TEXT NOT NULL,
                day_of_week TEXT,
                created_at INTEGER NOT NULL
            )
        """)

        // Customer profiles (local cache, synced with CustomerInsights)
        db.execSQL("""
            CREATE TABLE customer_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL,
                customer_key TEXT NOT NULL,
                total_visits INTEGER DEFAULT 0,
                total_spend REAL DEFAULT 0,
                first_visit TEXT,
                last_visit TEXT,
                preferred_service TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(worker_id, customer_key)
            )
        """)

        // Customer preferences
        db.execSQL("""
            CREATE TABLE customer_preferences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL,
                customer_key TEXT NOT NULL,
                pref_key TEXT NOT NULL,
                pref_value TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(worker_id, customer_key, pref_key)
            )
        """)

        // Visit reminders
        db.execSQL("""
            CREATE TABLE visit_reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL,
                customer_key TEXT NOT NULL,
                remind_date TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(worker_id, customer_key)
            )
        """)

        // Indexes
        db.execSQL("CREATE INDEX idx_visits_worker ON customer_visits(worker_id)")
        db.execSQL("CREATE INDEX idx_visits_customer ON customer_visits(worker_id, customer_key)")
        db.execSQL("CREATE INDEX idx_visits_date ON customer_visits(visit_date)")
        db.execSQL("CREATE INDEX idx_profiles_worker ON customer_profiles(worker_id)")
        db.execSQL("CREATE INDEX idx_profiles_customer ON customer_profiles(worker_id, customer_key)")
        db.execSQL("CREATE INDEX idx_prefs_customer ON customer_preferences(worker_id, customer_key)")
        db.execSQL("CREATE INDEX idx_reminders_date ON visit_reminders(worker_id, remind_date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }
}
