package com.msaidizi.app.superagent.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.msaidizi.app.core.database.KnowledgeDao
import com.msaidizi.app.model.KnowledgeEntity
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JobMatcher — Connect service workers to customers who need them.
 *
 * Solves the coordination failure that costs fundis, househelps,
 * entertainers, and construction workers income: they can't find
 * customers, and customers can't find them.
 *
 * Features:
 *  1. Worker skill registration (fundi wa umeme, fundi wa mashine, etc.)
 *  2. Customer job requests via voice: "Ninahitaji fundi wa kuweka tiles"
 *  3. Match by: skill, location proximity, availability, rating, price range
 *  4. Offline-first: cache nearby jobs/workers locally, sync when online
 *  5. Privacy: never expose exact worker location to strangers — use area names
 *  6. Job posting, bidding, acceptance, and completion tracking
 *
 * 8 Actions: register_worker, post_job, search_jobs, search_workers,
 *            accept_job, complete_job, my_jobs, stats
 *
 * Voice-first, bilingual (Kiswahili + English).
 * Integrates with CustomerInsights (customer data) and GamificationEngine (points).
 */
@Singleton
class JobMatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knowledgeDao: KnowledgeDao,
    private val gamificationEngine: GamificationEngine,
    private val gson: Gson
) : Tool {

    override val name = "job_matcher"
    override val description = "Match workers to jobs and customers. Voice: 'Ninahitaji fundi wa kuweka tiles' or 'Kuna kazi gani karibu?'"

    override val argsSchema = argSchema {
        enum(
            "action", "Job matching action to perform",
            listOf(
                "register_worker",  // Register worker skills and availability
                "post_job",         // Customer posts a job request
                "search_jobs",      // Worker searches for available jobs
                "search_workers",   // Customer searches for available workers
                "accept_job",       // Worker accepts/bids on a job
                "complete_job",     // Mark job as completed
                "my_jobs",          // View worker's active/completed jobs
                "stats"             // Worker job statistics
            ),
            required = false
        )
        string("worker_id", "Worker ID (phone number or unique ID)", required = false)
        string("customer_phone", "Customer phone number", required = false)
        string("skill", "Skill category: fundi_umeme, fundi_mashine, fundi_tiles, fundi_painting, fundi_plumber, fundi_carpenter, fundi_welder, househelp, cook, gardener, entertainer_dj, entertainer_mc, entertainer_musician, cleaner, driver, nanny, watchman, tailor, barber, salonist, mechanic, electrician, mason", required = false)
        string("title", "Job title or description", required = false)
        string("description", "Detailed job description", required = false)
        string("location", "Area name (e.g. Migori, Nairobi West, Kilimani) — NOT exact address", required = false)
        string("price_range", "Price range in KES, e.g. '1000-3000'", required = false)
        string("date", "Preferred date (yyyy-MM-dd) or 'leo'/'kesho'", required = false)
        string("time", "Preferred time (HH:mm) or 'asubuhi'/'mchana'/'jioni'", required = false)
        string("job_id", "Specific job ID for accept/complete actions", required = false)
        integer("radius_km", "Search radius in km (default: 5, max: 20)", required = false)
        integer("limit", "Max results (default: 10)", required = false)
        string("status", "Job status filter: open, accepted, completed, cancelled", required = false)
        string("voice_input", "Raw Swahili/English voice text to parse", required = false)
    }

    private val dbHelper: JobDbHelper by lazy { JobDbHelper(context) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "search_jobs"

        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!, params)
        }

        return when (action.lowercase()) {
            "register_worker" -> registerWorker(params)
            "post_job" -> postJob(params)
            "search_jobs" -> searchJobs(params)
            "search_workers" -> searchWorkers(params)
            "accept_job" -> acceptJob(params)
            "complete_job" -> completeJob(params)
            "my_jobs" -> myJobs(params)
            "stats" -> getStats(params)
            else -> ToolResult.error(name, "Unknown action: $action. Use: register_worker, post_job, search_jobs, search_workers, accept_job, complete_job, my_jobs, stats", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // REGISTER WORKER — Worker lists skills & availability
    // ──────────────────────────────────────────────

    private suspend fun registerWorker(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required. Try: worker_id=0712345678", "MISSING_WORKER_ID")
        val skills = params["skill"]
            ?: return ToolResult.error(name, "Skill required. Try: skill=fundi_umeme. Pia unaweza: fundi_tiles, fundi_painting, househelp, mechanic, n.k.", "MISSING_SKILL")
        val location = params["location"]
            ?: return ToolResult.error(name, "Location required (area name, not exact address). Try: location=Kilimani", "MISSING_LOCATION")
        val priceRange = params["price_range"]
        val description = params["description"]

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()

            // Check if worker already registered
            val existing = findWorkerId(db, workerId)
            if (existing != null) {
                // Update existing worker
                db.execSQL(
                    """UPDATE workers SET skills = ?, location_area = ?, price_range = ?,
                       description = ?, updated_at = ?, is_available = 1
                       WHERE worker_id = ?""",
                    arrayOf(skills, location, priceRange, description, now, workerId)
                )
            } else {
                db.execSQL(
                    """INSERT INTO workers (worker_id, skills, location_area, price_range, description,
                       avg_rating, completed_jobs, is_available, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, 0.0, 0, 1, ?, ?)""",
                    arrayOf(workerId, skills, location, priceRange, description, now, now)
                )
            }

            // Gamification: points for registering
            gamificationEngine.addPoints(mapOf("action_type" to "register_worker"))

            val skillLabel = skillToSwahili(skills)
            return ToolResult.success(
                name,
                data = mapOf("worker_id" to workerId, "skill" to skills, "location" to location),
                message = "✅ Umefanikiwa kujisajili kama $skillLabel hapa $location.\n\n" +
                        "Mteja anapokuomba kazi, utapata ujumbe. Pia unaweza kutafuta kazi: search_jobs"
            )
        } catch (e: Exception) {
            Timber.e(e, "Register worker failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // POST JOB — Customer requests a service
    // ──────────────────────────────────────────────

    private suspend fun postJob(params: Map<String, String>): ToolResult {
        val customerPhone = params["customer_phone"]
            ?: return ToolResult.error(name, "Customer phone required. Try: customer_phone=0712345678", "MISSING_PHONE")
        val skill = params["skill"]
            ?: return ToolResult.error(name, "Skill required. Try: skill=fundi_tiles. Aina: fundi_umeme, fundi_mashine, househelp, mechanic, n.k.", "MISSING_SKILL")
        val title = params["title"]
            ?: return ToolResult.error(name, "Job title required. Try: title='Kuweka tiles nyumba'", "MISSING_TITLE")
        val location = params["location"]
            ?: return ToolResult.error(name, "Location required (area name). Try: location=Westlands", "MISSING_LOCATION")
        val description = params["description"]
        val priceRange = params["price_range"]
        val date = parseDate(params["date"])
        val time = parseTime(params["time"])

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()
            val jobId = "JOB_${now}_${customerPhone.takeLast(4)}"

            db.execSQL(
                """INSERT INTO jobs (job_id, customer_phone, skill, title, description, location_area,
                   price_range, preferred_date, preferred_time, status, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)""",
                arrayOf(jobId, customerPhone, skill, title, description, location, priceRange, date, time, now, now)
            )

            // Cache for offline matching
            cacheJobForOffline(db, jobId, skill, location, priceRange, now)

            val skillLabel = skillToSwahili(skill)
            val message = buildString {
                appendLine("✅ Kazi imepostwa!")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📋 *$title*")
                appendLine("🔧 Aina: $skillLabel")
                appendLine("📍 Eneo: $location")
                if (priceRange != null) appendLine("💰 Budget: KES $priceRange")
                if (date != null) appendLine("📅 Tarehe: $date")
                if (time != null) appendLine("⏰ Saa: $time")
                appendLine()
                appendLine("🆔 Job ID: $jobId")
                appendLine()
                appendLine("Workers wa $skillLabel hapa $location watapata taarifa. Subiri mawasiliano!")
            }

            return ToolResult.success(
                name,
                data = mapOf("job_id" to jobId, "skill" to skill, "location" to location, "status" to "open"),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Post job failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SEARCH JOBS — Worker finds available jobs
    // ──────────────────────────────────────────────

    private fun searchJobs(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
        val skill = params["skill"]
        val location = params["location"]
        val radiusKm = params["radius_km"]?.toIntOrNull() ?: 5
        val limit = params["limit"]?.toIntOrNull() ?: 10

        val db = dbHelper.readableDatabase
        try {
            // If worker is registered, auto-detect their skill and location
            val workerSkill = skill ?: if (workerId != null) getWorkerSkill(db, workerId) else null
            val workerLocation = location ?: if (workerId != null) getWorkerLocation(db, workerId) else null

            val results = queryJobs(db, workerSkill, workerLocation, "open", limit)

            if (results.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📋 Hakuna kazi mpya${if (workerSkill != null) " za ${skillToSwahili(workerSkill)}" else ""}${if (workerLocation != null) " hapa $workerLocation" else ""} kwa sasa.\n\n" +
                            "Kazi mpya huja kila siku. Angalia tena baadaye au ongeza skill zako: register_worker"
                )
            }

            val output = buildString {
                appendLine("📋 *Kazi Zinazopatikana*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                results.forEachIndexed { i, job ->
                    val jobSkill = job["skill"]?.toString() ?: ""
                    val jobLocation = job["location_area"]?.toString() ?: ""
                    val price = job["price_range"]?.toString()
                    val date = job["preferred_date"]?.toString()
                    val time = job["preferred_time"]?.toString()
                    val createdAt = (job["created_at"] as? Number)?.toLong() ?: 0
                    val age = getTimeAgo(createdAt)

                    appendLine("${i + 1}. *${job["title"]}*")
                    appendLine("   🔧 ${skillToSwahili(jobSkill)}")
                    appendLine("   📍 $jobLocation")
                    if (price != null) appendLine("   💰 KES $price")
                    if (date != null) appendLine("   📅 $date")
                    if (time != null) appendLine("   ⏰ $time")
                    appendLine("   🕐 $age")
                    appendLine("   🆔 ${job["job_id"]}")
                    appendLine()
                }

                appendLine("Kupokea kazi: accept_job job_id=JINA_LA_JOB")
                if (workerId == null) {
                    appendLine()
                    appendLine("💡 Jisajili kupata kazi zaidi: register_worker worker_id=07XXXX skill=fundi_umeme location=EneoLako")
                }
            }

            return ToolResult.success(name, data = results, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "Search jobs failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SEARCH WORKERS — Customer finds available workers
    // ──────────────────────────────────────────────

    private fun searchWorkers(params: Map<String, String>): ToolResult {
        val skill = params["skill"]
            ?: return ToolResult.error(name, "Skill required. Try: skill=fundi_tiles", "MISSING_SKILL")
        val location = params["location"]
        val limit = params["limit"]?.toIntOrNull() ?: 10

        val db = dbHelper.readableDatabase
        try {
            val results = queryWorkers(db, skill, location, limit)

            if (results.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🔍 Hakuna ${skillToSwahili(skill)} waliopatikana${if (location != null) " hapa $location" else ""}.\n\n" +
                            "Jaribu eneo jingine au post kazi yako: post_job"
                )
            }

            val output = buildString {
                appendLine("🔍 *${skillToSwahili(skill)} Waliopatikana${if (location != null) " — $location" else ""}*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                results.forEachIndexed { i, worker ->
                    val rating = (worker["avg_rating"] as? Number)?.toDouble() ?: 0.0
                    val completedJobs = (worker["completed_jobs"] as? Number)?.toInt() ?: 0
                    val price = worker["price_range"]?.toString()
                    val desc = worker["description"]?.toString()
                    val isAvailable = (worker["is_available"] as? Number)?.toInt() == 1

                    val ratingStars = if (rating > 0) "⭐ ${"%.1f".format(rating)}/5" else "⭐ Bado haijaratediwa"
                    val availEmoji = if (isAvailable) "🟢" else "🔴"

                    appendLine("${i + 1}. $availEmoji *${worker["worker_id"]}*")
                    appendLine("   $ratingStars ($completedJobs kazi zilizokamilika)")
                    appendLine("   📍 ${worker["location_area"]}")
                    if (price != null) appendLine("   💰 KES $price")
                    if (desc != null) appendLine("   📝 $desc")
                    appendLine()
                }

                appendLine("Wasiliana na worker moja kwa moja kwa namba zao za simu.")
                appendLine("💡 Usikubali kulipa kabla ya kazi kuanza!")
            }

            return ToolResult.success(name, data = results, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "Search workers failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ACCEPT JOB — Worker accepts a job
    // ──────────────────────────────────────────────

    private suspend fun acceptJob(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required. Try: worker_id=0712345678", "MISSING_WORKER_ID")
        val jobId = params["job_id"]
            ?: return ToolResult.error(name, "Job ID required. Try: job_id=JOB_1234567890_5678", "MISSING_JOB_ID")

        val db = dbHelper.writableDatabase
        try {
            // Check job exists and is open
            val job = getJobById(db, jobId)
                ?: return ToolResult.error(name, "Kazi '$jobId' haikupatikana.", "JOB_NOT_FOUND")

            if (job["status"] != "open") {
                return ToolResult.error(name, "Kazi hii tayari imepokelewa na worker mwingine.", "JOB_NOT_OPEN")
            }

            val now = System.currentTimeMillis()
            db.execSQL(
                """UPDATE jobs SET status = 'accepted', accepted_by = ?, accepted_at = ?, updated_at = ?
                   WHERE job_id = ?""",
                arrayOf(workerId, now, now, jobId)
            )

            // Track the match
            db.execSQL(
                """INSERT INTO job_matches (job_id, worker_id, customer_phone, skill, location_area, matched_at)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                arrayOf(jobId, workerId, job["customer_phone"], job["skill"], job["location_area"], now)
            )

            // Gamification: points for accepting a job
            gamificationEngine.addPoints(mapOf("action_type" to "accept_job"))

            val title = job["title"]?.toString() ?: "Kazi"
            val customer = job["customer_phone"]?.toString() ?: ""

            return ToolResult.success(
                name,
                data = mapOf("job_id" to jobId, "worker_id" to workerId, "status" to "accepted"),
                message = "✅ Umepokea kazi: *$title*\n\n" +
                        "📱 Mteja: $customer\n" +
                        "📞 Wasiliana na mteja kujadili maelezo.\n\n" +
                        "Baada ya kumaliza: complete_job job_id=$jobId"
            )
        } catch (e: Exception) {
            Timber.e(e, "Accept job failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // COMPLETE JOB — Mark job as done
    // ──────────────────────────────────────────────

    private suspend fun completeJob(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")
        val jobId = params["job_id"]
            ?: return ToolResult.error(name, "Job ID required", "MISSING_JOB_ID")

        val db = dbHelper.writableDatabase
        try {
            val job = getJobById(db, jobId)
                ?: return ToolResult.error(name, "Kazi '$jobId' haikupatikana.", "JOB_NOT_FOUND")

            if (job["status"] != "accepted") {
                return ToolResult.error(name, "Kazi haijapokelewa bado.", "JOB_NOT_ACCEPTED")
            }

            val now = System.currentTimeMillis()
            db.execSQL(
                """UPDATE jobs SET status = 'completed', completed_at = ?, updated_at = ?
                   WHERE job_id = ?""",
                arrayOf(now, now, jobId)
            )

            // Update worker stats
            db.execSQL(
                """UPDATE workers SET completed_jobs = completed_jobs + 1, updated_at = ?
                   WHERE worker_id = ?""",
                arrayOf(now, workerId)
            )

            // Gamification: significant points for completing a job
            gamificationEngine.addPoints(mapOf("action_type" to "complete_job"))

            val title = job["title"]?.toString() ?: "Kazi"

            return ToolResult.success(
                name,
                data = mapOf("job_id" to jobId, "worker_id" to workerId, "status" to "completed"),
                message = "🎉 Kazi imekamilika: *$title*\n\n" +
                        "Umepata points! Endelea kupokea kazi zaidi.\n" +
                        "📊 Angalia stats zako: stats worker_id=$workerId"
            )
        } catch (e: Exception) {
            Timber.e(e, "Complete job failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // MY JOBS — Worker views their jobs
    // ──────────────────────────────────────────────

    private fun myJobs(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")
        val statusFilter = params["status"]
        val limit = params["limit"]?.toIntOrNull() ?: 20

        val db = dbHelper.readableDatabase
        try {
            val results = queryWorkerJobs(db, workerId, statusFilter, limit)

            if (results.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📋 Hakuna kazi${if (statusFilter != null) " za '$statusFilter'" else ""} kwa worker $workerId.\n\nTafuta kazi: search_jobs"
                )
            }

            val statusEmoji = mapOf("open" to "🟡", "accepted" to "🔵", "completed" to "✅", "cancelled" to "❌")

            val output = buildString {
                appendLine("📋 *Kazi Zako*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                results.forEachIndexed { i, job ->
                    val status = job["status"]?.toString() ?: "unknown"
                    val emoji = statusEmoji[status] ?: "⚪"
                    val createdAt = (job["created_at"] as? Number)?.toLong() ?: 0
                    val age = getTimeAgo(createdAt)

                    appendLine("${i + 1}. $emoji *${job["title"]}*")
                    appendLine("   🔧 ${skillToSwahili(job["skill"]?.toString() ?: "")}")
                    appendLine("   📍 ${job["location_area"]}")
                    val price = job["price_range"]?.toString()
                    if (price != null) appendLine("   💰 KES $price")
                    appendLine("   📊 Hali: ${statusToSwahili(status)}")
                    appendLine("   🕐 $age")
                    appendLine("   🆔 ${job["job_id"]}")
                    appendLine()
                }
            }

            return ToolResult.success(name, data = results, message = output.trim())
        } catch (e: Exception) {
            Timber.e(e, "My jobs failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // STATS — Worker job statistics
    // ──────────────────────────────────────────────

    private fun getStats(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")

        val db = dbHelper.readableDatabase
        try {
            val worker = getWorkerById(db, workerId)
                ?: return ToolResult.error(name, "Worker '$workerId' hajajisajili. register_worker kwanza.", "WORKER_NOT_FOUND")

            val totalJobs = (worker["completed_jobs"] as? Number)?.toInt() ?: 0
            val avgRating = (worker["avg_rating"] as? Number)?.toDouble() ?: 0.0
            val skills = worker["skills"]?.toString() ?: ""
            val location = worker["location_area"]?.toString() ?: ""
            val isAvailable = (worker["is_available"] as? Number)?.toInt() == 1
            val priceRange = worker["price_range"]?.toString()

            // Count active jobs
            val activeJobs = countJobsByStatus(db, workerId, "accepted")
            val openJobs = countJobsByStatus(db, workerId, "open")

            val ratingStars = if (avgRating > 0) "⭐ ${"%.1f".format(avgRating)}/5" else "⭐ Bado haijaratediwa"

            val message = buildString {
                appendLine("📊 *Stats za Worker: $workerId*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("🔧 Skills: ${skillToSwahili(skills)}")
                appendLine("📍 Eneo: $location")
                appendLine("🟢 Available: ${if (isAvailable) "Ndiyo" else "Hapana"}")
                if (priceRange != null) appendLine("💰 Bei: KES $priceRange")
                appendLine()
                appendLine("📈 *Utendaji:*")
                appendLine("   ✅ Kazi zilizokamilika: $totalJobs")
                appendLine("   🔵 Kazi za sasa: $activeJobs")
                appendLine("   $ratingStars")
                appendLine()
                appendLine("💡 Pata kazi zaidi: search_jobs")
            }

            return ToolResult.success(
                name,
                data = mapOf(
                    "worker_id" to workerId,
                    "completed_jobs" to totalJobs,
                    "active_jobs" to activeJobs,
                    "avg_rating" to avgRating,
                    "skills" to skills,
                    "location" to location
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Get stats failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSING
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili/English voice input into actions.
     *
     * Patterns:
     *  - "Ninahitaji fundi wa kuweka tiles" → post_job (skill=fundi_tiles)
     *  - "Kuna kazi gani karibu?" → search_jobs
     *  - "Nataka kazi ya umeme" → search_jobs (skill=fundi_umeme)
     *  - "Nimekamilisha kazi" → complete_job
     *  - "Nataka househelp" → search_workers (skill=househelp)
     */
    private suspend fun parseVoiceInput(voiceInput: String, params: Map<String, String>): ToolResult {
        val input = voiceInput.trim().lowercase()

        // Detect if this is a customer looking for a worker
        val customerPatterns = listOf(
            "ninahitaji", "nataka", "nahitaji", "natafuta", "najitaji",
            "i need", "looking for", "want", "find me"
        )
        val isCustomerRequest = customerPatterns.any { input.contains(it) }

        // Detect if this is a worker looking for jobs
        val workerPatterns = listOf(
            "kazi gani", "kuna kazi", "natafuta kazi", "jobs", "any work",
            "kazi yoyote", "nipate kazi"
        )
        val isWorkerSearch = workerPatterns.any { input.contains(it) }

        // Extract skill from voice
        val detectedSkill = extractSkill(input)

        // Completion patterns
        if (input.contains("nimemaliza") || input.contains("nimekamilisha") || input.contains("completed") || input.contains("done")) {
            val jobId = params["job_id"]
                ?: return ToolResult.error(name, "Job ID required. Try: job_id=JOB_XXX", "MISSING_JOB_ID")
            val workerId = params["worker_id"]
                ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")
            return completeJob(mapOf("worker_id" to workerId, "job_id" to jobId))
        }

        if (isCustomerRequest && detectedSkill != null) {
            // Customer posting a job
            val location = params["location"] ?: extractLocation(input) ?: ""
            return postJob(mapOf(
                "customer_phone" to (params["customer_phone"] ?: "unknown"),
                "skill" to detectedSkill,
                "title" to voiceInput,
                "location" to location
            ))
        }

        if (isWorkerSearch || input.contains("search_jobs")) {
            return searchJobs(mapOf(
                "worker_id" to (params["worker_id"] ?: ""),
                "skill" to (detectedSkill ?: ""),
                "location" to (params["location"] ?: extractLocation(input) ?: "")
            ))
        }

        if (detectedSkill != null && !isCustomerRequest) {
            // Could be either — search for jobs matching the skill
            return searchJobs(mapOf(
                "worker_id" to (params["worker_id"] ?: ""),
                "skill" to detectedSkill
            ))
        }

        // Default: show available jobs
        return searchJobs(params)
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun findWorkerId(db: SQLiteDatabase, workerId: String): Long? {
        val cursor = db.rawQuery("SELECT id FROM workers WHERE worker_id = ?", arrayOf(workerId))
        cursor.use { c -> return if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun getWorkerById(db: SQLiteDatabase, workerId: String): Map<String, Any?>? {
        val cursor = db.rawQuery("SELECT * FROM workers WHERE worker_id = ?", arrayOf(workerId))
        cursor.use { c ->
            return if (c.moveToFirst()) cursorToMap(c) else null
        }
    }

    private fun getWorkerSkill(db: SQLiteDatabase, workerId: String): String? {
        val cursor = db.rawQuery("SELECT skills FROM workers WHERE worker_id = ?", arrayOf(workerId))
        cursor.use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun getWorkerLocation(db: SQLiteDatabase, workerId: String): String? {
        val cursor = db.rawQuery("SELECT location_area FROM workers WHERE worker_id = ?", arrayOf(workerId))
        cursor.use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun getJobById(db: SQLiteDatabase, jobId: String): Map<String, Any?>? {
        val cursor = db.rawQuery("SELECT * FROM jobs WHERE job_id = ?", arrayOf(jobId))
        cursor.use { c -> return if (c.moveToFirst()) cursorToMap(c) else null }
    }

    private fun queryJobs(db: SQLiteDatabase, skill: String?, location: String?, status: String?, limit: Int): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val query = buildString {
            append("SELECT * FROM jobs WHERE 1=1")
            if (status != null) append(" AND status = ?")
            if (skill != null) append(" AND skill = ?")
            if (location != null) append(" AND location_area LIKE ?")
            append(" ORDER BY created_at DESC LIMIT ?")
        }

        val args = mutableListOf<String>()
        if (status != null) args.add(status)
        if (skill != null) args.add(skill)
        if (location != null) args.add("%$location%")
        args.add(limit.toString())

        val cursor = db.rawQuery(query, args.toTypedArray())
        cursor.use { c ->
            while (c.moveToNext()) results.add(cursorToMap(c))
        }
        return results
    }

    private fun queryWorkers(db: SQLiteDatabase, skill: String, location: String?, limit: Int): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val query = buildString {
            append("SELECT * FROM workers WHERE is_available = 1 AND skills LIKE ?")
            if (location != null) append(" AND location_area LIKE ?")
            append(" ORDER BY avg_rating DESC, completed_jobs DESC LIMIT ?")
        }

        val args = mutableListOf("%$skill%")
        if (location != null) args.add("%$location%")
        args.add(limit.toString())

        val cursor = db.rawQuery(query, args.toTypedArray())
        cursor.use { c ->
            while (c.moveToNext()) results.add(cursorToMap(c))
        }
        return results
    }

    private fun queryWorkerJobs(db: SQLiteDatabase, workerId: String, status: String?, limit: Int): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val query = buildString {
            append("""SELECT j.* FROM jobs j
                      LEFT JOIN job_matches m ON m.job_id = j.job_id
                      WHERE m.worker_id = ?""")
            if (status != null) append(" AND j.status = ?")
            append(" ORDER BY j.created_at DESC LIMIT ?")
        }

        val args = mutableListOf(workerId)
        if (status != null) args.add(status)
        args.add(limit.toString())

        val cursor = db.rawQuery(query, args.toTypedArray())
        cursor.use { c ->
            while (c.moveToNext()) results.add(cursorToMap(c))
        }
        return results
    }

    private fun countJobsByStatus(db: SQLiteDatabase, workerId: String, status: String): Int {
        val cursor = db.rawQuery(
            """SELECT COUNT(*) FROM jobs j
               LEFT JOIN job_matches m ON m.job_id = j.job_id
               WHERE m.worker_id = ? AND j.status = ?""",
            arrayOf(workerId, status)
        )
        cursor.use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun cacheJobForOffline(db: SQLiteDatabase, jobId: String, skill: String, location: String, priceRange: String?, timestamp: Long) {
        try {
            db.execSQL(
                """INSERT OR REPLACE INTO offline_job_cache (job_id, skill, location_area, price_range, cached_at)
                   VALUES (?, ?, ?, ?, ?)""",
                arrayOf(jobId, skill, location, priceRange, timestamp)
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to cache job for offline")
        }
    }

    private fun extractSkill(input: String): String? {
        val skillMap = mapOf(
            "fundi wa umeme" to "fundi_umeme", "umeme" to "fundi_umeme", "electrician" to "fundi_umeme", "electricity" to "fundi_umeme",
            "fundi wa mashine" to "fundi_mashine", "mashine" to "fundi_mashine", "machine" to "fundi_mashine",
            "tiles" to "fundi_tiles", "kuweka tiles" to "fundi_tiles",
            "painting" to "fundi_painting", "painty" to "fundi_painting", "rangi" to "fundi_painting",
            "plumber" to "fundi_plumber", "maji" to "fundi_plumber", "plumbing" to "fundi_plumber",
            "carpenter" to "fundi_carpenter", "seremala" to "fundi_carpenter", "wood" to "fundi_carpenter",
            "welder" to "fundi_welder", "vyuma" to "fundi_welder", "welding" to "fundi_welder",
            "househelp" to "househelp", "msaidizi" to "househelp", "maid" to "househelp", "fua" to "househelp", "kuosha" to "househelp",
            "cook" to "cook", "mpishi" to "cook", "chef" to "cook",
            "gardener" to "gardener", "bustani" to "gardener",
            "dj" to "entertainer_dj", "mc" to "entertainer_mc", "muziki" to "entertainer_musician",
            "cleaner" to "cleaner", "usafi" to "cleaner",
            "driver" to "driver", "dereva" to "driver",
            "nanny" to "nanny", "mtoto" to "nanny",
            "watchman" to "watchman", "ulinzi" to "watchman",
            "tailor" to "tailor", "shona" to "tailor", "mshoni" to "tailor",
            "barber" to "barber", "kinyozi" to "barber",
            "salonist" to "salonist", "salon" to "salonist",
            "mechanic" to "mechanic", "fundi gari" to "mechanic",
            "mason" to "mason", "mjengo" to "mason", "ujenzi" to "mason"
        )

        for ((keyword, skill) in skillMap) {
            if (input.contains(keyword)) return skill
        }
        return null
    }

    private fun extractLocation(input: String): String? {
        // Common location patterns in Swahili voice input
        val patterns = listOf(
            Regex("""(?:hapa|karibu na|katika|eneo la|area)\s+([A-Za-z\s]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:nairobi|mombasa|kisumu|nakuru|eldoret|thika|machakos|kiambu|migori|nyeri|meru)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(input)?.let { match ->
                return match.groupValues.getOrElse(1) { match.value }.trim().ifBlank { match.value.trim() }
            }
        }
        return null
    }

    private fun parseDate(dateStr: String?): String? {
        if (dateStr == null) return null
        return when (dateStr.lowercase()) {
            "leo" -> dateFormat.format(System.currentTimeMillis())
            "kesho" -> dateFormat.format(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1))
            "kesho kutwa" -> dateFormat.format(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2))
            else -> dateStr // Assume yyyy-MM-dd format
        }
    }

    private fun parseTime(timeStr: String?): String? {
        if (timeStr == null) return null
        return when (timeStr.lowercase()) {
            "asubuhi" -> "08:00"
            "mchana" -> "13:00"
            "jioni" -> "17:00"
            "usiku" -> "20:00"
            else -> timeStr
        }
    }

    private fun skillToSwahili(skill: String): String = when (skill) {
        "fundi_umeme" -> "Fundi wa Umeme"
        "fundi_mashine" -> "Fundi wa Mashine"
        "fundi_tiles" -> "Fundi wa Tiles"
        "fundi_painting" -> "Fundi wa Painting"
        "fundi_plumber" -> "Fundi wa Plumber"
        "fundi_carpenter" -> "Fundi wa Seremala"
        "fundi_welder" -> "Fundi wa Welder"
        "househelp" -> "Msaidizi wa Nyumbani"
        "cook" -> "Mpishi"
        "gardener" -> "Mtunzi wa Bustani"
        "entertainer_dj" -> "DJ"
        "entertainer_mc" -> "MC"
        "entertainer_musician" -> "Mwanamuziki"
        "cleaner" -> "Msafi"
        "driver" -> "Dereva"
        "nanny" -> "Nanny"
        "watchman" -> "Mlinzi"
        "tailor" -> "Mshoni"
        "barber" -> "Mtegemaji Kinyozi"
        "salonist" -> "Mtaalam wa Salon"
        "mechanic" -> "Fundi wa Gari"
        "mason" -> "Fundi wa Ujenzi"
        else -> skill.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun statusToSwahili(status: String): String = when (status) {
        "open" -> "Inapatikana"
        "accepted" -> "Imepokelewa"
        "completed" -> "Imekamilika"
        "cancelled" -> "Imefutwa"
        else -> status
    }

    private fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Sasa hivi"
            diff < TimeUnit.HOURS.toMillis(1) -> "Dakika ${TimeUnit.MILLISECONDS.toMinutes(diff)} zilizopita"
            diff < TimeUnit.DAYS.toMillis(1) -> "Saa ${TimeUnit.MILLISECONDS.toHours(diff)} zilizopita"
            diff < TimeUnit.DAYS.toMillis(7) -> "Siku ${TimeUnit.MILLISECONDS.toDays(diff)} zilizopita"
            else -> dateFormat.format(timestamp)
        }
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
// SQLiteOpenHelper — Job matching database
// ──────────────────────────────────────────────

class JobDbHelper(context: Context) : SQLiteOpenHelper(
    context, "jobs.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        // Workers registry
        db.execSQL("""
            CREATE TABLE workers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL UNIQUE,
                skills TEXT NOT NULL,
                location_area TEXT NOT NULL,
                price_range TEXT,
                description TEXT,
                avg_rating REAL DEFAULT 0,
                completed_jobs INTEGER DEFAULT 0,
                is_available INTEGER DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        // Jobs posted by customers
        db.execSQL("""
            CREATE TABLE jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL UNIQUE,
                customer_phone TEXT NOT NULL,
                skill TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT,
                location_area TEXT NOT NULL,
                price_range TEXT,
                preferred_date TEXT,
                preferred_time TEXT,
                status TEXT DEFAULT 'open',
                accepted_by TEXT,
                accepted_at INTEGER,
                completed_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        // Match history
        db.execSQL("""
            CREATE TABLE job_matches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL,
                worker_id TEXT NOT NULL,
                customer_phone TEXT,
                skill TEXT,
                location_area TEXT,
                matched_at INTEGER NOT NULL,
                FOREIGN KEY (job_id) REFERENCES jobs(job_id)
            )
        """)

        // Offline cache for jobs
        db.execSQL("""
            CREATE TABLE offline_job_cache (
                job_id TEXT PRIMARY KEY,
                skill TEXT NOT NULL,
                location_area TEXT NOT NULL,
                price_range TEXT,
                cached_at INTEGER NOT NULL
            )
        """)

        // Indexes
        db.execSQL("CREATE INDEX idx_workers_skill ON workers(skills)")
        db.execSQL("CREATE INDEX idx_workers_location ON workers(location_area)")
        db.execSQL("CREATE INDEX idx_workers_available ON workers(is_available)")
        db.execSQL("CREATE INDEX idx_workers_rating ON workers(avg_rating DESC)")
        db.execSQL("CREATE INDEX idx_jobs_skill ON jobs(skill)")
        db.execSQL("CREATE INDEX idx_jobs_location ON jobs(location_area)")
        db.execSQL("CREATE INDEX idx_jobs_status ON jobs(status)")
        db.execSQL("CREATE INDEX idx_jobs_created ON jobs(created_at DESC)")
        db.execSQL("CREATE INDEX idx_matches_worker ON job_matches(worker_id)")
        db.execSQL("CREATE INDEX idx_matches_job ON job_matches(job_id)")
        db.execSQL("CREATE INDEX idx_cache_skill ON offline_job_cache(skill)")
        db.execSQL("CREATE INDEX idx_cache_location ON offline_job_cache(location_area)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }
}
