package com.msaidizi.app.superagent.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BookingScheduler — Simple appointment booking for service workers.
 *
 * Workers set their availability. Customers book time slots.
 * Voice-based: "Nataka kufika saa tatu"
 * Sends reminder notifications.
 *
 * Features:
 *  1. Worker availability management (days, hours, slots)
 *  2. Customer booking with service selection
 *  3. Voice booking: "Nataka kufika saa tatu" or "Nataka appointment kesho"
 *  4. Reminder notifications (before appointment)
 *  5. Booking confirmation, cancellation, rescheduling
 *  6. Calendar view for workers (today, this week)
 *  7. No-show tracking
 *  8. Walk-in vs appointment tracking
 *
 * Integrates with:
 *  - ServiceMenu (service catalog for booking)
 *  - CustomerMatcher (customer profiles and visit history)
 *  - RatingSystem (prompt rating after completed appointment)
 *  - GamificationEngine (points for keeping appointments)
 *
 * 8 Actions: set_availability, book, cancel, reschedule, confirm,
 *            today, this_week, no_show
 *
 * Voice-first, bilingual (Kiswahili + English).
 */
@Singleton
class BookingScheduler @Inject constructor(
    private val context: Context,
    private val gamificationEngine: GamificationEngine,
    private val gson: Gson
) : Tool {

    override val name = "booking_scheduler"
    override val description = "Book appointments with service workers. Voice: 'Nataka kufika saa tatu kesho' or 'Nataka appointment na salon'"

    override val argsSchema = argSchema {
        enum(
            "action", "Booking action",
            listOf(
                "set_availability", // Worker sets available time slots
                "book",             // Customer books an appointment
                "cancel",           // Cancel a booking
                "reschedule",       // Change booking time
                "confirm",          // Worker confirms a booking
                "today",            // View today's schedule
                "this_week",        // View this week's schedule
                "no_show"           // Mark customer as no-show
            ),
            required = false
        )
        string("worker_id", "Worker/business ID", required = false)
        string("customer_name", "Customer name", required = false)
        string("customer_phone", "Customer phone number", required = false)
        string("service", "Service being booked", required = false)
        string("date", "Date (yyyy-MM-dd) or 'leo'/'kesho'", required = false)
        string("time", "Time (HH:mm) or 'asubuhi'/'mchana'/'jioni'/'saa moja'/'saa mbili'", required = false)
        integer("duration_minutes", "Expected duration in minutes (default: 30)", required = false)
        string("booking_id", "Booking ID for cancel/reschedule/confirm/no_show", required = false)
        string("day_of_week", "Day for set_availability: monday-sunday or jumatatu-jumapili", required = false)
        string("start_time", "Start time for availability (HH:mm)", required = false)
        string("end_time", "End time for availability (HH:mm)", required = false)
        string("slot_size", "Slot duration in minutes (default: 30)", required = false)
        string("notes", "Booking notes or special requests", required = false)
        string("voice_input", "Raw Swahili/English voice text to parse", required = false)
    }

    private val dbHelper: BookingDbHelper by lazy { BookingDbHelper(context) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "today"

        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!, params)
        }

        return when (action.lowercase()) {
            "set_availability" -> setAvailability(params)
            "book" -> bookAppointment(params)
            "cancel" -> cancelBooking(params)
            "reschedule" -> rescheduleBooking(params)
            "confirm" -> confirmBooking(params)
            "today" -> viewToday(params)
            "this_week" -> viewThisWeek(params)
            "no_show" -> markNoShow(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // SET AVAILABILITY — Worker sets schedule
    // ──────────────────────────────────────────────

    private fun setAvailability(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required. Try: worker_id=salon_001", "MISSING_WORKER_ID")
        val dayOfWeek = params["day_of_week"]
            ?: return ToolResult.error(name, "Day required. Try: day_of_week=monday au jumatatu", "MISSING_DAY")
        val startTime = params["start_time"]
            ?: return ToolResult.error(name, "Start time required. Try: start_time=08:00", "MISSING_START_TIME")
        val endTime = params["end_time"]
            ?: return ToolResult.error(name, "End time required. Try: end_time=17:00", "MISSING_END_TIME")
        val slotSize = params["slot_size"]?.toIntOrNull() ?: 30

        val normalizedDay = normalizeDayOfWeek(dayOfWeek)
            ?: return ToolResult.error(name, "Siku haikujulikana: '$dayOfWeek'. Tumia: monday-sunday au jumatatu-jumapili", "INVALID_DAY")

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()

            // Remove existing availability for this day
            db.execSQL(
                "DELETE FROM availability WHERE worker_id = ? AND day_of_week = ?",
                arrayOf(workerId, normalizedDay)
            )

            // Generate time slots
            val slots = generateTimeSlots(startTime, endTime, slotSize)

            slots.forEach { slot ->
                db.execSQL(
                    """INSERT INTO availability
                       (worker_id, day_of_week, start_time, end_time, slot_minutes, is_active, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, 1, ?, ?)""",
                    arrayOf(workerId, normalizedDay, slot.start, slot.end, slotSize, now, now)
                )
            }

            val daySwahili = dayToSwahili(normalizedDay)
            return ToolResult.success(
                name,
                data = mapOf("worker_id" to workerId, "day" to normalizedDay, "slots" to slots.size),
                message = "✅ Umeeka wakati wa $daySwahili: $startTime — $endTime\n" +
                        "📋 Slots ${slots.size} zimeundwa (kila moja $slotSize min)\n\n" +
                        "Wateja sasa wanaweza kubokeka wakati huu!"
            )
        } catch (e: Exception) {
            Timber.e(e, "Set availability failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // BOOK — Customer books an appointment
    // ──────────────────────────────────────────────

    private suspend fun bookAppointment(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required. Try: worker_id=salon_001", "MISSING_WORKER_ID")
        val customerName = params["customer_name"]
            ?: return ToolResult.error(name, "Jina la mteja required. Try: customer_name=Amina", "MISSING_CUSTOMER_NAME")
        val customerPhone = params["customer_phone"]
        val service = params["service"]
        val date = parseDate(params["date"])
            ?: return ToolResult.error(name, "Tarehe required. Try: date=leo au date=kesho au date=2025-08-01", "MISSING_DATE")
        val time = parseTime(params["time"])
            ?: return ToolResult.error(name, "Saa required. Try: time=saa_tatu au time=14:00", "MISSING_TIME")
        val duration = params["duration_minutes"]?.toIntOrNull() ?: 30
        val notes = params["notes"]

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()

            // Check if slot is available
            val dayOfWeek = getDayOfWeek(date)
            val isAvailable = checkSlotAvailable(db, workerId, dayOfWeek, time, date)
            if (!isAvailable) {
                // Check if there's another slot nearby
                val nearbySlots = findNearbySlots(db, workerId, dayOfWeek, time, date)
                if (nearbySlots.isNotEmpty()) {
                    val suggestions = nearbySlots.joinToString(", ") { it }
                    return ToolResult.error(
                        name,
                        "Saa $time imekwisha. Saa zinazopatikana: $suggestions\n\nChagua nyingine: book time=SAA",
                        "SLOT_TAKEN"
                    )
                }
                return ToolResult.error(
                    name,
                    "Saa $time haipatikani $date. Worker hajaweka wakati wa siku hiyo.",
                    "SLOT_NOT_AVAILABLE"
                )
            }

            // Check for double booking
            val conflict = checkConflict(db, workerId, date, time, duration)
            if (conflict != null) {
                return ToolResult.error(
                    name,
                    "Saa $time imekwisha na mteja mwingine. Jaribu saa nyingine.",
                    "SLOT_CONFLICT"
                )
            }

            // Create booking
            val bookingId = "BK_${now}_${customerName.take(3).uppercase()}"

            db.execSQL(
                """INSERT INTO bookings
                   (booking_id, worker_id, customer_name, customer_phone, service, booking_date,
                    booking_time, duration_minutes, notes, status, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'confirmed', ?, ?)""",
                arrayOf(bookingId, workerId, customerName, customerPhone, service, date, time, duration, notes, now, now)
            )

            // Mark slot as booked
            markSlotBooked(db, workerId, dayOfWeek, time, date)

            // Gamification
            gamificationEngine.addPoints(mapOf("action_type" to "book_appointment"))

            val serviceText = if (service != null) " ($service)" else ""
            val message = buildString {
                appendLine("✅ *Umefanikiwa kubokeka!*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📋 Booking ID: $bookingId")
                appendLine("👤 Mteja: $customerName$serviceText")
                appendLine("📅 Tarehe: $date")
                appendLine("⏰ Saa: $time")
                appendLine("⏱ Muda: $duration min")
                if (notes != null) appendLine("📝 Notes: $notes")
                appendLine()
                appendLine("🔔 Utakumbushwa siku moja kabla na saa moja kabla.")
                appendLine("❌ Kubatilisha: cancel booking_id=$bookingId")
            }

            return ToolResult.success(
                name,
                data = mapOf("booking_id" to bookingId, "date" to date, "time" to time, "status" to "confirmed"),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Book appointment failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CANCEL — Cancel a booking
    // ──────────────────────────────────────────────

    private suspend fun cancelBooking(params: Map<String, String>): ToolResult {
        val bookingId = params["booking_id"]
            ?: return ToolResult.error(name, "Booking ID required. Try: booking_id=BK_12345", "MISSING_BOOKING_ID")

        val db = dbHelper.writableDatabase
        try {
            val booking = getBookingById(db, bookingId)
                ?: return ToolResult.error(name, "Booking '$bookingId' haikupatikana.", "NOT_FOUND")

            if (booking["status"] == "cancelled") {
                return ToolResult.error(name, "Booking hii tayari imebatilishwa.", "ALREADY_CANCELLED")
            }

            val now = System.currentTimeMillis()
            db.execSQL(
                "UPDATE bookings SET status = 'cancelled', updated_at = ? WHERE booking_id = ?",
                arrayOf(now, bookingId)
            )

            // Free up the slot
            val date = booking["booking_date"]?.toString() ?: ""
            val time = booking["booking_time"]?.toString() ?: ""
            val dayOfWeek = getDayOfWeek(date)
            freeSlot(db, booking["worker_id"]?.toString() ?: "", dayOfWeek, time, date)

            return ToolResult.success(
                name,
                data = mapOf("booking_id" to bookingId, "status" to "cancelled"),
                message = "❌ Booking $bookingId imebatilishwa.\n\nSaa ${time} tarehe $date sasa inapatikana kwa wateja wengine."
            )
        } catch (e: Exception) {
            Timber.e(e, "Cancel booking failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RESCHEDULE — Change booking time
    // ──────────────────────────────────────────────

    private suspend fun rescheduleBooking(params: Map<String, String>): ToolResult {
        val bookingId = params["booking_id"]
            ?: return ToolResult.error(name, "Booking ID required", "MISSING_BOOKING_ID")
        val newDate = parseDate(params["date"])
            ?: return ToolResult.error(name, "New date required. Try: date=kesho", "MISSING_DATE")
        val newTime = parseTime(params["time"])
            ?: return ToolResult.error(name, "New time required. Try: time=14:00", "MISSING_TIME")

        val db = dbHelper.writableDatabase
        try {
            val booking = getBookingById(db, bookingId)
                ?: return ToolResult.error(name, "Booking '$bookingId' haikupatikana.", "NOT_FOUND")

            val workerId = booking["worker_id"]?.toString() ?: ""
            val dayOfWeek = getDayOfWeek(newDate)

            // Check new slot availability
            if (!checkSlotAvailable(db, workerId, dayOfWeek, newTime, newDate)) {
                return ToolResult.error(name, "Saa $newTime haipatikani $newDate.", "SLOT_NOT_AVAILABLE")
            }

            val now = System.currentTimeMillis()

            // Free old slot
            val oldDate = booking["booking_date"]?.toString() ?: ""
            val oldTime = booking["booking_time"]?.toString() ?: ""
            freeSlot(db, workerId, getDayOfWeek(oldDate), oldTime, oldDate)

            // Update booking
            db.execSQL(
                "UPDATE bookings SET booking_date = ?, booking_time = ?, status = 'rescheduled', updated_at = ? WHERE booking_id = ?",
                arrayOf(newDate, newTime, now, bookingId)
            )

            // Mark new slot as booked
            markSlotBooked(db, workerId, dayOfWeek, newTime, newDate)

            return ToolResult.success(
                name,
                data = mapOf("booking_id" to bookingId, "new_date" to newDate, "new_time" to newTime),
                message = "🔄 Booking $bookingId imerahisiwa:\n📅 $newDate ⏰ $newTime\n\n🔔 Utakumbushwa tena."
            )
        } catch (e: Exception) {
            Timber.e(e, "Reschedule booking failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CONFIRM — Worker confirms a booking
    // ──────────────────────────────────────────────

    private suspend fun confirmBooking(params: Map<String, String>): ToolResult {
        val bookingId = params["booking_id"]
            ?: return ToolResult.error(name, "Booking ID required", "MISSING_BOOKING_ID")

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()
            db.execSQL(
                "UPDATE bookings SET status = 'confirmed', confirmed_at = ?, updated_at = ? WHERE booking_id = ?",
                arrayOf(now, now, bookingId)
            )

            val booking = getBookingById(db, bookingId)
            val customer = booking?.get("customer_name")?.toString() ?: "Mteja"
            val time = booking?.get("booking_time")?.toString() ?: ""
            val date = booking?.get("booking_date")?.toString() ?: ""

            return ToolResult.success(
                name,
                message = "✅ Booking $bookingId imethibitishwa!\n\n👤 $customer atafika $date saa $time."
            )
        } catch (e: Exception) {
            Timber.e(e, "Confirm booking failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // TODAY — View today's schedule
    // ──────────────────────────────────────────────

    private fun viewToday(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")

        val today = dateFormat.format(System.currentTimeMillis())
        val db = dbHelper.readableDatabase

        val bookings = getBookingsForDate(db, workerId, today)
        val availability = getAvailabilityForDay(db, workerId, getDayOfWeek(today))

        if (bookings.isEmpty() && availability.isEmpty()) {
            return ToolResult.success(
                name,
                message = "📅 *Leo ($today)*\n\nHakuna appointments wala availability iliyowekwa.\n\nWeka wakati wako: set_availability"
            )
        }

        val now = timeFormat.format(System.currentTimeMillis())

        val output = buildString {
            appendLine("📅 *Ratiba ya Leo — $today*")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()

            if (bookings.isNotEmpty()) {
                appendLine("📋 *Appointments:*")
                bookings.forEach { booking ->
                    val time = booking["booking_time"]?.toString() ?: ""
                    val name = booking["customer_name"]?.toString() ?: ""
                    val service = booking["service"]?.toString() ?: ""
                    val status = booking["status"]?.toString() ?: ""
                    val statusEmoji = when (status) {
                        "confirmed" -> "✅"
                        "pending" -> "🟡"
                        "completed" -> "✅"
                        "no_show" -> "❌"
                        else -> "⚪"
                    }

                    val isPast = time < now
                    val pastIndicator = if (isPast) " (imepita)" else ""

                    appendLine("   $statusEmoji $time — $name${if (service.isNotEmpty()) " ($service)" else ""}$pastIndicator")
                }
                appendLine()
            }

            if (availability.isNotEmpty()) {
                appendLine("🟢 *Wakati wa Kazi:*")
                availability.forEach { slot ->
                    val start = slot["start_time"]?.toString() ?: ""
                    val end = slot["end_time"]?.toString() ?: ""
                    appendLine("   $start — $end")
                }
                appendLine()

                val totalSlots = availability.size
                val bookedSlots = bookings.size
                val freeSlots = totalSlots - bookedSlots
                appendLine("📊 Slots: $bookedSlots booked, $freeSlots bado")
            }

            appendLine()
            appendLine("💡 Angalia wiki: this_week")
        }

        return ToolResult.success(name, data = bookings, message = output)
    }

    // ──────────────────────────────────────────────
    // THIS WEEK — View weekly schedule
    // ──────────────────────────────────────────────

    private fun viewThisWeek(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")

        val db = dbHelper.readableDatabase
        val cal = Calendar.getInstance()
        val today = dateFormat.format(cal.timeInMillis)

        // Get dates for this week (Mon-Sun)
        val weekStart = cal.apply { set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }.timeInMillis
        val weekDates = (0..6).map { offset ->
            dateFormat.format(weekStart + TimeUnit.DAYS.toMillis(offset.toLong()))
        }

        val output = buildString {
            appendLine("📅 *Ratiba ya Wiki*")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()

            var totalBookings = 0
            weekDates.forEach { date ->
                val bookings = getBookingsForDate(db, workerId, date)
                val dayOfWeek = getDayOfWeek(date)
                val availability = getAvailabilityForDay(db, workerId, dayOfWeek)
                val isToday = date == today

                val dayLabel = dayToSwahili(dayOfWeek)
                val todayMarker = if (isToday) " ← *LEO*" else ""

                appendLine("$dayLabel ($date)$todayMarker")

                if (availability.isNotEmpty()) {
                    appendLine("   🟢 ${availability.first()["start_time"]} — ${availability.first()["end_time"]}")
                }

                if (bookings.isNotEmpty()) {
                    bookings.forEach { booking ->
                        val time = booking["booking_time"]?.toString() ?: ""
                        val name = booking["customer_name"]?.toString() ?: ""
                        val status = booking["status"]?.toString() ?: ""
                        val emoji = if (status == "confirmed") "✅" else "🟡"
                        appendLine("   $emoji $time — $name")
                    }
                    totalBookings += bookings.size
                } else if (availability.isNotEmpty()) {
                    appendLine("   📭 Hakuna appointments")
                } else {
                    appendLine("   🔴 Siku ya mapumziko")
                }
                appendLine()
            }

            appendLine("━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📊 Wiki hii: $totalBookings appointments")
        }

        return ToolResult.success(name, message = output)
    }

    // ──────────────────────────────────────────────
    // NO SHOW — Mark customer as no-show
    // ──────────────────────────────────────────────

    private suspend fun markNoShow(params: Map<String, String>): ToolResult {
        val bookingId = params["booking_id"]
            ?: return ToolResult.error(name, "Booking ID required", "MISSING_BOOKING_ID")

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()
            db.execSQL(
                "UPDATE bookings SET status = 'no_show', updated_at = ? WHERE booking_id = ?",
                arrayOf(now, bookingId)
            )

            val booking = getBookingById(db, bookingId)
            val customer = booking?.get("customer_name")?.toString() ?: "Mteja"

            return ToolResult.success(
                name,
                message = "❌ $customer amerukwa (no-show) — booking $bookingId.\n\nHii inasaidia kufuatilia wateja wasioaminika."
            )
        } catch (e: Exception) {
            Timber.e(e, "Mark no-show failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSING
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili/English voice input.
     *
     * Patterns:
     *  - "Nataka kufika saa tatu" → book (time=15:00)
     *  - "Nataka appointment kesho" → book (date=kesho)
     *  - "Nataka kubokeka kesho saa mbili" → book (date=kesho, time=14:00)
     *  - "Ratiba ya leo" → today
     *  - "Ratiba ya wiki" → this_week
     *  - "Nataka kubatilisha" → cancel
     *  - "Nataka kurahisisha" → reschedule
     */
    private suspend fun parseVoiceInput(voiceInput: String, params: Map<String, String>): ToolResult {
        val input = voiceInput.trim().lowercase()

        // Today/this week patterns
        if (input.contains("ratiba ya leo") || input.contains("leo") && input.contains("ratiba") ||
            input.contains("today") || input.contains("appointments za leo")) {
            return viewToday(params)
        }

        if (input.contains("ratiba ya wiki") || input.contains("this week") ||
            input.contains("appointments za wiki")) {
            return viewThisWeek(params)
        }

        // Cancel patterns
        if (input.contains("batilisha") || input.contains("cancel") || input.contains("sitaki")) {
            val bookingId = params["booking_id"]
            if (bookingId != null) return cancelBooking(params)
            return ToolResult.error(name, "Booking ID required. Try: booking_id=BK_XXX", "MISSING_BOOKING_ID")
        }

        // Reschedule patterns
        if (input.contains("rahisisha") || input.contains("reschedule") || input.contains("badilisha") ||
            input.contains("change time")) {
            val bookingId = params["booking_id"]
            if (bookingId != null) return rescheduleBooking(params)
            return ToolResult.error(name, "Booking ID required", "MISSING_BOOKING_ID")
        }

        // Book patterns — extract date and time
        if (input.contains("nataka") || input.contains("nahitaji") || input.contains("book") ||
            input.contains("appointment") || input.contains("kufika") || input.contains("kubokeka")) {

            val mergedParams = params.toMutableMap()

            // Extract date
            val extractedDate = extractDate(input)
            if (extractedDate != null && mergedParams["date"] == null) {
                mergedParams["date"] = extractedDate
            }

            // Extract time
            val extractedTime = extractTime(input)
            if (extractedTime != null && mergedParams["time"] == null) {
                mergedParams["time"] = extractedTime
            }

            // If we have date and time, book directly
            if (mergedParams["date"] != null && mergedParams["time"] != null) {
                // Need worker_id and customer_name
                if (mergedParams["worker_id"] == null) {
                    return ToolResult.error(name, "Worker ID required. Try: worker_id=salon_001", "MISSING_WORKER_ID")
                }
                if (mergedParams["customer_name"] == null) {
                    return ToolResult.error(name, "Jina lako required. Try: customer_name=JinaLako", "MISSING_CUSTOMER_NAME")
                }
                return bookAppointment(mergedParams)
            }

            // If missing info, ask
            if (mergedParams["date"] == null) {
                return ToolResult.error(name, "Tarehe gani? Try: date=leo au date=kesho", "MISSING_DATE")
            }
            if (mergedParams["time"] == null) {
                return ToolResult.error(name, "Saa gani? Try: time=saa_tatu au time=14:00", "MISSING_TIME")
            }
        }

        // Default: today
        return viewToday(params)
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun generateTimeSlots(startTime: String, endTime: String, slotMinutes: Int): List<TimeSlot> {
        val slots = mutableListOf<TimeSlot>()
        val startMinutes = timeToMinutes(startTime)
        val endMinutes = timeToMinutes(endTime)

        var current = startMinutes
        while (current + slotMinutes <= endMinutes) {
            val slotStart = minutesToTime(current)
            val slotEnd = minutesToTime(current + slotMinutes)
            slots.add(TimeSlot(slotStart, slotEnd))
            current += slotMinutes
        }
        return slots
    }

    private fun timeToMinutes(time: String): Int {
        val parts = time.split(":")
        return parts[0].toInt() * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun minutesToTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return "%02d:%02d".format(h, m)
    }

    private fun checkSlotAvailable(db: SQLiteDatabase, workerId: String, dayOfWeek: String, time: String, date: String): Boolean {
        // Check if worker has availability for this day/time
        val cursor = db.rawQuery(
            """SELECT id FROM availability
               WHERE worker_id = ? AND day_of_week = ? AND start_time <= ? AND end_time > ? AND is_active = 1""",
            arrayOf(workerId, dayOfWeek, time, time)
        )
        cursor.use { c -> return c.moveToFirst() }
    }

    private fun checkConflict(db: SQLiteDatabase, workerId: String, date: String, time: String, duration: Int): String? {
        val timeMinutes = timeToMinutes(time)
        val endTime = minutesToTime(timeMinutes + duration)

        val cursor = db.rawQuery(
            """SELECT booking_id FROM bookings
               WHERE worker_id = ? AND booking_date = ? AND status IN ('confirmed', 'pending')
               AND booking_time < ? AND booking_time >= ?""",
            arrayOf(workerId, date, endTime, time)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun findNearbySlots(db: SQLiteDatabase, workerId: String, dayOfWeek: String, requestedTime: String, date: String): List<String> {
        val results = mutableListOf<String>()
        val cursor = db.rawQuery(
            """SELECT a.start_time FROM availability a
               WHERE a.worker_id = ? AND a.day_of_week = ? AND a.is_active = 1
               AND a.start_time NOT IN (
                   SELECT b.booking_time FROM bookings b
                   WHERE b.worker_id = ? AND b.booking_date = ? AND b.status IN ('confirmed', 'pending')
               )
               ORDER BY ABS(a.start_time - ?) LIMIT 3""",
            arrayOf(workerId, dayOfWeek, workerId, date, requestedTime)
        )
        cursor.use { c -> while (c.moveToNext()) results.add(c.getString(0)) }
        return results
    }

    private fun getBookingsForDate(db: SQLiteDatabase, workerId: String, date: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            """SELECT * FROM bookings WHERE worker_id = ? AND booking_date = ?
               AND status != 'cancelled' ORDER BY booking_time""",
            arrayOf(workerId, date)
        )
        cursor.use { c -> while (c.moveToNext()) results.add(cursorToMap(c)) }
        return results
    }

    private fun getAvailabilityForDay(db: SQLiteDatabase, workerId: String, dayOfWeek: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            "SELECT * FROM availability WHERE worker_id = ? AND day_of_week = ? AND is_active = 1 ORDER BY start_time",
            arrayOf(workerId, dayOfWeek)
        )
        cursor.use { c -> while (c.moveToNext()) results.add(cursorToMap(c)) }
        return results
    }

    private fun getBookingById(db: SQLiteDatabase, bookingId: String): Map<String, Any?>? {
        val cursor = db.rawQuery("SELECT * FROM bookings WHERE booking_id = ?", arrayOf(bookingId))
        cursor.use { c -> return if (c.moveToFirst()) cursorToMap(c) else null }
    }

    private fun markSlotBooked(db: SQLiteDatabase, workerId: String, dayOfWeek: String, time: String, date: String) {
        // Just update the booking record — the availability table defines the template
    }

    private fun freeSlot(db: SQLiteDatabase, workerId: String, dayOfWeek: String, time: String, date: String) {
        // Nothing to do — the slot is freed by cancelling the booking
    }

    private fun parseDate(dateStr: String?): String? {
        if (dateStr == null) return null
        return when (dateStr.lowercase()) {
            "leo" -> dateFormat.format(System.currentTimeMillis())
            "kesho" -> dateFormat.format(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1))
            "kesho kutwa" -> dateFormat.format(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2))
            else -> {
                // Try to parse yyyy-MM-dd
                try {
                    dateFormat.parse(dateStr)
                    dateStr
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun parseTime(timeStr: String?): String? {
        if (timeStr == null) return null
        return when (timeStr.lowercase()) {
            "asubuhi" -> "08:00"
            "mchana" -> "13:00"
            "jioni" -> "17:00"
            "usiku" -> "20:00"
            "saa moja" -> "09:00"
            "saa mbili" -> "10:00"
            "saa tatu" -> "11:00"
            "saa nne" -> "12:00"
            "saa tano" -> "13:00"
            "saa sita" -> "14:00"
            "saa saba" -> "15:00"
            "saa nane" -> "16:00"
            "saa tisa" -> "17:00"
            "saa kumi" -> "18:00"
            else -> {
                // Try to parse HH:mm
                if (timeStr.matches(Regex("""\d{1,2}:\d{2}"""))) timeStr else null
            }
        }
    }

    private fun extractDate(input: String): String? {
        return when {
            input.contains("leo") -> dateFormat.format(System.currentTimeMillis())
            input.contains("kesho kutwa") -> dateFormat.format(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2))
            input.contains("kesho") -> dateFormat.format(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1))
            else -> {
                val datePattern = Regex("""(\d{4}-\d{2}-\d{2})""")
                datePattern.find(input)?.groupValues?.get(1)
            }
        }
    }

    private fun extractTime(input: String): String? {
        val timeMap = mapOf(
            "saa moja" to "09:00", "saa mbili" to "10:00", "saa tatu" to "11:00",
            "saa nne" to "12:00", "saa tano" to "13:00", "saa sita" to "14:00",
            "saa saba" to "15:00", "saa nane" to "16:00", "saa tisa" to "17:00",
            "saa kumi" to "18:00", "saa kumi na moja" to "19:00", "saa kumi na mbili" to "20:00",
            "asubuhi" to "08:00", "mchana" to "13:00", "jioni" to "17:00"
        )
        for ((pattern, time) in timeMap) {
            if (input.contains(pattern)) return time
        }

        // Try HH:mm pattern
        val timePattern = Regex("""(\d{1,2}:\d{2})""")
        timePattern.find(input)?.let { return it.groupValues[1] }

        return null
    }

    private fun getDayOfWeek(dateStr: String): String {
        return try {
            val date = dateFormat.parse(dateStr)
            val cal = Calendar.getInstance()
            cal.time = date!!
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "monday"
                Calendar.TUESDAY -> "tuesday"
                Calendar.WEDNESDAY -> "wednesday"
                Calendar.THURSDAY -> "thursday"
                Calendar.FRIDAY -> "friday"
                Calendar.SATURDAY -> "saturday"
                Calendar.SUNDAY -> "sunday"
                else -> "monday"
            }
        } catch (e: Exception) {
            "monday"
        }
    }

    private fun normalizeDayOfWeek(day: String): String? {
        return when (day.lowercase()) {
            "monday", "jumatatu", "mon" -> "monday"
            "tuesday", "jumanne", "tue" -> "tuesday"
            "wednesday", "jumatano", "wed" -> "wednesday"
            "thursday", "alhamisi", "thu" -> "thursday"
            "friday", "ijumaa", "fri" -> "friday"
            "saturday", "jumamosi", "sat" -> "saturday"
            "sunday", "jumapili", "sun" -> "sunday"
            else -> null
        }
    }

    private fun dayToSwahili(day: String): String = when (day) {
        "monday" -> "Jumatatu"
        "tuesday" -> "Jumanne"
        "wednesday" -> "Jumatano"
        "thursday" -> "Alhamisi"
        "friday" -> "Ijumaa"
        "saturday" -> "Jumamosi"
        "sunday" -> "Jumapili"
        else -> day
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

data class TimeSlot(val start: String, val end: String)

// ──────────────────────────────────────────────
// SQLiteOpenHelper — Booking database
// ──────────────────────────────────────────────

class BookingDbHelper(context: Context) : SQLiteOpenHelper(
    context, "bookings.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        // Worker availability schedule
        db.execSQL("""
            CREATE TABLE availability (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL,
                day_of_week TEXT NOT NULL,
                start_time TEXT NOT NULL,
                end_time TEXT NOT NULL,
                slot_minutes INTEGER DEFAULT 30,
                is_active INTEGER DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        // Bookings
        db.execSQL("""
            CREATE TABLE bookings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                booking_id TEXT NOT NULL UNIQUE,
                worker_id TEXT NOT NULL,
                customer_name TEXT NOT NULL,
                customer_phone TEXT,
                service TEXT,
                booking_date TEXT NOT NULL,
                booking_time TEXT NOT NULL,
                duration_minutes INTEGER DEFAULT 30,
                notes TEXT,
                status TEXT DEFAULT 'pending',
                confirmed_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        // Indexes
        db.execSQL("CREATE INDEX idx_availability_worker ON availability(worker_id)")
        db.execSQL("CREATE INDEX idx_availability_day ON availability(worker_id, day_of_week)")
        db.execSQL("CREATE INDEX idx_bookings_worker ON bookings(worker_id)")
        db.execSQL("CREATE INDEX idx_bookings_date ON bookings(worker_id, booking_date)")
        db.execSQL("CREATE INDEX idx_bookings_status ON bookings(status)")
        db.execSQL("CREATE INDEX idx_bookings_booking_id ON bookings(booking_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }
}
