package com.msaidizi.app.ui.mindset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msaidizi.app.core.model.MindsetLessonEntity
import com.msaidizi.app.mindset.AcademyProgress
import com.msaidizi.app.mindset.HabitStatus
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.mindset.RichHabitsScore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Mindset Academy screen.
 *
 * Handles:
 * - Loading daily lesson from MindsetAcademy
 * - Calculating rich habits score (0-100 gauge)
 * - Getting daily affirmations
 * - Tracking lesson delivery and completion
 * - Module progress across 5 categories
 */
@HiltViewModel
class MindsetViewModel @Inject constructor(
    private val mindsetAcademy: MindsetAcademy,
    private val richHabitsScore: RichHabitsScore
) : ViewModel() {

    private val _uiState = MutableStateFlow(MindsetUiState())
    val uiState: StateFlow<MindsetUiState> = _uiState.asStateFlow()

    init {
        loadMindsetData()
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA LOADING
    // ═══════════════════════════════════════════════════════════════

    fun loadMindsetData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)

                // Load daily lesson
                val nextLesson = mindsetAcademy.getNextLesson()
                val dailyPrompt = mindsetAcademy.getDailyLessonPrompt("sw")

                // Load rich habits score
                val todayScore = richHabitsScore.getTodayScore()
                val weeklyAverage = richHabitsScore.getWeeklyAverage()
                val habits = richHabitsScore.getTodayHabits()
                val peerComparison = richHabitsScore.getPeerComparison("sw")

                // Load academy progress
                val progress = mindsetAcademy.getProgress()

                // Generate daily affirmation
                val affirmation = generateDailyAffirmation(todayScore)

                // Build module data from progress
                val modules = buildModuleData(progress)

                // Build habit stacking formula
                val habitFormula = buildHabitFormula(habits)

                _uiState.value = MindsetUiState(
                    isLoading = false,
                    dailyLesson = nextLesson?.toLessonData(),
                    dailyLessonPrompt = dailyPrompt,
                    todayScore = todayScore,
                    weeklyAverage = weeklyAverage,
                    habits = habits.map { it.toHabitData() },
                    peerComparison = peerComparison,
                    affirmation = affirmation,
                    modules = modules,
                    habitFormula = habitFormula,
                    completedLessons = progress.completedLessons,
                    totalLessons = progress.totalLessons,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Imeshindwa kupakia data: ${e.message}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LESSON INTERACTIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mark current lesson as delivered (start listening).
     */
    fun deliverLesson() {
        viewModelScope.launch {
            try {
                val lesson = _uiState.value.dailyLesson ?: return@launch
                mindsetAcademy.deliverLesson(lesson.id, "sw")
                _uiState.value = _uiState.value.copy(
                    isLessonPlaying = true,
                    currentPlayingLessonId = lesson.id
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Imeshindwa kuanza somo: ${e.message}"
                )
            }
        }
    }

    /**
     * Mark current lesson as completed.
     */
    fun completeLesson() {
        viewModelScope.launch {
            try {
                val lessonId = _uiState.value.currentPlayingLessonId ?: return@launch
                mindsetAcademy.completeLesson(lessonId)
                _uiState.value = _uiState.value.copy(
                    isLessonPlaying = false,
                    currentPlayingLessonId = null,
                    successMessage = "🎉 Somo limekamilika!"
                )
                // Refresh data to show updated progress
                loadMindsetData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Imeshindwa kukamilisha somo: ${e.message}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HABIT INTERACTIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Toggle a habit completion for today.
     */
    fun toggleHabit(habitId: String) {
        viewModelScope.launch {
            try {
                val result = richHabitsScore.completeHabit(habitId, "sw")

                if (result.allCompleted) {
                    _uiState.value = _uiState.value.copy(
                        successMessage = "🎉 Hongera! Tabia zote 10 zimekamilika leo!"
                    )
                } else if (!result.alreadyCompleted) {
                    _uiState.value = _uiState.value.copy(
                        successMessage = result.message
                    )
                }

                // Refresh habits data
                val habits = richHabitsScore.getTodayHabits()
                val todayScore = richHabitsScore.getTodayScore()
                val weeklyAverage = richHabitsScore.getWeeklyAverage()
                val peerComparison = richHabitsScore.getPeerComparison("sw")

                _uiState.value = _uiState.value.copy(
                    habits = habits.map { it.toHabitData() },
                    todayScore = todayScore,
                    weeklyAverage = weeklyAverage,
                    peerComparison = peerComparison
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Imeshindwa kubadilisha tabia: ${e.message}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AFFIRMATION GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a daily affirmation based on current score and day.
     * Bilingual (Swahili primary, English secondary).
     */
    private fun generateDailyAffirmation(score: Int): DailyAffirmation {
        val affirmations = listOf(
            Triple(
                "Mimi ni mfanyabiashara mwenye ujasiri. Leo nitafanikiwa!",
                "I am a confident business owner. I will succeed today!",
                "💪"
            ),
            (
                Triple(
                    "Kila mauzo ni hatua ya mbele. Naendelea kupanda!",
                    "Every sale is a step forward. I keep growing!",
                    "📈"
                )
            ),
            Triple(
                "Pesa yangu inafanya kazi kwangu. Nafanya maamuzi mazuri!",
                "My money works for me. I make good decisions!",
                "💰"
            ),
            Triple(
                "Ninajiamini na ninajua thamani yako. Biashara yangu inakua!",
                "I believe in myself and know my worth. My business is growing!",
                "🌟"
            ),
            Triple(
                "Leo ni siku mpya ya fursa mpya. Nitafanya vizuri!",
                "Today is a new day of new opportunities. I will do well!",
                "☀️"
            ),
            Triple(
                "Ninaweka akiba kila siku. Baadaye yangu ni njema!",
                "I save every day. My future is bright!",
                "🏦"
            ),
            Triple(
                "Mimi ni shujaa wa familia yangu. Nitaendelea kupigana!",
                "I am the hero of my family. I will keep fighting!",
                "🦸"
            )
        )

        // Select based on score and day to rotate
        val dayOfYear = java.time.LocalDate.now().dayOfYear
        val index = (dayOfYear + score / 20) % affirmations.size
        val (sw, en, emoji) = affirmations[index]

        return DailyAffirmation(
            textSw = sw,
            textEn = en,
            emoji = emoji,
            scoreContext = when {
                score >= 80 -> "Umefanya vizuri sana leo! $score/100"
                score >= 50 -> "Unaendelea vizuri! $score/100"
                score >= 20 -> "Anza na tabia moja leo. $score/100"
                else -> "Leo ni siku mpya. Anza na tabia moja! $score/100"
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // MODULE DATA
    // ═══════════════════════════════════════════════════════════════

    private fun buildModuleData(progress: AcademyProgress): List<ModuleData> {
        val categoryEmojis = mapOf(
            "HABITS" to "🔄",
            "GOALS" to "🎯",
            "FINANCIAL_LITERACY" to "💰",
            "MINDSET" to "🧠",
            "GIVING" to "🤝"
        )

        val categoryDescriptions = mapOf(
            "HABITS" to "Jenga tabia za mafanikio kwa vitendo vya kila siku",
            "GOALS" to "Weka na ufuate malengo yako ya biashara",
            "FINANCIAL_LITERACY" to "Jifunze kuhusu fedha, faida, na akiba",
            "MINDSET" to "Badilisha mawazo yako kuwa ya mafanikio",
            "GIVING" to "Jifunze nguvu ya kutoa na kusaidia"
        )

        return MindsetAcademy.CATEGORIES.map { catId ->
            val catProgress = progress.categoryProgress[catId]
            val completed = catProgress?.completed ?: 0
            val total = catProgress?.total ?: 10
            val progressPercent = if (total > 0) (completed * 100 / total) else 0

            ModuleData(
                id = catId,
                name = MindsetAcademy.CATEGORIES_SW[catId] ?: catId,
                emoji = categoryEmojis[catId] ?: "📚",
                description = categoryDescriptions[catId] ?: "",
                completedLessons = completed,
                totalLessons = total,
                progressPercent = progressPercent,
                isCompleted = completed >= total
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HABIT STACKING FORMULA
    // ═══════════════════════════════════════════════════════════════

    private fun buildHabitFormula(habits: List<HabitStatus>): HabitFormula {
        val completedCount = habits.count { it.completed }
        val nextIncomplete = habits.firstOrNull { !it.completed }

        val steps = habits.mapIndexed { index, habit ->
            FormulaStep(
                order = index + 1,
                name = habit.habit.nameSw,
                emoji = habit.habit.emojiSw,
                completed = habit.completed
            )
        }

        return HabitFormula(
            title = "Fomula ya Tabia za Kila Siku",
            subtitle = "Fanya hizi hatua kwa mpango — kila tabia inaongoza nyingine",
            steps = steps,
            completedCount = completedCount,
            totalSteps = steps.size,
            nextStep = nextIncomplete?.let { "${it.habit.emojiSw} ${it.habit.nameSw}" }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // UI STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            successMessage = null,
            error = null
        )
    }

    fun refresh() {
        loadMindsetData()
    }
}

// ═══════════════════════════════════════════════════════════════
// UI STATE & DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class MindsetUiState(
    val isLoading: Boolean = false,
    val dailyLesson: LessonData? = null,
    val dailyLessonPrompt: String? = null,
    val todayScore: Int = 0,
    val weeklyAverage: Double = 0.0,
    val habits: List<HabitData> = emptyList(),
    val peerComparison: String = "",
    val affirmation: DailyAffirmation? = null,
    val modules: List<ModuleData> = emptyList(),
    val habitFormula: HabitFormula? = null,
    val completedLessons: Int = 0,
    val totalLessons: Int = 0,
    val isLessonPlaying: Boolean = false,
    val currentPlayingLessonId: String? = null,
    val successMessage: String? = null,
    val error: String? = null
)

data class LessonData(
    val id: String,
    val title: String,
    val content: String,
    val category: String,
    val sourceBook: String,
    val durationSeconds: Int,
    val categorySw: String
)

data class HabitData(
    val id: String,
    val name: String,
    val emoji: String,
    val completed: Boolean
)

data class DailyAffirmation(
    val textSw: String,
    val textEn: String,
    val emoji: String,
    val scoreContext: String
)

data class ModuleData(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String,
    val completedLessons: Int,
    val totalLessons: Int,
    val progressPercent: Int,
    val isCompleted: Boolean
)

data class HabitFormula(
    val title: String,
    val subtitle: String,
    val steps: List<FormulaStep>,
    val completedCount: Int,
    val totalSteps: Int,
    val nextStep: String?
)

data class FormulaStep(
    val order: Int,
    val name: String,
    val emoji: String,
    val completed: Boolean
)

// ═══════════════════════════════════════════════════════════════
// EXTENSIONS
// ═══════════════════════════════════════════════════════════════

private fun MindsetLessonEntity.toLessonData(): LessonData {
    return LessonData(
        id = lessonId,
        title = titleSw,
        content = contentSw,
        category = category,
        sourceBook = sourceBook,
        durationSeconds = durationSeconds,
        categorySw = MindsetAcademy.CATEGORIES_SW[category] ?: category
    )
}

private fun com.msaidizi.app.mindset.HabitStatus.toHabitData(): HabitData {
    return HabitData(
        id = habit.id,
        name = habit.nameSw,
        emoji = habit.emojiSw,
        completed = completed
    )
}
