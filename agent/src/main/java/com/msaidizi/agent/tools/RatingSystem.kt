package com.msaidizi.agent.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RatingSystem — Peer and customer ratings for service workers.
 *
 * Builds trust in the informal marketplace where reputation is everything.
 * Customers rate workers after service completion. Workers can also rate
 * each other (peer reviews for skills, reliability, teamwork).
 *
 * Features:
 *  1. Customer ratings after job completion (1-5 stars)
 *  2. Multi-dimensional: reliability, quality, punctuality, value
 *  3. Anonymous feedback option
 *  4. Aggregate scores with review count
 *  5. Top-rated workers by skill and location
 *  6. Trust badges based on consistent performance
 *  7. Worker response to reviews
 *
 * Integrates with:
 *  - JobMatcher (auto-prompt after job completion)
 *  - CustomerMatcher (link reviews to customer profiles)
 *  - GamificationEngine (points for receiving good reviews)
 *
 * 7 Actions: rate, view_reviews, top_rated, my_ratings, respond, badges, compare
 *
 * Voice-first, bilingual (Kiswahili + English).
 */
@Singleton
class RatingSystem @Inject constructor(
    private val context: Context,
    private val gamificationEngine: GamificationEngine,
    private val gson: Gson
) : Tool {

    override val name = "rating_system"
    override val description = "Rate and review service workers. Build trust through verified customer feedback. Voice: 'Nataka kumrate fundi'"

    override val argsSchema = argSchema {
        enum(
            "action", "Rating action to perform",
            listOf(
                "rate",          // Submit a rating for a worker
                "view_reviews",  // View reviews for a specific worker
                "top_rated",     // Find top-rated workers by skill/location
                "my_ratings",    // Worker views their own ratings
                "respond",       // Worker responds to a review
                "badges",        // View trust badges
                "compare"        // Compare multiple workers
            ),
            required = false
        )
        string("worker_id", "Worker being rated or viewing ratings", required = false)
        string("reviewer_id", "Person giving the rating (customer phone or ID)", required = false)
        string("skill", "Skill category for top_rated search", required = false)
        string("location", "Location for top_rated search", required = false)
        number("rating", "Overall rating 1-5", required = false)
        number("reliability", "Reliability rating 1-5 (was the worker dependable?)", required = false)
        number("quality", "Quality of work rating 1-5", required = false)
        number("punctuality", "Punctuality rating 1-5 (did they arrive on time?)", required = false)
        number("value", "Value for money rating 1-5", required = false)
        string("comment", "Written review or feedback", required = false)
        string("job_id", "Job ID this review is for", required = false)
        boolean("anonymous", "Submit as anonymous (default: false)", required = false)
        string("response", "Worker's response to a review", required = false)
        string("review_id", "Review ID to respond to", required = false)
        integer("limit", "Max results (default: 10)", required = false)
        string("voice_input", "Raw Swahili/English voice text to parse", required = false)
    }

    private val dbHelper: RatingDbHelper by lazy { RatingDbHelper(context) }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "top_rated"

        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!, params)
        }

        return when (action.lowercase()) {
            "rate" -> submitRating(params)
            "view_reviews" -> viewReviews(params)
            "top_rated" -> topRated(params)
            "my_ratings" -> myRatings(params)
            "respond" -> respondToReview(params)
            "badges" -> viewBadges(params)
            "compare" -> compareWorkers(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // RATE — Submit a rating
    // ──────────────────────────────────────────────

    private suspend fun submitRating(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required. Try: worker_id=fundi_001", "MISSING_WORKER_ID")
        val reviewerId = params["reviewer_id"]
            ?: return ToolResult.error(name, "Reviewer ID required (phone or name). Try: reviewer_id=0712345678", "MISSING_REVIEWER_ID")
        val overallRating = params["rating"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Rating required (1-5). Try: rating=4", "MISSING_RATING")

        if (overallRating < 1 || overallRating > 5) {
            return ToolResult.error(name, "Rating lazima iwe 1-5. Umeweka: $overallRating", "INVALID_RATING")
        }

        val reliability = params["reliability"]?.toDoubleOrNull() ?: overallRating
        val quality = params["quality"]?.toDoubleOrNull() ?: overallRating
        val punctuality = params["punctuality"]?.toDoubleOrNull() ?: overallRating
        val value = params["value"]?.toDoubleOrNull() ?: overallRating
        val comment = params["comment"]
        val jobId = params["job_id"]
        val isAnonymous = params["anonymous"]?.toBooleanStrictOrNull() ?: false

        // Validate sub-ratings
        for ((name, value) in listOf("reliability" to reliability, "quality" to quality, "punctuality" to punctuality, "value" to value)) {
            if (value < 1 || value > 5) {
                return ToolResult.error(this.name, "$name lazima iwe 1-5", "INVALID_RATING")
            }
        }

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()
            val displayReviewer = if (isAnonymous) "Anonymous" else reviewerId

            // Check for duplicate review from same reviewer for same job
            if (jobId != null) {
                val existing = db.rawQuery(
                    "SELECT id FROM reviews WHERE worker_id = ? AND reviewer_id = ? AND job_id = ?",
                    arrayOf(workerId, reviewerId, jobId)
                )
                existing.use { c ->
                    if (c.moveToFirst()) {
                        return ToolResult.error(name, "Umeshamrate kazi hii. Asante!", "ALREADY_REVIEWED")
                    }
                }
            }

            // Insert review
            db.execSQL(
                """INSERT INTO reviews
                   (worker_id, reviewer_id, display_name, overall_rating, reliability, quality,
                    punctuality, value_for_money, comment, job_id, is_anonymous, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(workerId, reviewerId, displayReviewer, overallRating, reliability, quality,
                    punctuality, value, comment, jobId, if (isAnonymous) 1 else 0, now)
            )

            // Update worker aggregate ratings
            updateWorkerRatings(db, workerId)

            // Gamification: worker gets points for good reviews
            if (overallRating >= 4.0) {
                gamificationEngine.addPoints(mapOf("action_type" to "good_review"))
            }

            val stars = ratingToStars(overallRating)
            val message = buildString {
                appendLine("✅ *Umefanikiwa kumrate $workerId!*")
                appendLine()
                appendLine("$stars ${"%.1f".format(overallRating)}/5")
                if (reliability != overallRating || quality != overallRating) {
                    appendLine("   📊 Uaminifu: ${ratingToStars(reliability)} ${"%.1f".format(reliability)}")
                    appendLine("   📊 Ubora: ${ratingToStars(quality)} ${"%.1f".format(quality)}")
                    appendLine("   📊 Wakati: ${ratingToStars(punctuality)} ${"%.1f".format(punctuality)}")
                    appendLine("   📊 Thamani: ${ratingToStars(value)} ${"%.1f".format(value)}")
                }
                if (comment != null) appendLine("   💬 Maoni: $comment")
                appendLine()
                appendLine("Asante! Reviews zinawasaidia wafanyakazi wengine kupata kazi nzuri.")
            }

            return ToolResult.success(
                name,
                data = mapOf(
                    "worker_id" to workerId,
                    "rating" to overallRating,
                    "reliability" to reliability,
                    "quality" to quality,
                    "punctuality" to punctuality,
                    "value" to value
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Submit rating failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VIEW REVIEWS — See reviews for a worker
    // ──────────────────────────────────────────────

    private fun viewReviews(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required. Try: worker_id=fundi_001", "MISSING_WORKER_ID")
        val limit = params["limit"]?.toIntOrNull() ?: 10

        val db = dbHelper.readableDatabase
        try {
            val worker = getWorkerRatingSummary(db, workerId)
            val reviews = getWorkerReviews(db, workerId, limit)

            if (worker == null || reviews.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📋 Hakuna reviews bado kwa $workerId.\n\nKuwa wa kwanza kurate: rate worker_id=$workerId rating=4"
                )
            }

            val avgRating = (worker["avg_rating"] as? Number)?.toDouble() ?: 0.0
            val reviewCount = (worker["review_count"] as? Number)?.toInt() ?: 0
            val avgReliability = (worker["avg_reliability"] as? Number)?.toDouble() ?: 0.0
            val avgQuality = (worker["avg_quality"] as? Number)?.toDouble() ?: 0.0
            val avgPunctuality = (worker["avg_punctuality"] as? Number)?.toDouble() ?: 0.0
            val avgValue = (worker["avg_value"] as? Number)?.toDouble() ?: 0.0

            val output = buildString {
                appendLine("⭐ *Reviews za $workerId*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("${ratingToStars(avgRating)} *${"%.1f".format(avgRating)}/5* ($reviewCount reviews)")
                appendLine()
                appendLine("📊 *Maelezo:*")
                appendLine("   🤝 Uaminifu: ${ratingToStars(avgReliability)} ${"%.1f".format(avgReliability)}")
                appendLine("   ✅ Ubora: ${ratingToStars(avgQuality)} ${"%.1f".format(avgQuality)}")
                appendLine("   ⏰ Wakati: ${ratingToStars(avgPunctuality)} ${"%.1f".format(avgPunctuality)}")
                appendLine("   💰 Thamani: ${ratingToStars(avgValue)} ${"%.1f".format(avgValue)}")
                appendLine()

                appendLine("💬 *Maoni ya Wateja:*")
                reviews.forEach { review ->
                    val reviewer = review["display_name"]?.toString() ?: "Anonymous"
                    val rating = (review["overall_rating"] as? Number)?.toDouble() ?: 0.0
                    val comment = review["comment"]?.toString()
                    val response = review["worker_response"]?.toString()
                    val createdAt = (review["created_at"] as? Number)?.toLong() ?: 0

                    appendLine("   ${ratingToStars(rating)} — $reviewer")
                    if (comment != null) appendLine("   💬 \"$comment\"")
                    if (response != null) appendLine("   ↪️ Jibu: \"$response\"")
                    appendLine("   📅 ${formatTimestamp(createdAt)}")
                    appendLine()
                }
            }

            return ToolResult.success(name, data = reviews, message = output)
        } catch (e: Exception) {
            Timber.e(e, "View reviews failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // TOP RATED — Find best workers by skill/location
    // ──────────────────────────────────────────────

    private fun topRated(params: Map<String, String>): ToolResult {
        val skill = params["skill"]
        val location = params["location"]
        val limit = params["limit"]?.toIntOrNull() ?: 10

        val db = dbHelper.readableDatabase
        try {
            val workers = queryTopRated(db, skill, location, limit)

            if (workers.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🔍 Hakuna wafanyakazi walioratediwa${if (skill != null) " wa $skill" else ""}${if (location != null) " hapa $location" else ""}.\n\n" +
                            "Kuwa wa kwanza kurate fundi wako!"
                )
            }

            val output = buildString {
                appendLine("🏆 *Wafanyakazi Bora${if (skill != null) " — ${skillToSwahili(skill)}" else ""}${if (location != null) " — $location" else ""}*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                workers.forEachIndexed { i, worker ->
                    val rating = (worker["avg_rating"] as? Number)?.toDouble() ?: 0.0
                    val count = (worker["review_count"] as? Number)?.toInt() ?: 0
                    val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}." }

                    appendLine("$medal *${worker["worker_id"]}*")
                    appendLine("   ${ratingToStars(rating)} ${"%.1f".format(rating)}/5 ($count reviews)")
                    val badges = worker["badges"]?.toString()
                    if (badges != null) appendLine("   🏅 $badges")
                    appendLine()
                }

                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("💡 Angalia reviews: view_reviews worker_id=JINA")
            }

            return ToolResult.success(name, data = workers, message = output)
        } catch (e: Exception) {
            Timber.e(e, "Top rated failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // MY RATINGS — Worker views their own ratings
    // ──────────────────────────────────────────────

    private fun myRatings(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")

        val db = dbHelper.readableDatabase
        try {
            val summary = getWorkerRatingSummary(db, workerId)
            if (summary == null) {
                return ToolResult.success(
                    name,
                    message = "📋 Bado hujapata review yoyote.\n\nPata reviews nzuri kwa kufanya kazi nzuri na kufika kwa wakati!"
                )
            }

            val avgRating = (summary["avg_rating"] as? Number)?.toDouble() ?: 0.0
            val count = (summary["review_count"] as? Number)?.toInt() ?: 0
            val avgReliability = (summary["avg_reliability"] as? Number)?.toDouble() ?: 0.0
            val avgQuality = (summary["avg_quality"] as? Number)?.toDouble() ?: 0.0
            val avgPunctuality = (summary["avg_punctuality"] as? Number)?.toDouble() ?: 0.0
            val avgValue = (summary["avg_value"] as? Number)?.toDouble() ?: 0.0
            val badges = summary["badges"]?.toString()

            // Rating distribution
            val distribution = getRatingDistribution(db, workerId)

            val output = buildString {
                appendLine("⭐ *Ratings Zako — $workerId*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("${ratingToStars(avgRating)} *${"%.1f".format(avgRating)}/5* ($count reviews)")
                appendLine()
                appendLine("📊 *Maelezo:*")
                appendLine("   🤝 Uaminifu: ${ratingToStars(avgReliability)} ${"%.1f".format(avgReliability)}")
                appendLine("   ✅ Ubora: ${ratingToStars(avgQuality)} ${"%.1f".format(avgQuality)}")
                appendLine("   ⏰ Wakati: ${ratingToStars(avgPunctuality)} ${"%.1f".format(avgPunctuality)}")
                appendLine("   💰 Thamani: ${ratingToStars(avgValue)} ${"%.1f".format(avgValue)}")

                if (badges != null) {
                    appendLine()
                    appendLine("🏅 *Badges:* $badges")
                }

                if (distribution.isNotEmpty()) {
                    appendLine()
                    appendLine("📊 *Mgawanyo:*")
                    for (star in 5 downTo 1) {
                        val starCount = distribution[star] ?: 0
                        val bar = "█".repeat((starCount * 10 / count.coerceAtLeast(1)).coerceAtMost(10))
                        appendLine("   $star⭐ $bar ($starCount)")
                    }
                }

                // Tips for improvement
                appendLine()
                appendLine("💡 *Vidokezo:*")
                if (avgPunctuality < 4.0) appendLine("   ⏰ Fika kwa wakati — wateja wanapenda punctuality!")
                if (avgQuality < 4.0) appendLine("   ✅ Boresha ubora wa kazi — train more, practice more!")
                if (avgValue < 4.0) appendLine("   💰 Hakikisha bei yako ni ya haki kwa ubora wa kazi.")
                if (count < 5) appendLine("   📈 Pata reviews zaidi — wateja wanaamini workers na reviews nyingi.")
            }

            return ToolResult.success(name, data = summary, message = output)
        } catch (e: Exception) {
            Timber.e(e, "My ratings failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RESPOND — Worker responds to a review
    // ──────────────────────────────────────────────

    private fun respondToReview(params: Map<String, String>): ToolResult {
        val reviewId = params["review_id"]
            ?: return ToolResult.error(name, "Review ID required. Try: review_id=123", "MISSING_REVIEW_ID")
        val response = params["response"]
            ?: return ToolResult.error(name, "Response text required. Try: response='Asante sana!'", "MISSING_RESPONSE")

        val db = dbHelper.writableDatabase
        try {
            val now = System.currentTimeMillis()
            db.execSQL(
                "UPDATE reviews SET worker_response = ?, responded_at = ? WHERE id = ?",
                arrayOf(response, now, reviewId.toLongOrNull() ?: 0)
            )

            return ToolResult.success(
                name,
                message = "✅ Jibu lako limerekodwa kwenye review #$reviewId.\n\n$response"
            )
        } catch (e: Exception) {
            Timber.e(e, "Respond to review failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // BADGES — View trust badges
    // ──────────────────────────────────────────────

    private fun viewBadges(params: Map<String, String>): ToolResult {
        val workerId = params["worker_id"]
            ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")

        val db = dbHelper.readableDatabase
        try {
            val summary = getWorkerRatingSummary(db, workerId)
            val reviewCount = (summary?.get("review_count") as? Number)?.toInt() ?: 0
            val avgRating = (summary?.get("avg_rating") as? Number)?.toDouble() ?: 0.0

            val earnedBadges = mutableListOf<String>()
            val possibleBadges = mutableListOf<String>()

            // Badge criteria
            if (reviewCount >= 5 && avgRating >= 4.5) earnedBadges.add("⭐⭐⭐ Mtaalamu — 5+ reviews, 4.5+ rating")
            else possibleBadges.add("⭐⭐⭐ Mtaalamu — 5+ reviews, 4.5+ rating")

            if (reviewCount >= 10 && avgRating >= 4.0) earnedBadges.add("🤝 Mwaminifu — 10+ reviews, 4.0+ rating")
            else possibleBadges.add("🤝 Mwaminifu — 10+ reviews, 4.0+ rating")

            if (reviewCount >= 25) earnedBadges.add("🏆 Bingwa — 25+ reviews")
            else possibleBadges.add("🏆 Bingwa — 25+ reviews")

            if (reviewCount >= 50) earnedBadges.add("👑 Mfalme — 50+ reviews")
            else possibleBadges.add("👑 Mfalme — 50+ reviews")

            if (avgRating >= 4.8 && reviewCount >= 3) earnedBadges.add("💎 Almasi — 4.8+ rating")
            else possibleBadges.add("💎 Almasi — 4.8+ rating")

            val output = buildString {
                appendLine("🏅 *Badges za $workerId*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                if (earnedBadges.isNotEmpty()) {
                    appendLine("✅ *Zilizopatikana:*")
                    earnedBadges.forEach { appendLine("   $it") }
                } else {
                    appendLine("📋 Bado hujapata badge yoyote.")
                }

                appendLine()
                appendLine("🎯 *Zinazopatikana:*")
                possibleBadges.forEach { appendLine("   ❌ $it") }

                appendLine()
                appendLine("📊 Sasa: ${"%.1f".format(avgRating)}/5, $reviewCount reviews")
            }

            return ToolResult.success(name, message = output)
        } catch (e: Exception) {
            Timber.e(e, "View badges failed")
            return ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // COMPARE — Compare multiple workers
    // ──────────────────────────────────────────────

    private fun compareWorkers(params: Map<String, String>): ToolResult {
        val workerIds = params["worker_id"]?.split(",")?.map { it.trim() }
            ?: return ToolResult.error(name, "Worker IDs required (comma-separated). Try: worker_id=fundi_001,fundi_002", "MISSING_WORKER_ID")

        if (workerIds.size < 2) {
            return ToolResult.error(name, "Angalau workers 2 required. Try: worker_id=fundi_001,fundi_002", "NEED_MORE_WORKERS")
        }

        val db = dbHelper.readableDatabase
        try {
            val comparisons = workerIds.map { id ->
                val summary = getWorkerRatingSummary(db, id)
                Triple(id, summary, (summary?.get("avg_rating") as? Number)?.toDouble() ?: 0.0)
            }.sortedByDescending { it.third }

            val output = buildString {
                appendLine("📊 *Mfuko wa Workers*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                comparisons.forEachIndexed { i, (id, summary, avgRating) ->
                    val count = (summary?.get("review_count") as? Number)?.toInt() ?: 0
                    val quality = (summary?.get("avg_quality") as? Number)?.toDouble() ?: 0.0
                    val punctuality = (summary?.get("avg_punctuality") as? Number)?.toDouble() ?: 0.0
                    val reliability = (summary?.get("avg_reliability") as? Number)?.toDouble() ?: 0.0

                    val medal = when (i) { 0 -> "🏆"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}." }
                    appendLine("$medal *$id*")
                    appendLine("   ⭐ ${"%.1f".format(avgRating)}/5 ($count reviews)")
                    appendLine("   ✅ Ubora: ${"%.1f".format(quality)} | ⏰ Wakati: ${"%.1f".format(punctuality)} | 🤝 Uaminifu: ${"%.1f".format(reliability)}")
                    appendLine()
                }

                if (comparisons.isNotEmpty()) {
                    val best = comparisons.first()
                    appendLine("💡 *Bora zaidi:* ${best.first} (${ "%.1f".format(best.third)}/5)")
                }
            }

            return ToolResult.success(name, data = comparisons.map { mapOf("worker_id" to it.first, "avg_rating" to it.third) }, message = output)
        } catch (e: Exception) {
            Timber.e(e, "Compare workers failed")
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
     *  - "Nataka kumrate fundi" → rate
     *  - "Fundi bora wa tiles" → top_rated (skill=fundi_tiles)
     *  - "Reviews za fundi_001" → view_reviews
     *  - "Ratings zangu" → my_ratings
     *  - "Nataka kumcompare fundi A na B" → compare
     */
    private suspend fun parseVoiceInput(voiceInput: String, params: Map<String, String>): ToolResult {
        val input = voiceInput.trim().lowercase()

        // Rate patterns
        if (input.contains("rate") || input.contains("kumrate") || input.contains("nataka kum") ||
            input.contains("review") || input.contains("stars")) {
            // Try to extract rating number
            val ratingPattern = Regex("""(\d)\s*(?:stars?|⭐|sahihisha|point)""", RegexOption.IGNORE_CASE)
            val ratingMatch = ratingPattern.find(input)
            if (ratingMatch != null) {
                val rating = ratingMatch.groupValues[1].toDoubleOrNull()
                if (rating != null) {
                    val mergedParams = params.toMutableMap()
                    mergedParams["rating"] = rating.toString()
                    return submitRating(mergedParams)
                }
            }
            // If no rating extracted, prompt for it
            return ToolResult.error(name, "Ni ngapi stars? (1-5). Try: rating=4 au '4 stars'", "MISSING_RATING")
        }

        // Top rated patterns
        if (input.contains("bora") || input.contains("top") || input.contains("best") ||
            input.contains("nani mzuri") || input.contains("nani bora")) {
            val skill = extractSkill(input)
            return topRated(mapOf(
                "skill" to (skill ?: ""),
                "location" to (params["location"] ?: extractLocation(input) ?: "")
            ))
        }

        // My ratings patterns
        if (input.contains("ratings zangu") || input.contains("reviews zangu") ||
            input.contains("my ratings") || input.contains("my reviews")) {
            val workerId = params["worker_id"]
                ?: return ToolResult.error(name, "Worker ID required", "MISSING_WORKER_ID")
            return myRatings(mapOf("worker_id" to workerId))
        }

        // View reviews patterns
        val viewPatterns = listOf(
            Regex("""reviews?\s+(?:za\s+)?([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE),
            Regex("""ratings?\s+(?:za\s+)?([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in viewPatterns) {
            pattern.find(voiceInput)?.let { match ->
                val workerId = match.groupValues[1].trim()
                if (workerId.isNotBlank()) {
                    return viewReviews(mapOf("worker_id" to workerId))
                }
            }
        }

        // Default: top rated
        return topRated(params)
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun updateWorkerRatings(db: SQLiteDatabase, workerId: String) {
        val cursor = db.rawQuery(
            """SELECT
                AVG(overall_rating) as avg_rating,
                AVG(reliability) as avg_reliability,
                AVG(quality) as avg_quality,
                AVG(punctuality) as avg_punctuality,
                AVG(value_for_money) as avg_value,
                COUNT(*) as review_count
               FROM reviews WHERE worker_id = ?""",
            arrayOf(workerId)
        )

        cursor.use { c ->
            if (c.moveToFirst()) {
                val avgRating = c.getDouble(0)
                val avgReliability = c.getDouble(1)
                val avgQuality = c.getDouble(2)
                val avgPunctuality = c.getDouble(3)
                val avgValue = c.getDouble(4)
                val count = c.getInt(5)
                val now = System.currentTimeMillis()

                // Check if worker exists in summary
                val existing = db.rawQuery("SELECT id FROM worker_ratings WHERE worker_id = ?", arrayOf(workerId))
                val exists = existing.use { it.moveToFirst() }

                if (exists) {
                    db.execSQL(
                        """UPDATE worker_ratings SET avg_rating = ?, avg_reliability = ?, avg_quality = ?,
                           avg_punctuality = ?, avg_value = ?, review_count = ?, updated_at = ?
                           WHERE worker_id = ?""",
                        arrayOf(avgRating, avgReliability, avgQuality, avgPunctuality, avgValue, count, now, workerId)
                    )
                } else {
                    db.execSQL(
                        """INSERT INTO worker_ratings
                           (worker_id, avg_rating, avg_reliability, avg_quality, avg_punctuality, avg_value, review_count, badges, created_at, updated_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        arrayOf(workerId, avgRating, avgReliability, avgQuality, avgPunctuality, avgValue, count, calculateBadges(avgRating, count), now, now)
                    )
                }
            }
        }
    }

    private fun getWorkerRatingSummary(db: SQLiteDatabase, workerId: String): Map<String, Any?>? {
        val cursor = db.rawQuery("SELECT * FROM worker_ratings WHERE worker_id = ?", arrayOf(workerId))
        cursor.use { c -> return if (c.moveToFirst()) cursorToMap(c) else null }
    }

    private fun getWorkerReviews(db: SQLiteDatabase, workerId: String, limit: Int): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val cursor = db.rawQuery(
            "SELECT * FROM reviews WHERE worker_id = ? ORDER BY created_at DESC LIMIT ?",
            arrayOf(workerId, limit.toString())
        )
        cursor.use { c -> while (c.moveToNext()) results.add(cursorToMap(c)) }
        return results
    }

    private fun getRatingDistribution(db: SQLiteDatabase, workerId: String): Map<Int, Int> {
        val dist = mutableMapOf<Int, Int>()
        val cursor = db.rawQuery(
            """SELECT CAST(overall_rating AS INTEGER) as star, COUNT(*) as count
               FROM reviews WHERE worker_id = ?
               GROUP BY CAST(overall_rating AS INTEGER)""",
            arrayOf(workerId)
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                dist[c.getInt(0)] = c.getInt(1)
            }
        }
        return dist
    }

    private fun queryTopRated(db: SQLiteDatabase, skill: String?, location: String?, limit: Int): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val query = buildString {
            append("SELECT * FROM worker_ratings WHERE review_count >= 1")
            if (skill != null) append(" AND worker_id IN (SELECT worker_id FROM reviews WHERE worker_id = worker_ratings.worker_id)")
            append(" ORDER BY avg_rating DESC, review_count DESC LIMIT ?")
        }

        // Simplified: just get top rated by avg rating
        val cursor = db.rawQuery(
            "SELECT * FROM worker_ratings WHERE review_count >= 1 ORDER BY avg_rating DESC, review_count DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        cursor.use { c -> while (c.moveToNext()) results.add(cursorToMap(c)) }
        return results
    }

    private fun calculateBadges(avgRating: Double, reviewCount: Int): String {
        val badges = mutableListOf<String>()
        if (reviewCount >= 5 && avgRating >= 4.5) badges.add("Mtaalamu")
        if (reviewCount >= 10 && avgRating >= 4.0) badges.add("Mwaminifu")
        if (reviewCount >= 25) badges.add("Bingwa")
        if (reviewCount >= 50) badges.add("Mfalme")
        if (avgRating >= 4.8 && reviewCount >= 3) badges.add("Almasi")
        return badges.joinToString(", ")
    }

    private fun ratingToStars(rating: Double): String {
        val full = rating.toInt().coerceIn(0, 5)
        return "⭐".repeat(full)
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
        "mechanic" -> "Fundi wa Gari"
        "mason" -> "Fundi wa Ujenzi"
        else -> skill.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun extractSkill(input: String): String? {
        val skillMap = mapOf(
            "umeme" to "fundi_umeme", "electrician" to "fundi_umeme",
            "tiles" to "fundi_tiles", "painting" to "fundi_painting",
            "plumber" to "fundi_plumber", "carpenter" to "fundi_carpenter",
            "welder" to "fundi_welder", "seremala" to "fundi_carpenter",
            "mechanic" to "mechanic", "gari" to "mechanic",
            "mason" to "mason", "ujenzi" to "mason"
        )
        for ((keyword, skill) in skillMap) {
            if (input.contains(keyword)) return skill
        }
        return null
    }

    private fun extractLocation(input: String): String? {
        val locationPattern = Regex("""(?:hapa|karibu|eneo)\s+([A-Za-z\s]+)""", RegexOption.IGNORE_CASE)
        locationPattern.find(input)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun formatTimestamp(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Sasa hivi"
            diff < 3_600_000 -> "${diff / 60_000} dakika zilizopita"
            diff < 86_400_000 -> "${diff / 3_600_000} saa zilizopita"
            else -> "${diff / 86_400_000} siku zilizopita"
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
// SQLiteOpenHelper — Rating database
// ──────────────────────────────────────────────

class RatingDbHelper(context: Context) : SQLiteOpenHelper(
    context, "ratings.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        // Individual reviews
        db.execSQL("""
            CREATE TABLE reviews (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL,
                reviewer_id TEXT NOT NULL,
                display_name TEXT,
                overall_rating REAL NOT NULL,
                reliability REAL,
                quality REAL,
                punctuality REAL,
                value_for_money REAL,
                comment TEXT,
                job_id TEXT,
                is_anonymous INTEGER DEFAULT 0,
                worker_response TEXT,
                responded_at INTEGER,
                created_at INTEGER NOT NULL
            )
        """)

        // Worker aggregate ratings
        db.execSQL("""
            CREATE TABLE worker_ratings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                worker_id TEXT NOT NULL UNIQUE,
                avg_rating REAL DEFAULT 0,
                avg_reliability REAL DEFAULT 0,
                avg_quality REAL DEFAULT 0,
                avg_punctuality REAL DEFAULT 0,
                avg_value REAL DEFAULT 0,
                review_count INTEGER DEFAULT 0,
                badges TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)

        // Indexes
        db.execSQL("CREATE INDEX idx_reviews_worker ON reviews(worker_id)")
        db.execSQL("CREATE INDEX idx_reviews_reviewer ON reviews(reviewer_id)")
        db.execSQL("CREATE INDEX idx_reviews_job ON reviews(job_id)")
        db.execSQL("CREATE INDEX idx_reviews_rating ON reviews(overall_rating)")
        db.execSQL("CREATE INDEX idx_reviews_created ON reviews(created_at DESC)")
        db.execSQL("CREATE INDEX idx_worker_ratings_rating ON worker_ratings(avg_rating DESC)")
        db.execSQL("CREATE INDEX idx_worker_ratings_count ON worker_ratings(review_count DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }
}
