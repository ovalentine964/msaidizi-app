package com.msaidizi.app.superagent.education

import timber.log.Timber
import com.msaidizi.app.mindset.VoiceLesson

/**
 * Education Module — unified entry point for financial literacy, wealth habits,
 * and business tips in the Msaidizi superagent architecture.
 *
 * Replaces the standalone MindsetAcademy, RichHabitsScore, and AdviceHandler
 * with a single module that delivers bite-sized financial education via voice
 * in the worker's dialect.
 *
 * ## Architecture
 * This module is a capability of the superagent — called directly by the
 * ReasoningEngine, not via event bus or inter-agent protocol.
 *
 * ## Content Delivery
 * All content is voice-first, delivered in Swahili (or worker's dialect).
 * Lessons are 2-3 minutes, habits are tracked daily, tips are contextual.
 *
 * ## Sub-modules
 * - [MindsetAcademy] — 50+ bite-sized financial lessons from classic books
 * - [WealthHabits] — 10 daily wealth-building habits tracking
 * - [BusinessTips] — peer-shared business advice and market wisdom
 *
 * @param mindsetAcademy Financial literacy lesson engine
 * @param wealthHabits Daily habits tracker
 * @param businessTips Community business tips
 */
class EducationModule(
    private val mindsetAcademy: com.msaidizi.app.mindset.MindsetAcademy,
    private val wealthHabits: WealthHabits,
    private val businessTips: BusinessTips
) {
    companion object {
        private const val TAG = "EducationModule"
    }

    /**
     * Handle an education-related intent from the ReasoningEngine.
     *
     * Supported intents:
     * - LESSON: deliver next financial literacy lesson
     * - HABITS_CHECK: show daily habits progress
     * - HABIT_COMPLETE: mark a habit as done
     * - BUSINESS_TIP: get contextual business advice
     * - ASK_ADVICE: general financial advice request
     * - GREETING: morning/evening greeting with educational nudge
     *
     * @param request Education request with intent and context
     * @param language "sw" for Swahili, "en" for English
     * @return EducationResponse with voice-ready content
     */
    suspend fun handle(request: EducationRequest, language: String = "sw"): EducationResponse {
        Timber.d(TAG, "Handling education request: %s", request.type)

        return when (request.type) {
            EducationType.LESSON -> handleLesson(request, language)
            EducationType.HABITS_CHECK -> handleHabitsCheck(request, language)
            EducationType.HABIT_COMPLETE -> handleHabitComplete(request, language)
            EducationType.BUSINESS_TIP -> handleBusinessTip(request, language)
            EducationType.ASK_ADVICE -> handleAdvice(request, language)
            EducationType.GREETING -> handleGreeting(request, language)
            EducationType.DAILY_PROMPT -> handleDailyPrompt(language)
        }
    }

    /**
     * Get the next lesson for voice delivery.
     * Called during morning briefing or when worker asks to learn.
     */
    suspend fun getNextLesson(language: String = "sw"): VoiceLesson? {
        return mindsetAcademy.getNextLesson()?.let { lesson ->
            mindsetAcademy.deliverLesson(lesson.lessonId, language)
        }
    }

    /**
     * Mark a lesson as completed and return gamification points.
     */
    suspend fun completeLesson(lessonId: String): Int {
        mindsetAcademy.completeLesson(lessonId)
        return 8 // POINTS_MINDSET_LESSON
    }

    /**
     * Get daily habits summary for the worker.
     */
    suspend fun getHabitsSummary(language: String = "sw"): HabitsSummary {
        return wealthHabits.getDailySummary(language)
    }

    /**
     * Complete a specific habit and return the updated state.
     */
    suspend fun completeHabit(habitId: String, language: String = "sw"): HabitCompletionResult {
        return wealthHabits.completeHabit(habitId, language)
    }

    /**
     * Get a contextual business tip based on worker's situation.
     */
    suspend fun getContextualTip(context: TipContext, language: String = "sw"): BusinessTip {
        return businessTips.getTip(context, language)
    }

    // ═══════════════ PRIVATE HANDLERS ═══════════════

    private suspend fun handleLesson(request: EducationRequest, language: String): EducationResponse {
        val category = request.params["category"]
        val lesson = mindsetAcademy.getNextLesson(category)

        if (lesson == null) {
            return EducationResponse(
                type = EducationType.LESSON,
                text = if (language == "sw") {
                    "🎉 Umekamilisha somo zote! Hongera! Soma tena somo lolote ukipenda."
                } else {
                    "🎉 You've completed all lessons! Congratulations! Review any lesson anytime."
                },
                shouldSpeak = true,
                pointsEarned = 0
            )
        }

        val voiceLesson = mindsetAcademy.deliverLesson(lesson.lessonId, language)
            ?: return EducationResponse.empty()

        return EducationResponse(
            type = EducationType.LESSON,
            text = voiceLesson.content,
            shouldSpeak = true,
            pointsEarned = 8,
            metadata = mapOf(
                "lessonId" to voiceLesson.lessonId,
                "category" to voiceLesson.category,
                "sourceBook" to voiceLesson.sourceBook,
                "durationSeconds" to voiceLesson.durationSeconds.toString()
            )
        )
    }

    private suspend fun handleHabitsCheck(request: EducationRequest, language: String): EducationResponse {
        val summary = wealthHabits.getDailySummary(language)

        return EducationResponse(
            type = EducationType.HABITS_CHECK,
            text = summary.voiceSummary,
            shouldSpeak = true,
            pointsEarned = 0,
            metadata = mapOf(
                "completedCount" to summary.completedCount.toString(),
                "totalCount" to summary.totalCount.toString(),
                "score" to summary.score.toString()
            )
        )
    }

    private suspend fun handleHabitComplete(request: EducationRequest, language: String): EducationResponse {
        val habitId = request.params["habitId"] ?: return EducationResponse.empty()
        val result = wealthHabits.completeHabit(habitId, language)

        return EducationResponse(
            type = EducationType.HABIT_COMPLETE,
            text = result.message,
            shouldSpeak = true,
            pointsEarned = if (result.allCompleted) 15 else 0,
            metadata = mapOf(
                "habitId" to habitId,
                "allCompleted" to result.allCompleted.toString()
            )
        )
    }

    private suspend fun handleBusinessTip(request: EducationRequest, language: String): EducationResponse {
        val context = TipContext(
            businessType = request.params["businessType"] ?: "general",
            timeOfDay = request.params["timeOfDay"] ?: "any",
            recentActivity = request.params["recentActivity"] ?: ""
        )

        val tip = businessTips.getTip(context, language)

        return EducationResponse(
            type = EducationType.BUSINESS_TIP,
            text = tip.text,
            shouldSpeak = true,
            pointsEarned = 0,
            metadata = mapOf(
                "tipId" to tip.id,
                "category" to tip.category
            )
        )
    }

    private suspend fun handleAdvice(request: EducationRequest, language: String): EducationResponse {
        val topic = request.params["topic"] ?: "general"

        // Combine lesson wisdom + business tips for contextual advice
        val tip = businessTips.getTip(TipContext(businessType = "general"), language)

        return EducationResponse(
            type = EducationType.ASK_ADVICE,
            text = tip.text,
            shouldSpeak = true,
            pointsEarned = 0
        )
    }

    private suspend fun handleGreeting(request: EducationRequest, language: String): EducationResponse {
        val lessonPrompt = mindsetAcademy.getDailyLessonPrompt(language)
        val habitsSummary = wealthHabits.getDailySummary(language)

        val greeting = buildString {
            if (language == "sw") {
                append("Habari! ")
                if (habitsSummary.completedCount > 0) {
                    append("Umekamilisha tabia ${habitsSummary.completedCount}/${habitsSummary.totalCount} leo. ")
                }
                lessonPrompt?.let { append(it) }
            } else {
                append("Hello! ")
                if (habitsSummary.completedCount > 0) {
                    append("You've completed ${habitsSummary.completedCount}/${habitsSummary.totalCount} habits today. ")
                }
                lessonPrompt?.let { append(it) }
            }
        }

        return EducationResponse(
            type = EducationType.GREETING,
            text = greeting.ifBlank {
                if (language == "sw") "Habari! Niko hapa kukusaidia leo."
                else "Hello! I'm here to help you today."
            },
            shouldSpeak = true,
            pointsEarned = 0
        )
    }

    private suspend fun handleDailyPrompt(language: String): EducationResponse {
        val prompt = buildString {
            // Morning motivation with lesson hint
            val lesson = mindsetAcademy.getNextLesson()
            if (lesson != null) {
                if (language == "sw") {
                    append("📖 Somo la leo: '${lesson.titleSw}'. ")
                    append("Sikiliza dakika ${lesson.durationSeconds / 60}. ")
                } else {
                    append("📖 Today's lesson: '${lesson.titleEn}'. ")
                    append("Listen for ${lesson.durationSeconds / 60} minutes. ")
                }
            }

            // Habits reminder
            val summary = wealthHabits.getDailySummary(language)
            if (summary.completedCount == 0) {
                if (language == "sw") {
                    append("Anza siku yako na tabia za utajiri! ")
                } else {
                    append("Start your day with wealth habits! ")
                }
            }
        }

        return EducationResponse(
            type = EducationType.DAILY_PROMPT,
            text = prompt.ifBlank {
                if (language == "sw") "Leo ni siku nzuri ya kujifunza!"
                else "Today is a great day to learn!"
            },
            shouldSpeak = true,
            pointsEarned = 0
        )
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Types of education requests the module can handle.
 */
enum class EducationType {
    /** Deliver a financial literacy lesson */
    LESSON,
    /** Check daily habits progress */
    HABITS_CHECK,
    /** Mark a specific habit as complete */
    HABIT_COMPLETE,
    /** Get a contextual business tip */
    BUSINESS_TIP,
    /** General financial advice request */
    ASK_ADVICE,
    /** Morning/evening greeting with educational nudge */
    GREETING,
    /** Daily educational prompt (for briefings) */
    DAILY_PROMPT
}

/**
 * Request to the education module.
 */
data class EducationRequest(
    val type: EducationType,
    val params: Map<String, String> = emptyMap()
)

/**
 * Response from the education module.
 */
data class EducationResponse(
    val type: EducationType,
    val text: String,
    val shouldSpeak: Boolean = true,
    val pointsEarned: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun empty() = EducationResponse(
            type = EducationType.LESSON,
            text = "",
            shouldSpeak = false
        )
    }
}
